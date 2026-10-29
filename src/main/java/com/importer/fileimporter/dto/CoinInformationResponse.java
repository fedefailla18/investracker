package com.importer.fileimporter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.importer.fileimporter.utils.OperationUtils.USDT;
import static java.math.BigDecimal.ZERO;

/**
 * This class keeps the information of a coin and soon it will be stored in a db.
 * amount: is the executed amount held of the coin
 * totalAmountBought: is the executed amount bought
 * totalAmountSold: is the executed amount historically sold
 * stableTotalCost: is the total amount paid
 * currentPositionInUsdt: is the amount times the current price
 * spent: is use to keep track of the spent amount by symbolPair
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class CoinInformationResponse {

    private String coinName;
    private BigDecimal amount;
    private BigDecimal totalAmountBought;
    private BigDecimal totalAmountSold;
    private BigDecimal stableTotalCost;

    private BigDecimal currentPrice;
    private BigDecimal currentPositionInUsdt;

    private BigDecimal totalExecuted; // Track total executed amount for average calculation
    private Map<String, BigDecimal> avgEntryPrice;
    private Map<String, BigDecimal> spent;
    private Map<String, BigDecimal> sold;

    private BigDecimal totalRealizedProfitUsdt; // Realized profit from sold transactions
    private BigDecimal unrealizedProfitUsdt; // currentPositionInUsdt - stableTotalCost (paper P&L on the open position)

    private int totalTransactions;
    private List<TransactionData> rows;

    public static CoinInformationResponse createEmpty(String coinName) {
        return CoinInformationResponse.builder()
                .amount(ZERO)
                .stableTotalCost(ZERO)
                .rows(new ArrayList<>())
                .coinName(coinName)
                .spent(new HashMap<>())
                .sold(new HashMap<>())
                .avgEntryPrice(new HashMap<>())
                .totalExecuted(ZERO)
                .totalAmountBought(ZERO)
                .totalAmountSold(ZERO)
                .currentPositionInUsdt(ZERO)
                .totalRealizedProfitUsdt(ZERO)
                .unrealizedProfitUsdt(ZERO)
                .totalExecuted(ZERO)
                .build();
    }

    public void addRows(TransactionData row) {
        rows.add(row);
        totalTransactions++;
    }

    public void setAvgEntryPriceInUsdt(BigDecimal priceInUsdt, BigDecimal executed, boolean isBuy) {
        avgEntryPrice.putIfAbsent(USDT, ZERO);
        if (isBuy) {
            stableTotalCost = stableTotalCost.add(priceInUsdt.multiply(executed));
            amount = amount.add(executed);
        } else {
            stableTotalCost = stableTotalCost.subtract(priceInUsdt.multiply(executed));
            amount = amount.subtract(executed);
        }
    }

    public void calculateAvgPrice() {
        if (totalExecuted.compareTo(BigDecimal.ZERO) > 0) {
            currentPositionInUsdt = stableTotalCost.divide(totalExecuted, 10, RoundingMode.HALF_UP);
        }
    }

    public void addTotalAmountBought(BigDecimal purchasedAmount, String transactionSide) {
        if (!StringUtils.hasText(transactionSide) || !transactionSide.equals("BUY")) {
            log.warn("transaction Side has to be a BUY");
            return;
        }

        Optional.ofNullable(this.totalAmountBought)
                .ifPresentOrElse(
                        amountBought -> setTotalAmountBought(amountBought.add(purchasedAmount)),
                        () -> setTotalAmountBought(ZERO.add(purchasedAmount))
                );
    }

    public void addTotalAmountSold(BigDecimal soldAmount, String transactionSide) {
        if (!StringUtils.hasText(transactionSide) || !transactionSide.equals("SELL")) {
            log.warn("transaction Side has to be a SELL");
            return;
        }

        Optional.ofNullable(this.totalAmountSold)
                .ifPresentOrElse(
                        totalSold -> setTotalAmountSold(totalSold.add(soldAmount)),
                        () -> setTotalAmountSold(soldAmount)
                );
    }

    public void addTotalRealizedProfit(BigDecimal payedAmount) {
        Optional.ofNullable(this.totalRealizedProfitUsdt)
                .ifPresentOrElse(
                        totalProfit -> setTotalRealizedProfitUsdt(totalProfit.add(payedAmount)),
                        () -> setTotalRealizedProfitUsdt(payedAmount)
                );
    }

    /**
     * Only add the spent for original spent transactions
     * @param paidWithSymbol
     * @param paidAmount
     */
    public void addSpent(String paidWithSymbol, BigDecimal paidAmount) {
        getSpent().computeIfPresent(paidWithSymbol, (k, v) -> paidAmount.add(v));
        getSpent().computeIfAbsent(paidWithSymbol, k -> paidAmount);
    }

    public void addSold(String paidWithSymbol, BigDecimal paidAmount) {
        getSold().computeIfPresent(paidWithSymbol, (k, v) -> paidAmount.add(v));
        getSold().computeIfAbsent(paidWithSymbol, k -> paidAmount);
    }
}
