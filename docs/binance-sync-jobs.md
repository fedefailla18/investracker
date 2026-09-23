# Binance Full-Sync Job Tracking

Design spec for the resumable, chunked job model behind `POST /transaction/sync/binance/full`.
Written before implementation (spec-driven) and kept up to date as the source of truth for
*why* this is shaped the way it is — see `BinanceFullSyncService` for the current implementation.

## Problem

Binance's full-history sync (spot trades, deposits, withdrawals, fiat orders, convert trades)
can run for hours against a years-old account. The original implementation:

- Wrapped the entire multi-hour sync in one `@Transactional` method — any failure rolled back
  everything already fetched in that run, with no way to resume.
- Was fire-and-forget: `BinanceAsyncSyncService` ran it on a background thread and only pushed a
  best-effort WebSocket message on completion/failure. Nothing was persisted mid-run; if the app
  restarted or the client wasn't connected, the outcome was lost.
- Had no per-data-type isolation: a rate-limit failure on withdrawals could abort trades that had
  already synced successfully in the same call (depending on where in the sequence it failed).

Binance's APIs enforce a mix of pagination strategies that make "just retry the whole thing" both
slow and risky:
- `/myTrades` and `/allOrders` support **cursor pagination** (`fromId`) but reject a `startTime`/
  `endTime` window wider than ~24h if both are set (the bug fixed in PR #64).
- `/capital/deposit/hisrec` and `/capital/withdraw/history` expose **no cursor** — only a 90-day
  time window, so pagination there is inherently window-based, not id-based.

A naive "retry from the start" risks both duplicate transactions (already-processed pages
re-inserted) and, if retries are abandoned too early, missing data.

## Design

### Job model

One `sync_job` row per `SyncEntityType` (`TRADES`, `DEPOSITS`, `WITHDRAWALS`, `FIAT_ORDERS`,
`CONVERT_TRADES`) per full-sync request, all sharing a `batch_id`. Each job is broken into
`sync_job_chunk` rows — the actual resumable unit of work:

| Entity type | Chunk = | Resume mechanism |
|---|---|---|
| `TRADES` | one symbol | `cursor_value` = last processed trade id, checkpointed after every page |
| `DEPOSITS` / `WITHDRAWALS` | one 90-day window | window either fully succeeds or is retried whole (no cursor available from Binance) |
| `FIAT_ORDERS` | one 90-day window × {deposit-type, withdraw-type} | same as above, `chunk_key` prefixed `D:`/`W:` |
| `CONVERT_TRADES` | one 30-day window | same as deposits/withdrawals |

```
sync_job                                sync_job_chunk
├─ id, batch_id                         ├─ id, sync_job_id (FK, cascade delete)
├─ user_id, portfolio_id                ├─ chunk_key (symbol, or "prefix?start-end")
├─ exchange_name, entity_type           ├─ window_start_time / window_end_time
├─ requested_start_time / end_time      ├─ cursor_value (TRADES only)
├─ status: PENDING→RUNNING→             ├─ status: PENDING→RUNNING→COMPLETED/FAILED
│          COMPLETED/FAILED             ├─ records_processed
├─ error_message, attempt_count         └─ error_message
└─ created_at/started_at/finished_at
```

Why per-entity-type jobs instead of one umbrella job: Binance rate-limits each endpoint
independently, and a withdrawals failure has nothing to do with trades succeeding — coupling them
into one status would either hide the trades success or block on an unrelated failure. The FE
shows 5 rows per full-sync trigger instead of one opaque status, each independently retryable.

**Deliberately separate from `binance_sync_progress`/`BinanceSyncProgress`** (added earlier,
migration `2026-04-27-038`): that table already implements a similar PENDING/IN_PROGRESS/
COMPLETED/FAILED model, but it's wired to a different, narrower feature —
`BinanceIntegrationController`'s diagnostic `POST /sync-all` / `GET /sync-status` / `GET
/raw-orders` endpoints, which sync raw orders only (one symbol at a time) into a diagnostic
table, not into `transactions`. It is live and unrelated; this design does not touch it.

### Why no `@Transactional` anywhere in the job path

The original bug's root cause was one `@Transactional` spanning the whole sync. The fix is not
"add finer-grained `@Transactional` boundaries" — it's the opposite: **no ambient transaction at
all** in `createSyncJobs`/`runJob`/the per-chunk runners. A Spring Data JPA repository method
called with no surrounding transaction commits immediately as its own atomic unit. So:

- `chunk.setStatus(RUNNING); syncJobChunkRepository.save(chunk);` commits before any Binance API
  call for that chunk is made — a crash between "marked RUNNING" and "chunk finished" is visible
  and retryable, not silently lost.
- For `TRADES`, `chunk.setCursorValue(lastTradeId); syncJobChunkRepository.save(chunk);` commits
  after every page (not just at chunk end) — a crash mid-symbol resumes from the last completed
  page, not from scratch.
- Every `transactionService.saveIfAbsent(tx)` call commits its own row immediately.

This means a retry only ever re-does work that didn't already commit — no separate
compensation/rollback logic needed.

### Deduplication under retry

Two layers, matching the existing `Transaction.externalId` + `uk_portfolio_exchange_extid`
unique constraint:
1. App-level check: `TransactionService.saveIfAbsent` looks up by
   `(portfolio, exchangeName, externalId)` before inserting.
2. DB-level: the unique constraint is the actual guarantee. `saveIfAbsent` now catches
   `DataIntegrityViolationException` and treats it as "already exists" (a clean skip) instead of
   letting it surface as an unhandled 500 — the app-level check alone isn't race-safe against two
   overlapping chunk retries both passing the check before either commits.

### Concurrency guard

A partial unique index prevents two active (PENDING/RUNNING) jobs of the same
`(user, exchange, entity_type)`:

```sql
CREATE UNIQUE INDEX uk_sync_job_active ON sync_job (user_id, exchange_name, entity_type)
    WHERE status IN ('PENDING', 'RUNNING');
```

`createSyncJobs` pre-checks via a repository query (fast-fail with a clear message) but the index
is the real guarantee — a race between two concurrent trigger requests is caught at insert time
and surfaces as `SyncJobAlreadyRunningException` → **409 Conflict**.

### Retry

`POST /transaction/sync/binance/jobs/{jobId}/retry` — only valid when the job is currently
`FAILED`. Resets **only its `FAILED` chunks** back to `PENDING` (chunks already `COMPLETED` are
left untouched) and re-runs the job, so a retry after a withdrawals rate-limit failure re-fetches
only the windows that failed, not the ones that already succeeded.

### `lastSyncTimestamp` and the incremental path

`UserExchangeConfig.lastSyncTimestamp` (used by the separate incremental `/transaction/sync/binance`
endpoint) only advances once **every** job in the batch reaches `COMPLETED`. A partially-failed
full sync must not make the incremental path assume history is complete up to the requested end
date — advancing it early would cause the incremental sync to silently skip a gap.

### API surface

| Method | Path | Purpose |
|---|---|---|
| POST | `/transaction/sync/binance/full` | Creates the (up to 5) jobs and starts them async. 202 with the created jobs, or 409 if one of that type is already active. |
| GET | `/transaction/sync/binance/jobs` | List jobs for the current user's Binance config, newest first. |
| GET | `/transaction/sync/binance/jobs/{jobId}` | Job detail + every chunk (to see exactly which date range/symbol failed). |
| POST | `/transaction/sync/binance/jobs/{jobId}/retry` | Retry a FAILED job (400 if not currently FAILED). |

WebSocket (`/user/queue/sync-status`) messages for this path are `JOB_FINISHED`/`JOB_CRASHED` with
a `jobId` — deliberately a "go refetch" ping, not the full job state, so the frontend has one
source of truth (the jobs endpoints) instead of trusting a socket payload that might arrive out
of order or be missed entirely. The frontend (`SyncJobsPanel` in `importer-porfolio`) also polls
every 4s while any job is PENDING/RUNNING, so the socket ping is a latency optimization, not a
requirement.

## Known limitations (carried over or accepted, not fixed here)

- Deposits/withdrawals/fiat/convert windows are still single-shot per 90/30-day window — if a
  window's true result set exceeds what Binance returns in one call, older code never paginated
  within a window and this design doesn't add that either (no evidence it's hit in practice; flag
  if it ever is).
- `TRADES` symbol discovery only considers currently-held assets (`getAccountInfo` balances) —
  an asset fully sold before the sync runs is never discovered, same limitation as before this
  design.
- Fully deleting a job's chunks (e.g. to force a from-scratch resync) isn't exposed via API yet —
  only chunk-level retry of FAILED chunks. A "hard reset" endpoint could be added if needed.

## Update (2026-09-16): portfolio targeting is now guarded

`createSyncJobs` originally resolved its target portfolio via the plain `PortfolioService.findOrSave`,
which would happily retarget *any* portfolio name the caller passed — including the user's own
manually-managed portfolio. That's since been hardened: it now goes through
`PortfolioService.resolveExchangePortfolio`, which refuses (400) to sync into anything but
Binance's own dedicated portfolio. See "Portfolio vs Exchanges" in
[architecture.md](architecture.md) for the full rationale — this was a real gap in the original
design here, not a hypothetical.

## Update (2026-09-23): orphaned jobs from a killed process now self-heal on restart

Hit in practice, not hypothetically: a full sync was triggered, the backend was restarted a few
seconds later (to pick up an unrelated migration), and every job from that batch was left sitting
in `PENDING`/`RUNNING` forever — one had actually started (`TRADES`), the other four never got a
thread from `syncTaskExecutor` before the process died. Nothing reconciled that state, and the
retry endpoint only accepts `FAILED` jobs, so these were stuck: not running, not retriable, no
worker coming back for them.

Fixed with `BinanceFullSyncService.reconcileOrphanedJobs()`, run once via
`SyncJobStartupReconciler` on `ApplicationReadyEvent`. Since this runs right after the app comes
up, any `sync_job` found `PENDING`/`RUNNING` at that moment cannot belong to *this* process — it
can only be left over from one that died. It marks those jobs `FAILED` (with a clear error
message) and resets any of their chunks still `RUNNING` back to `PENDING` — chunks already
`COMPLETED` (and their checkpointed `cursor_value` for `TRADES`) are untouched, so a subsequent
retry resumes rather than restarts. Deliberately does **not** auto-retry — a dev restart shouldn't
silently kick off a multi-hour Binance sync; the user (or the FE's `SyncJobsPanel`, which already
shows a retry button on `FAILED` jobs) decides when. MexC's full sync isn't covered — it's still
the older fire-and-forget design, not job-tracked.
