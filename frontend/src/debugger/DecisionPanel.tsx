import { useEffect, useState } from 'react'
import { api, type BarExplanationDto, type RunContext } from '../api'
import ConditionTree from './ConditionTree'
import { BLOCKED, num, OUTCOME_LABEL, utc } from './format'
import './debugger.css'

/**
 * Module 3: "why did (or didn't) it trade at this bar?"
 *
 * Every true/false shown here comes from the backend interpreter and every
 * filled/ignored/rejected from the engine's own record of the run — this
 * panel only lays them out.
 */
export default function DecisionPanel({
  run,
  timeMillis,
  onClose,
}: {
  run: RunContext
  timeMillis: number
  onClose: () => void
}) {
  const [data, setData] = useState<BarExplanationDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .explainBar(run, timeMillis)
      .then((res) => {
        if (cancelled) return
        if (res.ok && res.explanation) {
          setData(res.explanation)
        } else {
          setData(null)
          setError(
            res.runError ||
              res.diagnostics.map((d) => d.message).join('; ') ||
              'Could not explain this bar.',
          )
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setData(null)
          setError(e instanceof Error ? e.message : String(e))
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [run, timeMillis])

  return (
    <section className="tsb-dbg tsb-dbg__panel" aria-live="polite">
      <header className="tsb-dbg__header">
        <div>
          <div className="tsb-dbg__eyebrow">Decision debugger</div>
          <div className="tsb-dbg__title">
            {data ? utc(data.openTimeMillis) : utc(timeMillis)}
          </div>
        </div>
        <button type="button" className="ghost" onClick={onClose}>
          Close
        </button>
      </header>

      {loading && !data && <p className="tsb-dbg__muted">Re-running the backtest…</p>}
      {error && <p className="error-note">{error}</p>}

      {data && (
        <div className={loading ? 'is-stale' : undefined}>
          <div className="tsb-dbg__bar">
            <span>O {num(data.open)}</span>
            <span>H {num(data.high)}</span>
            <span>L {num(data.low)}</span>
            <span>C {num(data.close)}</span>
            <span className="tsb-dbg__chip">
              {data.warmup
                ? `Warm-up (bar ${data.bar + 1} of the first ${data.warmupBars})`
                : data.inPosition
                  ? 'In a position at this close'
                  : 'No position at this close'}
            </span>
            {data.lastBar && <span className="tsb-dbg__chip">Last bar</span>}
          </div>

          {data.warmup && <p className="tsb-dbg__lead">{data.summary}</p>}

          {data.statements.map((s) => (
            <article
              key={`${s.ruleIndex}-${s.statementIndex}`}
              className="tsb-dbg__stmt"
            >
              <div className="tsb-dbg__stmt-head">
                <span className="tsb-dbg__rule">rule {s.ruleName}</span>
                <code className="tsb-dbg__code">
                  THEN {s.thenAction}
                  {s.elseAction && ` ELSE ${s.elseAction}`}
                </code>
                <span
                  className={`tsb-dbg__outcome${
                    BLOCKED.includes(s.outcome) ? ' is-blocked' : ''
                  }${s.outcome === 'FILLED' ? ' is-filled' : ''}`}
                >
                  {OUTCOME_LABEL[s.outcome]}
                </span>
              </div>
              <p className="tsb-dbg__lead">{s.sentence}</p>
              <ConditionTree
                node={s.condition}
                highlight={s.closestChange?.span ?? null}
              />
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
