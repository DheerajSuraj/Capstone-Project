# Report by period

Every backtest result now has a **Report · by period** panel under the charts
(and a "By year, month, week and day ↓" link next to the measures). You read the
run from the top down:

**All time → year → month → week → day**

Click a row, a month in the "Every month" grid, a day in the calendar, or a
best/worst card to go down; click the breadcrumb to go back up. "Show on
chart" moves the price and cash charts to the period you are looking at.

## What each level shows

| Part | What it says |
|---|---|
| Numbers | Return and P&L, buy & hold over the same candles (and by how many points you beat it or trail it), max drawdown, trades closed (and opened), win rate, closed P&L, fees, time in the market, green/red days |
| The periods below | The same numbers for each year / month / week / day, with a bar for the return |
| Every month (all time) | Years × months, coloured by return |
| Calendar (a month) | One square per day: its return and how many trades closed |
| Best and worst | Best and worst year, month, week and day below this level, and the longest run of green and red days |
| When it makes money | Mean return by weekday, by hour of the day (UTC) and by month of the year, with P&L, green share, trades entered and their win rate |
| Trades this day (a day) | Every trade that was open that day: opened, closed, both, or held through it |

## How it is counted

- **Days are UTC**, by candle open time: the same days as the daily measures
  and the exchange's own 1d candles.
- **A period runs from the close before it to its last close**, open positions
  included (the same marked-to-market equity as the rest of the result). So
  periods chain exactly: the days of a week compound to the week, the months
  to the year, the years to the whole run.
- **A week runs Monday to Sunday and stops at the end of its month**, so the
  weeks of a month add up to the month. A week that crosses into the next
  month shows up in both months, each part with its own days.
- **Trades count on the day they close** (win, loss, closed P&L). "Opened"
  counts entries. Fees count on the day each buy or sell filled.
- **Max drawdown inside a period** counts from the equity the period started
  with, so a fall on its first candle counts.
- **By weekday / hour / month**: the mean return of those days, hours or
  months. Trades there are counted by when they were entered. Hours are left
  out when one candle is a day or longer.
- The warm-up is not part of the report: it starts at the first candle the
  rules ran on.

## Where it lives

- Backend: `execution/PeriodReport.java` builds it from the full-resolution
  equity curve and the trades; `BacktestController.Result.report` sends it.
  Tests: `PeriodReportTest` (12), including one that checks the whole run
  against the backtest's own return, fees, trades, win rate and drawdown.
- Frontend: `components/PeriodReport.tsx` and `components/report.css`; the
  types are in `api.ts` (`PeriodReportDto`).

Two years of hourly candles give about 840 days and roughly 0.6–0.7 MB of
JSON for the report. Turning on response compression in `application.yml`
shrinks it five to ten times (measured on two sample runs):

```yaml
server:
  compression:
    enabled: true
```
