package com.importer.fileimporter.service

import com.importer.fileimporter.entity.ExchangeName
import com.importer.fileimporter.entity.Portfolio
import com.importer.fileimporter.entity.Transaction
import com.importer.fileimporter.repository.TransactionRepository
import com.importer.fileimporter.utils.DateUtils
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import spock.lang.Specification

import java.time.LocalDateTime

class TransactionServiceSpec extends Specification {

    def transactionRepository = Mock(TransactionRepository)
    TransactionService transactionService = new TransactionService(transactionRepository)

    def "test getAllBySymbol"() {
        given:
        String symbol = "ETH"
        Pageable pageable = Pageable.unpaged()
        Page<Transaction> page = new PageImpl<>([])

        when:
        def result = transactionService.getAllBySymbol(symbol, pageable)

        then:
        1 * transactionRepository.findAllBySymbol(symbol, pageable) >> page
        result == page
    }

    def "test getAll"() {
        given:
        List<Transaction> transactions = []

        when:
        def result = transactionService.getAll()

        then:
        1 * transactionRepository.findAll() >> transactions
        result == transactions
    }

    def "test saveTransaction"() {
        given:
        String coinName = "BTC"
        String symbolPair = "USD"
        String date = "2023-01-01 00:00:00"
        String pair = "BTCUSD"
        String side = "BUY"
        BigDecimal price = BigDecimal.valueOf(20000)
        BigDecimal executed = BigDecimal.valueOf(1)
        BigDecimal amount = BigDecimal.valueOf(20000)
        BigDecimal fee = BigDecimal.valueOf(10)
        String origin = "test-origin"

        LocalDateTime dateTime = DateUtils.getLocalDateTime(date)
        Transaction transaction = Transaction.builder()
                .dateUtc(dateTime)
                .pair(pair)
                .executed(executed)
                .side(side)
                .price(price)
                .symbol(coinName)
                .paidWith(symbolPair)
                .paidAmount(amount)
                .created(LocalDateTime.now())
                .createdBy(origin)
                .feeAmount(fee)
                .modified(LocalDateTime.now())
                .modifiedBy(origin)
                .build()

        when:
        def result = transactionService.saveTransaction(coinName, symbolPair, date, pair, side, price, executed, amount, fee, origin, new Portfolio())

        then:
        1 * transactionRepository.save(_) >> transaction
        result == transaction
        result.symbol == coinName
        result.paidWith == symbolPair
        result.paidAmount == amount
        result.feeAmount == fee
    }

    def "saveIfAbsent skips a transaction that already exists by external id"() {
        given:
        Portfolio portfolio = new Portfolio()
        Transaction transaction = Transaction.builder()
                .externalId("ext-1")
                .exchangeName(ExchangeName.BINANCE)
                .portfolio(portfolio)
                .build()

        when:
        def result = transactionService.saveIfAbsent(transaction)

        then:
        1 * transactionRepository.findByPortfolioAndExchangeNameAndExternalId(portfolio, ExchangeName.BINANCE, "ext-1") >> Optional.of(transaction)
        0 * transactionRepository.save(_)
        !result.isPresent()
    }

    def "saveIfAbsent treats a concurrent unique-constraint violation as an already-existing row, not a crash"() {
        given: "two concurrent chunk retries both pass the pre-check before either commits"
        Portfolio portfolio = new Portfolio()
        Transaction transaction = Transaction.builder()
                .externalId("ext-2")
                .exchangeName(ExchangeName.BINANCE)
                .portfolio(portfolio)
                .build()

        when:
        def result = transactionService.saveIfAbsent(transaction)

        then:
        1 * transactionRepository.findByPortfolioAndExchangeNameAndExternalId(portfolio, ExchangeName.BINANCE, "ext-2") >> Optional.empty()
        1 * transactionRepository.save(transaction) >> { throw new DataIntegrityViolationException("uk_portfolio_exchange_extid") }
        notThrown(DataIntegrityViolationException)
        !result.isPresent()
    }
}
