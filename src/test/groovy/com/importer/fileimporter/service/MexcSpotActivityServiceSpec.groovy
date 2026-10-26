package com.importer.fileimporter.service

import com.importer.fileimporter.dto.integration.mexc.MexcAccountResponse
import com.importer.fileimporter.entity.ExchangeName
import com.importer.fileimporter.entity.Portfolio
import com.importer.fileimporter.entity.Transaction
import com.importer.fileimporter.entity.User
import com.importer.fileimporter.entity.UserExchangeConfig
import com.importer.fileimporter.facade.PricingFacade
import com.importer.fileimporter.repository.UserExchangeConfigRepository
import spock.lang.Specification

import java.math.BigDecimal
import java.time.LocalDateTime

class MexcSpotActivityServiceSpec extends Specification {

    def mexcApiService = Mock(MexcApiService)
    def userExchangeConfigRepository = Mock(UserExchangeConfigRepository)
    def encryptionService = Mock(EncryptionService)
    def transactionService = Mock(TransactionService)
    def portfolioService = Mock(PortfolioService)
    def pricingFacade = Mock(PricingFacade)

    def service = new MexcSpotActivityService(
            mexcApiService,
            userExchangeConfigRepository,
            encryptionService,
            transactionService,
            portfolioService,
            pricingFacade
    )

    def user = Mock(User)

    def setup() {
        pricingFacade.getPrices(_) >> [
                FET : [USDT: 0.5d, BTC: 0.00001d],
                USDT: [USDT: 1.0d, BTC: 0.0000153d],
                BTC : [USDT: 65000.0d, BTC: 1.0d],
        ]
    }

    private UserExchangeConfig configWith(Long lastSyncTimestamp = 123456L) {
        Mock(UserExchangeConfig) {
            getApiKey() >> 'api-key'
            getApiSecret() >> 'encrypted-secret'
            getLastSyncTimestamp() >> lastSyncTimestamp
        }
    }

    private MexcAccountResponse.AssetBalance balance(String asset, String free, String locked = '0') {
        Stub(MexcAccountResponse.AssetBalance) {
            getAsset() >> asset
            getFree() >> new BigDecimal(free)
            getLocked() >> new BigDecimal(locked)
        }
    }

    def "should return fresh balances and raw spot trade summary"() {
        given:
        userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC) >> Optional.of(configWith())
        encryptionService.decrypt('encrypted-secret') >> 'plain-secret'
        def accountInfo = Stub(MexcAccountResponse) {
            getBalances() >> [
                    balance('FET', '12.5'),
                    balance('USDT', '100'),
                    balance('BTC', '0'),
            ]
        }
        mexcApiService.getAccountInfo('api-key', 'plain-secret') >> accountInfo

        def portfolio = Mock(Portfolio)
        portfolioService.getByNameForUser(ExchangeName.MEXC.name(), user) >> Optional.of(portfolio)

        def buyTx = Transaction.builder()
                .side('BUY')
                .pair('FETUSDT')
                .symbol('FET')
                .paidWith('USDT')
                .externalId('1')
                .executed(new BigDecimal('10'))
                .paidAmount(new BigDecimal('25'))
                .feeAmount(new BigDecimal('0.01'))
                .feeSymbol('MX')
                .price(new BigDecimal('2.5'))
                .dateUtc(LocalDateTime.of(2023, 11, 14, 22, 13, 20))
                .exchangeName(ExchangeName.MEXC)
                .build()
        def sellTx = Transaction.builder()
                .side('SELL')
                .pair('FETUSDT')
                .symbol('FET')
                .paidWith('USDT')
                .externalId('2')
                .executed(new BigDecimal('2'))
                .paidAmount(new BigDecimal('6'))
                .feeAmount(new BigDecimal('0.01'))
                .feeSymbol('MX')
                .price(new BigDecimal('2.5'))
                .dateUtc(LocalDateTime.of(2023, 11, 14, 22, 15, 0))
                .exchangeName(ExchangeName.MEXC)
                .build()
        transactionService.findByPortfolio(portfolio) >> [buyTx, sellTx]

        when:
        def response = service.getSpotActivity(user)

        then:
        response.balances*.asset == ['USDT', 'FET']
        response.summary.activeAssetCount == 2
        response.summary.totalTradeCount == 2
        response.summary.buyTradeCount == 1
        response.summary.sellTradeCount == 1
        response.summary.symbolCountWithTrades == 1
        response.summary.grossBuyQuoteQty == new BigDecimal('25')
        response.summary.grossSellQuoteQty == new BigDecimal('6')
        response.summary.lastSyncTimestamp == 123456L
        response.trades[0].tradeId == 2L
        response.trades[0].side == 'SELL'
    }

    def "excludes DEPOSIT/WITHDRAW rows so a non-numeric externalId never reaches the trade mapper"() {
        given:
        userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC) >> Optional.of(configWith())
        encryptionService.decrypt('encrypted-secret') >> 'plain-secret'
        mexcApiService.getAccountInfo('api-key', 'plain-secret') >> Stub(MexcAccountResponse) {
            getBalances() >> []
        }

        def portfolio = Mock(Portfolio)
        portfolioService.getByNameForUser(ExchangeName.MEXC.name(), user) >> Optional.of(portfolio)

        def withdrawal = Transaction.builder()
                .side('WITHDRAW')
                .symbol('BTC')
                .externalId('non-numeric-ref-123')
                .executed(new BigDecimal('0.1'))
                .dateUtc(LocalDateTime.of(2023, 11, 14, 22, 0, 0))
                .exchangeName(ExchangeName.MEXC)
                .build()
        def buyTx = Transaction.builder()
                .side('BUY')
                .pair('FETUSDT')
                .symbol('FET')
                .paidWith('USDT')
                .externalId('1')
                .executed(new BigDecimal('10'))
                .paidAmount(new BigDecimal('25'))
                .price(new BigDecimal('2.5'))
                .dateUtc(LocalDateTime.of(2023, 11, 14, 22, 13, 20))
                .exchangeName(ExchangeName.MEXC)
                .build()
        transactionService.findByPortfolio(portfolio) >> [withdrawal, buyTx]

        when:
        def response = service.getSpotActivity(user)

        then:
        noExceptionThrown()
        response.trades.size() == 1
        response.trades[0].side == 'BUY'
    }

    def "sorts balances by USDT value, not raw quantity — a small-quantity/high-value asset outranks large-quantity/low-value dust"() {
        given: "144,200 RSR (dust) vs 0.01 BTC — BTC is worth far more despite the tiny quantity"
        userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.MEXC) >> Optional.of(configWith())
        encryptionService.decrypt('encrypted-secret') >> 'plain-secret'
        mexcApiService.getAccountInfo('api-key', 'plain-secret') >> Stub(MexcAccountResponse) {
            getBalances() >> [balance('RSR', '144200'), balance('BTC', '0.01')]
        }
        portfolioService.getByNameForUser(ExchangeName.MEXC.name(), user) >> Optional.empty()
        pricingFacade.getPrices(_) >> [
                RSR: [USDT: 0.001d, BTC: 0.0000000001d],
                BTC: [USDT: 65000.0d, BTC: 1.0d],
        ]

        when:
        def response = service.getSpotActivity(user)

        then: "BTC (0.01 * 65000 = 650 USDT) outranks RSR (144200 * 0.001 = 144.2 USDT) despite the far smaller raw quantity"
        response.balances*.asset == ['BTC', 'RSR']
        response.balances[0].valueUsdt == new BigDecimal('650')
        response.summary.totalValueUsdt == response.balances[0].valueUsdt + response.balances[1].valueUsdt
    }
}
