package com.importer.fileimporter.payload.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BinanceSpotActivitySummaryResponse {
    private Integer activeAssetCount;
    private Integer candidatePairCount;
    private Integer symbolCountWithTrades;
    private Integer totalTradeCount;
    private Integer buyTradeCount;
    private Integer sellTradeCount;
    private BigDecimal grossBuyQuoteQty;
    private BigDecimal grossSellQuoteQty;
    private BigDecimal totalValueUsdt;
    private BigDecimal totalValueBtc;
    private Long fetchedAt;
    private Long lastSyncTimestamp;
}
