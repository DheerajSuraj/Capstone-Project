import { useState } from 'react'
import { api, type SharedStrategyDto } from '../api'
import { pnlClass, signedPct } from './util'

/**
 * A shared strategy inside a post: its backtest numbers (saved when the post
 * was made, so every reader sees the same), a small equity curve, the code,
 * and "Copy to my strategies".
 */
export default function StrategyCard({
  postId,
  s,
  signedIn,
  onSignIn,
  onOpenStrategies,
}: {
  postId: number
  s: SharedStrategyDto
  signedIn: boolean
  onSignIn: () => void
  onOpenStrategies: () => void
}) {
  const [showCode, setShowCode] = useState(false)
  const [copy, setCopy] = useState<'idle' | 'busy' | 'done' | 'err'>('idle')
  const [err, setErr] = useState<string | null>(null)
  const r = s.results

  const doCopy = async () => {
    if (!signedIn) return onSignIn()
    setCopy('busy')
    setErr(null)
    try {
      const res = await api.forumCopyStrategy(postId)
      if (res.ok) setCopy('done')
      else {
        setCopy('err')
        setErr("It didn't compile — the language may have changed since it was shared.")
      }
    } catch (e) {
      setCopy('err')
      setErr(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className="tsb-fs">
      <div className="tsb-fs__head">
        <div>
          <div className="tsb-fs__eyebrow">Shared strategy</div>
          <div className="tsb-fs__name">
            {s.name} <span className="tsb-f__muted">v{s.version}</span>
          </div>
          <div className="tsb-f__muted tsb-f__small">
            {s.symbol} · {s.timeframe}
            {r?.from && r?.to && ` · backtested ${r.from.slice(0, 10)} → ${r.to.slice(0, 10)}`}
          </div>
        </div>
        {r?.curve && r.curve.length > 1 && <Sparkline points={r.curve} />}
      </div>

      {r?.error ? (
        <p className="tsb-f__muted tsb-f__small">Could not be backtested: {r.error}</p>
      ) : (
        r && (
          <div className="tsb-fs__stats">
            <Stat k="Return" v={r.returnPct != null ? signedPct(r.returnPct) : '—'} cls={r.returnPct != null ? pnlClass(r.returnPct) : ''} />
            <Stat k="Max drawdown" v={r.maxDrawdownPct != null ? `${r.maxDrawdownPct.toFixed(2)}%` : '—'} />
            <Stat k="Win rate" v={r.winRate != null ? `${r.winRate.toFixed(1)}%` : '—'} />
            <Stat k="Trades" v={r.trades != null ? String(r.trades) : '—'} />
            <Stat k="Sharpe" v={r.sharpe != null ? r.sharpe.toFixed(2) : '—'} />
          </div>
        )
      )}

      <div className="tsb-fs__actions">
        <button type="button" onClick={doCopy} disabled={copy === 'busy' || copy === 'done'}>
          {copy === 'busy' ? 'Copying…' : copy === 'done' ? '✓ Copied to My strategies' : 'Copy to my strategies'}
        </button>
        {copy === 'done' && (
          <button type="button" className="ghost" onClick={onOpenStrategies}>
            Open My strategies
          </button>
        )}
        {s.source && (
          <button type="button" className="ghost" onClick={() => setShowCode((x) => !x)}>
            {showCode ? 'Hide code' : 'View code'}
          </button>
        )}
      </div>
      {err && <p className="error-note">{err}</p>}
      {showCode && s.source && (
        <pre className="tsb-rt__pre tsb-fs__code">
          <code>{s.source}</code>
        </pre>
      )}
    </div>
  )
}

function Stat({ k, v, cls = '' }: { k: string; v: string; cls?: string }) {
  return (
    <div className="tsb-fs__stat">
      <div className="tsb-f__muted tsb-f__small">{k}</div>
      <div className={`tsb-fs__v ${cls}`}>{v}</div>
    </div>
  )
}

/** The equity curve as a tiny line, amber like the app's other curves. */
function Sparkline({ points }: { points: { t: number; equity: number }[] }) {
  const w = 160
  const h = 44
  const ys = points.map((p) => p.equity)
  const min = Math.min(...ys)
  const max = Math.max(...ys)
  const span = max - min || 1
  const d = points
    .map((p, i) => {
      const x = (i / (points.length - 1)) * w
      const y = h - 2 - ((p.equity - min) / span) * (h - 4)
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg className="tsb-fs__spark" viewBox={`0 0 ${w} ${h}`} width={w} height={h} aria-label="Equity curve">
      <path d={d} fill="none" stroke="#e8b44c" strokeWidth="1.5" />
    </svg>
  )
}
