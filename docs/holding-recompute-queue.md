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

**Gap found by review, not in the original version of this doc**: `DELETE /transaction/{id}`,
`DELETE /transaction`, and `DELETE /transaction/portfolio/{portfolioName}` (`TransactionController`)
delete rows directly via `transactionService.deleteById(...)` — none of them go through
`saveIfAbsent` either, and none of them currently trigger any recompute at all, so deleting a
transaction today leaves `Holding` overstated forever (a real, separate, pre-existing bug, same
category as `consolidate()`'s). Out of scope for step 3 below (that step is sync-path parity, not a
general bugfix sweep) but logged as a fast-follow: the fix is the same shape — after a successful
delete, mark `(portfolio, symbol)` dirty — except the recompute itself can't just "process the
unprocessed delta" the way it does for a new transaction, since deleting a transaction retroactively
changes a row that may already be marked `processed = true` and already folded into `Holding`. That
case needs the blunt `/unprocess`-style reset-then-replay this doc's "no full rebuild path" gap
describes below, not the incremental path — flagged, not solved, by this design.

### Worker

A new `HoldingRecomputeWorker` processes one PENDING task at a time: marks it RUNNING, calls the
exact same `coinInformationService.getCoinInformationResponse(symbol,
transactionService.findByPortfolioAndSymbol(portfolio, symbol))` that `ProcessFileV2` already
calls — no new business logic, pure reuse of the existing recompute — then marks
COMPLETED, or FAILED with `error_message` set on exception.

Triggered two ways, both active (confirmed with the user — not either/or):

1. **Near-immediate**: the `saveIfAbsent` hook fires an `@Async` nudge (dedicated
   `holdingRecomputeTaskExecutor`, small pool — this is lighter work than a Binance API sync),
   processing just that one task. **Correction from the original version of this doc**: the nudge
   cannot fire *right after the upsert* unconditionally — `BinanceSyncService.sync()`,
   `MexcSyncService.sync()`, and `MexcFullSyncService`'s sync methods are all `@Transactional`
   (only `BinanceFullSyncService` deliberately avoids it, per its own doc comment and
   binance-sync-jobs.md), so a `saveIfAbsent` call made from inside those three doesn't actually
   commit until the whole sync method returns — firing the nudge immediately would let the worker's
   thread try to read "current transactions for this symbol" against data that may not be visible
   yet (or may still roll back). The hook instead checks
   `TransactionSynchronizationManager.isSynchronizationActive()`: if there's an active transaction,
   it registers the nudge via `registerSynchronization(... afterCommit() { nudge } ...)`; if not
   (the `BinanceFullSyncService`/`saveIfAbsent`-with-no-ambient-transaction case, where the insert
   already committed by the time `saveIfAbsent` returns), it fires immediately. Same mark-dirty
   upsert either way — only the timing of the nudge changes.
2. **~20s `@Scheduled` poller** as a backstop, sweeping any PENDING task — covers the nudge being
   lost (e.g. the process restarting between the DB commit and the `@Async` method actually
   running) without the whole design depending on the nudge never failing.

Why both: the nudge gives near-real-time freshness for the common case; the poller is what actually
makes this *resilient to failure*, which was the explicit requirement — a poller-only design would
work but feel laggy, a nudge-only design would silently drop tasks on a badly-timed crash.

### Atomic claim, and `@Transactional` around the whole task (revised 2026-11-19)

**Correction from the original version of this doc**: the claim-and-process step is wrapped in a
single `@Transactional` boundary, not left to per-row auto-commit. This reverses the sync-job
model's "no ambient `@Transactional`" philosophy on purpose — see the reviewer-feedback update at
the bottom of this doc for why that philosophy doesn't transfer here.

`HoldingRecomputeWorker.processTask(taskId)`:

```java
@Transactional
public void processTask(UUID taskId) {
    int claimed = holdingRecomputeTaskRepository.claim(taskId); // UPDATE ... SET status='RUNNING', started_at=now() WHERE id=:taskId AND status='PENDING'
    if (claimed == 0) return; // already claimed by the poller or a concurrent nudge — not an error, just lost the race

    HoldingRecomputeTask task = holdingRecomputeTaskRepository.getReferenceById(taskId);
    try {
        coinInformationService.getCoinInformationResponse(task.getSymbol(),
                transactionService.findByPortfolioAndSymbol(task.getPortfolio(), task.getSymbol()));
        task.setStatus(COMPLETED);
        task.setFinishedAt(now());
    } catch (Exception e) {
        task.setStatus(FAILED);
        task.setErrorMessage(e.getMessage());
        task.setAttemptCount(task.getAttemptCount() + 1);
        task.setFinishedAt(now());
    }
}
```

The `claim()` update is the atomic-claim mechanism, and it works *because* it's the first statement
in the transaction: Postgres takes a row lock on that `UPDATE ... WHERE id=? AND status='PENDING'`,
so if the `@Async` nudge and the `@Scheduled` poller race for the same task, the second caller's
`UPDATE` blocks until the first transaction commits — and by then `status` is no longer `PENDING`,
so the second `UPDATE` affects 0 rows and that caller just returns. No `SELECT ... FOR UPDATE SKIP
LOCKED` or app-level lock needed; the partial unique index plus this conditional update is enough,
same spirit as `uk_sync_job_active`.

Wrapping the *whole* claim→recompute→complete sequence in one transaction also means a crash
mid-recompute rolls the claim back too — the task reverts to `PENDING` (not stuck `RUNNING`), ready
for the poller to retry on its own, no startup reconciler needed for the common in-process-crash
case (the reconciler below still exists for the "`RUNNING` left over from a process that no longer
exists at all" case, e.g. a kill -9 or OOM that doesn't get a chance to roll back cleanly before the
JVM dies).

### Side effect on a second symbol (`updatePaidWithHolding`)

**Gap found by review, not in the original version of this doc.** `CalculateAmountSpent.getAmountInUsdt`
— called from inside `calculateAndSetStableMetrics`'s per-transaction loop, i.e. from inside the
recompute this design triggers — has a side effect on a *different* symbol: for a SELL paid in a
non-stable asset (a crypto-to-crypto trade, e.g. selling ETH paid in BTC), it calls
`holdingService.updatePaidWithHolding(...)`, which directly mutates the **paid-with symbol's**
`Holding` row (`amount`, `totalAmountSold`) — not the symbol the task was created for.

This breaks the clean "one task, one symbol, one Holding row" model: recomputing ETH can silently
mutate BTC's `Holding` as a side effect, with no `HoldingRecomputeTask` ever created or claimed for
`(portfolio, BTC)` — so nothing coordinates that write against a separately-running task that might
*also* be recomputing BTC at the same moment from BTC's own transactions, and the mutation happens
outside the `@Transactional` claim described above only in the sense that it's a different table
than the one the claim was protecting — it still happens inside the same transaction as the rest of
`processTask`, so it's at least atomic with the rest of that task's work, just not *coordinated*
with BTC's own task lifecycle.

Fix folded into `HoldingRecomputeWorker.processTask`: after a successful recompute, if any of the
symbol's transactions in that batch were a non-stable-paid SELL, also mark `(portfolio,
paidWithSymbol)` dirty (same upsert as everywhere else). This doesn't need `CalculateAmountSpent`
itself to change — the worker already has the transaction list it just processed, so it can inspect
`paidWith`/`side` after the fact and issue the extra dirty-mark itself.

### Startup reconciler

`HoldingRecomputeStartupReconciler`, same `ApplicationReadyEvent` pattern as
`SyncJobStartupReconciler`: any task found RUNNING at boot cannot belong to the current process —
reset it to PENDING. Deliberately **not** FAILED (unlike orphaned sync jobs): a holding recompute
is cheap and idempotent, so there's no reason to make the user click retry on something the poller
will just pick back up on its own within ~20s.

### Folding in `consolidate()`

Today, `consolidate()` moves transactions via plain `save()` (one commit per row, no surrounding
`@Transactional`), never resets `processed`, never touches `Holding`. Fix, revised after review:

1. Wrap the whole method in `@Transactional`. Unlike the Binance full-sync jobs (hours, thousands
   of API pages — see binance-sync-jobs.md for why *that* path deliberately avoids
   `@Transactional`), `consolidate()` is a bounded, user-triggered, all-local-DB operation — a
   partial move left half-committed by a crash is a worse outcome than losing a few seconds of
   work to a rollback-and-retry. Same reasoning as the worker's claim transaction above.
2. After the move loop, for each distinct symbol actually moved, reset `processed = false` on
   those transactions and mark `(target, symbol)` **and** `(source, symbol)` dirty (upsert into
   `holding_recompute_task`). Both sides need a fresh number — source's holdings should drop to
   reflect what left, target's should pick up what arrived.

Making `consolidate()` transactional also answers a review question about reading intermediate
state: because the move only becomes visible on commit, and the dirty-mark upsert only happens
(inside that same transaction) once every row has been moved, nothing can observe a half-moved
state — a worker that already had an older task for `(source, symbol)` queued before `consolidate`
ran might still process against a stale transaction set if it happens to run concurrently, but that
run is simply superseded by the fresh task `consolidate` creates on commit; it's wasted work, not
an incorrect result.

### No FE indicator (this iteration)

Confirmed with the user 2026-10-29: backend-only. `GET /portfolio/distribution` keeps reading the
`Holding` table exactly as today; the window between "sync/consolidate committed" and "recompute
task finished" (bounded in practice by the near-immediate nudge) is accepted for this iteration.
Fast-follow candidate if it's ever visibly annoying: a `recomputePending: boolean` on
`HoldingDto`/`PortfolioDistribution`.

## Known limitations (accepted for this iteration, not fixed)

- No API surface to inspect task status (unlike sync jobs' `GET .../jobs`) — backend-only per the
  no-FE-indicator decision above. Natural extension point if an indicator is ever wanted.
- **FAILED tasks are retried by the poller with backoff, not left to wait for the next dirty-mark**
  (revised after review — see the 2026-11-19 update below for why "just wait for the next sync"
  underestimated how often that never happens in practice). The `@Scheduled` poller also sweeps
  `FAILED` tasks where `attempt_count < 5` and `finished_at` is older than a simple backoff
  (`attempt_count` minutes, capped), resetting them to `PENDING`. A task still FAILED after 5
  attempts is left FAILED with no further automatic retry — genuinely exhausted retries need a
  human look (bad price data, a malformed transaction), not another automatic attempt.
- `MexcSyncService`/`MexcFullSyncService` get this for free through the same `saveIfAbsent` hook —
  no separate design needed, unlike MexC full sync historically lagging Binance's job-tracking
  features.
- **No full-rebuild path** — this design only ever adds the delta from newly-`processed=false`
  transactions onto whatever's already in the `Holding` row (`OperationUtils.sumBigDecimal`,
  additive, never a from-scratch recompute). That's existing behavior, unchanged by this design,
  and it means the queue is only as correct as that accumulation already is — if `Holding` is ever
  known to be wrong (a bug upstream, manual DB surgery, a transaction deleted after being
  processed), the existing blunt admin tool — `POST /transaction/unprocess?symbol=` — is still the
  only way to force a true rebuild: it zeroes every portfolio's `Holding` for that symbol and
  resets `processed=false` on every transaction for that symbol **system-wide** (not
  portfolio-scoped), then relies on something re-triggering a recompute afterward. Once this queue
  exists, mark-dirty for the affected `(portfolio, symbol)` pairs could be added to the end of
  `/unprocess` so that "something re-triggering a recompute" is this queue instead of "manually
  re-upload a file" — worth doing, not in scope for the 5 steps below.
- Single-instance assumption: the startup reconciler treats any task found `RUNNING` at
  `ApplicationReadyEvent` as orphaned, same reasoning as `SyncJobStartupReconciler` — correct only
  because this app runs as one process. If it's ever horizontally scaled, this (and the matching
  assumption already baked into the sync-job model) needs a per-instance heartbeat or lease instead
  of "anything RUNNING at my boot must be dead."
- Transaction **deletes** (`DELETE /transaction/{id}` and friends) don't mark anything dirty —
  flagged above under "Where tasks get created," not fixed by this design (needs the no-full-rebuild
  gap above solved first, since a delete after processing can't just be replayed as a delta).

## Implementation plan — small, independently-shippable increments

Each step below is its own PR, leaves `main` in a working state, and is independently testable —
so if work pauses between any two of them, nothing is half-wired:

1. **Migration + entity + repository.** `holding_recompute_task` table
   (`2026-10-30-047-add-holding-recompute-task.sql` — next free migration number), the
   `HoldingRecomputeTask` entity, `HoldingRecomputeTaskRepository` with the upsert-as-dedupe query,
   the atomic `claim()` update, and the `uk_holding_recompute_task_active` partial unique index. No
   behavior change yet — dead code until wired up in step 3.
2. **Worker + poller.** `HoldingRecomputeWorker.processTask` (`@Transactional`: claim → reuse
   `getCoinInformationResponse` → mark the paid-with symbol dirty too if applicable → complete/fail,
   per the sections above), the `@Scheduled` ~20s poller (sweeps PENDING, and FAILED tasks eligible
   for backoff retry), the dedicated `holdingRecomputeTaskExecutor` bean. Still nothing creates
   tasks yet — testable by hand-inserting a PENDING row and confirming it gets picked up and the
   `Holding` row updates exactly once even if `processTask` is called twice concurrently for the
   same task id (the idempotency test this design is supposed to pass).
3. **Hook `saveIfAbsent`.** Mark-dirty on a genuine new insert (the `Optional.of(...)` success
   path only, never the duplicate-skip paths), fire the `@Async` nudge via the
   `isSynchronizationActive()` / `afterCommit()` branch described above. **This is the PR that
   actually turns the feature on** for all 4 sync paths at once — the one to review and roll out
   most carefully, since steps 1–2 change nothing observable on their own. Test specifically
   against `BinanceSyncService`/`MexcSyncService`/`MexcFullSyncService` (the three `@Transactional`
   ones) to confirm the nudge really waits for commit, not just against `BinanceFullSyncService`
   (the one with no ambient transaction, which would hide a bug here).
4. **Startup reconciler.** `HoldingRecomputeStartupReconciler`, mirroring
   `SyncJobStartupReconciler` — reset orphaned RUNNING rows to PENDING on `ApplicationReadyEvent`.
   With step 2's `@Transactional` claim, this mainly covers a hard process kill (`kill -9`, OOM)
   that didn't get a chance to roll back; an ordinary exception during `processTask` already rolls
   the claim back on its own.
5. **Fold in the `consolidate()` fix.** Wrap `consolidate()` in `@Transactional`; reset
   `processed = false` + mark dirty (both symbol *and* any paid-with symbols touched) on both
   portfolios after the move loop.

Status: **not started** — this document is the confirmed design (2026-10-29/30, revised 2026-11-19
after review), next session picks up at step 1.

## Update (2026-11-19): review — the queue alone doesn't make the incremental model safe

A reviewer went through this doc before any code was written and asked 14 pointed questions,
converging on one real concern: *the queue infrastructure (table, worker, poller, nudge,
reconciler) doesn't by itself make recompute correct under retries, consolidation, or concurrent
execution if the underlying model is still incremental* — and it is still incremental
(`CoinInformationService` only ever processes `transaction.isProcessed() == false` rows and adds
the delta onto the existing `Holding` via `OperationUtils.sumBigDecimal`; there is no
rebuild-from-scratch path except the blunt, system-wide `/unprocess` admin tool). That framing was
correct, and this update is what changed in the doc above as a result, mapped to each question:

1. **Still incremental, confirmed** — no rebuild-from-scratch path was added or found. The queue
   design doesn't change this; it changes *when* the existing incremental step runs, not *what* it
   does.
2. **`consolidate()` and already-processed transactions** — confirmed unfixed in current code
   (still plain `save()`, never resets `processed`, never touches `Holding`). Addressed above:
   `consolidate()` becomes `@Transactional`, resets `processed=false`, and marks both sides dirty.
3. **Worker idempotency** — was a real gap in the original doc (nothing stopped the nudge and the
   poller from both running `processTask` for the same row). Fixed with the atomic `claim()` update
   described above; re-running `processTask` on an already-COMPLETED task is a no-op (`claimed ==
   0`), not a double-count.
4. **Atomicity** — was a real gap (mark-processed and holding-update were two separate commits in
   the current code, and would have stayed that way). Fixed by wrapping the whole claim→recompute→
   complete sequence in one `@Transactional` method — a deliberate reversal of the sync-job model's
   "no ambient transaction" philosophy, justified because a holding recompute is cheap and bounded,
   unlike a multi-hour Binance sync.
5. **A new transaction arriving mid-RUNNING-task** — resolved by the same atomic-claim mechanism:
   the dirty-mark upsert for the new transaction blocks on the partial unique index until the
   RUNNING task's transaction resolves, then either finds no conflicting active row (task
   completed) and inserts a fresh PENDING task, or finds one still active (task somehow still
   running) and no-ops — either way nothing is lost, see "Side effect on a second symbol" and
   "Atomic claim" sections above.
6. **Nudge firing after commit** — was a real gap: 3 of the 4 sync services (`BinanceSyncService`,
   `MexcSyncService`, `MexcFullSyncService`) wrap their sync method in `@Transactional`, so a nudge
   fired immediately after `saveIfAbsent` returns would race the outer commit. Fixed by checking
   `TransactionSynchronizationManager.isSynchronizationActive()` and deferring to `afterCommit()`
   when there's an active transaction, firing immediately otherwise.
7. **Concurrency between the nudge and the poller** — same atomic `claim()` answers this; no
   separate lease/lock needed beyond the row lock the claiming `UPDATE` itself takes.
8. **Startup recovery distinguishing abandoned vs. still-running** — the reconciler's "anything
   RUNNING at my boot is orphaned" logic is only sound because this app runs as a single instance,
   same assumption the existing `SyncJobStartupReconciler` already makes. Documented explicitly as
   a known limitation now rather than left implicit.
9. **FAILED retry policy** — the original doc's "wait for the next dirty-mark" was too optimistic
   (plenty of realistic failures, like a pricing-service outage, have no reason to ever dirty that
   symbol again). Revised: the poller also retries FAILED tasks with a simple backoff, capped at 5
   attempts.
10. **Write-path coverage** — `saveIfAbsent` is still the only insert path across all 4 sync
    services (confirmed, nothing new appeared), but transaction **deletes** were found to be an
    uncovered write path (they don't call `saveIfAbsent` and don't trigger any recompute today).
    Logged as a known, explicitly out-of-scope gap for this design (see "Known limitations") since
    fixing it properly needs the no-full-rebuild gap solved first.
11. **Consistency during `consolidate()`** — resolved by making `consolidate()` itself
    `@Transactional`; see point 2 and the "Folding in `consolidate()`" section above for why a
    stale-but-superseded read is the worst case, not a wrong one.
12. **Side effects via `updatePaidWithHolding`** — a real, previously-undocumented gap: a SELL paid
    in a non-stable asset mutates a *second* symbol's `Holding` as a side effect of recomputing the
    first. Fixed by having `processTask` also mark the paid-with symbol dirty when this happens —
    see "Side effect on a second symbol" above.
13. **Transaction edits/deletes marking old and new portfolio/symbol dirty** — there is no
    transaction-edit endpoint today (checked: no `PUT`/`PATCH` on `TransactionController`), so the
    "edit" half doesn't currently apply. The "delete" half is the same gap as point 10.
14. **`ProcessFileV2` vs. exchange sync unification** — confirmed still separate; this design
    doesn't unify them, by design (`ProcessFileV2`'s synchronous recompute right after upload is
    unchanged and is not the thing that was broken).
