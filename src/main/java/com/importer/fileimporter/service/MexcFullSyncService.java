package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.integration.mexc.*;
import com.importer.fileimporter.dto.integration.binance.BinanceExchangeInfoResponse;
import com.importer.fileimporter.entity.*;
import com.importer.fileimporter.repository.UserExchangeConfigRepository;
import com.importer.fileimporter.utils.DateUtils;
import com.importer.fileimporter.utils.OperationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MexcFullSyncService {

    private final MexcApiService mexcApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;
    private final RawResponseService rawResponseService;

    private static final long WINDOW_90_DAYS = 90L * 24 * 60 * 60 * 1000;
    private static final long START_TIME_2017 = 1483228800000L;

    @Transactional
    public void syncFullHistory(User user, String portfolioName) {
        syncFullHistory(user, portfolioName, START_TIME_2017, System.currentTimeMillis());
    }

    @Transactional
    public void syncFullHistory(User user, String portfolioName, Long startDateEpochMs, Long endDateEpochMs) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC)
                .orElseThrow(() -> new IllegalArgumentException("MexC API keys not configured for user"));

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());
        Portfolio portfolio = portfolioService.resolveExchangePortfolio(portfolioName, ExchangeName.MEXC);

        long now = System.currentTimeMillis();
        long startTime = startDateEpochMs != null ? startDateEpochMs : START_TIME_2017;
        long endTime = endDateEpochMs != null ? endDateEpochMs : now;

        // 1. Sync Deposits
        syncDeposits(apiKey, secretKey, portfolio, startTime, endTime);

        // 2. Sync Withdrawals
        syncWithdrawals(apiKey, secretKey, portfolio, startTime, endTime);

        // 3. Sync Spot Trades
        syncSpotTrades(apiKey, secretKey, portfolio, startTime, endTime);

        config.setLastSyncTimestamp(now);
        userExchangeConfigRepository.save(config);
    }

    private void syncDeposits(String apiKey, String secretKey, Portfolio portfolio, long start, long end) {
        log.info("Syncing MexC Deposits...");
        for (long t = start; t < end; t += WINDOW_90_DAYS) {
            long e = Math.min(t + WINDOW_90_DAYS, end);
            try {
                List<MexcDepositResponse> deposits = mexcApiService.getDepositHistory(apiKey, secretKey, t, e);
                if (deposits != null && !deposits.isEmpty()) {
                    rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.MEXC, "DEPOSIT", null, deposits);
                    for (MexcDepositResponse d : deposits) {
                        Transaction tx = Transaction.builder()
                                .dateUtc(DateUtils.toLocalDateTime(d.getInsertTime()))
                                .side(OperationUtils.DEPOSIT_STRING)
                                .symbol(d.getCoin())
                                .executed(d.getAmount())
                                .price(BigDecimal.ZERO)
                                .pair(d.getCoin() + "EXTERNAL")
                                .externalId(d.getTxId())
                                .exchangeName(ExchangeName.MEXC)
                                .created(LocalDateTime.now())
                                .createdBy("MexcFullSync-Deposit")
                                .portfolio(portfolio)
                                .build();
                        transactionService.saveIfAbsent(tx);
                    }
                }
            } catch (Exception ex) {
                log.error("Error syncing MexC deposits: {}", ex.getMessage());
            }
            sleep(500);
        }
    }

    private void syncWithdrawals(String apiKey, String secretKey, Portfolio portfolio, long start, long end) {
        log.info("Syncing MexC Withdrawals...");
        for (long t = start; t < end; t += WINDOW_90_DAYS) {
            long e = Math.min(t + WINDOW_90_DAYS, end);
            try {
                List<MexcWithdrawResponse> withdrawals = mexcApiService.getWithdrawHistory(apiKey, secretKey, t, e);
                if (withdrawals != null && !withdrawals.isEmpty()) {
                    rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.MEXC, "WITHDRAW", null, withdrawals);
                    for (MexcWithdrawResponse w : withdrawals) {
                        Transaction tx = Transaction.builder()
                                .dateUtc(parseDateTime(w.getApplyTime()))
                                .side(OperationUtils.WITHDRAW_STRING)
                                .symbol(w.getCoin())
                                .executed(w.getAmount())
                                .price(BigDecimal.ZERO)
                                .pair(w.getCoin() + "EXTERNAL")
                                .feeAmount(w.getTransactionFee())
                                .feeSymbol(w.getCoin())
                                .externalId(w.getTxId() != null ? w.getTxId() : w.getId())
                                .exchangeName(ExchangeName.MEXC)
                                .created(LocalDateTime.now())
                                .createdBy("MexcFullSync-Withdraw")
                                .portfolio(portfolio)
                                .build();
                        transactionService.saveIfAbsent(tx);
                    }
                }
            } catch (Exception ex) {
                log.error("Error syncing MexC withdrawals: {}", ex.getMessage());
            }
            sleep(500);
        }
    }

    private void syncSpotTrades(String apiKey, String secretKey, Portfolio portfolio, long start, long end) {
        log.info("Syncing MexC Spot Trades...");
        
        MexcAccountResponse account = mexcApiService.getAccountInfo(apiKey, secretKey);
        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.MEXC, "ACCOUNT_SNAPSHOT", null, account);

        Set<String> assets = account.getBalances().stream()
                .filter(b -> b.getFree().add(b.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(MexcAccountResponse.AssetBalance::getAsset)
                .collect(Collectors.toSet());

        BinanceExchangeInfoResponse exchangeInfo = mexcApiService.getExchangeInfo();
        List<BinanceExchangeInfoResponse.SymbolInfo> relevantSymbols = exchangeInfo.getSymbols().stream()
                .filter(s -> assets.contains(s.getBaseAsset()) || assets.contains(s.getQuoteAsset()))
                .collect(Collectors.toList());

        for (BinanceExchangeInfoResponse.SymbolInfo sInfo : relevantSymbols) {
            log.info("Fetching exhaustive MexC history for {}", sInfo.getSymbol());
            
            Long lastTradeId = null;
            boolean hasMore = true;
            
            while (hasMore) {
                try {
                    List<MexcTradeResponse> trades = mexcApiService.getMyTrades(apiKey, secretKey, sInfo.getSymbol(), start, end, lastTradeId != null ? lastTradeId + 1 : null);

                    if (trades == null || trades.isEmpty()) {
                        hasMore = false;
                    } else {
                        log.info("Processing {} MexC trades for {}", trades.size(), sInfo.getSymbol());
                        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.MEXC, "TRADES_" + sInfo.getSymbol(), null, trades);
                        for (MexcTradeResponse tr : trades) {
                            Transaction tx = Transaction.builder()
                                    .dateUtc(tr.getTimeAsLocalDateTime())
                                    .pair(sInfo.getSymbol())
                                    .executed(tr.getQty())
                                    .side(tr.getIsBuyer() ? OperationUtils.BUY_STRING : OperationUtils.SELL_STRING)
                                    .price(tr.getPrice())
                                    .symbol(sInfo.getBaseAsset())
                                    .paidWith(sInfo.getQuoteAsset())
                                    .paidAmount(tr.getQuoteQty())
                                    .feeAmount(tr.getCommission())
                                    .feeSymbol(tr.getCommissionAsset())
                                    .externalId(tr.getId().toString())
                                    .exchangeName(ExchangeName.MEXC)
                                    .created(LocalDateTime.now())
                                    .createdBy("MexcFullSync-Spot")
                                    .portfolio(portfolio)
                                    .build();
                            transactionService.saveIfAbsent(tx);
                            lastTradeId = tr.getId();
                        }
                        if (trades.size() < 1000) hasMore = false;
                    }
                } catch (Exception ex) {
                    log.error("Error syncing MexC spot {}: {}", sInfo.getSymbol(), ex.getMessage());
                    hasMore = false;
                }
                sleep(200);
            }
        }
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(dateTimeStr, formatter);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
