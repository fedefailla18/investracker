package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.integration.binance.BinanceExchangeInfoResponse;
import com.importer.fileimporter.dto.integration.mexc.MexcAccountResponse;
import com.importer.fileimporter.dto.integration.mexc.MexcTradeResponse;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MexcSyncService {

    // Assets that are never worth syncing trade history *for* (stablecoins/fiat) — BTC/ETH/MX
    // are legitimate investment assets in their own right (see OperationUtils.GRAND_SYMBOLS)
    // even though they're also commonly used as quote currencies for other pairs, so they must
    // not be excluded here (same fix as BinanceSyncService — see NON_INVESTMENT_ASSETS there).
    private static final Set<String> NON_INVESTMENT_ASSETS;
    static {
        Set<String> assets = new java.util.HashSet<>(OperationUtils.STABLE);
        assets.addAll(Set.of("EUR", "TRY"));
        NON_INVESTMENT_ASSETS = assets;
    }

    private final MexcApiService mexcApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;

    @Transactional
    public void sync(User user, String portfolioName) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC)
                .orElseThrow(() -> new IllegalArgumentException("MexC API keys not configured for user"));

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());
        Portfolio portfolio = portfolioService.resolveExchangePortfolio(portfolioName, ExchangeName.MEXC);

        MexcAccountResponse accountInfo = mexcApiService.getAccountInfo(apiKey, secretKey);
        Set<String> investmentAssets = accountInfo.getBalances().stream()
                .filter(b -> b.getFree().add(b.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(MexcAccountResponse.AssetBalance::getAsset)
                .filter(asset -> !NON_INVESTMENT_ASSETS.contains(asset))
                .collect(Collectors.toSet());

        BinanceExchangeInfoResponse exchangeInfo = mexcApiService.getExchangeInfo();
        List<BinanceExchangeInfoResponse.SymbolInfo> candidatePairs = exchangeInfo.getSymbols().stream()
                .filter(s -> investmentAssets.contains(s.getBaseAsset()) || investmentAssets.contains(s.getQuoteAsset()))
                .collect(Collectors.toList());

        Long lastSync = config.getLastSyncTimestamp();
        Long newSyncTimestamp = System.currentTimeMillis();

        for (BinanceExchangeInfoResponse.SymbolInfo pair : candidatePairs) {
            try {
                List<MexcTradeResponse> trades = mexcApiService.getMyTrades(
                        apiKey,
                        secretKey,
                        pair.getSymbol(),
                        lastSync,
                        newSyncTimestamp,
                        null
                );
                if (trades != null && !trades.isEmpty()) {
                    log.info("Syncing {} MexC trades for {}", trades.size(), pair.getSymbol());
                    for (MexcTradeResponse trade : trades) {
                        Transaction transaction = mapToTransaction(trade, pair, portfolio);
                        transactionService.saveIfAbsent(transaction);
                    }
                }
            } catch (Exception e) {
                log.error("Error syncing MexC trades for {}: {}", pair.getSymbol(), e.getMessage());
            }
        }

        config.setLastSyncTimestamp(newSyncTimestamp);
        userExchangeConfigRepository.save(config);
    }

    private Transaction mapToTransaction(
            MexcTradeResponse trade,
            BinanceExchangeInfoResponse.SymbolInfo pair,
            Portfolio portfolio
    ) {
        return Transaction.builder()
                .dateUtc(trade.getTimeAsLocalDateTime())
                .pair(pair.getSymbol())
                .executed(trade.getQty())
                .side(trade.getIsBuyer() ? OperationUtils.BUY_STRING : OperationUtils.SELL_STRING)
                .price(trade.getPrice())
                .symbol(pair.getBaseAsset())
                .paidWith(pair.getQuoteAsset())
                .paidAmount(trade.getQuoteQty())
                .feeAmount(trade.getCommission())
                .feeSymbol(trade.getCommissionAsset())
                .externalId(trade.getId() != null ? trade.getId().toString() : null)
                .exchangeName(ExchangeName.MEXC)
                .created(LocalDateTime.now())
                .createdBy("MexcSyncService")
                .portfolio(portfolio)
                .build();
    }
}
