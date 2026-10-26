# Architecture & Engineering Standards

System architecture, layered design, execution flows, and engineering standards.

## Layered Architecture

```
Controller → Facade → Service → Repository → Entity (JPA)
```

| Layer | Responsibility | Key Standards |
|---|---|---|
| **Controller** | HTTP routing, request validation, OpenAPI metadata | SpringDoc annotations (`@Tag`, `@Operation`, `@ApiResponse`). |
| **Facade** | Multi-service orchestration and DTO transformation | No domain business logic. |
| **Service** | Core business logic, accounting rules, exchange sync | Pure domain logic. `BigDecimal` arithmetic only. |
| **Repository** | Data access layer | Spring Data JPA interfaces. |
| **Entity** | Relational mapping | JPA entities mapped to `file_importer_schema`. |

## Core Spring Modules

- **Spring Boot 2.7.15**: Core application runtime on port `9080`.
- **Spring Security**: Stateless JWT bearer filter chain (`JwtAuthenticationFilter`).
- **Spring Data JPA & Hibernate Spatial**: PostgreSQL persistence with PostGIS dialect.
- **Liquibase**: Relational schema migrations (`db.changelog-master.xml`).
- **Spring Data Redis**: High-performance persistent key-value cache for immutable historical prices.
- **Spring Cloud OpenFeign & Spring WebFlux**: Declarative REST (`IolClient`) and reactive HMAC-signed clients (`BinanceApiService`, `MexcApiService`).
- **Spring WebSocket**: STOMP messaging (`/ws`) for asynchronous full sync progress updates.

## Engineering Standards

- **Runtime Target**: JDK 11+ (runtime compatible with JDK 17/21; `sourceCompatibility = '11'`).
- **Financial Precision**: All calculations use `BigDecimal` with minimum 8 decimal places for crypto amounts, 10 for intermediate values, and `RoundingMode.HALF_UP`.
- **Dependency Injection**: Use Lombok `@RequiredArgsConstructor` constructor injection.
- **Extensibility**: Adapter factories (`TransactionAdapterFactory`, `ProcessFileFactory`) for ingestion formats.

## Request Execution Flows

### Ingestion Flow (File Upload)

```mermaid
graph TD
    Client -->|POST /transaction/upload/{portfolio}| Controller[TransactionController]
    Controller --> Factory{ProcessFileFactory}
    Factory --> Engine[ProcessFileV2]
    Engine --> Parser[FileImporterService]
    Parser --> AdapterFactory[TransactionAdapterFactory]
    AdapterFactory --> Adapter[Exchange Adapter]
    Adapter --> Persistence[TransactionService / DB]
    Persistence --> HoldingCalc[CoinInformationService]
    HoldingCalc --> Response[FileInformationResponse]
```

### Manual Transaction Flow

```mermaid
graph TD
    Client -->|POST /transaction| Controller[TransactionController]
    Controller --> Facade[TransactionFacade]
    Facade --> Pricing[PricingFacade - Fetch price if missing]
    Pricing --> DB[TransactionService - Persist Transaction]
    DB --> Recalc[Recalculate Holdings]
```

## Portfolio vs Exchanges: two comparable views, not one merged pile

The product deliberately keeps two ways of looking at a portfolio, and the backend enforces the
boundary between them:

- **Portfolio** (manual): transactions the user enters/uploads themselves — `POST /transaction`,
  `POST /transaction/upload/{portfolio}`. Lives in a portfolio whose `Portfolio.exchangeName` is
  `null`.
- **Exchanges** (synced): transactions pulled automatically from Binance/MexC via the sync
  endpoints. Lives in a portfolio whose `Portfolio.exchangeName` is set (`BINANCE`/`MEXC`),
  auto-created the first time the user saves that exchange's API keys
  (`ExchangeConfigController`).

The point is comparison: a user can see what they *think* they hold (manual) side-by-side with
what the exchange API actually reports (synced), and decide whether/when to reconcile them — not
have every sync click silently rewrite their manually-tracked numbers.

**Enforcement**: every sync entry point (`BinanceSyncService`, `BinanceFullSyncService`,
`MexcSyncService`, `MexcFullSyncService`) resolves its target portfolio through
`PortfolioService.resolveExchangePortfolio(name, exchangeName)`, not the older `findOrSave`.
It auto-creates the exchange's dedicated portfolio on first use, but throws
`PortfolioNotExchangeOwnedException` (→ HTTP 400) if asked to target a portfolio that's either
manually-managed (`exchangeName == null`) or owned by a *different* exchange. A sync can never
silently take over — or relabel — a portfolio the user manages by hand.

**Reconciling the two views** is an explicit, separate action: `POST /portfolio/consolidate?
source=<exchangePortfolio>&target=<manualPortfolio>` (`PortfolioService.consolidate`) moves every
transaction from the exchange portfolio onto the manual one. It only moves rows that aren't
already there (skips anything that would collide with the target's own
`(exchangeName, externalId)`, via the same unique constraint the dedup path uses), so it's safe
to run again after a later sync — it just picks up whatever's new. It only supports
exchange → manual; both directions between two manually-managed or two exchange portfolios are
rejected (400), since those aren't the comparison this feature is for.

**Trade transaction identity**: this only works because every synced trade carries a stable
`externalId` (the exchange's own trade id) and `exchangeName`. That wasn't always true —
`BinanceSyncService`/`MexcSyncService`'s incremental sync originally left trade rows untagged
(only deposits/withdrawals were tagged), making them indistinguishable from manual entries and
invisible to the `/binance-activity`/`/mexc-activity` pages, which filter by `exchangeName`.
Fixed 2026-09-16 alongside the portfolio-ownership guard above.

## Core API Routing

Interactive documentation: [Swagger UI](http://localhost:9080/swagger-ui.html).

| Domain | Base Route | Key Endpoints | Controller |
|---|---|---|---|
| **Transactions** | `/transaction` | `GET /filter`, `POST /`, `DELETE /{id}`, `POST /upload/{portfolio}`, `POST /information/all/{portfolio}` | [`TransactionController`](../src/main/java/com/importer/fileimporter/controller/TransactionController.java) |
| **Holdings** | `/holding` | `GET /`, `POST /add`, `POST /addMultiple` | [`HoldingController`](../src/main/java/com/importer/fileimporter/controller/HoldingController.java) |
| **Portfolios** | `/portfolio` | `GET /`, `GET /names`, `GET /mine` (with `exchangeName`), `POST /distribution`, `POST /consolidate`, `GET /download` | [`PortfolioController`](../src/main/java/com/importer/fileimporter/controller/PortfolioController.java) |
| **Exchange Config** | `/api/exchange` | `POST /config`, `GET /config` | [`ExchangeConfigController`](../src/main/java/com/importer/fileimporter/controller/ExchangeConfigController.java) |

## Accounting Rules

- **Cost Basis (AVCO)**: Weighted average cost. Buys accumulate `inventoryCostUsdt`; sells decrease cost basis proportionally (`avgCost = inventoryCostUsdt / oldAmount`).
- **Realized P&L**: Calculated on SELL: `proceedsUsdt - proportionalCostBasis`.
- **Unrealized P&L**: Mark-to-market position: `currentValueUsdt - inventoryCostUsdt`.
- **Stablecoin Anchors** (`OperationUtils.STABLE`): `USDT`, `DAI`, `BUSD`, `USD`, `USDC`, `TUSD`, `FDUSD` (fixed 1:1 USD valuation). `UST`/`USTC` is strictly non-stable.
