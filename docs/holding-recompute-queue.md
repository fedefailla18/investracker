# Holding Recompute Queue

Design spec for making `Holding` rows recompute automatically after *any* transaction-writing
path, not just the manual upload path — asynchronously and resiliently, per the user's explicit
requirement (2026-10-29). Written before implementation (spec-driven) and kept up to date as the
source of truth for *why* this is shaped the way it is, the same convention as
[binance-sync-jobs.md](binance-sync-jobs.md). See "Implementation plan" at the bottom for how this
gets built incrementally.

## Problem

`GET /portfolio/distribution` reads the persisted `Holding` table — a write-time materialized
cache of `Transaction` rows, not a live computation (see
[architecture.md](architecture.md)). Today, only the manual-upload path (`ProcessFileV2`) ever
triggers the recompute that updates it (`CoinInformationService.getCoinInformationResponse`).
None of the 4 exchange sync paths do:

- `BinanceSyncService` (incremental)
- `BinanceFullSyncService` (job-tracked full history, see binance-sync-jobs.md)
- `MexcSyncService` (incremental)
- `MexcFullSyncService` (full history, fire-and-forget)

So a user who only ever syncs from an exchange and never uploads a file manually sees holdings
that never update after the first sync — found during the 2026-10-23 holdings-correctness
investigation (`active_tasks.md`), and confirmed as the highest-urgency bug from that pass.

Separately, `PortfolioService.consolidate()` moves transactions between portfolios via plain
`save()` but never resets `Transaction.processed` or touches either side's `Holding` rows — the
target's next recompute silently skips already-`processed` moved transactions, and the source
keeps phantom balances. Folded into this same design rather than fixed separately, since both are
really "something changed the transactions under a portfolio/symbol, and nothing told `Holding` to
catch up."

A full historical sync can touch years of transactions across many symbols, so recompute can't
just run synchronously inline with sync — and it can't be bare fire-and-forget either, or a crash
mid-recompute (or between "sync committed" and "recompute kicked off") silently leaves `Holding`
stale with nothing to notice or retry. Confirmed with the user: this needs the same
durable-and-resumable spirit as the sync-job model, not a quick `@Async` call with no tracking.

## Design

### Task model

One row per `(portfolio, symbol)` in a new `holding_recompute_task` table:

```
holding_recompute_task
├─ id
├─ portfolio_id (FK)
├─ symbol
├─ status: PENDING → RUNNING → COMPLETED/FAILED
├─ attempt_count
├─ error_message
└─ created_at / started_at / finished_at
```

Single-table, unlike `sync_job`/`sync_job_chunk` — a holding recompute's unit of work is already
small (one symbol, re-reading that portfolio's current transactions and rewriting one `Holding`
row), so there's no sub-chunking to track.

### Dedup / upsert-as-coalesce

A partial unique index, same pattern as `uk_sync_job_active`:

```sql
CREATE UNIQUE INDEX uk_holding_recompute_task_active ON holding_recompute_task (portfolio_id, symbol)
    WHERE status IN ('PENDING', 'RUNNING');
```

Marking a `(portfolio, symbol)` dirty is an upsert: if an active (PENDING/RUNNING) row already
exists for it, do nothing — the worker always re-reads current transactions when it actually runs,
not a snapshot taken at mark-dirty time, so one coalesced task still picks up everything. This is
what keeps 500 trades landing on the same symbol during one full-sync run from creating 500 tasks.

### Where tasks get created — one choke point, not four

Found while writing this spec, and it changes the shape of the fix for the better: all 4 sync
services insert transactions exclusively through `TransactionService.saveIfAbsent(Transaction)` —
11 call sites total (`BinanceSyncService` ×3, `MexcSyncService` ×1, `BinanceFullSyncService` ×5,
`MexcFullSyncService` ×3) — and it already returns `Optional<Transaction>`: present only on a
genuine new insert, empty on *every* kind of duplicate skip (both the pre-check and the
race-caught `DataIntegrityViolationException`). That return value is exactly the "this symbol just
got dirtied" signal needed, so the mark-dirty hook goes **once, inside `saveIfAbsent` itself** —
not duplicated across 4 services' 11 call sites, and automatically covers any future sync path that
also routes through it.

`ProcessFileV2` (manual upload) and `PortfolioService.consolidate()` both call
`transactionService.save(...)` directly, never `saveIfAbsent` — so this hook doesn't touch either.
That's correct for `ProcessFileV2` (it already does its own synchronous recompute right after,
unchanged by this design) and `consolidate()` needs explicit handling anyway (below), since moving
an existing transaction isn't a "new insert."

### Worker

A new `HoldingRecomputeWorker` processes one PENDING task at a time: marks it RUNNING, calls the
exact same `coinInformationService.getCoinInformationResponse(symbol,
transactionService.findByPortfolioAndSymbol(portfolio, symbol))` that `ProcessFileV2` already
calls — no new business logic, pure reuse of the existing recompute — then marks
COMPLETED, or FAILED with `error_message` set on exception.

Triggered two ways, both active (confirmed with the user — not either/or):

1. **Near-immediate**: the `saveIfAbsent` hook fires an `@Async` nudge (dedicated
   `holdingRecomputeTaskExecutor`, small pool — this is lighter work than a Binance API sync) right
   after the upsert, processing just that one task.
2. **~20s `@Scheduled` poller** as a backstop, sweeping any PENDING task — covers the nudge being
   lost (e.g. the process restarting between the DB commit and the `@Async` method actually
   running) without the whole design depending on the nudge never failing.

Why both: the nudge gives near-real-time freshness for the common case; the poller is what actually
makes this *resilient to failure*, which was the explicit requirement — a poller-only design would
work but feel laggy, a nudge-only design would silently drop tasks on a badly-timed crash.

### No ambient `@Transactional`

Same rationale as binance-sync-jobs.md: every `save()` in the task path (marking RUNNING, marking
COMPLETED/FAILED) commits independently, so a crash mid-recompute leaves the task plainly RUNNING —
visible and reconcilable, not silently lost, and not a half-committed `Holding` row either (since
`getCoinInformationResponse`'s own persistence is already its own unit of work, not touched by
this design).

### Startup reconciler

`HoldingRecomputeStartupReconciler`, same `ApplicationReadyEvent` pattern as
`SyncJobStartupReconciler`: any task found RUNNING at boot cannot belong to the current process —
reset it to PENDING. Deliberately **not** FAILED (unlike orphaned sync jobs): a holding recompute
is cheap and idempotent, so there's no reason to make the user click retry on something the poller
will just pick back up on its own within ~20s.

### Folding in `consolidate()`

Today, `consolidate()` moves transactions via plain `save()`, never resets `processed`, never
touches `Holding`. Fix: after the move loop, for each distinct symbol actually moved, reset
`processed = false` on those transactions and mark `(target, symbol)` **and** `(source, symbol)`
dirty (upsert into `holding_recompute_task`). Both sides need a fresh number — source's holdings
should drop to reflect what left, target's should pick up what arrived.

### No FE indicator (this iteration)

Confirmed with the user 2026-10-29: backend-only. `GET /portfolio/distribution` keeps reading the
`Holding` table exactly as today; the window between "sync/consolidate committed" and "recompute
task finished" (bounded in practice by the near-immediate nudge) is accepted for this iteration.
Fast-follow candidate if it's ever visibly annoying: a `recomputePending: boolean` on
`HoldingDto`/`PortfolioDistribution`.

## Known limitations (accepted for this iteration, not fixed)

- No API surface to inspect task status (unlike sync jobs' `GET .../jobs`) — backend-only per the
  no-FE-indicator decision above. Natural extension point if an indicator is ever wanted.
- A FAILED task has no automatic retry beyond the next time that `(portfolio, symbol)` gets
  dirtied again by a new transaction. Accepted because recompute is idempotent and cheap — unlike
  a multi-hour Binance job, there's little cost to just letting the next sync re-trigger it, and a
  manual trigger endpoint is a cheap thing to add later if a FAILED task ever needs to be forced
  sooner.
- `MexcSyncService`/`MexcFullSyncService` get this for free through the same `saveIfAbsent` hook —
  no separate design needed, unlike MexC full sync historically lagging Binance's job-tracking
  features.

## Implementation plan — small, independently-shippable increments

Each step below is its own PR, leaves `main` in a working state, and is independently testable —
so if work pauses between any two of them, nothing is half-wired:

1. **Migration + entity + repository.** `holding_recompute_task` table
   (`2026-10-30-047-add-holding-recompute-task.sql` — next free migration number), the
   `HoldingRecomputeTask` entity, `HoldingRecomputeTaskRepository` with the upsert-as-dedupe query
   and the `uk_holding_recompute_task_active` partial unique index. No behavior change yet — dead
   code until wired up in step 3.
2. **Worker + poller.** `HoldingRecomputeWorker` (processes one PENDING task, reuses
   `getCoinInformationResponse`), the `@Scheduled` ~20s poller, the dedicated
   `holdingRecomputeTaskExecutor` bean. Still nothing creates tasks yet — testable by hand-inserting
   a PENDING row and confirming it gets picked up and the `Holding` row updates.
3. **Hook `saveIfAbsent`.** Mark-dirty on a genuine new insert (the `Optional.of(...)` success
   path only, never the duplicate-skip paths), fire the `@Async` nudge. **This is the PR that
   actually turns the feature on** for all 4 sync paths at once — the one to review and roll out
   most carefully, since steps 1–2 change nothing observable on their own.
4. **Startup reconciler.** `HoldingRecomputeStartupReconciler`, mirroring
   `SyncJobStartupReconciler` — reset orphaned RUNNING rows to PENDING on `ApplicationReadyEvent`.
5. **Fold in the `consolidate()` fix.** Reset `processed = false` + mark dirty on both portfolios
   after the move loop.

Status: **not started** — this document is the confirmed design (2026-10-29/30), next session
picks up at step 1.
