import { useCallback, useMemo, useRef, useState } from 'react'
import type { BacktestResultDto, CandleColumns, MeasuresDto, RunContext } from '../api'
import PriceChart from '../charts/PriceChart'
import PeriodReport from './PeriodReport'
import CashUsageChart, {
  DEFAULT_LEVELS,
  type CashLevels,
  type TimeRange,
} from '../charts/CashUsageChart'
import DecisionPanel from '../debugger/DecisionPanel'
import SignalPanel from '../debugger/SignalPanel'
import '../charts/charts.css'

const fmt = (n: number, digits = 2) =>
  n.toLocaleString('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })

const pnlClass = (n: number) => (n > 0 ? 'up' : n < 0 ? 'down' : '')

/** Verdict strip + charts + trade table — the shared result experience
 *  for both the builder's ad-hoc runs and saved-strategy runs.
 *
 *  Laid out after TradAO (Tsang et al., IEEE VIS 2020): its measures in
 *  six categories, the trading history (price, trades and the coins held),
 *  and the cash-usage view, linked in time — and a click on any candle opens
 *  what the account and the rules looked like there. */
export default function ResultPanel({
  result,
  candles,
  source,
  chartNote,
}: {
  result: BacktestResultDto
  candles: CandleColumns | null
  /** The TSL that produced this result. Without it the debugger is off. */
  source?: string
  /** Said under the price chart: e.g. that it shows only the newest candles. */
  chartNote?: string | null
}) {
  const [selectedTime, setSelectedTime] = useState<number | null>(null)
  // Warning and danger levels for cash, shared by the chart and the panel.
  const [levels, setLevels] = useState<CashLevels>(DEFAULT_LEVELS)
  // The stretch of time on screen, shared by the price and cash charts.
  const [range, setRange] = useState<TimeRange | null>(null)
  const closePanel = useCallback(() => setSelectedTime(null), [])

  // The report's "Show on chart": move both charts to that period and bring
  // the price chart into view.
  const chartRef = useRef<HTMLDivElement>(null)
  const showOnChart = useCallback((from: number, to: number) => {
    setRange({ from, to, source: 'report' })
    chartRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [])

  // Pin the run: every run so far covers all history, but the candle sync
  // keeps appending bars. Ending the range just past this result's last bar
  // makes the debugger see exactly the bars this result saw. (The engine only
  // looks backwards, so earlier decisions would match anyway — this keeps the
  // statistics and the final bar exact too.)
  const run = useMemo<RunContext | null>(
    () =>
      source == null
        ? null
        : {
            source,
            from: null,
            to: new Date(Date.parse(result.lastBarTime) + 1).toISOString(),
          },
    [source, result.lastBarTime],
  )

  // A click on the cash chart lands on one of its points; the panel explains
  // the strategy candle that point belongs to.
  const pick = useCallback(
    (t: number) => {
      if (!run) return
      setSelectedTime(t)
    },
    [run],
  )

  return (
    <section className="panel">
      <h2>
        {result.strategyName} · {result.symbol} {result.timeframe} ·{' '}
        {result.firstBarTime.slice(0, 10)} → {result.lastBarTime.slice(0, 10)}
      </h2>

      <div className="verdict">
        <div className="cell">
          <div className="k">Return</div>
          <div className={`v ${pnlClass(result.totalReturnPct)}`}>
            {fmt(result.totalReturnPct)}%
          </div>
        </div>
        <div className="cell" title="What buying on the first candle the strategy could trade and holding to the end would have returned, with the same fee. A strategy has to beat this to be worth running.">
          <div className="k">Buy &amp; hold</div>
          <div className={`v ${result.buyHoldReturnPct == null ? '' : pnlClass(result.buyHoldReturnPct)}`}>
            {result.buyHoldReturnPct == null ? '—' : `${fmt(result.buyHoldReturnPct)}%`}
          </div>
        </div>
        <div className="cell">
          <div className="k">Final equity</div>
          <div className="v">{fmt(result.finalEquity)}</div>
        </div>
        <div className="cell">
          <div className="k">Max drawdown</div>
          <div className="v down">−{fmt(result.maxDrawdownPct)}%</div>
        </div>
        <div className="cell">
          <div className="k">Sharpe</div>
          <div className="v">
            {result.metrics.sharpeRatio == null ? '—' : fmt(result.metrics.sharpeRatio)}
          </div>
        </div>
        <div className="cell">
          <div className="k">Profit factor</div>
          <div className="v">
            {result.metrics.profitFactor == null ? '—' : fmt(result.metrics.profitFactor)}
          </div>
        </div>
        <div className="cell">
          <div className="k">Trades</div>
          <div className="v">{result.tradeCount}</div>
        </div>
        <div className="cell">
          <div className="k">Win rate</div>
          <div className="v">{fmt(result.winRate, 1)}%</div>
        </div>
        <div className="cell">
          <div className="k">Fees paid</div>
          <div className="v">{fmt(result.totalFees)}</div>
          {result.feeRate != null && (
            <div className="note">{+(result.feeRate * 100).toFixed(3)}% per buy or sell</div>
          )}
        </div>
      </div>

      {result.measures && <Measures m={result.measures} r={result} />}

      {candles && (
        <div ref={chartRef}>
          <PriceChart
            candles={candles}
            trades={result.trades}
            symbol={result.symbol}
            timeframe={result.timeframe}
            usedIndicators={result.indicators}
            onBarClick={run ? setSelectedTime : undefined}
            selectedTime={selectedTime}
            range={range}
            onRange={setRange}
          />
          {chartNote && <p className="tsb-chartnote">{chartNote}</p>}
        </div>
      )}
      {!candles && chartNote && <p className="tsb-chartnote">{chartNote}</p>}

      {run && selectedTime != null && (
        <DecisionPanel
          run={run}
          timeMillis={selectedTime}
          times={candles?.t ?? []}
          onNavigate={setSelectedTime}
          onClose={closePanel}
          timeframe={result.timeframe}
          levels={levels}
        />
      )}

      {result.book && result.book.length > 0 && (
        <CashUsageChart
          book={result.book}
          initialCapital={result.initialCapital}
          levels={levels}
          onLevels={setLevels}
          selectedTime={selectedTime}
          onPick={run ? pick : undefined}
          range={range}
          onRange={setRange}
        />
      )}

      {result.report && (
        <PeriodReport
          report={result.report}
          trades={result.trades}
          onShow={candles ? showOnChart : undefined}
        />
      )}

      {run && <SignalPanel key={`${run.source}|${run.to}`} run={run} />}

      <div className="tsb-tablewrap">
      <table>
        <thead>
          <tr>
            <th>Entry</th>
            <th>Exit</th>
            <th className="num">Qty</th>
            <th className="num">In</th>
            <th className="num">Out</th>
            <th className="num">PnL</th>
            <th className="num">PnL %</th>
            <th>Exit reason</th>
          </tr>
        </thead>
        <tbody>
          {result.trades.map((t, i) => (
            <tr key={i}>
              <td>{t.entryTime.slice(0, 16).replace('T', ' ')}</td>
              <td>{t.exitTime.slice(0, 16).replace('T', ' ')}</td>
              <td className="num">{Number(t.qty.toPrecision(10))}</td>
              <td className="num">{fmt(t.entryPrice)}</td>
              <td className="num">{fmt(t.exitPrice)}</td>
              <td className={`num ${pnlClass(t.pnl)}`}>{fmt(t.pnl)}</td>
              <td className={`num ${pnlClass(t.pnl)}`}>{fmt(t.pnlPercent)}%</td>
              <td>{t.exitReason}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
    </section>
  )
}

/* ── TradAO's measures ───────────────────────────────────────────────── */

const show = (v: number | null | undefined, digits = 2, suffix = '') =>
  v == null || !Number.isFinite(v) ? '—' : `${fmt(v, digits)}${suffix}`

const days = (v: number | null | undefined) =>
  v == null || !Number.isFinite(v) ? '—' : v < 1 ? `${fmt(v * 24, 1)} h` : `${fmt(v, 1)} d`

/**
 * The six categories TradAO judges a trading algorithm by, each with the
 * measures it names (Activeness has none in the paper: trades per 30 days
 * and time in the market are our reading of "how often it trades").
 */
function Measures({ m, r }: { m: MeasuresDto; r: BacktestResultDto }) {
  const cats: { name: string; what: string; rows: [string, string, string?][] }[] = [
    {
      name: 'Profitability',
      what: 'How much it made.',
      rows: [
        ['Yield (a year)', show(m.yieldPct, 2, '%'), 'Mean daily return × 365: what a typical day adds up to over a year.'],
        ['Max drawdown', show(m.maxDrawdownPct, 2, '%'), 'The worst fall from a high, in percent.'],
      ],
    },
    {
      name: 'Consistency',
      what: 'How steadily, for the risk taken.',
      rows: [
        ['Sharpe', show(r.metrics.sharpeRatio), 'Return per unit of ups and downs (a year).'],
        ['Sortino', show(r.metrics.sortinoRatio), 'Like Sharpe, but only the downs count as risk.'],
      ],
    },
    {
      name: 'Prediction',
      what: 'How often its calls were right.',
      rows: [['Win rate', show(r.winRate, 1, '%'), 'Share of trades that made money, after fees.']],
    },
    {
      name: 'Recovery',
      what: 'How fast it climbed back after a loss.',
      rows: [
        ['Longest drawdown', days(m.maxDrawdownDays), 'Longest time from a high until equity was back at it (or the end).'],
        ['Average drawdown', days(m.avgDrawdownDays), 'Average length of all those times.'],
      ],
    },
    {
      name: 'Robustness',
      what: 'How rough the ride was.',
      rows: [
        ['VaR 99% (1 day)', show(m.var99Pct, 2, '%'), 'On 99% of days the loss was smaller than this (worst day if under 100 days).'],
        ['Volatility (a year)', show(m.volatilityPct, 2, '%'), 'Spread of daily returns, scaled to a year.'],
      ],
    },
    {
      name: 'Activeness',
      what: 'How often it trades.',
      rows: [
        ['Trades / 30 days', show(m.tradesPer30Days, 1), 'Completed trades per 30 days.'],
        ['Time in the market', show(m.timeInMarketPct, 0, '%'), 'Share of candles with a position open.'],
      ],
    },
  ]
  return (
    <div className="tsb-measures">
      <div className="tsb-measures__head">
        <span className="tsb-measures__title">Measures · after TradAO</span>
        <span className="tsb-measures__sub">
          Daily figures from {m.days} day{m.days === 1 ? '' : 's'}, 365 days a year (crypto never closes).
          {r.report && (
            <>
              {' '}
              <button
                type="button"
                className="tsb-measures__jump"
                onClick={() =>
                  document.getElementById('tsb-report')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                }
              >
                By year, month, week and day ↓
              </button>
            </>
          )}
        </span>
      </div>
      <div className="tsb-measures__grid">
        {cats.map((c) => (
          <div key={c.name} className="tsb-measures__cat">
            <h4>{c.name}</h4>
            <p>{c.what}</p>
            {c.rows.map(([k, v, help]) => (
              <div key={k} className="tsb-measures__row" title={help}>
                <span>{k}</span>
                <b>{v}</b>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}
