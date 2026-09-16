# CLAUDE.md

Guidance for AI agents and engineers working in the InvestTracker repository.

## System Invariants

- **Root Project**: `investracker`
- **Java Base Package**: `com.importer.fileimporter` (legacy package preserved by design)
- **PostgreSQL Schema**: `file_importer_schema` (database `importer_database`)
- **Default Application Port**: `9080`
- **Interactive Documentation**: `http://localhost:9080/swagger-ui.html` | `/api-docs`

## Essential Commands

```bash
# Infrastructure
docker compose -f docker/docker-compose.yml up -d       # Spin up PostgreSQL (5435) & Redis (6379)

# Build & Run
./gradlew build                                         # Full build
./gradlew bootRun                                       # Run locally on port 9080

# Testing
./gradlew test                                          # Unit tests (Spock)
./gradlew integrationTest                               # Integration tests (Testcontainers Postgres)
./gradlew test --tests '*CoinInformationFacadeSpec'     # Single test class
./gradlew test --tests '*CoinInformationFacadeSpec.should\ calculate\ transaction\ information*' # Single test method
./gradlew check                                         # All checks + coverage verification (minimum 75%)
./gradlew jacocoAllTestReport                           # Combined coverage report (build/reports/jacoco/allTests/html/index.html)
```

## Architecture & Layers

```
Controller → Facade → Service → Repository → Entity (JPA)
```

- **Controllers** (`controller/`): REST endpoints annotated with SpringDoc OpenAPI. Documented in [api-documentation-guide.md](docs/api-documentation-guide.md).
- **Facades** (`facade/`): Service orchestration and aggregation across domains (no core business logic).
- **Services** (`service/`): Core domain logic (accounting, parsing, exchange synchronization).
- **Repositories** (`repository/`): Spring Data JPA interfaces.
- **Adapters** (`dto/adapter/`): Ingestion parsers managed via `TransactionAdapterFactory` and `ProcessFileFactory`.
- **Database Migrations**: Liquibase changelogs in `src/main/resources/db/changelog/` (`db.changelog-master.xml`).

## Pricing Cache Stack

Historical crypto prices are immutable. The caching flow delegates via Spring AOP `@Cacheable`:

```
PricingFacade.getPrice()
  → HistoricalPriceCacheService.lookup()  [@Cacheable AOP proxy]
      → Redis "historicalPrice" cache     [Persistent, no TTL, key: hp:{symbol}:{pair}:{YYYY-MM-DDTHH}]
      → DB (price_history table)          [Covering index on (symbol, symbolpair, hour)]
      → CryptoCompareProxy                [Cold miss fallback]
```

- **Warmup**: `ProcessFileV2.warmPriceCache()` queries missing tuples in bulk per symbol and seeds Redis via `@CachePut`.
- **Spot Prices**: Cached in Caffeine with a 1-minute TTL in `PricingFacade`.

## Financial Accounting Core

- **Cost Basis Model**: Average Cost (AVCO). Buys increase `inventoryCostUsdt`; sells decrease cost proportionally (`avgCost = inventoryCostUsdt / oldAmount`).
- **Normalized Stables** (`OperationUtils.STABLE`): `USDT`, `DAI`, `BUSD`, `USD`, `USDC`, `TUSD`, `FDUSD`. `UST`/`USTC` is strictly excluded.
- **Fee Handling**: Stablecoin fees on BUY orders increase cost basis. Non-stable fees (e.g., BNB) are persisted on `Transaction` for reference.
- **Metrics**:
  - `netCapitalFromPocket` = `totalBuySpentUsdt - totalSellEarnedUsdt`
  - `unrealizedProfitUsdt` = `currentPositionInUsdt - inventoryCostUsdt`
  - `totalRealizedProfitUsdt` = Sum of realized profits across all liquidated holdings.

## Exchange Sync & Integration Reference

All exchange credentials are AES-encrypted at rest (`EncryptionService`) and managed via `ExchangeConfigController` (`/api/exchange/config`).

| Endpoint | Service | Mode | Scope |
|---|---|---|---|
| `POST /transaction/sync/binance` | `BinanceSyncService` | Sync | Incremental spot trades for currently-held assets |
| `POST /transaction/sync/mexc` | `MexcSyncService` | Sync | Incremental MexC spot trades since `lastSyncTimestamp` |
| `POST /transaction/sync/binance/full` | `BinanceFullSyncService` | Async (202) | Full history (trades, deposits, withdrawals, fiat, convert). Updates sent via WebSocket `/user/queue/sync-status` |
| `POST /transaction/sync/mexc/full` | `MexcFullSyncService` | Async (202) | Full history (trades, deposits, withdrawals) |
| `GET /api/integration/iol/**` | `IolIntegrationService` | Live | On-demand proxy via `IolClient` (OpenFeign) with 14-min cached OAuth2 token |

## Testing Protocol

- Tests use **Spock Framework** (`Specification`) in Groovy.
- Unit tests live in `src/test/groovy/` using `Mock()`.
- Integration tests live in `src/integration-test/groovy/` extending `BaseIntegrationSpec` (Docker Testcontainers).
- JaCoCo enforces a minimum 75% coverage verification rule on `check`.
- DTOs and configuration packages are excluded from coverage enforcement.
