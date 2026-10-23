import { useRef, useState } from 'react'
import {
  api,
  type BacktestResultDto,
  type CandleColumns,
  type DiagnosticDto,
} from '../api'
import BlockEditor from '../blocks/BlockEditor'
import ResultPanel from '../components/ResultPanel'
import { STARTERS } from '../lab/starters'

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
  startInCode,
  blocksSource,
  onBlocksSource,
  blocksState,
  onBlocksState,
}: {
  source: string
  setSource: (s: string) => void
  name: string
  setName: (s: string) => void
  editingId: number | null
  /** After a save: the strategy it went into, so the next save adds a
   *  version to it instead of making another strategy. */
  onSaved: (strategyId: number) => void
  /** Open on the Code tab: the source came from somewhere other than the blocks. */
  startInCode?: boolean
  /** The last code the Blocks view produced (null = never used). */
  blocksSource?: string | null
  onBlocksSource?: (s: string) => void
  blocksState?: object | null
  onBlocksState?: (state: object) => void
}) {
  const [mode, setMode] = useState<'blocks' | 'text'>(
    editingId || startInCode ? 'text' : 'blocks',
  )

  // Blocks always show their own strategy. Switching to them replaces the
  // code below — so say so first whenever that code is not theirs.
  const toBlocks = () => {
    if (mode === 'blocks') return
    if (source.trim() && source !== blocksSource) {
      const ok = window.confirm(
        'The Blocks view shows its own strategy and will replace the code below. ' +
          'Save first if you want to keep it. Switch to Blocks anyway?',
      )
      if (!ok) return
    }
    setMode('blocks')
  }
  const [busy, setBusy] = useState(false)
  const [diagnostics, setDiagnostics] = useState<DiagnosticDto[]>([])
  const [runError, setRunError] = useState<string | null>(null)
  const [result, setResult] = useState<BacktestResultDto | null>(null)
  const [candles, setCandles] = useState<CandleColumns | null>(null)
  const [savedNote, setSavedNote] = useState<string | null>(null)
  // The source exactly as it was when this result was produced — the
  // debugger must re-run THAT, not whatever is in the editor now.
  const [runSource, setRunSource] = useState<string | null>(null)
  const [chartNote, setChartNote] = useState<string | null>(null)
  // Runs are numbered, so a chart still loading for an earlier run can't
  // land under the result of a later one.
  const runSeq = useRef(0)

  const run = async () => {
    const mine = ++runSeq.current
    setBusy(true)
    setResult(null)
    setCandles(null)
    setChartNote(null)
    setRunError(null)
    setDiagnostics([])
    setSavedNote(null)
    const submitted = source
    try {
      const res = await api.runAdhocBacktest(submitted)
      if (mine !== runSeq.current) return
      if (res.ok && res.result) {
        setRunSource(submitted)
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
            setCandles(null)
            setChartNote(`The price chart could not be loaded: ${e instanceof Error ? e.message : String(e)}`)
          })
      } else if (res.runError) setRunError(res.runError)
      else setDiagnostics(res.diagnostics)
    } catch (e) {
      // A network failure or a server error used to vanish here, leaving
      // the button doing nothing at all.
      if (mine === runSeq.current) setRunError(e instanceof Error ? e.message : String(e))
    } finally {
      if (mine === runSeq.current) setBusy(false)
    }
  }

  const save = async () => {
    setBusy(true)
    setDiagnostics([])
    setSavedNote(null)
    setRunError(null)
    try {
      const res = editingId
        ? await api.addVersion(editingId, source)
        : await api.createStrategy(name || 'Untitled', source)
      if (!res.ok) setDiagnostics(res.diagnostics)
      else {
        setSavedNote(`Saved as v${res.versionNumber}`)
        if (res.strategyId != null) onSaved(res.strategyId)
      }
    } catch (e) {
      setRunError(e instanceof Error ? e.message : String(e))
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
            onClick={toBlocks}
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

        {!editingId && (
          <div className="tsb-starter">
            <label htmlFor="starter">Start from a ready-made strategy</label>
            <select
              id="starter"
              value=""
              onChange={(e) => {
                const s = STARTERS.find((x) => x.id === e.target.value)
                if (!s) return
                setSource(s.source)
                setName(s.name)
                setMode('text')
              }}
            >
              <option value="">Choose one…</option>
              {STARTERS.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} ({s.timeframe}) — {s.blurb}
                </option>
              ))}
            </select>
          </div>
        )}

        {mode === 'blocks' ? (
          <BlockEditor
            onSource={(s) => {
              setSource(s)
              onBlocksSource?.(s)
            }}
            initialState={blocksState}
            onState={onBlocksState}
          />
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