import { useMemo, useState } from 'react'
import type {
  BacktestResultDto,
  CandleColumns,
  CurvePoint,
  RunContext,
} from '../api'
import PriceChart from '../charts/PriceChart'
import EquityChart from '../charts/EquityChart'
import DecisionPanel from '../debugger/DecisionPanel'
import SignalPanel from '../debugger/SignalPanel'

const fmt = (n: number, digits = 2) =>
  n.toLocaleString('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })

const pnlClass = (n: number) => (n > 0 ? 'up' : n < 0 ? 'down' : '')

/** BUY & HOLD baseline: same capital riding the raw close. */
const buyAndHold = (candles: CandleColumns, capital: number): CurvePoint[] =>
  candles.c.map((close, i) => ({
    t: candles.t[i],
    equity: (capital * close) / candles.c[0],
  }))

/** Verdict strip + charts + trade table — the shared result experience
 *  for both the builder's ad-hoc runs and saved-strategy runs. */
export default function ResultPanel({
  result,
  candles,
  source,
}: {
  result: BacktestResultDto
  candles: CandleColumns | null
  /** The TSL that produced this result. Without it the debugger is off. */
  source?: string
}) {
  const [selectedTime, setSelectedTime] = useState<number | null>(null)

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
        </div>
      </div>

      {candles && (
        <>
          <PriceChart
            candles={candles}
            trades={result.trades}
            symbol={result.symbol}
            timeframe={result.timeframe}
            usedIndicators={result.indicators}
            onBarClick={run ? setSelectedTime : undefined}
            selectedTime={selectedTime}
          />
          {run && selectedTime != null && (
            <DecisionPanel
              run={run}
              timeMillis={selectedTime}
              onClose={() => setSelectedTime(null)}
            />
          )}
          <EquityChart
            curve={result.equityCurve}
            benchmark={buyAndHold(candles, result.initialCapital)}
          />
        </>
      )}

      {run && <SignalPanel key={`${run.source}|${run.to}`} run={run} />}

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
              <td className="num">{t.qty}</td>
              <td className="num">{fmt(t.entryPrice)}</td>
              <td className="num">{fmt(t.exitPrice)}</td>
              <td className={`num ${pnlClass(t.pnl)}`}>{fmt(t.pnl)}</td>
              <td className={`num ${pnlClass(t.pnl)}`}>{fmt(t.pnlPercent)}%</td>
              <td>{t.exitReason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}