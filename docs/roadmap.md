# Engineering Roadmap

Active technical debt, architectural priorities, and planned feature milestones.

## High-Priority Technical Debt

| Item | Context | Impact |
|---|---|---|
| Unify Cost Basis Field Naming | Coexistence of legacy `stableTotalCost` and `inventoryCostUsdt` across facades and DTOs. | Eliminates cross-layer naming ambiguities. |
| Include Trading Fees in Cost Basis | `Transaction.feeAmount` is persisted but not factored into `inventoryCostUsdt` on BUY trades. | Fixes cost basis understatement on fee-bearing trades. |
| Security Test Coverage | `JwtAuthenticationFilter`, `JwtService`, and `AuthController` currently lack automated tests. | Prevents silent auth regression and security vulnerabilities. |
| Holding Recalculation Batching | Multiple DB queries per holding update during transaction ingestion. | Resolves latency bottlenecks during large CSV imports. |

## Planned Capabilities

### Portfolio Analytics

- **Advanced Returns**: Time-Weighted Return (TWR) and Money-Weighted Return (MWR) engines.
- **Drawdown Analysis**: Historical peak-to-trough decline metrics.
- **Multi-Fiat Base**: Portfolio valuation in EUR, GBP, and ARS alongside USDT.
- **Tax Export**: Pre-formatted transaction exports for Koinly and CoinTracker.

### Infrastructure & Resilience

- **Asynchronous Ingestion**: Decouple file upload from holding updates using background event queues.
- **Fault Tolerance**: Resilience4j circuit breakers on external pricing calls (`CryptoCompareProxy`).
- **Database Optimization**: Composite indexes on `(portfolio_id, symbol, date_utc)`.
- **API Versioning**: Formal `/v1/` route prefixes for public contracts.
