package com.importer.fileimporter.controller

import com.importer.fileimporter.entity.User
import com.importer.fileimporter.facade.PortfolioDistributionFacade
import com.importer.fileimporter.service.PortfolioService
import org.springframework.http.HttpStatus
import spock.lang.Specification

class PortfolioControllerSpec extends Specification {

    def portfolioDistributionFacade = Mock(PortfolioDistributionFacade)
    def portfolioService = Mock(PortfolioService)
    def controller = new PortfolioController(portfolioDistributionFacade, portfolioService)

    def user = Mock(User)

    def "consolidate returns 200 with the moved/skipped counts on success"() {
        given:
        portfolioService.consolidate("BINANCE", "MyStuff", user) >> ([3, 1] as int[])

        when:
        def response = controller.consolidate(user, "BINANCE", "MyStuff")

        then:
        response.statusCode == HttpStatus.OK
        response.body == [movedCount: 3, skippedCount: 1]
    }

    def "consolidate returns 400 when the source isn't an exchange portfolio"() {
        given:
        portfolioService.consolidate("MyStuff", "Other", user) >> {
            throw new IllegalArgumentException("Source portfolio 'MyStuff' isn't an exchange portfolio")
        }

        when:
        def response = controller.consolidate(user, "MyStuff", "Other")

        then:
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "consolidate returns 404 when a portfolio doesn't exist"() {
        given:
        portfolioService.consolidate("BINANCE", "Ghost", user) >> {
            throw new IllegalArgumentException("Target portfolio not found: Ghost")
        }

        when:
        def response = controller.consolidate(user, "BINANCE", "Ghost")

        then:
        response.statusCode == HttpStatus.NOT_FOUND
    }
}
