package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.integration.mexc.MexcAccountResponse;
import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.entity.User;
import com.importer.fileimporter.entity.UserExchangeConfig;
import com.importer.fileimporter.payload.response.MexcAssetBalanceResponse;
import com.importer.fileimporter.payload.response.MexcSpotActivityResponse;
import com.importer.fileimporter.payload.response.MexcSpotActivitySummaryResponse;
import com.importer.fileimporter.payload.response.MexcSpotTradeRowResponse;
import com.importer.fileimporter.repository.UserExchangeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MexcSpotActivityService {

    private final MexcApiService mexcApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;

    public MexcSpotActivityResponse getSpotActivity(User user) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC)
                .orElseThrow(() -> new IllegalArgumentException("MexC API keys not configured for user"));

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());

        // 1. Get real-time balances from API
        MexcAccountResponse accountInfo = mexcApiService.getAccountInfo(apiKey, secretKey);
        List<MexcAssetBalanceResponse> balances = mapNonZeroBalances(accountInfo);

        // 2. Fetch processed trades from local database
        Portfolio portfolio = portfolioService.getByNameForUser(ExchangeName.MEXC.name(), user)
                .orElse(null);

        List<Transaction> dbTransactions = portfolio != null 
                ? transactionService.findByPortfolio(portfolio)
                : List.of();

        List<MexcSpotTradeRowResponse> trades = dbTransactions.stream()
                .filter(t -> t.getExchangeName() == ExchangeName.MEXC)
                // DEPOSIT/WITHDRAW rows share this exchangeName tag but aren't trades — see the
                // same filter (and why it's needed) in BinanceSpotActivityService.getSpotActivity.
                .filter(t -> "BUY".equals(t.getSide()) || "SELL".equals(t.getSide()))
                .map(this::mapToTradeRow)
                .sorted(Comparator.comparing(MexcSpotTradeRowResponse::getTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        BigDecimal grossBuyQuoteQty = trades.stream()
                .filter(trade -> "BUY".equals(trade.getSide()))
                .map(MexcSpotTradeRowResponse::getQuoteQty)
                .filter(qty -> qty != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossSellQuoteQty = trades.stream()
                .filter(trade -> "SELL".equals(trade.getSide()))
                .map(MexcSpotTradeRowResponse::getQuoteQty)
                .filter(qty -> qty != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MexcSpotActivitySummaryResponse summary = MexcSpotActivitySummaryResponse.builder()
                .activeAssetCount(balances.size())
                .symbolCountWithTrades((int) trades.stream().map(MexcSpotTradeRowResponse::getSymbol).distinct().count())
                .totalTradeCount(trades.size())
                .buyTradeCount((int) trades.stream().filter(trade -> "BUY".equals(trade.getSide())).count())
                .sellTradeCount((int) trades.stream().filter(trade -> "SELL".equals(trade.getSide())).count())
                .grossBuyQuoteQty(grossBuyQuoteQty)
                .grossSellQuoteQty(grossSellQuoteQty)
                .fetchedAt(System.currentTimeMillis())
                .lastSyncTimestamp(config.getLastSyncTimestamp())
                .build();

        return MexcSpotActivityResponse.builder()
                .summary(summary)
                .balances(balances)
                .trades(trades)
                .build();
    }

    private List<MexcAssetBalanceResponse> mapNonZeroBalances(MexcAccountResponse accountInfo) {
        if (accountInfo == null || accountInfo.getBalances() == null) {
            return List.of();
        }

        return accountInfo.getBalances().stream()
                .filter(balance -> balance.getFree().add(balance.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(balance -> MexcAssetBalanceResponse.builder()
                        .asset(balance.getAsset())
                        .free(balance.getFree())
                        .locked(balance.getLocked())
                        .total(balance.getFree().add(balance.getLocked()))
                        .build())
                .sorted(Comparator.comparing(MexcAssetBalanceResponse::getTotal, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    private MexcSpotTradeRowResponse mapToTradeRow(Transaction t) {
        return MexcSpotTradeRowResponse.builder()
                .symbol(t.getPair())
                .baseAsset(t.getSymbol())
                .quoteAsset(t.getPaidWith())
                .side(t.getSide())
                .tradeId(t.getExternalId() != null ? Long.parseLong(t.getExternalId()) : null)
                .price(t.getPrice())
                .qty(t.getExecuted())
                .quoteQty(t.getPaidAmount())
                .commission(t.getFeeAmount())
                .commissionAsset(t.getFeeSymbol())
                .time(t.getDateUtc().atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli())
                .buyer("BUY".equals(t.getSide()))
                .build();
    }
}
