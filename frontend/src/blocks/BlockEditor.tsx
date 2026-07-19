import { useEffect, useRef, useState } from 'react'
import * as Blockly from 'blockly'
import './defs' // registers block definitions on import
import { toolbox, tslTheme } from './defs'
import { generateTsl, STARTER_STATE } from './generator'
import type { DiagnosticDto } from '../api'

/**
 * The drag-and-drop strategy builder. Blocks -> TSL -> the REAL compiler
 * (live, debounced /api/compile) -> diagnostics under the canvas. Blockly
 * is a view; the compiler stays the single brain — the blocks never
 * re-implement a language rule.
 */
export default function BlockEditor({
  onSource,
}: {
  onSource: (source: string) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const workspaceRef = useRef<Blockly.WorkspaceSvg | null>(null)
  const debounce = useRef<number>(0)
  const [tsl, setTsl] = useState('')
  const [diagnostics, setDiagnostics] = useState<DiagnosticDto[]>([])
  const [showCode, setShowCode] = useState(true)

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
    Blockly.serialization.workspaces.load(STARTER_STATE, ws)
    // Frame all top blocks (both entry and exit rules) on first load.
    setTimeout(() => {
      ws.scrollCenter()
      ws.zoomToFit()
      ws.zoomCenter(-1) // ease off max zoom so blocks aren't edge-to-edge
    }, 50)

    const regenerate = () => {
      const source = generateTsl(ws)
      setTsl(source)
      onSource(source)
      window.clearTimeout(debounce.current)
      if (!source) {
        setDiagnostics([])
        return
      }
      debounce.current = window.setTimeout(async () => {
        try {
          const res = await fetch('/api/compile', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ source }),
          })
          const body = await res.json()
          setDiagnostics(body.diagnostics ?? [])
        } catch {
          setDiagnostics([])
        }
      }, 400)
    }

    ws.addChangeListener((e: Blockly.Events.Abstract) => {
      if (e.isUiEvent) return
      regenerate()
    })
    regenerate()

    return () => {
      workspaceRef.current = null
      ws.dispose()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div>
      <div ref={host} style={{ height: '62vh', minHeight: 420, borderRadius: 6, overflow: 'hidden', border: '1px solid #2a2f38' }} />

      {diagnostics.length > 0 ? (
        diagnostics.map((d, i) => (
          <div className="diag" key={i}>
            <span className="code">
              {d.severity.toLowerCase()}[{d.code}]
            </span>{' '}
            {d.message}
          </div>
        ))
      ) : tsl ? (
        <p className="note" style={{ margin: '8px 0 0' }}>
          ✓ compiles cleanly
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