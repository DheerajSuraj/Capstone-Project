import { useState } from 'react'
import {
  api,
  type BacktestResultDto,
  type CandleColumns,
  type DiagnosticDto,
} from '../api'
import BlockEditor from '../blocks/BlockEditor'
import ResultPanel from '../components/ResultPanel'

/**
 * The builder: blocks (the MVP centerpiece) or code, then Run for an
 * instant ad-hoc backtest — no save required, tightest possible
 * idea->verdict loop — and Save to persist an immutable version.
 */
export default function BuilderView({
  source,
  setSource,
  name,
  setName,
  editingId,
  onSaved,
}: {
  source: string
  setSource: (s: string) => void
  name: string
  setName: (s: string) => void
  editingId: number | null
  onSaved: () => void
}) {
  const [mode, setMode] = useState<'blocks' | 'text'>(editingId ? 'text' : 'blocks')
  const [busy, setBusy] = useState(false)
  const [diagnostics, setDiagnostics] = useState<DiagnosticDto[]>([])
  const [runError, setRunError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResultDto | null>(null)
  const [candles, setCandles] = useState<CandleColumns | null>(null)
  const [savedNote, setSavedNote] = useState<string | null>(null)

  const run = async () => {
    setBusy(true)
    setResult(null)
    setCandles(null)
    setRunError(null)
    setDiagnostics([])
    try {
      const res = await api.runAdhocBacktest(source)
      if (res.ok && res.result) {
        setResult(res.result)
        api
          .getCandles(res.result.symbol, res.result.timeframe,
            res.result.firstBarTime, res.result.lastBarTime)
          .then(setCandles)
          .catch(() => setCandles(null))
      } else if (res.runError) setRunError(res.runError)
      else setDiagnostics(res.diagnostics)
    } finally {
      setBusy(false)
    }
  }

  const save = async () => {
    setBusy(true)
    setDiagnostics([])
    setSavedNote(null)
    try {
      const res = editingId
        ? await api.addVersion(editingId, source)
        : await api.createStrategy(name || 'Untitled', source)
      if (!res.ok) setDiagnostics(res.diagnostics)
      else {
        setSavedNote(`Saved as v${res.versionNumber}`)
        onSaved()
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <section className="panel">
        <h2>{editingId ? `New version of strategy #${editingId}` : 'Strategy builder'}</h2>

        <div style={{ display: 'flex', gap: 8, margin: '0 0 12px' }}>
          <button
            className="ghost"
            style={mode === 'blocks' ? { background: 'rgba(232,180,76,0.14)' } : undefined}
            onClick={() => setMode('blocks')}
          >
            Blocks
          </button>
          <button
            className="ghost"
            style={mode === 'text' ? { background: 'rgba(232,180,76,0.14)' } : undefined}
            onClick={() => setMode('text')}
          >
            Code
          </button>
        </div>

        {mode === 'blocks' ? (
          <BlockEditor onSource={setSource} />
        ) : (
          <>
            <label htmlFor="source">TSL source</label>
            <textarea
              id="source"
              value={source}
              spellCheck={false}
              onChange={(e) => setSource(e.target.value)}
            />
          </>
        )}

        {!editingId && (
          <>
            <label htmlFor="name">Save as</label>
            <input
              id="name"
              type="text"
              value={name}
              placeholder="My Strategy"
              onChange={(e) => setName(e.target.value)}
            />
          </>
        )}

        {diagnostics.map((d, i) => (
          <div className="diag" key={i}>
            <span className="code">
              {d.severity.toLowerCase()}[{d.code}]
            </span>{' '}
            {d.span.startLine}:{d.span.startCol} — {d.message}
          </div>
        ))}
        {runError && <p className="error-note">{runError}</p>}
        {savedNote && <p className="note">✓ {savedNote}</p>}

        <button onClick={run} disabled={busy}>
          Run backtest
        </button>{' '}
        <button className="ghost" onClick={save} disabled={busy}>
          {editingId ? 'Save as new version' : 'Save strategy'}
        </button>
      </section>

      {result && <ResultPanel result={result} candles={candles} />}
    </>
  )
}