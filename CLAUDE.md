# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

InvestTracker is a Spring Boot application built for crypto investors to manage and track their portfolios. It specializes in historical transaction ingestion, precise cost-basis accounting, and tracking realized/unrealized profit/loss across market cycles.

**Naming history:** the project started as a single-purpose "upload a file, parse transactions" tool (hence the original name, `file-importer`) and grew into this. Renamed to `investracker` 2026-09-12 — GitHub repo, local directory, `settings.gradle` (`rootProject.name`), `spring.application.name`, and the Spring Boot main class (`InvestrackerApplication`, 2026-09-13) all say `investracker` now. Two things remain deliberately **not** renamed, since changing them has real risk/cost for zero user-facing benefit: the Java package (`com.importer.fileimporter`) and the Postgres schema (`file_importer_schema`) — both still carry the old name. Don't be surprised by the mismatch; it's intentional, not leftover debris.

## Build & Run Commands

```bash
./gradlew build              # Full build (compile + test + package)
./gradlew bootRun            # Run locally on port 9080
./gradlew test               # Unit tests only
./gradlew integrationTest    # Integration tests only (requires Docker)
./gradlew check              # All checks: unit + integration + coverage
```

**Authentication (JWT):**
```bash
# Register
curl -X POST http://localhost:9080/api/auth/register -H "Content-Type: application/json" -d '{"username":"user","email":"user@mail.com","password":"pwd"}'
# Login
curl -X POST http://localhost:9080/api/auth/login -H "Content-Type: application/json" -d '{"username":"user","password":"pwd"}'
# Authenticated Request
curl -H "Authorization: Bearer <TOKEN>" http://localhost:9080/api/holdings
```

**Single test:**
```bash
./gradlew test --tests com.importer.fileimporter.facade.CoinInformationFacadeSpec
# Specific method (escape spaces):
./gradlew test --tests '*CoinInformationFacadeSpec.should\ calculate\ transaction\ information*'
```

**Coverage reports:**
```bash
./gradlew jacocoAllTestReport   # Combined unit + integration coverage
# Reports at: build/reports/jacoco/jacocoAllTestReport/html/index.html
```

**Swagger UI:** `http://localhost:9080/swagger-ui.html`

## Local Dev with Docker

```bash
cd docker && docker-compose up -d   # Spins up PostgreSQL 13.1 (port 5435) + Redis 7 (port 6379)
cd docker && ./start-db.sh          # Alternative: PostgreSQL only
```

The `Dockerfile` at repo root is a **multi-stage build** — it compiles the jar from source (`./gradlew bootJar`, skipping tests) in a build stage, then copies it into a slim runtime image. It does not depend on a host-side `./gradlew build` having been run first; `docker build .` (or `docker compose build`) is self-sufficient and always reflects current source. Both stages use non-`-alpine` / `-jre-alpine` Temurin tags specifically because several `-alpine` Temurin tags are amd64-only and this needs to build natively on Apple Silicon too — check `docker manifest inspect <tag>` before changing either base image.

`project-hub/docker-compose.yml`'s `crypto-db`/`crypto-redis`/`crypto-api`/`crypto-ui` services (driven by `project-hub/wp`, e.g. `./wp run docker`) build this same Dockerfile and point at this **same** Postgres data directory (`docker/data`) on a **different** container (`crypto-db` vs this file's `postgres`) — don't run both docker-compose files' postgres service at the same time; only one process can hold the data directory's lock.

The script tears down existing containers, cleans the data volume, and rebuilds. Database: `importer_database`, schema: `file_importer_schema`. Redis is used as the historical price cache (no TTL — entries persist forever).

## Architecture

The app uses a strict layered architecture:

```
Controller → Facade → Service → Repository → Entity (JPA)
```

- **Controllers** (`/controller`): REST endpoints with SpringDoc/OpenAPI annotations
- **Facades** (`/facade`): Orchestration layer — coordinate multiple services; e.g., `CoinInformationFacade` aggregates holdings + transactions
- **Services** (`/service`): Core business logic. Key ones:
  - `HoldingService` — recalculates holdings from transactions
  - `CoinInformationService` — aggregates per-coin stats
  - `FileImporterService` — parses CSV/Excel uploads
  - `CryptoCompareProxy` — external pricing API integration
  - `HistoricalPriceCacheService` — Redis-backed `@Cacheable` AOP proxy; see Pricing Cache section below
- **Repositories** (`/repository`): Spring Data JPA
- **Adapters** (`/dto/adapter`): Exchange-specific transaction parsers (`BinanceTransactionAdapter`, `MexcTransactionAdapter`)

**Factory pattern** is used for plugging in exchange adapters: `TransactionAdapterFactory` and `ProcessFileFactory`.

## Testing

Tests are written in **Groovy + Spock** (not JUnit directly):
- Unit tests: `src/test/groovy/` — extend `Specification`, use `Mock()` and given/when/then blocks
- Integration tests: `src/integration-test/groovy/` — extend `BaseIntegrationSpec`, which starts a real PostgreSQL container via TestContainers

Coverage targets: 80% line, 70% branch. Config/DTO packages are excluded from JaCoCo.

## Pricing Cache Architecture

Historical crypto prices (fetched from CryptoCompare) are immutable — the price of BTC at 14:00 on a given date never changes. The caching stack is:

```
PricingFacade.getPrice()
  → HistoricalPriceCacheService.lookup()   ← @Cacheable AOP proxy (Spring)
      → Redis "historicalPrice" cache        ← persistent, no TTL, key: hp:{symbol}:{pair}:{YYYY-MM-DDTHH}
      → DB (price_history table)             ← indexed on (symbol, symbolpair, DATE_TRUNC('hour', time))
      → CryptoCompareProxy                   ← only hit on cold DB miss
```

**Key components:**
- `config/CacheConfig.java` — `@EnableCaching` + `RedisCacheManager`; `historicalPrice` cache uses `Duration.ZERO` (no TTL)
- `service/HistoricalPriceCacheService.java` — `@Cacheable` / `@CachePut` annotations; Spring AOP intercepts `lookup()` — no cache code in the method body
- `facade/PricingFacade.java` — delegates all historical lookups to `HistoricalPriceCacheService`; still holds Caffeine caches for current spot prices (1-min TTL)
- `repository/PriceHistoryRepository.java` — `findBySymbolAndPairAndHoursIn()` for batch warmup
- `service/PriceHistoryService.java` — `findAllForWarmup()` aggregates bulk results into a cache-key map

**Batch pre-warming** (`ProcessFileV2.warmPriceCache()`): Before processing a CSV batch, all unique (symbol, symbolpair, hour) tuples are collected, bulk-loaded from DB in one query per symbol, and written to Redis via `@CachePut`. This means the first transaction in a batch hits DB once; all subsequent transactions with the same symbol+date are Redis hits.

**DB indexes** (migration 041): `uidx_price_history_symbol_pair_hour` (unique, prevents duplicates) + `idx_price_history_lookup` (covering index on `high`).

## Database Migrations

Managed by **Liquibase**. Changelogs live in `src/main/resources/db/changelog/`. The master file is `db.changelog-master.xml`; individual changes go in `changes/`. Contexts: `development`, `production`.

## Key Config

`src/main/resources/application.yml` — database, JWT settings, Swagger paths, CryptoCompare API key, and file upload limits (10MB/50MB). Credentials in this file and `docker/docker-compose.yml` are hardcoded for local dev and should be externalized via environment variables before any production deployment.

## Financial Calculation Model

**Cost basis method**: Average Cost (AVCO). Every BUY adds to `stableTotalCost`; every SELL removes a proportional slice using `avgCost = stableTotalCost / oldAmount`.

**Stable coin list** (`OperationUtils.STABLE`): `USDT, DAI, BUSD, USD, USDC, TUSD, FDUSD`. UST/USTC is intentionally excluded — it depegged in May 2022 and must not be treated as $1.

**Fee handling**: When `feeSymbol` is a stable (e.g. USDT fee on Binance), `feeAmount` is added to `stableTotalCost` on BUY transactions — increasing cost basis correctly. Non-stable fees (e.g. BNB) are stored on the `Transaction` entity for reference but do not affect `stableTotalCost`.

**`HoldingDto` computed fields** (populated by `PortfolioDistributionFacade`):
- `unrealizedProfitUsdt` = `currentPositionInUsdt - stableTotalCost` (paper P&L on open position)

**`PortfolioDistribution` computed getters** (serialized into JSON by Jackson):
- `netCapitalFromPocket` = `totalBuySpentUsdt - totalSellEarnedUsdt` (real cash needed from wallet)
- `totalRealizedProfitUsdt` = sum of `holding.totalRealizedProfitUsdt` across all holdings
- `totalUnrealizedProfitUsdt` = sum of `holding.unrealizedProfitUsdt` across all holdings

## Exchange Integrations

Binance and MexC both support incremental and full-history sync:

| Endpoint | Service | Scope |
|----------|---------|-------|
| `POST /transaction/sync/binance?portfolio=` | `BinanceSyncService` | Incremental — fetches only trades since `lastSyncTimestamp`. Only spot trades for currently-held assets. |
| `POST /transaction/sync/mexc?portfolio=` | `MexcSyncService` | Incremental — fetches only MexC spot trades since `lastSyncTimestamp` for relevant account assets. |
| `POST /transaction/sync/binance/full?portfolio=&startDate=&endDate=` | `BinanceFullSyncService` | Full historical — spot trades, deposits, withdrawals, fiat orders, convert trades. `startDate`/`endDate` are epoch milliseconds (optional; defaults to 2017-01-01 → now). |
| `POST /transaction/sync/mexc/full?portfolio=&startDate=&endDate=` | `MexcFullSyncService` | Full historical — spot trades, deposits, withdrawals. `startDate`/`endDate` are epoch milliseconds (optional; defaults to 2017-01-01 → now). |

Key classes:
- **`ExchangeConfigController`** (`/api/exchange`) — `POST /config` saves/updates keys (secret AES-encrypted via `EncryptionService`); `GET /config` returns configs with masked secret (never returned).
- **`BinanceSyncService`** — incremental sync with 200ms rate-limit delay between candidate pairs.
- **`MexcSyncService`** — incremental MexC spot-trade sync using account balances + exchange symbols and `lastSyncTimestamp`.
- **`BinanceFullSyncService`** — full historical sync with 200ms rate-limit delay per API window. Accepts optional `startDate`/`endDate` epoch-ms params.
- **`MexcFullSyncService`** — full historical MexC sync for deposits, withdrawals, and spot trades.
- **`BinanceApiService`** — Spring `WebClient`-based; signs requests with HMAC-SHA256.
- **`MexcApiService`** — Spring `WebClient`-based MexC integration; signs requests with HMAC-SHA256.
- **`UserExchangeConfig`** entity — keyed by `(user, exchangeName)`. `ExchangeName` supports `BINANCE`, `MEXC`, and `IOL`. For IOL the `apiKey` field stores the username and `apiSecret` stores the AES-encrypted password.
- **DB migration**: `db/changelog/changes/2026-04-25-035-add-exchange-config.sql`.

If a user has no exchange config saved, sync services throw `IllegalArgumentException("<Exchange> API keys not configured for user")` — the FE checks for `"not configured"` in error responses.

**Rate limiting**: `rateLimitDelayMs = 1000ms` between every API call. Windows: spot trades 180 days, deposits/withdrawals 90 days (Binance limit), fiat orders 90 days, convert trades 30 days (Binance limit). On `-1003` (Too Many Requests) the service sleeps 60 s before continuing.

**Async full sync**: `POST /transaction/sync/binance/full` returns **202 Accepted** immediately. Work runs on `BinanceAsyncSyncService` (`@Async("syncTaskExecutor")`). On completion/failure a WebSocket STOMP message is pushed to `/user/queue/sync-status` via `SyncNotificationService`.

**WebSocket** (`config/WebSocketConfig.java`): STOMP endpoint `/ws` (SockJS-wrapped). JWT authenticated via `ChannelInterceptor` on the STOMP CONNECT frame. `/ws/**` is permitted in `WebSecurityConfig`. `AsyncConfig` defines the `syncTaskExecutor` thread pool (core=2, max=4).

**Known gaps in `BinanceFullSyncService`**:
- No deduplication guard — running twice creates duplicate deposits/withdrawals (no DB unique constraint). Use "Clear All Transactions" + re-sync as a workaround.
- `syncSpotTrades` only fetches trades for currently-held assets; fully-sold assets are skipped.
- Deposits/withdrawals are saved with `price = BigDecimal.ZERO`; cost basis for deposited assets is 0.

**Transaction delete**: `DELETE /transaction/{id}` — verifies the transaction belongs to the authenticated user's portfolio before deleting.

**Clear portfolio transactions**: `DELETE /transaction/portfolio/{portfolioName}` — verifies portfolio ownership, deletes all transactions for that portfolio (204 No Content).

**`transactions.pair` column**: `VARCHAR(32)` — increased from 12 to support EXTERNAL suffix deposits/withdrawals (migration `2026-04-26-036-increase-pair-column-length.sql`).

## IOL (InvertirOnline) Integration

Read-only integration with the Argentine broker InvertirOnline. The BE authenticates with IOL using an OAuth2 password-flow token retrieved from the user's stored credentials, then proxies portfolio, account, and operations data.

**Controller**: `IolIntegrationController` — base path `/api/integration/iol`

| Method | Path | Returns |
|--------|------|---------|
| GET | `/profile` | `IolProfileResponse` — user identity, investor profile, comitente account |
| GET | `/account-statement` | `IolAccountStatementResponse` — ARS/USD cuentas with totals |
| GET | `/portfolio/{country}` | `IolPortfolioResponse` — activos list (`country`: `argentina` \| `estados_unidos`) |
| GET | `/operations` | `List<IolOperationResponse>` — full operations history |
| GET | `/operations/{number}` | `IolOperationResponse` — single operation detail (filtered client-side from operations list) |

**Service**: `IolIntegrationService` — orchestrates calls to `IolApiService` and maps raw IOL API responses into FE-ready DTOs (applies USD exchange rates where applicable).

**API client**: `IolApiService` is a thin wrapper delegating to `IolClient` — a **Feign client** (`@FeignClient`, not `WebClient`; corrected 2026-09-12), which handles the actual HTTP calls. `IolTokenService` obtains the bearer token via `POST https://api.invertironline.com/token` with the user's credentials, cached 14 minutes via Caffeine (IOL tokens expire at 15), before every request chain.

**DTOs** (`dto/integration/iol/`): `IolProfileResponse`, `IolAccountStatementResponse`, `IolPortfolioResponse`, `IolOperationResponse` — all classes expose `exchangeRate` and USD-converted amounts alongside the original ARS values.

**Credential flow**: `IolIntegrationService` fetches the user's `UserExchangeConfig(exchangeName=IOL)`, decrypts the password via `EncryptionService`, and passes username + password to `IolApiService`. If no config exists, an `IllegalArgumentException` is thrown.

## API Documentation

Follow the patterns in `docs/api-documentation-guide.md`. Use `@Tag`, `@Operation`, `@ApiResponse`, and `@Parameter` annotations from SpringDoc. `TransactionController` is the reference example.
