# Exchange Integrations

Configuration and synchronization specifications for Binance, MEXC, and InvertirOnline (IOL).

## Supported Integrations

| Provider | Auth Type | Sync Scope | Client Implementation |
|---|---|---|---|
| **Binance** | HMAC-SHA256 (API Key / Secret) | Trades, Deposits, Withdrawals, Fiat, Convert | Spring WebClient (`BinanceApiService`) |
| **MEXC** | HMAC-SHA256 (API Key / Secret) | Trades, Deposits, Withdrawals | Spring WebClient (`MexcApiService`) |
| **IOL** | OAuth2 Bearer (Username / Password) | Balances, Portfolios, Operations | Spring Cloud OpenFeign (`IolClient`) |

## Credential Management

Manage credentials via [`ExchangeConfigController`](../src/main/java/com/importer/fileimporter/controller/ExchangeConfigController.java) or [Swagger UI](http://localhost:9080/swagger-ui.html#/Exchange%20Config):

- `POST /api/exchange/config`: Encrypts credentials with AES-256 (`EncryptionService`) before persisting. Automatically initializes a matching portfolio.
- `GET /api/exchange/config`: Retrieves stored configs with masked secret fields.
- **IOL Credentials**: `apiKey` maps to username; `apiSecret` maps to password.

## Synchronization Endpoints

| Endpoint | Execution | Description |
|---|---|---|
| `POST /transaction/sync/binance?portfolio=<name>` | Sync | Incremental spot trades since `lastSyncTimestamp` for currently-held assets. |
| `POST /transaction/sync/mexc?portfolio=<name>` | Sync | Incremental spot trades since `lastSyncTimestamp`. |
| `POST /transaction/sync/binance/full?portfolio=<name>` | Async (202) | Full history (trades, deposits, withdrawals, fiat, convert). Status sent via WebSocket `/user/queue/sync-status`. |
| `POST /transaction/sync/mexc/full?portfolio=<name>` | Async (202) | Full history (trades, deposits, withdrawals). |

Optional parameters for full sync: `startDate`, `endDate` (epoch milliseconds; default: `2017-01-01` to current timestamp).

## Diagnostic & Direct Endpoints

Read-only proxy routes bypassing the internal accounting engine:

| Endpoint | Provider | Description |
|---|---|---|
| `GET /api/integration/binance/orders` | Binance | Orders for a symbol (`?symbol=BTCUSDT`) |
| `GET /api/integration/binance/my-trades` | Binance | Executed trades for a symbol |
| `GET /api/integration/binance/deposits` | Binance | Crypto deposit logs |
| `GET /api/integration/binance/withdrawals` | Binance | Crypto withdrawal logs |
| `GET /api/integration/binance/raw-orders` | Binance | Staged raw orders stored in database |
| `GET /api/integration/iol/profile` | IOL | Comitente account details and investor profile |
| `GET /api/integration/iol/account-statement` | IOL | ARS and USD balances across accounts |
| `GET /api/integration/iol/portfolio/{country}` | IOL | Positions by country (`argentina` \| `estados_unidos`) |
| `GET /api/integration/iol/operations` | IOL | Transaction history |

## Integration Invariants

- **Rate Limiting**: Enforces a 1,000 ms pause between paginated upstream requests.
- **Upstream Paging**: Binance trades and orders page by ID cursor; deposit/withdrawal requests chunk into 90-day windows.
- **IOL Error Translation**: Upstream 401/403 errors from IOL map to HTTP `424 Failed Dependency` in `IolIntegrationController` to preserve local app sessions.
