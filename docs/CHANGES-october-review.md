# October review: bug fixes and the TradAO candle panel

Everything below was found in a full review of the code (engine, compiler,
competitions, paper trading, sign-in, market data, builder, forum) and fixed.
"Before" is what a user could hit; "now" is what happens instead.

## 1. The candle panel, after TradAO (our base paper)

Tsang et al., *TradAO: A Visual Analytics System for Trading Algorithm
Optimization*, IEEE VIS 2020. We took its views that fit one strategy run:

| TradAO | In TSB now |
|---|---|
| Cash usage view: NAV (blue), available cash (green → orange/red under the warning/danger levels), initial capital (grey dotted), warning/danger lines (dotted), gear with two sliders, brush/zoom linked to the other views | **Cash usage** chart under the price chart, same encoding. ⚙ Levels moves the two levels. Zooming or scrolling it moves the price chart too (and back). A band marks the candle being explained; clicking it explains that candle. Buy & hold rides along. |
| Trading history: price, transactions (price and volume), inventory shading (green = long) | Price chart: trade arrows as before, plus a green **"held"** band along the bottom: the coins held at each candle's close. |
| Performance measures in six categories: Profitability (Yield, MD), Consistency (Sharpe, Sortino), Prediction (Win rate), Recovery (MaxDD/AvgDD duration), Robustness (VaR99 1-day, Vol), Activeness | **Measures · after TradAO** grid in the result. Daily returns, 365 days a year. Activeness has no measure in the paper; we show trades per 30 days and time in the market. |

Clicking a candle opens the redesigned panel:

1. **Verdict** — what happened here, in one line. It now speaks about the
   candle the chart's ▲/▼ sits on ("Bought at this candle's open"), and
   names a stop-loss / take-profit / trailing stop that fired inside the candle.
2. **The account at this close** — net asset value (vs buy & hold), available
   cash with an "in the coin" bar and the warning/danger marks, the position
   (coins, entry, unrealized P&L), and the drop from the peak.
3. **Orders on this candle** — every buy and sell (price, size, value, fee,
   P&L, and *when*: at the open / inside the candle / at the close), and the
   stop-loss / take-profit / trailing levels waiting from the next candle.
4. **Why** — the rules, as before.
5. **The market** — OHLCV, change vs the previous close, and the strategy
   against buy & hold over the same candles.

Not taken from TradAO: the algorithm-evolution tree, parameter-correlation
matrix and trading-residual view. They compare many tuned versions of one
algorithm, which TSB does not store yet.

## 2. Engine and compiler

- **NOT of missing data bought.** `NOT (CLOSE > MACD_SIGNAL(...))` was true
  during the warm-up (and after a division by zero). Conditions now have three
  answers — true, false, unknown — and only a definite true trades.
- **A fixed BUY could spend money it didn't have.** With BTC near 60,000,
  `BUY qty = 2` on a 10,000 USDT account bought about 120,000 USDT of BTC and
  left the cash at about −110,000. Now it is refused ("more than the cash
  available"), in backtests and competitions alike.
- **`BUY qty = 2` bought 1.998.** It now buys exactly 2 and pays the fee on top.
- **Unsellable leftovers.** A partial sell could leave less than one exchange step,
  which then blocked every later BUY ("already long"). Now that sell closes fully.
  Partial sells below the exchange minimum are refused; a full exit never is.
- **`SELL qty = 25% OF EQUITY`** now sells coins worth 25% of the account
  (it used to be treated like `OF POSITION`). `BUY ... OF POSITION` is refused.
- **Exponential slowdowns.** Lets that read each other through lookbacks
  (`a2 = a1[1] + a1[2]`, ...) doubled the work with every line. Measured on
  2,000 candles: 18 such lines took 46 seconds before; now 30 lines take
  under 0.1 seconds, because each let keeps its recent values. Chains of lets
  that double each other were also exponential in the compiler and the debugger.
- **Stack overflows (HTTP 500).** Very deep brackets, very long expressions and
  chains of 1,000 lets crashed the server. They are now compile errors.
- **`MACD_SIGNAL` with the fast period above the slow one** never produced a
  value; now it does, and the compiler warns (`SEM016`) rather than refusing,
  so older strategies still open.
- **HMA** used a rounded instead of floored square root (Hull's definition).
- **WMA** overflowed for huge periods; periods are capped at 10,000.
- **Rounding:** `0.09583000000000001` in trade lists is now `0.09583`.
- Out-of-range capital/fee/lookbacks, endless lexer errors, self-referencing lets.

## 3. Competitions

- One broken entry froze the whole competition → that entry alone is disqualified.
- Pending orders overflowed a 200-character column → `TEXT` (migration V10).
- Max drawdown was only checked at the close → also inside the candle, at the low.
- The daily loss limit is re-checked after every fill (a BUY right after the SELL
  that hit the limit no longer goes through).
- Eliminated entries no longer turn into "failed" at the end (ranking changed).
- Same strategy version / same source entered twice → refused (+ unique indexes).
- Entries could slip in after the start → checked against the real clock, row locked.
- Cancel: only the creator or an admin, and now also while running ("Stop competition").
- Leaderboard: no medals before the first candle or when cancelled; equal money
  shares a place (1, 1, 3). The target meter counts the selling fee.
- Lists refresh themselves; countdowns use the server's clock; "start in 10
  minutes" is worked out when you press Create.
- The scheduler has 4 threads, so one slow job can't freeze prices, paper fills,
  candle sync and competitions together.
- Also: a saved version that no longer compiles is refused when entered (not on
  its first candle); if the final candle never arrives, the competition ends an
  hour after the end once the candle sync has moved past it (24 hours at the
  latest); a "minimum trading days" longer than the competition is refused; a
  clash with the database's entry checks answers 409 instead of 500.

## 4. Paper trading

- Huge numbers in an order could tie up the server → bounded first.
- Flipping long→short and take-profit limits used the wrong buying power.
- The 5 USDT minimum applied to closing orders → only to the opening part.
- Stale prices: the chart's live feed now reconnects (1 s … 30 s back-off), and when
  it is down the ticket shows the server's price, labelled "server price".
- Late polls overwrote a newer balance; a failed account load looked like an empty account.
- Marketable limit orders are estimated at the market price (as they fill).
- Also: the order history shows finished orders, newest activity first (open
  orders no longer push them out); a new account opened by two requests at once
  no longer fails; orders that cross in the same tick fill oldest first.

## 5. Sign-in

- An expired token sent to /auth/refresh made the refresh fail → not sent any more.
- Two tabs refreshing at once could revoke the session → one shared refresh,
  tabs take turns (Web Locks), plus one retry.
- When the session ended, the screen still looked signed in → it signs out.
- Signing out left the last user's strategy and lists on screen → cleared.
- Reuse detection was rolled back; parallel password guesses passed the limit;
  passwords over 72 bytes gave a 500; a Google account could take over another
  account by email; the dead "Forgot password?" link and the "change it later in
  Settings" promise are gone; unknown errors no longer show SQL or stack text.
- Also: Google's 5-minute sign-up token no longer works as a normal sign-in; a
  sign-in lasts at most 60 days however often it refreshes; old sign-ins are
  deleted once a day (a whole sign-in at a time, so the 60 days count from the
  real start); only `/actuator/health` is public; a malformed request answers
  400, not 500; two sign-ups with the same name at once get a readable error;
  a Google account without a name or picture can sign up (it was a 500).

## 6. Market data

- The startup catch-up crashed the app when Binance was unreachable.
- Gaps after a restart and a backfill are topped up and repaired.
- No timeouts, no 429 back-off, 20,000-candle cap applied after loading everything,
  unknown timeframe looked like "no data" → all fixed.
- `compose.yaml` now binds Postgres to 127.0.0.1 only; the README says to run
  `docker compose up -d` (nothing starts it automatically).
- Also: a candle is stored only once it is 30 s past its close; the startup
  catch-up, the 5-minute sync and the gap repair take turns per coin and
  timeframe; dates far outside 2000–2200 are clamped instead of causing a 500.

## 7. Builder, results, lab

- Saving twice made two strategies instead of v1, v2 → the next save adds a version.
- An empty slot in a block (e.g. half an AND) became an always-true condition, and an
  IF without an action became BUY ALL → now the editor lists what is missing and the
  strategy can't run until it is filled. Odd names are made safe.
- Errors (network, server) vanished silently; late chart data could land under a newer
  run; "Checking…" could spin forever; the equity chart was rebuilt on every click.
- Lab: "past too" with zero trades, a coin swap that silently didn't happen, the
  unseen window starting one candle early, "too few trades" counting partial sells,
  and the check running on after leaving the page.
- VWAP added from the picker duplicated the strategy's VWAP line.
- Also: two saves of the same strategy at once no longer fail; "Infinity" no
  longer shows in the debugger (it counts as unknown, as in the engine);
  stepping quickly through candles no longer floods the server; "Nothing saved
  yet" no longer flashes before your strategies load.

## 8. Forum

- Image uploads could exhaust memory → 16 MP cap, two decodes at a time, attempts limited.
- Phone photos showed sideways → turned upright from EXIF.
- Hidden posts' pictures were broken for their author and moderators.
- Reports were never cleared, so a restored post was hidden again by the next report
  (and with no moderators set, reported posts were hidden forever).
- Long requests held a database connection; likes/comments counters could drift;
  limits could be raced; page numbers could crash the list; "load more" showed
  duplicates; pictures were cached publicly for a year.
- A chart snapshot re-attached itself to later posts; "Sign in to post" had no way
  back; reporting didn't refresh the page; links swallowed the full stop after them;
  `2 * 3 * 4` turned into italics; re-adding a removed picture did nothing.

## Database: migration V10 (runs by itself on start)

`V10__review_fixes.sql`: pending orders → TEXT; unique entries (if old duplicates
exist, the index covers new entries only); entry window on the real clock with the
row locked; `TRUNCATE` refused on the append-only tables; forum reports get a
`reviewed` flag; an index for the comment rate limit. V1–V9 are untouched.

## Tests

Backend: 362 engine/compiler/competition/forum tests pass (42 new, e.g.
`EngineFixesTest`, `CompilerLimitsTest`, `PerformanceMeasuresTest`).
`CandleControllerTest` and `CandleRepositoryIT` were updated for the new candle query.

## Follow-up (17 October): ELSE on missing data

- **ELSE ran when the answer was unknown.** Section 2 made THEN wait for a
  definite true, but ELSE still ran on everything that was not true, unknown
  included. `IF (CLOSE - OPEN) / (HIGH - LOW) > 0.5 THEN SELL ALL ELSE BUY ALL`
  bought on a candle with no trades (high = low, so the ratio has no value).
  Now THEN runs only on true, ELSE only on false, and on unknown nothing runs.
  Fixed in backtests, competitions, the debugger ("neither true nor false")
  and the signal statistics. Found while measuring for the research paper.
- New tests: `EngineFixesTest.elseNeedsADefiniteFalse`,
  `CompetitionEngineTest` (unknown runs no branch) and `DecisionExplainerTest`
  (unknown takes no branch). `notOfUnknownDoesNotBuy` now also checks a 0/0
  (NaN), not only a division by zero that gives infinity. Backend: 365 tests.
- TradAO's authors are Tsang et al. (section 1 and the comments in the code
  said "Ma, Wang et al.").
