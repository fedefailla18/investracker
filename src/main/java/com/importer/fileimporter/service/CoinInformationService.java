package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.CoinInformationResponse;
import com.importer.fileimporter.entity.Holding;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.facade.PricingFacade;
import com.importer.fileimporter.service.usecase.CalculateAmountSpent;
import com.importer.fileimporter.utils.OperationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Slf4j
public class CoinInformationService {

    private final PricingFacade pricingFacade;
    private final HoldingService holdingService;
    private final CalculateAmountSpent calculateAmountSpent;
    private final TransactionService transactionService;

    public CoinInformationResponse getCoinInformationResponse(String symbol, List<Transaction> transactions) {
        if (CollectionUtils.isEmpty(transactions)) {
            log.warn("No transactions found for symbol: {}", symbol);
            return CoinInformationResponse.createEmpty(symbol);
        }

        List<Transaction> unprocessedTransactions = transactions.stream()
                .filter(transaction -> !transaction.isProcessed())
                .collect(Collectors.toList());
        
        return calculateAndSetStableMetrics(symbol, unprocessedTransactions);
    }

    private CoinInformationResponse calculateAndSetStableMetrics(String symbol, List<Transaction> transactions) {
        CoinInformationResponse response = CoinInformationResponse.createEmpty(symbol);
        if (CollectionUtils.isEmpty(transactions)) {
            log.warn("No transactions found for symbol: {}", symbol);
            return response;
        }

        transactions.sort(Comparator.comparing(Transaction::getDateUtc,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        Portfolio portfolio = transactions.stream()
                .findFirst()
                .map(Transaction::getPortfolio)
                .orElseThrow(() -> new IllegalArgumentException("No portfolio found for transactions"));

        InventoryState inventoryState = new InventoryState();

        for (Transaction transaction : transactions) {
            String side = transaction.getSide();
            BigDecimal quantity = transaction.getExecuted();
            BigDecimal amountInStable = calculateAmountSpent.getAmountInUsdt(transaction, response, portfolio);

            if (OperationUtils.isBuy(side)) {
                applyBuy(inventoryState, quantity, amountInStable);
            } else {
                applySell(inventoryState, quantity, amountInStable.abs());
            }
            markTransactionProcessed(transaction);
        }

        response.setAmount(inventoryState.amountHeld);
        response.setStableTotalCost(inventoryState.inventoryCostUsdt);
        response.setTotalRealizedProfitUsdt(inventoryState.realizedProfitUsdt);
        response.setTotalAmountBought(inventoryState.totalBought);
        response.setTotalAmountSold(inventoryState.totalSold);

        BigDecimal currentMarketPrice = pricingFacade.getCurrentMarketPrice(symbol);
        BigDecimal currentMarketValue = currentMarketPrice.multiply(response.getAmount());

        response.setCurrentPrice(currentMarketPrice);
        response.setCurrentPositionInUsdt(currentMarketValue);
        response.setUnrealizedProfitUsdt(currentMarketValue.subtract(inventoryState.inventoryCostUsdt));

        setAndSaveHolding(symbol, response, portfolio);

        return response;
    }

    private BigDecimal processSellTransactions(List<Transaction> sellTransactions, CoinInformationResponse response,
                                               Portfolio portfolio, BigDecimal totalHeldAmount) {
        BigDecimal realizedProfit = BigDecimal.ZERO;
        for (Transaction transaction : sellTransactions) {
            BigDecimal sellQty = transaction.getExecuted();
            BigDecimal amountSold = sellQty.min(totalHeldAmount);

            if (sellQty.compareTo(totalHeldAmount) > 0) {
                log.warn("Attempting to sell more than held amount: " + transaction);
            }

            totalHeldAmount = totalHeldAmount.subtract(amountSold);
            response.setAmount(totalHeldAmount);

            BigDecimal amountSpentInUsdt = calculateAmountSpent.getAmountInUsdt(transaction, response, portfolio);
            realizedProfit = realizedProfit.subtract(amountSpentInUsdt);
            response.addTotalAmountSold(sellQty, transaction.getSide());
        }
        response.setAmount(totalHeldAmount);
        return realizedProfit;
    }

    private void markTransactionProcessed(Transaction transaction) {
        transaction.setProcessed(true);
        LocalDateTime now = LocalDateTime.now();
        transaction.setLastProcessedAt(now);
        transaction.setModified(now);
        transaction.setModifiedBy(this.getClass().getName());
        transactionService.save(transaction);
    }

    private void setAndSaveHolding(String symbol, CoinInformationResponse response, Portfolio portfolio) {
        Holding holding = holdingService.getOrCreateByPortfolioAndSymbol(portfolio, symbol);
        updateHoldingFields(holding, response);
        holdingService.save(holding);
    }

    /**
     * Updates the fields of a Holding entity with values from a CoinInformationResponse.
     * This method ensures that all updates are null-safe by using the OperationUtils.sumBigDecimal method.
     *
     * @param holding The Holding entity to update
     * @param response The CoinInformationResponse containing the new values
     */
    private void updateHoldingFields(Holding holding, CoinInformationResponse response) {
        // Null-safe updates for all fields
        holding.setAmount(OperationUtils.sumBigDecimal(holding.getAmount(), response.getAmount()));
        holding.setTotalAmountBought(OperationUtils.sumBigDecimal(holding.getTotalAmountBought(), response.getTotalAmountBought()));
        holding.setTotalAmountSold(OperationUtils.sumBigDecimal(holding.getTotalAmountSold(), response.getTotalAmountSold()));
        holding.setInventoryCostUsdt(OperationUtils.sumBigDecimal(holding.getInventoryCostUsdt(), response.getStableTotalCost()));
        holding.setCurrentPositionInUsdt(OperationUtils.sumBigDecimal(holding.getCurrentPositionInUsdt(), response.getCurrentPositionInUsdt()));
        holding.setTotalRealizedProfitUsdt(OperationUtils.sumBigDecimal(holding.getTotalRealizedProfitUsdt(), response.getTotalRealizedProfitUsdt()));

        // Set amountInUsdt to the current market value (amount * current price)
        // This ensures it reflects the current value of the holding in USDT
        if (response.getAmount() != null && response.getCurrentPrice() != null) {
            BigDecimal currentValueInUsdt = response.getAmount().multiply(response.getCurrentPrice());
            holding.setAmountInUsdt(OperationUtils.sumBigDecimal(holding.getAmountInUsdt(), currentValueInUsdt));
        } else {
            // Fallback to the already-computed current market value if amount or currentPrice is null
            holding.setAmountInUsdt(OperationUtils.sumBigDecimal(holding.getAmountInUsdt(), response.getCurrentPositionInUsdt()));
        }
    }

    // Java
// ... existing imports, annotations, fields ...

    private static final int AMOUNT_SCALE = 18;
    private static final int MONEY_SCALE = 8;

    private static class InventoryState {
        BigDecimal amountHeld = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        BigDecimal inventoryCostUsdt = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal realizedProfitUsdt = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal totalBought = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        BigDecimal totalSold = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }

    private void applyBuy(InventoryState s, BigDecimal qty, BigDecimal costUsdt) {
        if (qty == null || costUsdt == null) return;
        s.amountHeld = s.amountHeld.add(qty).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        s.totalBought = s.totalBought.add(qty).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        s.inventoryCostUsdt = s.inventoryCostUsdt.add(costUsdt).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void applySell(InventoryState s, BigDecimal qty, BigDecimal proceedsUsdt) {
        if (qty == null || proceedsUsdt == null) return;
        if (qty.compareTo(BigDecimal.ZERO) <= 0) return;
        // Guard: do not allow selling more than held (unless supporting short)
        if (qty.compareTo(s.amountHeld) > 0) {
            // You can throw, clamp, or log. Here we clamp to prevent negative inventory.
            log.warn("Attempting to sell more than held amount: {} > {}", qty, s.amountHeld);
            qty = s.amountHeld;
        }
        if (s.amountHeld.signum() == 0) return;

        BigDecimal avgCostPerUnit = s.inventoryCostUsdt
                .divide(s.amountHeld, MONEY_SCALE + 4, RoundingMode.HALF_UP);
        BigDecimal costOfSold = avgCostPerUnit.multiply(qty)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal realized = proceedsUsdt.subtract(costOfSold).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        s.realizedProfitUsdt = s.realizedProfitUsdt.add(realized).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        s.amountHeld = s.amountHeld.subtract(qty).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        s.totalSold = s.totalSold.add(qty).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        s.inventoryCostUsdt = s.inventoryCostUsdt.subtract(costOfSold).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (s.inventoryCostUsdt.signum() < 0) {
            // Protect against small negative due to rounding
            s.inventoryCostUsdt = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

}
