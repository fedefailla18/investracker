package com.importer.fileimporter.facade;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.importer.fileimporter.service.GetSymbolHistoricPriceHelper;
import com.importer.fileimporter.service.HistoricalPriceCacheService;
import com.importer.fileimporter.service.PriceHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.importer.fileimporter.utils.OperationUtils.BTC;
import static com.importer.fileimporter.utils.OperationUtils.USDT;

@Service
@Slf4j
public class PricingFacade {

    private final PriceHistoryService priceHistoryService;
    private final GetSymbolHistoricPriceHelper getSymbolHistoricPriceHelper;
    private final HistoricalPriceCacheService historicalPriceCacheService;

    private final Cache<String, Map<String, Double>> priceCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    private final Cache<String, BigDecimal> singlePriceCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    public PricingFacade(PriceHistoryService priceHistoryService,
                         GetSymbolHistoricPriceHelper getSymbolHistoricPriceHelper,
                         HistoricalPriceCacheService historicalPriceCacheService) {
        this.priceHistoryService = priceHistoryService;
        this.getSymbolHistoricPriceHelper = getSymbolHistoricPriceHelper;
        this.historicalPriceCacheService = historicalPriceCacheService;
    }

    public BigDecimal getPriceInUsdt(String symbol, LocalDateTime dateTime) {
        return getPrice(symbol, USDT, dateTime);
    }

    public BigDecimal getPriceInBTC(String symbol, LocalDateTime dateTime) {
        return getPrice(symbol, BTC, dateTime);
    }

    public Optional<BigDecimal> findHighPrice(String symbol, String symbolPair, LocalDateTime dateTime) {
        return priceHistoryService.findHighPrice(symbol, symbolPair, dateTime);
    }

    public BigDecimal getPrice(String symbol, String symbolPair, LocalDateTime dateTime) {
        if (StringUtils.trimAllWhitespace(symbol).isEmpty()) {
            symbol = BTC;
        }
        if (!StringUtils.hasText(symbolPair)) {
            symbolPair = USDT;
        }
        if (dateTime == null) {
            dateTime = LocalDateTime.now();
        }
        return historicalPriceCacheService.lookup(
                symbol.toUpperCase(), symbolPair.toUpperCase(), dateTime);
    }

    public Map<String, Double> getPrices(String symbol) {
        return priceCache.get(symbol, k -> {
            try {
                Map<String, ?> price = getSymbolHistoricPriceHelper.getPrice(k);
                Map<String, Double> priceAsDouble = new HashMap<>();
                if (price == null || (price.get("Response") instanceof String && "Error".equals(price.get("Response")))) {
                    log.error("Error in pricingFacade when getting prices. Symbol: " + k);
                    return new HashMap<>();
                }
                for (Map.Entry<String, ?> entry : price.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        priceAsDouble.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
                    }
                }
                return priceAsDouble;
            } catch (Exception e) {
                log.error("Error in pricingFacade when getting prices. Symbol: " + k, e);
                return new HashMap<>();
            }
        });
    }

    public BigDecimal getCurrentMarketPrice(String symbol) {
        return singlePriceCache.get(symbol, k -> {
            try {
                return getSymbolHistoricPriceHelper.getCurrentMarketPriceInUSDT(k);
            } catch (Exception e) {
                log.error("Error fetching current market price for {}: {}", k, e.getMessage());
                return BigDecimal.ZERO;
            }
        });
    }

    /** Batch price lookup — see {@link GetSymbolHistoricPriceHelper#getPrice(List)}. */
    public Map<String, Map<String, Double>> getPrices(List<String> symbols) {
        return getSymbolHistoricPriceHelper.getPrice(symbols);
    }

}
