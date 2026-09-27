package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.integration.CryptoCompareResponse;
import com.importer.fileimporter.entity.PriceHistory;
import com.sun.istack.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.importer.fileimporter.utils.OperationUtils.BTC;
import static com.importer.fileimporter.utils.OperationUtils.USDT;

@RequiredArgsConstructor
@Service
@Slf4j
public class GetSymbolHistoricPriceHelper {

    private final CryptoCompareProxy cryptoCompareProxy;
    private final PriceHistoryService priceHistoryService;

    public BigDecimal getPriceInUsdt(String symbol, BigDecimal price, LocalDateTime dateTime) {
        return getPriceBySymbol(symbol, price, dateTime, USDT);
    }

    public Map<String, Number> getPrice(String symbol) {
        String symbols;
        if (BTC.equals(symbol)) {
            symbols = USDT;
        } else if (USDT.equals(symbol)) {
            return Collections.emptyMap();
        } else {
            symbols = BTC + "," + USDT;
        }
        return getPricesAtDate(symbol, symbols);
    }

    public BigDecimal getCurrentMarketPriceInUSDT(String symbol) {
        Map<String, Number> price = getPrice(symbol);
        Number val = price.get(USDT);
        return val == null ? BigDecimal.ZERO : BigDecimal.valueOf(val.doubleValue());
    }

    @NotNull
    public BigDecimal getPricesAtDate(String fromSymbol, String toSymbol, LocalDateTime dateTime) {
        log.info(String.format("Fetching price for: %s, with: %s. Date: %s", toSymbol, fromSymbol, dateTime));
        CryptoCompareResponse cryptoCompareResponse = cryptoCompareProxy.getHistoricalData(fromSymbol, toSymbol,
                dateTime.toEpochSecond(ZoneOffset.UTC));

        CryptoCompareResponse.ChartData exactTime = getExactTimeExecuted(dateTime, cryptoCompareResponse);
        if (exactTime != null) {
            priceHistoryService.saveAll(fromSymbol, toSymbol, cryptoCompareResponse);
            return exactTime.getHigh().setScale(10, RoundingMode.DOWN);
        }
        return BigDecimal.ZERO;
    }

    @NotNull
    private Map<String, Number> getPricesAtDate(String symbol, String ...symbols) {
        String toSymbols = String.join(",", symbols);
        Map<?, ?> data = cryptoCompareProxy.getData(symbol, toSymbols);
        return (Map<String, Number>) data;
    }

    private BigDecimal getPriceBySymbol(String symbolPair, BigDecimal price, LocalDateTime dateTime, String symbol) {
        try {
            BigDecimal priceInUsdt;
            Optional<PriceHistory> usdtPriceHistory = priceHistoryService.findData(symbolPair, symbol, dateTime);

            if (usdtPriceHistory.isEmpty()) {
                priceInUsdt = getPricesAtDate(symbolPair, symbol, dateTime);
            } else {
                priceInUsdt = usdtPriceHistory.get().getHigh();
            }

            return price.multiply(priceInUsdt);
        } catch (Exception e ) {
            String msg = String.format("Error when requesting historical data for % on %", symbolPair, dateTime);
            log.error(msg, e);
            return BigDecimal.ZERO;
        }
    }

    private CryptoCompareResponse.ChartData getExactTimeExecuted(LocalDateTime dateTime, CryptoCompareResponse cryptoCompareResponse) {
        if (cryptoCompareResponse == null || "Error".equals(cryptoCompareResponse.getResponse()) || cryptoCompareResponse.getData() == null || cryptoCompareResponse.getData().getChartDataList() == null) {
            return null;
        }
        return cryptoCompareResponse.getData().getChartDataList().stream()
                .filter(e -> e.getTime() != null && e.getTime().getHour() == dateTime.getHour())
                .findFirst()
                .orElseGet(() -> cryptoCompareResponse.getData()
                        .getChartDataList().stream()
                        .findAny()
                        .orElse(null));
    }

    /**
     * Batch price lookup: for each requested symbol, its price in BTC and in USDT — one external
     * call regardless of how many symbols are requested. CryptoCompare's {@code /pricemulti}
     * (which {@link CryptoCompareProxy#getData(List, String)} hits) returns a nested map keyed by
     * symbol, e.g. {@code {"BTC": {"BTC": 1, "USDT": 65000}, "ETH": {...}}} — not the flat
     * {@code Map<String, Double>} this method used to (wrongly) claim, which would have thrown a
     * ClassCastException the moment anyone read a value as a Double. Never caught in practice
     * because nothing called this overload until now. Self-referential rates (BTC→BTC, USDT→USDT)
     * come back correctly from CryptoCompare as long as the caller includes BTC/USDT in {@code
     * symbols} whenever it needs a coherent cross-conversion table.
     */
    public Map<String, Map<String, Double>> getPrice(List<String> symbols) {
        Map<?, ?> raw = cryptoCompareProxy.getData(symbols, BTC + "," + USDT);
        Map<String, Map<String, Double>> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Double> pricesForSymbol = new HashMap<>();
            for (Map.Entry<?, ?> priceEntry : ((Map<?, ?>) entry.getValue()).entrySet()) {
                if (priceEntry.getValue() instanceof Number) {
                    pricesForSymbol.put(String.valueOf(priceEntry.getKey()), ((Number) priceEntry.getValue()).doubleValue());
                }
            }
            result.put(String.valueOf(entry.getKey()), pricesForSymbol);
        }
        return result;
    }
}
