package com.importer.fileimporter.service

import com.importer.fileimporter.entity.Holding
import com.importer.fileimporter.entity.Portfolio
import com.importer.fileimporter.entity.Transaction
import com.importer.fileimporter.facade.PricingFacade
import com.importer.fileimporter.service.usecase.CalculateAmountSpent
import spock.lang.Specification

import java.math.RoundingMode
import java.time.LocalDateTime

class CoinInformationServiceSpec extends Specification {

    def pricingFacade = Mock(PricingFacade)
    def holdingService = Mock(HoldingService)
    def calculateAmountSpent = new CalculateAmountSpent(pricingFacade, holdingService)
    def transactionService = Mock(TransactionService)

    def coinInformationService = new CoinInformationService(pricingFacade, holdingService, calculateAmountSpent, transactionService)


    def "should return response even if all transactions are already processed"() {
        given:
        def symbol = "RLC"
        def portfolio = new Portfolio(name: "Test")
        def transactions = [
                new Transaction(symbol: symbol, side: "BUY", executed: 1, paidWith: "USDT", paidAmount: 100, processed: true, portfolio: portfolio)
        ]
        holdingService.getHolding(portfolio, symbol) >> new Holding(symbol: symbol, portfolio: portfolio, amount: 1, stableTotalCost: 100)
        pricingFacade.getCurrentMarketPrice(symbol) >> 150

        when:
        def result = coinInformationService.getCoinInformationResponse(symbol, transactions)

        then:
        result != null
        result.coinName == symbol
        result.amount == 1
    }

    def "should calculate cost in USDT when paidWith is non-stable"() {
        given:
        def symbol = "RLC"
        def portfolio = new Portfolio(name: "Test")
        def transaction = new Transaction(symbol: symbol, side: "BUY", executed: 1, paidWith: "BTC", paidAmount: 0.01, portfolio: portfolio)
        pricingFacade.getCurrentMarketPrice(_) >> 20000
        holdingService.getOrCreateByPortfolioAndSymbol(_, _) >> new Holding(symbol: symbol, portfolio: portfolio)

        when:
        def result = coinInformationService.getCoinInformationResponse(symbol, [transaction])

        then:
        result.stableTotalCost == 20000
        result.amount == 1

        and:
        1 * pricingFacade.getPriceInUsdt(_, _) >> 20000
    }

    def "should warn if attempting to sell more than held"() {
        given:
        def symbol = "RLC"
        def portfolio = new Portfolio(name: "Test")
        def transactions = [
                new Transaction(symbol: symbol, side: "BUY", executed: 1, paidWith: "USDT", paidAmount: 100, portfolio: portfolio,
                        dateUtc: LocalDateTime.parse("2024-01-03T10:00:00")),
                new Transaction(symbol: symbol, side: "SELL", executed: 2, paidWith: "USDT", paidAmount: 200, portfolio: portfolio,
                        dateUtc: LocalDateTime.parse("2024-01-03T10:00:00"))
        ]
        pricingFacade.getCurrentMarketPrice(_) >> 100
        holdingService.getOrCreateByPortfolioAndSymbol(_, _) >> new Holding(symbol: symbol, portfolio: portfolio)

        when:
        def result = coinInformationService.getCoinInformationResponse(symbol, transactions)

        then:
        result.amount == 0
        result.getTotalRealizedProfitUsdt() == 100
    }

    def "should correctly update holding fields including amountInUsdt"() {
        given:
        def symbol = "ETH"
        def portfolio = new Portfolio(name: "Test")
        def initialAmount = 2.5
        def currentPrice = 3000.0
        def expectedAmountInUsdt = initialAmount * currentPrice

        // Create a transaction that will result in the holding having the initialAmount
        def transaction = new Transaction(
            symbol: symbol, 
            side: "BUY", 
            executed: initialAmount, 
            paidWith: "USDT", 
            paidAmount: 2500.0, 
            portfolio: portfolio
        )

        // Mock the holding that will be updated
        def holding = new Holding(
            symbol: symbol,
            portfolio: portfolio,
            amount: BigDecimal.ZERO,
            amountInUsdt: BigDecimal.ZERO,
            totalAmountBought: BigDecimal.ZERO,
            totalAmountSold: BigDecimal.ZERO,
            inventoryCostUsdt: BigDecimal.ZERO,
            currentPositionInUsdt: BigDecimal.ZERO,
            totalRealizedProfitUsdt: BigDecimal.ZERO
        )

        // Mock dependencies
        pricingFacade.getCurrentMarketPrice(_) >> currentPrice
        holdingService.getOrCreateByPortfolioAndSymbol(portfolio, symbol) >> holding

        when:
        def result = coinInformationService.getCoinInformationResponse(symbol, [transaction])

        then:
        // Verify the holding was saved with the correct values
        1 * holdingService.save({ Holding savedHolding ->
            savedHolding.amount == initialAmount &&
            savedHolding.getAmountInUsdt().setScale(1, BigDecimal.ROUND_HALF_UP) == expectedAmountInUsdt.setScale(1, BigDecimal.ROUND_HALF_UP) &&
            savedHolding.totalAmountBought == initialAmount &&
            savedHolding.currentPositionInUsdt == expectedAmountInUsdt
        })

        // Verify the response has the correct values
        result.amount == initialAmount
        result.currentPrice == currentPrice
        result.currentPositionInUsdt == expectedAmountInUsdt
    }

    def "should return empty response when transactions list is empty"() {
        given:
        def symbol = "ETH"
        def transactions = []

        when:
        def result = coinInformationService.getCoinInformationResponse(symbol, transactions)

        then:
        result.getCoinName() == symbol
        result.getAmount() == BigDecimal.ZERO
        result.getStableTotalCost() == BigDecimal.ZERO
        result.getTotalTransactions() == 0
    }

    def "happy path: average cost sell reduces basis and realizes profit"() {
        given: "a portfolio and buy/sell transactions"
        def portfolio = new Portfolio(name: "Test")
        def symbol = "BTC"

        def buy1 = new Transaction(
                symbol: symbol, side: "BUY", executed: 1.0, paidWith: "USDT",
                paidAmount: 10000, portfolio: portfolio, dateUtc: LocalDateTime.parse("2024-01-01T10:00:00")
        )
        def buy2 = new Transaction(
                symbol: symbol, side: "BUY", executed: 1.0, paidWith: "USDT",
                paidAmount: 12000, portfolio: portfolio, dateUtc: LocalDateTime.parse("2024-01-02T10:00:00")
        )
        def sell1 = new Transaction(
                symbol: symbol, side: "SELL", executed: 1.5, paidWith: "USDT",
                paidAmount: 18000, portfolio: portfolio, dateUtc: LocalDateTime.parse("2024-01-03T10:00:00")
        )
        def txs = [buy2, sell1, buy1] // out of order on purpose

        and: "the holding is mocked"
        def holding = new Holding(symbol: symbol, portfolio: portfolio, amount: BigDecimal.ZERO)
        holdingService.getOrCreateByPortfolioAndSymbol(portfolio, symbol) >> holding

        and: "the current price is mocked"
        pricingFacade.getCurrentMarketPrice(symbol) >> new BigDecimal("12000") // Current price for remaining 0.5 BTC

        when: "calculating coin information response"
        def resp = coinInformationService.getCoinInformationResponse(symbol, txs)

        then: "the final state of the response is correct"
        resp.amount.setScale(1, RoundingMode.HALF_UP) == new BigDecimal("0.5")
        resp.totalAmountBought.setScale(1, RoundingMode.HALF_UP) == new BigDecimal("2.0")
        resp.totalAmountSold.setScale(1, RoundingMode.HALF_UP) == new BigDecimal("1.5")
        resp.stableTotalCost.setScale(1, RoundingMode.HALF_UP) == new BigDecimal("5500.0") // (10000+12000)/2 * 0.5
        resp.getTotalRealizedProfitUsdt().setScale(1, RoundingMode.HALF_UP) == new BigDecimal("1500.0") // 18000 - (1.5 * 11000 avg cost)
        resp.currentPositionInUsdt == new BigDecimal("6000.0") // 0.5 * 12000
    }

}
