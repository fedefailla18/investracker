package com.importer.fileimporter.entity;

/**
 * One of the independently-tracked data types a Binance full-history sync
 * pulls. Each full-sync request creates one {@link SyncJob} per type so a
 * failure in one (e.g. withdrawals rate-limited) never blocks or hides the
 * progress of the others.
 */
public enum SyncEntityType {
    TRADES,
    DEPOSITS,
    WITHDRAWALS,
    FIAT_ORDERS,
    CONVERT_TRADES
}
