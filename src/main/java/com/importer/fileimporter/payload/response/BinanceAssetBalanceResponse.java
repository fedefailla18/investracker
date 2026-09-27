package com.importer.fileimporter.payload.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BinanceAssetBalanceResponse {
    private String asset;
    private BigDecimal free;
    private BigDecimal locked;
    private BigDecimal total;
    private BigDecimal valueUsdt;
    private BigDecimal valueBtc;
}
