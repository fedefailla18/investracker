# Accounting Scenarios & Financial Model

Mathematical specifications for portfolio accounting, cost basis calculation, and P&L attribution.

## Core Accounting Formulas

| Metric | Formula | Description |
|---|---|---|
| **Average Cost (AVCO)** | `avgCost = inventoryCostUsdt / amount` | Cost per coin unit |
| **Buy Processing** | `newInventoryCost = oldInventoryCost + (amount * price) + stableFee` | Increases holding quantity and total cost basis |
| **Sell Processing** | `costOfSold = executedAmount * avgCost`<br>`newInventoryCost = oldInventoryCost - costOfSold` | Proportional cost basis deduction |
| **Realized Profit/Loss** | `realizedProfit = (executedAmount * sellPrice) - costOfSold` | P&L recognized upon trade execution |
| **Unrealized Profit/Loss** | `unrealizedProfit = (amount * currentMarketPrice) - inventoryCostUsdt` | Mark-to-market paper gain/loss |
| **Net Capital Invested** | `netCapitalFromPocket = totalBuySpentUsdt - totalSellEarnedUsdt` | Net external cash deployed to portfolio |
| **Total Net Gain** | `(currentPortfolioValueUsdt + totalSellEarnedUsdt) - totalBuySpentUsdt` | Portfolio economic performance |

## Accounting Scenarios Matrix

| ID | Scenario | Event / Inputs | Expected Output |
|---|---|---|---|
| **A1** | Buy Averaging | Buy 0.5 BTC @ 20k, Buy 0.5 BTC @ 30k | Amount: `1.0 BTC`, Cost Basis: `25,000 USDT`, Avg Price: `25,000 USDT` |
| **A2** | Partial Sell (Profit) | Hold 1.0 BTC @ 25k cost; Sell 0.4 BTC @ 40k | Holding: `0.6 BTC`, Cost Basis: `15,000 USDT`, Realized Profit: `+6,000 USDT` |
| **A3** | Partial Sell (Loss) | Hold 1.0 BTC @ 60k cost; Sell 0.5 BTC @ 20k | Holding: `0.5 BTC`, Cost Basis: `30,000 USDT`, Realized Profit: `-20,000 USDT` |
| **A4** | Cumulative Sells | Sell 0.3 BTC @ 40k (+3k), Sell 0.3 BTC @ 50k (+6k) | Cumulative Realized Profit: `+9,000 USDT`, Remaining Holding: `0.4 BTC` |
| **B1** | Cross-Asset Trade | Buy 10 ETH with 0.5 BTC (ETH/BTC @ 0.05, ETH = 1,500 USDT) | ETH Holding: `10`, ETH Cost Basis: `7,500 USDT`, BTC Holding: `-0.5 BTC` (treated as BTC sell) |
| **C1** | Fresh Capital | Buy 1.0 BTC @ 40k USDT | `totalBuySpentUsdt = 40k`, `totalSellEarnedUsdt = 0`, `netCapitalFromPocket = 40k` |
| **C2** | Recycled Profit | Sell 0.5 BTC for 25k; Buy ETH for 12.5k | `totalBuySpent = 52.5k`, `totalSellEarned = 25k`, `netCapitalFromPocket = 27.5k` |
| **E1** | Open Position P&L | Hold 1.0 BTC @ 40k cost; Spot price = 60k | Unrealized Profit: `+20,000 USDT` |
| **H1** | Quote Stable Fee | Buy 1.0 BTC @ 50k with 100 USDT fee | Cost Basis: `50,100 USDT` (fee capitalized into inventory cost) |
| **H2** | Third-Currency Fee | Buy 100 FET @ 1 USDT with 0.01 BNB fee | FET Cost Basis: `100 USDT`; BNB fee logged for auditing without affecting FET cost |
| **J1** | Oversell Guard | Hold 0.5 BTC; Attempt sell of 1.0 BTC | Holding clamped to `0.0 BTC`; Realized profit calculated on 0.5 BTC; warning logged |
| **K1** | Multi-Portfolio Isolation | Portfolio A buys @ 40k; Portfolio B buys @ 30k | Positions, cost basis, and metrics remain fully isolated |

## Stablecoin Normalization

- **Pegged Anchors (1:1 USD)**: `USDT`, `DAI`, `BUSD`, `USD`, `USDC`, `TUSD`, `FDUSD` (via `OperationUtils.STABLE`).
- **Excluded / Depegged Coins**: `UST`, `USTC`, `USDN`. Must trade against historical market spot prices, never pegged to $1.

## Implementation & Test Coverage Gaps

| Area | Status | Description |
|---|---|---|
| **Fee Capitalization** | Open | `feeAmount` is recorded on `Transaction` entity but not yet accumulated into `inventoryCostUsdt`. |
| **Schema Field Naming** | In Progress | Transition between legacy `stableTotalCost` and `inventoryCostUsdt` is ongoing across DTOs and facades. |
| **Opportunity Cost** | Unimplemented | Theoretical lost profit tracking on liquidated assets is not yet modeled. |
| **Multi-Sell Test Coverage** | Open | Cumulative P&L across successive partial sells lacks an automated integration spec. |
| **Overselling Guard Test** | Open | Edge-case spec for sell attempts exceeding current balance is not yet in `EndToEndScenariosIntegrationSpec`. |
