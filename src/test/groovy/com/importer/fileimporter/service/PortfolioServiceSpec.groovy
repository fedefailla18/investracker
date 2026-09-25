package com.importer.fileimporter.service

import com.importer.fileimporter.config.security.services.CurrentUserProvider
import com.importer.fileimporter.entity.ExchangeName
import com.importer.fileimporter.entity.Portfolio
import com.importer.fileimporter.entity.Transaction
import com.importer.fileimporter.entity.User
import com.importer.fileimporter.repository.PortfolioRepository
import org.springframework.dao.DataIntegrityViolationException
import spock.lang.Specification

class PortfolioServiceSpec extends Specification {

    def portfolioRepository = Mock(PortfolioRepository)
    def currentUserProvider = Mock(CurrentUserProvider)
    def transactionService = Mock(TransactionService)
    def portfolioService = new PortfolioService(portfolioRepository, currentUserProvider, transactionService)

    def "findOrSave should return existing portfolio for current user"() {
        given:
        def portfolioName = "TestPortfolio"
        def user = new User(username: "testuser")
        def portfolio = new Portfolio(name: portfolioName, user: user)

        and:
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser(portfolioName, user) >> Optional.of(portfolio)

        when:
        def result = portfolioService.findOrSave(portfolioName)

        then:
        result == portfolio
        0 * portfolioRepository.save(_ as Portfolio)
    }

    def "findOrSave should create new portfolio if not found for current user"() {
        given:
        def portfolioName = "TestPortfolio"
        def user = new User(username: "testuser")

        and:
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser(portfolioName, user) >> Optional.empty()

        when:
        def result = portfolioService.findOrSave(portfolioName)

        then:
        1 * portfolioRepository.save({ Portfolio p ->
            p.getName() == portfolioName && p.getUser() == user
        }) >> { Portfolio p -> p }
    }

    def "findOrSave should find portfolio by name when no current user"() {
        given:
        def portfolioName = "TestPortfolio"
        def portfolio = new Portfolio(name: portfolioName)

        and:
        currentUserProvider.getCurrentUser() >> null
        portfolioRepository.findAllByName(portfolioName) >> [portfolio]

        when:
        def result = portfolioService.findOrSave(portfolioName)

        then:
        result == portfolio
        0 * portfolioRepository.save(_ as Portfolio)
    }

    def "findOrSave should create new portfolio when no current user and portfolio not found"() {
        given:
        def portfolioName = "TestPortfolio"

        and:
        currentUserProvider.getCurrentUser() >> null
        portfolioRepository.findAllByName(portfolioName) >> []

        when:
        def result = portfolioService.findOrSave(portfolioName)

        then:
        1 * portfolioRepository.save({ Portfolio p ->
            p.getName() == portfolioName && p.getUser() == null
        }) >> { Portfolio p -> p }
    }

    // ── resolveExchangePortfolio ────────────────────────────────────────────────

    def "resolveExchangePortfolio auto-creates the dedicated exchange portfolio when it doesn't exist yet"() {
        given:
        def user = new User(username: "trader")
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser("BINANCE", user) >> Optional.empty()

        when:
        def result = portfolioService.resolveExchangePortfolio(null, ExchangeName.BINANCE)

        then: "a blank/null request defaults to the exchange's own name"
        1 * portfolioRepository.save({ Portfolio p -> p.name == "BINANCE" && p.exchangeName == ExchangeName.BINANCE }) >> { Portfolio p -> p }
        result.exchangeName == ExchangeName.BINANCE
    }

    def "resolveExchangePortfolio accepts the existing portfolio when it's already owned by this exchange"() {
        given:
        def user = new User(username: "trader")
        def existing = new Portfolio(name: "BINANCE", exchangeName: ExchangeName.BINANCE, user: user)
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser("BINANCE", user) >> Optional.of(existing)

        when:
        def result = portfolioService.resolveExchangePortfolio("BINANCE", ExchangeName.BINANCE)

        then:
        result == existing
        0 * portfolioRepository.save(_)
    }

    def "resolveExchangePortfolio refuses to sync into a manually-managed portfolio"() {
        given:
        def user = new User(username: "trader")
        def manual = new Portfolio(name: "MyStuff", exchangeName: null, user: user)
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser("MyStuff", user) >> Optional.of(manual)

        when:
        portfolioService.resolveExchangePortfolio("MyStuff", ExchangeName.BINANCE)

        then:
        thrown(PortfolioNotExchangeOwnedException)
        0 * portfolioRepository.save(_)
    }

    def "resolveExchangePortfolio refuses to sync Binance into a MexC-owned portfolio"() {
        given:
        def user = new User(username: "trader")
        def mexcPortfolio = new Portfolio(name: "MEXC", exchangeName: ExchangeName.MEXC, user: user)
        currentUserProvider.getCurrentUser() >> user
        portfolioRepository.findByNameAndUser("MEXC", user) >> Optional.of(mexcPortfolio)

        when:
        portfolioService.resolveExchangePortfolio("MEXC", ExchangeName.BINANCE)

        then:
        thrown(PortfolioNotExchangeOwnedException)
    }

    // ── consolidate ──────────────────────────────────────────────────────────────

    def "consolidate moves every source transaction onto the target portfolio"() {
        given:
        def user = new User(username: "trader")
        def source = new Portfolio(name: "BINANCE", exchangeName: ExchangeName.BINANCE, user: user)
        def target = new Portfolio(name: "MyStuff", exchangeName: null, user: user)
        def tx1 = Transaction.builder().id(1L).externalId("a").portfolio(source).build()
        def tx2 = Transaction.builder().id(2L).externalId("b").portfolio(source).build()

        portfolioRepository.findByNameAndUser("BINANCE", user) >> Optional.of(source)
        portfolioRepository.findByNameAndUser("MyStuff", user) >> Optional.of(target)
        transactionService.findByPortfolio(source) >> [tx1, tx2]

        when:
        def result = portfolioService.consolidate("BINANCE", "MyStuff", user)

        then:
        2 * transactionService.save({ Transaction t -> t.portfolio == target })
        result == [2, 0] as int[]
    }

    def "consolidate skips (not crashes on) a transaction that already exists on the target"() {
        given:
        def user = new User(username: "trader")
        def source = new Portfolio(name: "BINANCE", exchangeName: ExchangeName.BINANCE, user: user)
        def target = new Portfolio(name: "MyStuff", exchangeName: null, user: user)
        def tx = Transaction.builder().id(1L).externalId("dup").portfolio(source).build()

        portfolioRepository.findByNameAndUser("BINANCE", user) >> Optional.of(source)
        portfolioRepository.findByNameAndUser("MyStuff", user) >> Optional.of(target)
        transactionService.findByPortfolio(source) >> [tx]
        transactionService.save(_) >> { throw new DataIntegrityViolationException("uk_portfolio_exchange_extid") }

        when:
        def result = portfolioService.consolidate("BINANCE", "MyStuff", user)

        then:
        notThrown(DataIntegrityViolationException)
        result == [0, 1] as int[]
    }

    def "consolidate refuses a source that isn't an exchange portfolio"() {
        given:
        def user = new User(username: "trader")
        def source = new Portfolio(name: "MyStuff", exchangeName: null, user: user)
        def target = new Portfolio(name: "Other", exchangeName: null, user: user)
        portfolioRepository.findByNameAndUser("MyStuff", user) >> Optional.of(source)
        portfolioRepository.findByNameAndUser("Other", user) >> Optional.of(target)

        when:
        portfolioService.consolidate("MyStuff", "Other", user)

        then:
        thrown(IllegalArgumentException)
    }

    def "consolidate refuses a target that is itself an exchange portfolio"() {
        given:
        def user = new User(username: "trader")
        def source = new Portfolio(name: "BINANCE", exchangeName: ExchangeName.BINANCE, user: user)
        def target = new Portfolio(name: "MEXC", exchangeName: ExchangeName.MEXC, user: user)
        portfolioRepository.findByNameAndUser("BINANCE", user) >> Optional.of(source)
        portfolioRepository.findByNameAndUser("MEXC", user) >> Optional.of(target)

        when:
        portfolioService.consolidate("BINANCE", "MEXC", user)

        then:
        thrown(IllegalArgumentException)
    }
}
