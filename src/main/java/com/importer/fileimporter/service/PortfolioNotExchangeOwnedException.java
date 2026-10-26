package com.importer.fileimporter.service;

import com.importer.fileimporter.entity.ExchangeName;

/**
 * Thrown when an exchange sync (Binance/MexC) is asked to target a portfolio that is not that
 * exchange's dedicated auto-created portfolio — either a manually-managed portfolio
 * (exchangeName == null) or another exchange's portfolio. Exchange-synced data must never land
 * in a manually-managed portfolio: the product keeps "Portfolio" (manual) and "Exchanges"
 * (synced) as two comparable views, reconciled only via an explicit consolidate action, not by
 * a sync silently writing into whatever portfolio name was passed.
 */
public class PortfolioNotExchangeOwnedException extends RuntimeException {
    public PortfolioNotExchangeOwnedException(String portfolioName, ExchangeName exchangeName) {
        super("Portfolio '" + portfolioName + "' is not a " + exchangeName +
                " exchange portfolio (it's either manually managed or owned by a different exchange). " +
                "Sync must target the dedicated exchange portfolio (default: '" + exchangeName + "'); " +
                "use POST /portfolio/consolidate to bring exchange data into a manual portfolio instead.");
    }
}
