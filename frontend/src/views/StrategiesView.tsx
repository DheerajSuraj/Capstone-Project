import { useEffect, useRef, useState } from 'react'
import {
  api,
  type BacktestResultDto,
  type CandleColumns,
  type StrategyDto,
} from '../api'
import ResultPanel from '../components/ResultPanel'

/** Saved strategies: the version history lives here. Run any strategy's
 *  latest version, or load it into the builder as a new version. */
export default function StrategiesView({
  onEdit,
}: {
  onEdit: (strategyId: number, name: string, source: string) => void
}) {
  const [strategies, setStrategies] = useState<StrategyDto[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResultDto | null>(null)
  const [candles, setCandles] = useState<CandleColumns | null>(null)
  const [runSource, setRunSource] = useState<string | null>(null)
  const [chartNote, setChartNote] = useState<string | null>(null)
  // Until the list has answered, "nothing saved yet" would be a guess.
  const [loading, setLoading] = useState(true)
  const runSeq = useRef(0)

  useEffect(() => {
    api
      .listStrategies()
      .then(setStrategies)
      .catch((e: unknown) =>
        setError(`Your strategies could not be loaded: ${e instanceof Error ? e.message : String(e)}`),
      )
      .finally(() => setLoading(false))
  }, [])

  const run = async (s: StrategyDto) => {
    const mine = ++runSeq.current
    setBusy(true)
    setResult(null)
    setCandles(null)
    setChartNote(null)
    setError(null)
    try {
      // The version's source travels with the result so the debugger can
      // re-run exactly this version.
      const [res, source] = await Promise.all([
        api.runVersion(s.id, s.latestVersion),
        api.getVersionSource(s.id, s.latestVersion).catch(() => null),
      ])
      if (mine !== runSeq.current) return
      if (res.ok && res.result) {
        setRunSource(source)
        setResult(res.result)
        // The server's `to` is exclusive: end just past the last candle's
        // open, or the chart would miss the run's final candle.
        api
          .getCandles(res.result.symbol, res.result.timeframe,
            res.result.firstBarTime,
            new Date(Date.parse(res.result.lastBarTime) + 1).toISOString())
          .then((c) => {
            if (mine !== runSeq.current) return
            setCandles(c)
            if (c.truncated) {
              setChartNote(
                `The chart shows the newest ${c.t.length.toLocaleString()} candles of this run; ` +
                  'the numbers and the account chart cover all of it.',
              )
            }
          })
          .catch((e: unknown) => {
            if (mine !== runSeq.current) return
            setChartNote(`The price chart could not be loaded: ${e instanceof Error ? e.message : String(e)}`)
          })
      } else if (res.runError) setError(res.runError)
      else if (res.diagnostics.length > 0) {
        // A version saved under older rules can stop compiling.
        setError(
          `v${s.latestVersion} of “${s.name}” no longer compiles: ` +
            res.diagnostics.map((d) => d.message).join('; ') +
            ' — open it with Edit, fix it, and save a new version.',
        )
      }
    } catch (e) {
      if (mine === runSeq.current) setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (mine === runSeq.current) setBusy(false)
    }
  }

  const edit = async (s: StrategyDto) => {
    try {
      const source = await api.getVersionSource(s.id, s.latestVersion)
      onEdit(s.id, s.name, source)
    } catch (e) {
      setError(`Could not open “${s.name}”: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  return (
    <>
      <section className="panel">
        <h2>My strategies</h2>
        {error && <p className="error-note">{error}</p>}
        {loading ? (
          <p className="note">Loading your strategies…</p>
        ) : strategies.length === 0 && !error ? (
          <p className="note">Nothing saved yet — build one in the Builder tab.</p>
        ) : strategies.length === 0 ? null : (
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
                    <button className="ghost" onClick={() => run(s)} disabled={busy}>
                      Run backtest
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {result && (
        <ResultPanel
          result={result}
          candles={candles}
          source={runSource ?? undefined}
          chartNote={chartNote}
        />
      )}
    </>
  )
}