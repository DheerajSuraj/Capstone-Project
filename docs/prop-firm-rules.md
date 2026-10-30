# Prop-firm rules for competitions

A competition can now run the way a funding challenge runs (FTMO and similar
"prop firms"). Three new choices sit in **Create → 2 · The rules**, and they
are locked with the rest of the rule set the moment it is saved.

| Choice | Options | What it does |
|---|---|---|
| When the daily loss limit is hit | **Pause for the day** (as before) · **End the entry** | Pause: no new trades until 00:00 UTC, closing still allowed. End: the entry is closed out and eliminated, the way a prop firm closes the account. |
| Max drawdown measured from | **The best equity** (trailing, as before) · **The start** (a fixed "max loss") | Fixed: out below starting capital × (1 − max loss). The line never moves. |
| When the target counts | **Judged at the end** (as before) · **Pass on reaching it** | Pass on reaching it: at the first close where the target is met (after the exit fee) with the trading days done, the position is sold and the entry passes. |

Three presets fill in the form ("Start from"):

| Preset | Target | Max loss | Daily loss | Trading days | Trades a day | Duration |
|---|---|---|---|---|---|---|
| Prop challenge · step 1 | +10%, pass on reaching it | 10% from the start | 5%, ends the entry | 4 | no cap | 30 days |
| Prop challenge · step 2 | +5%, pass on reaching it | 10% from the start | 5%, ends the entry | 4 | no cap | 60 days |
| TSB classic | +5%, judged at the end | 10% trailing | 5%, pauses the day | 1 | 20 | 1 day |

Change any field afterwards and the preset turns off: the rules are your own.
Existing competitions keep exactly the rules they were made with — the
defaults are the old behaviour.

## How it is counted

- **The limits are watched inside every candle**, with the open position
  counted. An entry that crosses one is closed at the limit price, or at the
  open if the price gapped past it. If its own stop-loss sat higher, the stop
  fills first (prices are assumed to fall first, as in the backtester).
- **Daily loss** is measured from the equity the UTC day started with
  (00:00 UTC; the page shows the time on your clock). It is checked after
  every fill and at every close, open trades included. With "End the entry"
  it is checked inside candles too.
- **Passing on the target** is judged at a candle's close, on the return
  after the fee for selling what is held. Today counts as a trading day if
  the entry traded today. The sale is recorded as "target reached".
- **Trading days** are UTC days with at least one buy or sell.
- **Trades a day**: every buy and every sell counts, so a round trip is 2. At
  the cap no new trades open; closing is always allowed.
- **Standings**: passed first, then still in the running, then failed, then
  eliminated; higher equity first inside each group. Medals only go to
  entries that passed, or are in the running and in profit.

## Also fixed, from a walk-through as a first-time user

- A daily loss reached while *holding* (no fill that day) never paused the
  entry: the dashboard could read "6% of 5%" with trading still allowed. The
  daily rules are now checked at every close.
- Medals were handed out by money alone (silver next to "Failed", bronze for
  a trader in loss). Now: passed or in profit only, plus an "n of m passed" line.
- Nothing said the money was virtual. The list, the rules and the form say so.
- Limits are shown as money ("out below 9,000.00", "paused at 9,651.53"),
  and the daily reset in local time.
- A finished entry said "Now: Waiting". It now says Finished or Out, shows when
  it passed or was eliminated, and the dates use one format. Status chips
  have tooltips; "halted" reads "paused today".
- Join said "wrong market or timeframe" without saying which; it now names
  the market and timeframe needed.
- Table headings sit over their numbers (`th.num` is right-aligned like
  `td.num`), which fixes the shifted headings in the report's tables.

## How close this is to a real challenge

The presets follow the FTMO two-step challenge: +10% then +5%, a 10% max
loss from the initial balance, a 5% daily loss, at least 4 trading days, and
the account closed on a breach ([rules summary](https://tradetanto.com/learn/ftmo-rules-evaluation-process)).
What is still different:

| Rule | FTMO (per the summary above) | TSB |
|---|---|---|
| Daily loss base | 5% of the *initial* balance, below the midnight balance, open positions included | 5% of the equity the day started with, open positions included |
| Day starts | Midnight; the summary gives 23:59:59 CET as FTMO's daily close | 00:00 UTC |
| Trading day | A day a position was opened | A day with any buy or sell |
| Time limit | None | A competition needs an end (30 / 60 days) |
| Market | Many instruments, long and short | One spot market, long only |

## Suggestions, not built

1. **Practice run**: "Backtest with these rules" before an entry is locked.
   The competition engine can replay past months, so a trader sees how often
   the strategy would have passed, failed or been eliminated.
2. **Daily loss of the initial balance**, as FTMO counts it, as a fourth choice.
3. **End-of-day trailing drawdown** that stops trailing at the starting
   balance, as Topstep does ([overview](https://proptradingvibes.com/blog/topstep-rules-overview)).
4. **A consistency rule**: the best day may be at most a set share of the total profit.
5. **Step 2 follows step 1**: passing step 1 enters the same strategy into step 2.
6. **Notices** when an entry is paused, eliminated or passes.

## Strategy search (15m)

`backend/src/test/java/com/tsb/research/Search15m.java` searches 84 candidate
15m strategies (trend pullback, breakout, EMA cross, 4h trend + 15m dip,
band reversion, Supertrend) with the project's own compiler, backtester and
competition engine:

- **Walk-forward**: pick the best on 6 months, run it untouched on the next
  2, move on; only the chained test months count.
- **Placebo**: the same search on copies of the candles shuffled inside each
  month (same month-end prices and candle sizes, random order). A real edge
  must beat most of them.
- **Prop check**: each pick is also run through the step-1 rules on every
  30-day window of its test months.

```bash
cd backend
./mvnw test-compile
# Windows: use ; instead of : in the class path
java -cp target/classes:target/test-classes com.tsb.research.Search15m <folder of BTCUSDT-15m-*.zip> --placebo 50
```

The input is Binance's monthly kline ZIPs (the same files the ingestion reads,
`https://data.binance.vision/data/spot/monthly/klines/BTCUSDT/15m/BTCUSDT-15m-2024-01.zip`),
or a CSV whose first six columns are open time in epoch ms, open, high, low,
close, volume. From the app's own database:

```sql
\copy (SELECT (extract(epoch FROM c.open_time) * 1000)::bigint, c.open, c.high, c.low, c.close, c.volume FROM candles c JOIN symbols s ON s.id = c.symbol_id WHERE s.ticker = 'BTCUSDT' AND c.timeframe = '15m' ORDER BY c.open_time) TO 'btcusdt-15m.csv' WITH CSV
```

On random-walk candles (no edge by construction) the same search still
showed +28.7% out of sample on one and −42.8% on another, and the step-1
rules passed 9–19% of 30-day windows on shuffled copies. So one good test
period, or one passed challenge, is weak evidence on its own.

## Where it lives

- Backend: `competition/RuleSet.java` (the choices), `CompetitionEngine.java`
  (intrabar limits, daily checks at every close, pass on target),
  `EntryState.java`, `Standings.java`, `Competition.java`,
  `CompetitionController.java`; migration `V11__prop_firm_rules.sql`.
  Tests: `PropFirmRulesTest` (11), `Search15mTest` (4).
- Frontend: `competition/CreateCompetition.tsx`, `CompetitionDetail.tsx`,
  `EntryDashboard.tsx`, `CompetitionList.tsx`, `util.ts`, `competition.css`;
  types in `api.ts`; the heading fix in `index.css`.
