package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.integration.binance.BinanceAccountResponse;
import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.entity.User;
import com.importer.fileimporter.entity.UserExchangeConfig;
import com.importer.fileimporter.payload.response.BinanceAssetBalanceResponse;
import com.importer.fileimporter.payload.response.BinanceSpotActivityResponse;
import com.importer.fileimporter.payload.response.BinanceSpotActivitySummaryResponse;
import com.importer.fileimporter.payload.response.BinanceSpotTradeRowResponse;
import com.importer.fileimporter.repository.UserExchangeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BinanceSpotActivityService {

    private static final Set<String> QUOTE_CURRENCIES = Set.of(
            "USDT", "BUSD", "USDC", "BTC", "ETH", "BNB", "FDUSD", "USDS", "DAI", "TUSD", "EUR", "TRY"
    );

    private final BinanceApiService binanceApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;

    public BinanceSpotActivityResponse getSpotActivity(User user) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.BINANCE)
                .orElseThrow(() -> new IllegalArgumentException("Binance API keys not configured for user"));

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());

        // 1. Get real-time balances from API
        BinanceAccountResponse accountInfo = binanceApiService.getAccountInfo(apiKey, secretKey);
        List<BinanceAssetBalanceResponse> balances = mapNonZeroBalances(accountInfo);

        // 2. Fetch processed trades from local database instead of API crawl
        Portfolio portfolio = portfolioService.getByNameForUser(ExchangeName.BINANCE.name(), user)
                .orElse(null);

        List<Transaction> dbTransactions = portfolio != null 
                ? transactionService.findByPortfolio(portfolio)
                : List.of();

        List<BinanceSpotTradeRowResponse> trades = dbTransactions.stream()
                .filter(t -> t.getExchangeName() == ExchangeName.BINANCE)
                // DEPOSIT/WITHDRAW rows share this exchangeName tag but aren't trades — their
                // externalId is Binance's deposit txId, which for off-chain transfers is free text
                // (e.g. "Off-chain transfer 60285041508"), not a numeric trade id. mapToTradeRow
                // parses externalId as a Long, so including them here 500s the whole endpoint.
                .filter(t -> "BUY".equals(t.getSide()) || "SELL".equals(t.getSide()))
                .map(this::mapToTradeRow)
                .sorted(Comparator.comparing(BinanceSpotTradeRowResponse::getTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        BigDecimal grossBuyQuoteQty = trades.stream()
                .filter(trade -> "BUY".equals(trade.getSide()))
                .map(BinanceSpotTradeRowResponse::getQuoteQty)
                .filter(qty -> qty != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossSellQuoteQty = trades.stream()
                .filter(trade -> "SELL".equals(trade.getSide()))
                .map(BinanceSpotTradeRowResponse::getQuoteQty)
                .filter(qty -> qty != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BinanceSpotActivitySummaryResponse summary = BinanceSpotActivitySummaryResponse.builder()
                .activeAssetCount(balances.size())
                .symbolCountWithTrades((int) trades.stream().map(BinanceSpotTradeRowResponse::getSymbol).distinct().count())
                .totalTradeCount(trades.size())
                .buyTradeCount((int) trades.stream().filter(trade -> "BUY".equals(trade.getSide())).count())
                .sellTradeCount((int) trades.stream().filter(trade -> "SELL".equals(trade.getSide())).count())
                .grossBuyQuoteQty(grossBuyQuoteQty)
                .grossSellQuoteQty(grossSellQuoteQty)
                .fetchedAt(System.currentTimeMillis())
                .lastSyncTimestamp(config.getLastSyncTimestamp())
                .build();

        return BinanceSpotActivityResponse.builder()
                .summary(summary)
                .balances(balances)
                .trades(trades)
                .build();
    }

    private List<BinanceAssetBalanceResponse> mapNonZeroBalances(BinanceAccountResponse accountInfo) {
        if (accountInfo == null || accountInfo.getBalances() == null) {
            return List.of();
        }

        return accountInfo.getBalances().stream()
                .filter(balance -> balance.getFree().add(balance.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(balance -> BinanceAssetBalanceResponse.builder()
                        .asset(balance.getAsset())
                        .free(balance.getFree())
                        .locked(balance.getLocked())
                        .total(balance.getFree().add(balance.getLocked()))
                        .build())
                .sorted(Comparator.comparing(BinanceAssetBalanceResponse::getTotal, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    private BinanceSpotTradeRowResponse mapToTradeRow(Transaction t) {
        return BinanceSpotTradeRowResponse.builder()
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
