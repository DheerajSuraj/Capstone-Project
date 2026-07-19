import { useEffect, useState } from 'react'
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

  useEffect(() => {
    api
      .listStrategies()
      .then(setStrategies)
      .catch(() => setError('Backend unreachable — is the server running on 8080?'))
  }, [])

  const run = async (s: StrategyDto) => {
    setBusy(true)
    setResult(null)
    setCandles(null)
    setError(null)
    try {
      const res = await api.runVersion(s.id, s.latestVersion)
      if (res.ok && res.result) {
        setResult(res.result)
        api
          .getCandles(res.result.symbol, res.result.timeframe,
            res.result.firstBarTime, res.result.lastBarTime)
          .then(setCandles)
          .catch(() => setCandles(null))
      } else if (res.runError) setError(res.runError)
    } finally {
      setBusy(false)
    }
  }

  const edit = async (s: StrategyDto) => {
    const source = await api.getVersionSource(s.id, s.latestVersion)
    onEdit(s.id, s.name, source)
  }

  return (
    <>
      <section className="panel">
        <h2>My strategies</h2>
        {error && <p className="error-note">{error}</p>}
        {strategies.length === 0 && !error ? (
          <p className="note">Nothing saved yet — build one in the Builder tab.</p>
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

      {result && <ResultPanel result={result} candles={candles} />}
    </>
  )
}