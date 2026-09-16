--liquibase formatted sql
--changeset ffailla:2026-09-16-046-backfill-exchange-name-on-transactions
--comment: BinanceSyncService/MexcSyncService's incremental sync never tagged trade rows with
--comment: exchange_name (only deposits/withdrawals were) before 2026-09-16, so trades synced
--comment: before that fix are indistinguishable from manual entries and invisible on the
--comment: /binance-activity and /mexc-activity pages, which filter by exchange_name. Safe to
--comment: backfill: a transaction sitting in a portfolio that's already exchange-owned
--comment: (portfolio.exchange_name is set) could only have gotten there via that exchange's
--comment: sync path, per PortfolioService.resolveExchangePortfolio's guard - never a manual entry.

UPDATE file_importer_schema.transactions t
SET exchange_name = p.exchange_name
FROM file_importer_schema.portfolio p
WHERE t.portfolio_id = p.id
  AND t.exchange_name IS NULL
  AND p.exchange_name IS NOT NULL;
