package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.BinanceApiTransactionAdapter;
import com.importer.fileimporter.dto.integration.binance.BinanceAccountResponse;
import com.importer.fileimporter.dto.integration.binance.BinanceDepositResponse;
import com.importer.fileimporter.dto.integration.binance.BinanceTradeResponse;
import com.importer.fileimporter.dto.integration.binance.BinanceWithdrawResponse;
import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.entity.User;
import com.importer.fileimporter.entity.UserExchangeConfig;
import com.importer.fileimporter.repository.UserExchangeConfigRepository;
import com.importer.fileimporter.utils.DateUtils;
import com.importer.fileimporter.utils.OperationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceSyncService {

    // Currencies to pair candidate assets against when building symbols (e.g. "BTCUSDT").
    private static final List<String> QUOTE_CURRENCIES_ORDERED = List.of(
            "USDT", "BTC", "ETH", "BNB", "BUSD", "USDC", "FDUSD"
    );
    // Assets that are never worth syncing trade history *for* (stablecoins/fiat) — NOT the
    // same set as QUOTE_CURRENCIES_ORDERED above. BTC/ETH/BNB can be quote currencies for
    // other pairs AND legitimate investment assets in their own right (see
    // OperationUtils.GRAND_SYMBOLS), so they must not be excluded here.
    private static final Set<String> NON_INVESTMENT_ASSETS;
    static {
        Set<String> assets = new HashSet<>(OperationUtils.STABLE);
        assets.addAll(Set.of("EUR", "TRY"));
        NON_INVESTMENT_ASSETS = assets;
    }
    // Package-private so unit tests can set it to 0 without Spring context
    long rateLimitDelayMs = 200L;

    private final BinanceApiService binanceApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;

    @Transactional
    public void sync(User user, String portfolioName) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.BINANCE)
                .orElseThrow(() -> new IllegalArgumentException("Binance API keys not configured for user"));

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());

        Portfolio portfolio = portfolioService.resolveExchangePortfolio(portfolioName, ExchangeName.BINANCE);

        long binanceServerTime = binanceApiService.getServerTime();
        long clockOffset = binanceServerTime - System.currentTimeMillis();
        log.info("Binance server time offset: {}ms", clockOffset);

        BinanceAccountResponse accountInfo = binanceApiService.getAccountInfo(apiKey, secretKey);
        Set<String> investmentAssets = accountInfo.getBalances().stream()
                .filter(b -> b.getFree().add(b.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(BinanceAccountResponse.AssetBalance::getAsset)
                .filter(asset -> !NON_INVESTMENT_ASSETS.contains(asset))
                .collect(Collectors.toSet());

        log.info("Found {} investment assets for user {}: {}", investmentAssets.size(), user.getUsername(), investmentAssets);

        List<String[]> candidatePairs = buildCandidatePairs(investmentAssets);
        log.info("Checking {} candidate pairs", candidatePairs.size());

        Long lastSync = config.getLastSyncTimestamp();
        Long newSyncTimestamp = System.currentTimeMillis();

        for (String[] pair : candidatePairs) {
            String symbol = pair[0];
            String baseAsset = pair[1];
            String quoteAsset = pair[2];
            try {
                List<BinanceTradeResponse> trades = binanceApiService.getMyTrades(apiKey, secretKey, symbol, lastSync);
                if (trades != null && !trades.isEmpty()) {
                    log.info("Syncing {} trades for {}", trades.size(), symbol);
                    for (BinanceTradeResponse trade : trades) {
                        BinanceApiTransactionAdapter adapter = new BinanceApiTransactionAdapter(trade, baseAsset, quoteAsset);
                        Transaction transaction = mapToTransaction(adapter, portfolio);
                        transaction.setFeeSymbol(adapter.getFeeSymbol());
                        transactionService.saveIfAbsent(transaction);
                    }
                }
                Thread.sleep(rateLimitDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Binance sync interrupted");
                break;
            } catch (Exception e) {
                // -1121 = invalid symbol; expected for non-existent pairs, not an error
                if (e.getMessage() != null && e.getMessage().contains("-1121")) {
                    log.debug("Symbol {} does not exist on Binance, skipping", symbol);
                } else {
                    log.error("Error syncing trades for {}: {}", symbol, e.getMessage());
                }
            }
        }

        // Sync deposits and withdrawals since last checkpoint
        long depositStart = lastSync != null ? lastSync : 0L;
        syncIncrementalDeposits(apiKey, secretKey, portfolio, depositStart, newSyncTimestamp);
        syncIncrementalWithdrawals(apiKey, secretKey, portfolio, depositStart, newSyncTimestamp);

        config.setLastSyncTimestamp(newSyncTimestamp);
        userExchangeConfigRepository.save(config);
    }

    private static final long WINDOW_90_DAYS = 90L * 24 * 60 * 60 * 1000;

    private void syncIncrementalDeposits(String apiKey, String secretKey, Portfolio portfolio, long start, long end) {
        for (long t = start; t < end; t += WINDOW_90_DAYS) {
            long e = Math.min(t + WINDOW_90_DAYS, end);
            try {
                List<BinanceDepositResponse> deposits = binanceApiService.getDepositHistory(apiKey, secretKey, t, e);
                if (deposits != null && !deposits.isEmpty()) {
                    log.info("Syncing {} deposits for window {} - {}", deposits.size(), t, e);
                    for (BinanceDepositResponse d : deposits) {
                        Transaction tx = Transaction.builder()
                                .dateUtc(DateUtils.toLocalDateTime(d.getInsertTime()))
                                .side(OperationUtils.DEPOSIT_STRING)
                                .symbol(d.getCoin())
                                .executed(d.getAmount())
                                .price(BigDecimal.ZERO)
                                .pair(d.getCoin() + "EXTERNAL")
                                .externalId(d.getTxId())
                                .exchangeName(ExchangeName.BINANCE)
                                .created(LocalDateTime.now())
                                .createdBy("BinanceSyncService-Deposit")
                                .portfolio(portfolio)
                                .build();
                        transactionService.saveIfAbsent(tx);
                    }
                }
                Thread.sleep(rateLimitDelayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Deposit sync interrupted");
                return;
            } catch (Exception ex) {
                log.error("Error syncing deposits for window {} - {}: {}", t, e, ex.getMessage());
            }
        }
    }

    private void syncIncrementalWithdrawals(String apiKey, String secretKey, Portfolio portfolio, long start, long end) {
        for (long t = start; t < end; t += WINDOW_90_DAYS) {
            long e = Math.min(t + WINDOW_90_DAYS, end);
            try {
                List<BinanceWithdrawResponse> withdrawals = binanceApiService.getWithdrawHistory(apiKey, secretKey, t, e);
                if (withdrawals != null && !withdrawals.isEmpty()) {
                    log.info("Syncing {} withdrawals for window {} - {}", withdrawals.size(), t, e);
                    for (BinanceWithdrawResponse w : withdrawals) {
                        Transaction tx = Transaction.builder()
                                .dateUtc(DateUtils.getLocalDateTime(w.getApplyTime()))
                                .side(OperationUtils.WITHDRAW_STRING)
                                .symbol(w.getCoin())
                                .executed(w.getAmount())
                                .price(BigDecimal.ZERO)
                                .pair(w.getCoin() + "EXTERNAL")
                                .feeAmount(w.getTransactionFee())
                                .feeSymbol(w.getCoin())
                                .externalId(w.getTxId() != null ? w.getTxId() : w.getId())
                                .exchangeName(ExchangeName.BINANCE)
                                .created(LocalDateTime.now())
                                .createdBy("BinanceSyncService-Withdraw")
                                .portfolio(portfolio)
                                .build();
                        transactionService.saveIfAbsent(tx);
                    }
                }
                Thread.sleep(rateLimitDelayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Withdrawal sync interrupted");
                return;
            } catch (Exception ex) {
                log.error("Error syncing withdrawals for window {} - {}: {}", t, e, ex.getMessage());
            }
        }
    }

    private List<String[]> buildCandidatePairs(Set<String> investmentAssets) {
        List<String[]> candidates = new ArrayList<>();
        for (String asset : investmentAssets) {
            for (String quote : QUOTE_CURRENCIES_ORDERED) {
                if (!asset.equals(quote)) {
                    candidates.add(new String[]{asset + quote, asset, quote});
                }
            }
        }
        return candidates;
    }

    private Transaction mapToTransaction(BinanceApiTransactionAdapter data, Portfolio portfolio) {
        return Transaction.builder()
                .dateUtc(DateUtils.getLocalDateTime(data.getDate()))
                .pair(data.getPair())
                .executed(data.getExecuted())
                .side(data.getSide())
                .price(data.getPrice())
                .symbol(data.getSymbol())
                .paidWith(data.getPaidWith())
                .paidAmount(data.getAmount())
                .feeAmount(data.getFee())
                .externalId(data.getTradeId() != null ? data.getTradeId().toString() : null)
                .exchangeName(ExchangeName.BINANCE)
                .created(LocalDateTime.now())
                .createdBy("BinanceSyncService")
                .portfolio(portfolio)
                .build();
    }
}
