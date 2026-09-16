--liquibase formatted sql
--changeset ffailla:2026-09-16-045-rename-stable-total-cost-to-inventory-cost-usdt
--comment: Holding.inventoryCostUsdt has had no @Column override since the field was renamed from
--comment: stableTotalCost, so Hibernate expects a column named inventory_cost_usdt. The DB column
--comment: was never actually renamed to match, breaking every query that touches portfolio_holding
--comment: (SQLGrammarException: column holdings0_.inventory_cost_usdt does not exist).

ALTER TABLE portfolio_holding RENAME COLUMN stable_total_cost TO inventory_cost_usdt;
