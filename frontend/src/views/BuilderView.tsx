import { useEffect, useRef, useState } from 'react'
import {
  api,
  type BacktestResultDto,
  type CandleColumns,
  type DiagnosticDto,
} from '../api'
import BlockEditor from '../blocks/BlockEditor'
import { codeToBlocks } from '../blocks/codeToBlocks'
import { EMPTY_STATE } from '../blocks/fromTree'
import ResultPanel from '../components/ResultPanel'
import { STARTERS } from '../lab/starters'

/** Why the code is not (yet) shown as blocks. */
type BlocksNotice =
  | { kind: 'syntax'; diagnostics: DiagnosticDto[] }
  | { kind: 'inexact'; problems: string[]; state: object; source: string }
  | { kind: 'error'; message: string }

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
  /** The code that `blocksState` shows (null = no blocks yet). */
  blocksSource?: string | null
  onBlocksSource?: (s: string) => void
  blocksState?: object | null
  onBlocksState?: (state: object) => void
}) {
  // The blocks in hand still show this code: reopen them as they were,
  // layout and all, instead of building them again.
  const blocksFit = blocksState != null && blocksSource === source
  const wantBlocks = !(editingId || startInCode)
  const [mode, setMode] = useState<'blocks' | 'text'>(wantBlocks && blocksFit ? 'blocks' : 'text')
  const [building, setBuilding] = useState(false)
  const [notice, setNotice] = useState<BlocksNotice | null>(null)
  // A new set of blocks needs a fresh editor (it reads its blocks once).
  const [editorKey, setEditorKey] = useState(0)
  const [syncOnLoad, setSyncOnLoad] = useState(false)
  const buildSeq = useRef(0)
  // The code as it is now, for a build that finishes after it changed.
  const latestSource = useRef(source)
  useEffect(() => {
    latestSource.current = source
  }, [source])

  const showBlocks = (state: object, code: string, sync: boolean) => {
    onBlocksState?.(state)
    onBlocksSource?.(code)
    setSyncOnLoad(sync)
    setEditorKey((k) => k + 1)
    setNotice(null)
    setMode('blocks')
  }

  // Blocks show the strategy in the code: the compiler reads the code and
  // the blocks are built from what it read. Nothing is replaced: the code
  // stays as written until a block is changed. Only code the blocks can't
  // show exactly stops here, and then the person decides.
  const openBlocks = async (code: string = source) => {
    if (blocksState != null && blocksSource === code) {
      setSyncOnLoad(false)
      setNotice(null)
      setMode('blocks')
      return
    }
    const mine = ++buildSeq.current
    setBuilding(true)
    setNotice(null)
    const stay = (n: BlocksNotice) => {
      setNotice(n)
      setMode('text') // the code, with the reason above it
    }
    try {
      const res = await codeToBlocks(code)
      // Typed into the code meanwhile, or pressed Code: this answer is stale.
      if (mine !== buildSeq.current || latestSource.current !== code) return
      if (res.kind === 'exact') showBlocks(res.state, code, false)
      else if (res.kind === 'inexact') stay({ kind: 'inexact', problems: res.problems, state: res.state, source: code })
      else stay({ kind: 'syntax', diagnostics: res.diagnostics })
    } catch (e) {
      if (mine !== buildSeq.current) return
      stay({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
    } finally {
      if (mine === buildSeq.current) setBuilding(false)
    }
  }

  const toBlocks = () => {
    if (mode === 'blocks' || building) return
    void openBlocks()
  }
  const toCode = () => {
    buildSeq.current++ // a build still on its way is no longer wanted
    setBuilding(false)
    setNotice(null)
    setMode('text')
  }

  // Opening the Builder on Blocks with code the blocks don't show yet
  // (the starter on a first visit): build them from that code.
  useEffect(() => {
    if (wantBlocks && !blocksFit) void openBlocks()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

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

  // A health-check idea put into the editor: what it was, and the code
  // before it, for Undo.
  const [applied, setApplied] = useState<{ label: string; previous: string } | null>(null)
  const editorTop = useRef<HTMLElement>(null)

  /** Swap in new code and show it the way the person is looking at it. */
  const putSource = (next: string) => {
    setSource(next)
    setNotice(null)
    if (mode === 'blocks' || building) void openBlocks(next)
  }

  const applyFilter = (next: string, label: string) => {
    // The idea was worked out on the code that was backtested. If the code
    // has been edited since, adding it means going back to that code.
    if (
      runSource != null &&
      source !== runSource &&
      !window.confirm(
        'Your code has changed since this backtest. Adding this filter puts back the code ' +
          'you backtested, with the filter added, and your later edits are lost (Undo brings ' +
          'them back). Continue?',
      )
    ) {
      return
    }
    setApplied({ label, previous: source })
    setSavedNote(null)
    putSource(next)
    editorTop.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const undoApply = () => {
    if (!applied) return
    putSource(applied.previous)
    setApplied(null)
  }

  const run = async () => {
    const mine = ++runSeq.current
    setApplied(null)
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
      <section className="panel" ref={editorTop}>
        <h2>{editingId ? `New version of strategy #${editingId}` : 'Strategy builder'}</h2>

        <div style={{ display: 'flex', gap: 8, margin: '0 0 12px' }}>
          <button
            className="ghost"
            style={mode === 'blocks' || building ? { background: 'rgba(232,180,76,0.14)' } : undefined}
            onClick={toBlocks}
            aria-busy={building}
          >
            Blocks
          </button>
          <button
            className="ghost"
            style={mode === 'text' && !building ? { background: 'rgba(232,180,76,0.14)' } : undefined}
            onClick={toCode}
          >
            Code
          </button>
        </div>

        {applied && (
          <div className="note tsb-applied" role="status">
            <span>
              ✓ Added “<b>{applied.label}</b>” to every rule that buys. Run the backtest to see
              what it changes.
            </span>
            <span className="tsb-applied__actions">
              <button onClick={run} disabled={busy}>
                Run backtest
              </button>
              <button className="ghost" onClick={undoApply}>
                Undo
              </button>
            </span>
          </div>
        )}

        {notice?.kind === 'syntax' && (
          <div className="diag" role="alert">
            <b>The code has a mistake, so it can't be shown as blocks yet.</b> Fix it
            below, then press Blocks again:
            <ul style={{ margin: '4px 0 0 18px' }}>
              {notice.diagnostics.map((d, i) => (
                <li key={i}>
                  {d.span.startLine}:{d.span.startCol} — {d.message}
                </li>
              ))}
            </ul>
          </div>
        )}
        {notice?.kind === 'inexact' && (
          <div className="diag blocks-notice" role="alert">
            <b>Some of this code can't be shown as blocks:</b>
            <ul style={{ margin: '4px 0 8px 18px' }}>
              {notice.problems.map((p, i) => (
                <li key={i}>{p}</li>
              ))}
            </ul>
            <button onClick={() => setNotice(null)}>Keep editing the code</button>{' '}
            <button className="ghost" onClick={() => showBlocks(notice.state, notice.source, true)}>
              Use the blocks anyway
            </button>
            <p className="note" style={{ margin: '6px 0 0' }}>
              “Use the blocks anyway” rewrites the code to what the blocks show, without
              the parts above. Save first if you want to keep this version.
            </p>
          </div>
        )}
        {notice?.kind === 'error' && (
          <p className="error-note">Couldn't build the blocks from the code: {notice.message}</p>
        )}

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
                // On Blocks, show the starter's blocks; on Code, its code.
                if (mode === 'blocks' || building) void openBlocks(s.source)
                else setNotice(null)
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

        {building ? (
          <div
            className="note"
            role="status"
            style={{
              height: '62vh',
              minHeight: 420,
              display: 'grid',
              placeItems: 'center',
              border: '1px solid var(--line)',
              borderRadius: 6,
            }}
          >
            Building the blocks from the code…
          </div>
        ) : mode === 'blocks' ? (
          <BlockEditor
            key={editorKey}
            initialState={blocksState ?? EMPTY_STATE}
            source={source}
            onSource={(s) => {
              setSource(s)
              onBlocksSource?.(s)
            }}
            onState={onBlocksState}
            syncOnLoad={syncOnLoad}
          />
        ) : (
          <>
            <label htmlFor="source">TSL source</label>
            <textarea
              id="source"
              value={source}
              spellCheck={false}
              onChange={(e) => {
                setSource(e.target.value)
                setApplied(null) // Undo would now also throw away this typing
                // Its blocks were for the code as it was.
                if (notice?.kind === 'inexact') setNotice(null)
              }}
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
          onApplyFilter={applyFilter}
        />
      )}
    </>
  )
}