--liquibase formatted sql
--changeset ffailla:2026-09-15-044-add-sync-job-tracking
--comment: Add resumable job/chunk tracking for exchange full-history sync (starts with Binance)

CREATE TABLE file_importer_schema.sync_job (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    portfolio_id UUID NOT NULL,
    exchange_name VARCHAR(20) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    batch_id UUID NOT NULL,
    requested_start_time BIGINT NOT NULL,
    requested_end_time BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT fk_sync_job_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_sync_job_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio(id)
);

-- Guards against two concurrent full-syncs of the same (user, exchange, entity type):
-- the DB constraint is the real enforcement, not just the app-level pre-check.
CREATE UNIQUE INDEX uk_sync_job_active ON file_importer_schema.sync_job (user_id, exchange_name, entity_type)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_sync_job_batch ON file_importer_schema.sync_job(batch_id);
CREATE INDEX idx_sync_job_user_exchange ON file_importer_schema.sync_job(user_id, exchange_name);

CREATE TABLE file_importer_schema.sync_job_chunk (
    id UUID PRIMARY KEY,
    sync_job_id UUID NOT NULL,
    chunk_key VARCHAR(100) NOT NULL,
    window_start_time BIGINT,
    window_end_time BIGINT,
    cursor_value BIGINT,
    status VARCHAR(20) NOT NULL,
    records_processed INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    CONSTRAINT fk_sync_job_chunk_job FOREIGN KEY (sync_job_id) REFERENCES file_importer_schema.sync_job(id) ON DELETE CASCADE,
    CONSTRAINT uk_sync_job_chunk_key UNIQUE (sync_job_id, chunk_key)
);

CREATE INDEX idx_sync_job_chunk_job ON file_importer_schema.sync_job_chunk(sync_job_id);
