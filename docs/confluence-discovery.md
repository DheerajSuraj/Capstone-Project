# Confluence discovery (25 October 2026)

Health check → **What your winners had in common**.

## What it does

After a backtest, it looks for conditions that the winning trades had and
the losing trades didn't, and reports each as a filter you could add to
your entry:

> **Only take trades when: Price above its 50-bar EMA**
> 68% of the earlier winners had this, against 29% of the losers.
> Trades it was found in: win rate 40% → 61% (keeps 31 of 70)
> Later trades (unseen): win rate 46% → 58% (keeps 19 of 35)
> Your backtest re-run with this filter added:
> later period, 10 Jun 2024 to the end: return +126.4% → +140.0%
> whole backtest (the return in your report): return +570.0% → +1100.4%
> `AND CLOSE > EMA(CLOSE, 50)` [Add to my strategy] [Copy]

(Stand-in hourly candles, not real market data.)

## The overfitting problem

Search enough conditions on the same trades and some will "lift the win
rate" by chance. On 60 coin-flip trades (no edge at all), the best of the
filters below raised the win rate by **20 points on average**, and by 20+
points in **90 of 200** runs. A naive "what did the winners share" report
would show a big, fake edge almost half the time.

## The guards

1. **A fixed, coarse list of conditions, decided in advance.** About 35–40:
   RSI levels, price above the 10/20/30/50/100/200 EMA, 50 SMA above
   200 SMA, ADX levels, volume ×1/×1.5/×2 its average, Bollinger, MACD, up
   candle, volatility rising, 4h and daily trend, sessions, weekend. Each
   is tried both ways (with it / without it). No thresholds are tuned to
   the data: tuning is where most overfitting comes from.
2. **Hold out the later trades.** The search only sees the first two
   thirds of the trades (in time order). The last third is kept back to
   check every idea on. A pattern that was real should still help there.
3. **A luck bar.** The wins and losses are shuffled 500 times and the
   whole search is run again each time. An idea must beat what the best
   shuffled filter reached 95% of the time. The bar is scaled to the
   filter's size: a filter keeping 15 trades swings more by chance than
   one keeping 40, so it has to clear a higher bar. (Fixed seed, so the
   same backtest always gives the same answer.)
4. **Enough trades.** At least 30 completed trades or nothing is reported.
   A filter must keep at least 10 and 20% of the earlier trades, and at
   most 90% (otherwise it isn't filtering).
5. **Money, not just win rate.** A filter must also raise the average
   return per trade, so it can't win more often by cutting the big winners.
6. **At most three ideas**, the best from each family (trend, momentum,
   volume, time, …), so ten versions of "price is going up" don't crowd
   the list.
7. **A real re-run.** The filter is added to every BUY rule's condition
   (`IF <yours> AND <filter>`) and the backtest is run again through the
   real engine. The return is shown before and after, for the later period
   and for the whole backtest. The code shown to copy is exactly what was
   tested.

## Add to my strategy

In the Builder, each idea that can be written as code has an **Add to my
strategy** button (**Add anyway** on ideas that didn't pass the checks,
which stay in plain sight).

- It adds `AND <filter>` to the condition of every IF that buys, and
  changes nothing else: layout, comments and the other rules stay as
  written. A condition whose top level is an OR is put in brackets first
  (`IF (A OR B) AND <filter>`), since AND binds tighter.
- The code it puts in the editor is **the exact code the re-run ran**, so
  the next backtest gives the "after" numbers on the card.
- On Blocks, the blocks are rebuilt from the new code; on Code, the code
  changes. All 60 possible filters show as blocks (checked through the
  real code-to-blocks converter).
- A banner says what was added, with **Run backtest** and **Undo**.
- If the code was edited after the backtest, it asks first: adding means
  going back to the backtested code plus the filter (Undo brings the edits
  back).
- Time-of-day ideas have no button (no hour check in the language), and
  the saved-strategy view has Copy only, since its code can't be edited
  there.

## Why the panel's return differs from the report's

The report's **Return** is for the whole backtest. The re-run's first line
is for the **later period only** (its dates are shown): the last third of
the trades, which the search never saw. A strategy can be +9% overall and
−8% over its last third; the monthly table in the report shows the same
thing. The panel also shows the whole-backtest return before and after,
and "before" equals the report's Return exactly. Judge a filter by the
later period: the whole-backtest figure includes the trades the filter was
picked on, so it flatters the filter.

Each idea gets a verdict:

| Verdict | Meaning |
|---|---|
| Held up on later trades | beat luck; on the later trades the win rate and average return rose, and the re-run made more money (when it could be re-run) |
| Partly held up | beat luck, but some of those didn't improve on the later trades (it says which) |
| Didn't hold up: overfit | beat luck on the earlier trades, but on the later ones neither the win rate nor the average return rose |
| Too few later trades to check | fewer than 5 later trades had the condition |
| Could be luck | did not beat the luck bar |

When nothing beats luck the panel says so plainly. That is the right answer
for most strategies on most data.

## Measured (200 runs each, `OverfitCheck`)

Trades from a fixed random walk. "Coin flips": each trade wins or loses at
random, so any edge found is fake. "Real edge": trades taken above the
50-bar EMA win 75% of the time, the rest 35%.

| | 60 trades | 120 trades |
|---|---|---|
| Coin flips: naive search shows a 20+ point lift | 90 / 200 | 10 / 200 |
| Coin flips: an idea beats the luck bar | 12 / 200 | 8 / 200 |
| Coin flips: an idea is reported as **held up** | **4 / 200** | **4 / 200** |
| Real edge: reported as **held up** | **73 / 200** | **156 / 200** |
| Real edge: the right condition ranked first | 135 / 200 | 180 / 200 |

So fake edges get through about 2% of the time. A real, strong edge is
found about a third of the time with 60 trades and three quarters of the
time with 120. **With fewer than about 100 trades, "nothing found" is
common even when something is there.**

End to end (stand-in server, 6000 hourly candles): on a trending market
the Breakout strategy's EMA-50 idea held up (re-run on the later period:
+126.4% → +140.0%). On a random market, nothing beat luck for any strategy.

## Limits

- It finds what is on the list. A condition not on the list (another
  indicator, a different threshold) is never tried.
- One market and one date range. "Held up" means it held on the later
  trades of this backtest, not that it will hold in future. Test on other
  dates before relying on it.
- Time-of-day filters can't be added as code (the strategy language has no
  hour check), so they are not re-run.
- A strategy that buys in an ELSE branch is not re-run (the filter can't be
  added cleanly); the panel says why.

## Code

- `backend/.../signals/ConfluenceDiscovery.java`: the search, luck bar,
  hold-out check and re-run.
- `SignalController`: `POST /api/signals` now also returns `discovery`.
- `frontend/src/debugger/DiscoveryPanel.tsx`: the tab (replaces the old
  "winners" list in `SignalPanel.tsx`), with the Add button.
- `ResultPanel.tsx` → `SignalPanel.tsx` → `DiscoveryPanel.tsx` pass
  `onApplyFilter`; `BuilderView.tsx` puts the code in, rebuilds the
  blocks, and shows the banner with Run and Undo.
- `ConfluenceDiscoveryTest`: 10 tests (the Add code: exact text with
  comments kept and an OR bracketed; for 4 tricky conditions × every 1h
  filter both ways, it trades exactly like the filter added to the parsed
  strategy; each idea's code is what the re-run ran; planted edge found and held up; a
  pattern that changes half way is reported as overfit; 40 coin-flip runs
  where at most 2 hold up; the re-run really adds the filter; ELSE buy
  refused; fewer than 30 trades refused; every condition compiles).
