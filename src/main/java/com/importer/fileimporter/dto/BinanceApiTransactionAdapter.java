package com.importer.fileimporter.dto;

import com.importer.fileimporter.dto.integration.binance.BinanceTradeResponse;
import com.importer.fileimporter.utils.DateUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class BinanceApiTransactionAdapter extends TransactionCoinName {

    private final BinanceTradeResponse trade;
    private final String baseAsset;
    private final String quoteAsset;

    public BinanceApiTransactionAdapter(BinanceTradeResponse trade, String baseAsset, String quoteAsset) {
        this.trade = trade;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.coinName = baseAsset;
    }

    @Override
    public String getDate() {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(trade.getTime()), ZoneId.of("UTC"));
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public String getPair() {
        return trade.getSymbol();
    }

    @Override
    public String getSide() {
        return trade.getIsBuyer() ? "BUY" : "SELL";
    }

    @Override
    public BigDecimal getPrice() {
        return trade.getPrice();
    }

    @Override
    public BigDecimal getExecuted() {
        return trade.getQty();
    }

    @Override
    public BigDecimal getAmount() {
        return trade.getQuoteQty();
    }

    @Override
    public BigDecimal getFee() {
        return trade.getCommission();
    }

    @Override
    public String getSymbol() {
        return baseAsset;
    }

    @Override
    public String getFeeSymbol() {
        return trade.getCommissionAsset();
    }

    @Override
    public String getPaidWith() {
        return quoteAsset;
    }

    public Long getTradeId() {
        return trade.getId();
    }
}
