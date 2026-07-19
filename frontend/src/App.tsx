import { useEffect, useState } from 'react'
import {
  api,
  type BacktestResultDto,
  type CandleColumns,
  type CurvePoint,
  type DiagnosticDto,
  type StrategyDto,
} from './api'
import PriceChart from './charts/PriceChart'
import EquityChart from './charts/EquityChart'

const STARTER = `strategy "My Strategy" {
    symbol = BTCUSDT
    timeframe = 1h
    capital = 10000
    fee = 0.1%

    let rsi = RSI(14)

    rule entry {
        IF rsi < 30 AND CLOSE > SMA(CLOSE, 200)
        THEN BUY qty = 25% OF EQUITY
    }

    rule exit {
        IF rsi > 70 THEN SELL ALL
    }
}
`

const fmt = (n: number, digits = 2) =>
  n.toLocaleString('en-US', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })

/** PnL class: the only place the UI hands out green/red. */
const pnlClass = (n: number) => (n > 0 ? 'up' : n < 0 ? 'down' : '')

/** BUY & HOLD baseline: the same starting capital riding the raw close.
 *  The humbling line — a strategy that can't beat just-holding should
 *  have to look at that fact. */
const buyAndHold = (candles: CandleColumns, capital: number): CurvePoint[] =>
  candles.c.map((close, i) => ({
    t: candles.t[i],
    equity: (capital * close) / candles.c[0],
  }))

export default function App() {
  const [strategies, setStrategies] = useState<StrategyDto[]>([])
  const [name, setName] = useState('')
  const [source, setSource] = useState(STARTER)
  const [diagnostics, setDiagnostics] = useState<DiagnosticDto[]>([])
  const [editing, setEditing] = useState<number | null>(null) // strategyId
  const [busy, setBusy] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResultDto | null>(null)
  const [candles, setCandles] = useState<CandleColumns | null>(null)

  const refresh = () => api.listStrategies().then(setStrategies)

  useEffect(() => {
    refresh().catch(() => setRunError('Backend unreachable — is the server running on 8080?'))
  }, [])

  const save = async () => {
    setBusy(true)
    setDiagnostics([])
    setRunError(null)
    try {
      const res = editing
        ? await api.addVersion(editing, source)
        : await api.createStrategy(name || 'Untitled', source)
      if (!res.ok) {
        setDiagnostics(res.diagnostics)
      } else {
        setEditing(null)
        setName('')
        setSource(STARTER)
        await refresh()
      }
    } finally {
      setBusy(false)
    }
  }

  const edit = async (s: StrategyDto) => {
    setEditing(s.id)
    setName(s.name)
    setSource(await api.getVersionSource(s.id, s.latestVersion))
    setDiagnostics([])
    window.scrollTo({ top: 0 })
  }

  const run = async (s: StrategyDto) => {
    setBusy(true)
    setResult(null)
    setCandles(null)
    setRunError(null)
    setDiagnostics([])
    try {
      const res = await api.runVersion(s.id, s.latestVersion)
      if (res.ok && res.result) {
        setResult(res.result)
        // Candles for exactly the run's window, so markers land on their bars.
        api
          .getCandles(
            res.result.symbol,
            res.result.timeframe,
            res.result.firstBarTime,
            res.result.lastBarTime,
          )
          .then(setCandles)
          .catch(() => setCandles(null))
      } else if (res.runError) setRunError(res.runError)
      else setDiagnostics(res.diagnostics)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <header className="masthead">
        <h1>TSB</h1>
        <span className="tag">Trading Strategy Builder</span>
      </header>

      {/* ── Editor ── */}
      <section className="panel">
        <h2>{editing ? `New version of #${editing}` : 'New strategy'}</h2>
        {!editing && (
          <>
            <label htmlFor="name">Name</label>
            <input
              id="name"
              type="text"
              value={name}
              placeholder="RSI Mean Reversion"
              onChange={(e) => setName(e.target.value)}
            />
          </>
        )}
        <label htmlFor="source">TSL source</label>
        <textarea
          id="source"
          value={source}
          spellCheck={false}
          onChange={(e) => setSource(e.target.value)}
        />
        {diagnostics.map((d, i) => (
          <div className="diag" key={i}>
            <span className="code">
              {d.severity.toLowerCase()}[{d.code}]
            </span>{' '}
            {d.span.startLine}:{d.span.startCol} — {d.message}
          </div>
        ))}
        <button onClick={save} disabled={busy}>
          {editing ? 'Save as new version' : 'Save strategy'}
        </button>
        {editing && (
          <>
            {' '}
            <button
              className="ghost"
              onClick={() => {
                setEditing(null)
                setName('')
                setSource(STARTER)
                setDiagnostics([])
              }}
            >
              Cancel
            </button>
          </>
        )}
      </section>

      {/* ── Strategy list ── */}
      <section className="panel">
        <h2>Strategies</h2>
        {strategies.length === 0 ? (
          <p className="note">
            Nothing saved yet — write a strategy above and save it to see it
            here.
          </p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Symbol</th>
                <th>TF</th>
                <th>Ver</th>
                <th>Updated</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {strategies.map((s) => (
                <tr key={s.id}>
                  <td>{s.name}</td>
                  <td>{s.symbol}</td>
                  <td>{s.timeframe}</td>
                  <td className="num">v{s.latestVersion}</td>
                  <td>{new Date(s.updatedAt).toLocaleString()}</td>
                  <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className="ghost" onClick={() => edit(s)}>
                      Edit
                    </button>{' '}
                    <button
                      className="ghost"
                      onClick={() => run(s)}
                      disabled={busy}
                    >
                      Run backtest
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* ── Result ── */}
      {runError && (
        <section className="panel">
          <h2>Run failed</h2>
          <p className="error-note">{runError}</p>
        </section>
      )}

      {result && (
        <section className="panel">
          <h2>
            {result.strategyName} · {result.symbol} {result.timeframe} ·{' '}
            {result.firstBarTime.slice(0, 10)} → {result.lastBarTime.slice(0, 10)}
          </h2>

          {/* The verdict strip */}
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
                {result.metrics.sharpeRatio == null
                  ? '—'
                  : fmt(result.metrics.sharpeRatio)}
              </div>
            </div>
            <div className="cell">
              <div className="k">Profit factor</div>
              <div className="v">
                {result.metrics.profitFactor == null
                  ? '—'
                  : fmt(result.metrics.profitFactor)}
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
              <PriceChart candles={candles} trades={result.trades} />
              <EquityChart
                curve={result.equityCurve}
                benchmark={buyAndHold(candles, result.initialCapital)}
              />
            </>
          )}

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
                  <td className={`num ${pnlClass(t.pnl)}`}>
                    {fmt(t.pnlPercent)}%
                  </td>
                  <td>{t.exitReason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  )
}