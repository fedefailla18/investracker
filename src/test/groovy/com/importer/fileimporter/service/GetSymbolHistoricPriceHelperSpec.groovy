package com.importer.fileimporter.service

import spock.lang.Specification

class GetSymbolHistoricPriceHelperSpec extends Specification {

    def cryptoCompareProxy = Mock(CryptoCompareProxy)
    def priceHistoryService = Mock(PriceHistoryService)

    GetSymbolHistoricPriceHelper helper = new GetSymbolHistoricPriceHelper(cryptoCompareProxy, priceHistoryService)

    def "getPrice(List) parses CryptoCompare's nested /pricemulti response into a per-symbol BTC/USDT map"() {
        given: "the real shape of a /pricemulti response — nested, not flat"
        cryptoCompareProxy.getData(['BTC', 'ETH', 'RSR'], 'BTC,USDT') >> [
                BTC: [BTC: 1, USDT: 65000.0],
                ETH: [BTC: 0.05, USDT: 3250.5],
                RSR: [BTC: 0.0000001, USDT: 0.0065],
        ]

        when:
        def prices = helper.getPrice(['BTC', 'ETH', 'RSR'])

        then:
        prices['BTC'] == [BTC: 1.0d, USDT: 65000.0d]
        prices['ETH'] == [BTC: 0.05d, USDT: 3250.5d]
        prices['RSR'] == [BTC: 0.0000001d, USDT: 0.0065d]
    }

    def "getPrice(List) returns an empty map when CryptoCompare returns null"() {
        given:
        cryptoCompareProxy.getData(_, _) >> null

        when:
        def prices = helper.getPrice(['BTC'])

        then:
        prices == [:]
    }
}
