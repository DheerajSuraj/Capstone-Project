import { useEffect, useRef, useState } from 'react'
import * as Blockly from 'blockly'
import './defs' // registers block definitions on import
import { toolbox, tslTheme } from './defs'
import { generateTslChecked, MISSING, STARTER_STATE } from './generator'
import { api, type DiagnosticDto } from '../api'

/**
 * The drag-and-drop strategy builder. Blocks -> TSL -> the REAL compiler
 * (live, debounced /api/compile) -> diagnostics under the canvas. Blockly
 * is a view; the compiler stays the single brain — the blocks never
 * re-implement a language rule.
 */
export default function BlockEditor({
  onSource,
  initialState,
  onState,
}: {
  onSource: (source: string) => void
  /** The blocks as the user left them last time (null = the starter). */
  initialState?: object | null
  /** Called with the blocks after every change, so leaving the Builder
   *  and coming back does not lose them. */
  onState?: (state: object) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const workspaceRef = useRef<Blockly.WorkspaceSvg | null>(null)
  const debounce = useRef<number>(0)
  const [tsl, setTsl] = useState('')
  const [diagnostics, setDiagnostics] = useState<DiagnosticDto[]>([])
  // False until the compiler has answered for the current blocks (or when
  // it could not be reached): never claim "compiles" without its answer.
  const [checked, setChecked] = useState<boolean>(false)
  const [checkError, setCheckError] = useState<string | null>(null)
  // Empty sockets, in words (the compiler would only say "unknown name").
  const [missing, setMissing] = useState<string[]>([])
  const [showCode, setShowCode] = useState(true)
  // Each check is numbered; only the newest one's answer is shown. A slow
  // answer for blocks as they were a moment ago must not overwrite the
  // answer for the blocks as they are.
  const checkSeq = useRef(0)

  useEffect(() => {
    if (!host.current || workspaceRef.current) return
    const ws = Blockly.inject(host.current, {
      toolbox,
      theme: tslTheme,
      renderer: 'zelos', // the rounded Scratch-like renderer
      grid: { spacing: 24, length: 2, colour: '#2a2f38', snap: true },
      zoom: { controls: true, wheel: true, startScale: 0.8 },
      trashcan: true,
    })
    workspaceRef.current = ws
    Blockly.serialization.workspaces.load(initialState ?? STARTER_STATE, ws)
    // Frame all top blocks (both entry and exit rules) on first load.
    setTimeout(() => {
      ws.scrollCenter()
      ws.zoomToFit()
      ws.zoomCenter(-1) // ease off max zoom so blocks aren't edge-to-edge
    }, 50)

    const regenerate = () => {
      const { source, missing: holes } = generateTslChecked(ws)
      setTsl(source)
      setMissing(holes)
      onSource(source)
      onState?.(Blockly.serialization.workspaces.save(ws))
      window.clearTimeout(debounce.current)
      setChecked(false)
      setCheckError(null)
      const mine = ++checkSeq.current
      if (!source) {
        setDiagnostics([])
        return
      }
      debounce.current = window.setTimeout(async () => {
        try {
          // Through the api helper, which sends the sign-in token: the
          // compile endpoint needs it, and a refused request must not read
          // as "no errors".
          const res = await api.compile(source)
          if (mine !== checkSeq.current) return
          setDiagnostics(res.diagnostics ?? [])
          setChecked(true)
        } catch (e) {
          if (mine !== checkSeq.current) return
          setDiagnostics([])
          setChecked(false)
          setCheckError(e instanceof Error ? e.message : String(e))
        }
      }, 400)
    }

    ws.addChangeListener((e: Blockly.Events.Abstract) => {
      if (e.isUiEvent) return
      regenerate()
    })
    regenerate()

    return () => {
      window.clearTimeout(debounce.current)
      checkSeq.current++ // an answer still on its way has nowhere to go
      workspaceRef.current = null
      ws.dispose()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // The compiler's own complaint about an empty slot says "unknown name
  // 'missing_input'"; the list above already says it in words.
  const shown = missing.length > 0
    ? diagnostics.filter((d) => !d.message.includes(MISSING))
    : diagnostics

  return (
    <div>
      <div ref={host} style={{ height: '62vh', minHeight: 420, borderRadius: 6, overflow: 'hidden', border: '1px solid #2a2f38' }} />

      {missing.length > 0 && (
        <div className="diag" role="alert">
          <b>Some blocks still have empty slots</b> — fill them before running:
          <ul style={{ margin: '4px 0 0 18px' }}>
            {missing.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        </div>
      )}
      {checkError ? (
        <p className="error-note" style={{ margin: '8px 0 0' }}>
          Couldn't check the blocks right now: {checkError}
        </p>
      ) : shown.length > 0 ? (
        shown.map((d, i) => (
          <div className="diag" key={i}>
            <span className="code">
              {d.severity.toLowerCase()}[{d.code}]
            </span>{' '}
            {d.message}
          </div>
        ))
      ) : missing.length > 0 ? null : tsl && checked ? (
        <p className="note" style={{ margin: '8px 0 0' }}>
          ✓ compiles cleanly
        </p>
      ) : tsl ? (
        <p className="note" style={{ margin: '8px 0 0' }}>
          Checking…
        </p>
      ) : (
        <p className="note" style={{ margin: '8px 0 0' }}>
          Drag a strategy block onto the canvas to begin.
        </p>
      )}

      <button className="ghost" style={{ marginTop: 10 }} onClick={() => setShowCode(!showCode)}>
        {showCode ? 'Hide code' : 'Show code'}
      </button>
      {showCode && (
        <pre
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 12.5,
            background: 'var(--bg)',
            border: '1px solid var(--line)',
            borderRadius: 4,
            padding: '10px 12px',
            whiteSpace: 'pre-wrap',
            marginTop: 8,
          }}
        >
          {tsl || '// blocks will appear here as TSL code'}
        </pre>
      )}
    </div>
  )
}