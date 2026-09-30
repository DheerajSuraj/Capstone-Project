import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api,
  type BarExplanationDto,
  type RunContext,
  type StatementExplanationDto,
} from '../api'
import ConditionTree from './ConditionTree'
import { crossStory } from './Gauge'
import { num, pct, utc } from './format'
import { action as sayAction, leaf } from './humanize'
import './debugger.css'

/**
 * Module 3: "why did (or didn't) it trade at this candle?"
 *
 * Every true/false comes from the backend interpreter and every
 * filled/ignored from the engine's own record of the run — this panel only
 * translates them into plain words and pictures.
 */
export default function DecisionPanel({
  run,
  timeMillis,
  times,
  onNavigate,
  onClose,
}: {
  run: RunContext
  timeMillis: number
  /** Every candle's open time (ms), for stepping ◀ ▶. */
  times: number[]
  onNavigate: (timeMillis: number) => void
  onClose: () => void
}) {
  const [data, setData] = useState<BarExplanationDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const panel = useRef<HTMLElement>(null)

  // Stepping back and forth revisits candles; don't re-run the backtest
  // for one we've already explained. Reset when the run changes.
  const cache = useRef(new Map<number, BarExplanationDto>())
  useEffect(() => {
    cache.current = new Map()
  }, [run])

  useEffect(() => {
    const hit = cache.current.get(timeMillis)
    if (hit) {
      setData(hit)
      setError(null)
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .explainBar(run, timeMillis)
      .then((res) => {
        if (cancelled) return
        if (res.ok && res.explanation) {
          cache.current.set(timeMillis, res.explanation)
          setData(res.explanation)
        } else {
          setData(null)
          setError(
            res.runError ||
              res.diagnostics.map((d) => d.message).join('; ') ||
              'Could not explain this candle.',
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

  // ── Stepping through time ──────────────────────────────────────────
  const index = indexOf(times, timeMillis)
  const step = useCallback(
    (d: number) => {
      const next = index + d
      if (next >= 0 && next < times.length) onNavigate(times[next])
    },
    [index, times, onNavigate],
  )

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      // Only when nothing else wants the keys (the Blockly editor, a text box).
      const a = document.activeElement
      const free = a === document.body || (a != null && panel.current?.contains(a))
      if (!free) return
      if (e.key === 'ArrowLeft') step(-1)
      else if (e.key === 'ArrowRight') step(1)
      else if (e.key === 'Escape') onClose()
      else return
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [step, onClose])

  const verdict = data ? verdictFor(data) : null
  const main = data ? mainStatement(data) : null
  const others = data ? data.statements.filter((s) => s !== main) : []

  return (
    <section ref={panel} className="tsb-dbg tsb-dbg__panel" aria-live="polite">
      <header className="tsb-dbg__header">
        <div className="tsb-nav">
          <button
            type="button"
            className="ghost tsb-nav__btn"
            onClick={() => step(-1)}
            disabled={index <= 0}
            title="Previous candle (←)"
          >
            ◀
          </button>
          <div>
            <div className="tsb-dbg__eyebrow">Why here?</div>
            <div className="tsb-dbg__title">
              {utc(data?.openTimeMillis ?? timeMillis)}
            </div>
          </div>
          <button
            type="button"
            className="ghost tsb-nav__btn"
            onClick={() => step(1)}
            disabled={index < 0 || index >= times.length - 1}
            title="Next candle (→)"
          >
            ▶
          </button>
        </div>
        <button type="button" className="ghost" onClick={onClose} title="Close (Esc)">
          Close
        </button>
      </header>

      {loading && !data && (
        <p className="tsb-dbg__muted">Replaying the backtest up to this candle…</p>
      )}
      {error && <p className="error-note">{error}</p>}

      {data && verdict && (
        <div className={loading ? 'is-stale' : undefined}>
          <div className={`tsb-verdict tsb-verdict--${verdict.tone}`}>
            <div className="tsb-verdict__icon" aria-hidden>
              {verdict.icon}
            </div>
            <div>
              <div className="tsb-verdict__title">{verdict.title}</div>
              <div className="tsb-verdict__sub">{verdict.sub}</div>
            </div>
          </div>

          <div className="tsb-candle">
            <span>Open {num(data.open)}</span>
            <span>High {num(data.high)}</span>
            <span>Low {num(data.low)}</span>
            <span>Close {num(data.close)}</span>
            {!data.warmup && (
              <span className="tsb-dbg__chip">
                {data.inPosition ? '📦 Holding a position' : '💤 Not holding anything'}
              </span>
            )}
          </div>

          {main && <RuleCard s={main} warmup={data.warmup} open />}

          {others.length > 0 && (
            <details className="tsb-others">
              <summary>
                Other rules on this candle ({others.length})
              </summary>
              {others.map((s) => (
                <RuleCard
                  key={`${s.ruleIndex}-${s.statementIndex}`}
                  s={s}
                  warmup={data.warmup}
                />
              ))}
            </details>
          )}

          <p className="tsb-dbg__hint">
            Tip: use ◀ ▶ or your arrow keys to step candle by candle.
          </p>
        </div>
      )}
    </section>
  )
}

// ── One rule ────────────────────────────────────────────────────────────

function RuleCard({
  s,
  warmup,
  open = false,
}: {
  s: StatementExplanationDto
  warmup: boolean
  open?: boolean
}) {
  const taken =
    s.taken === 'THEN' ? s.thenAction : s.taken === 'ELSE' ? s.elseAction : null
  return (
    <article className="tsb-dbg__stmt">
      <div className="tsb-dbg__stmt-head">
        <span className="tsb-dbg__rule">Rule “{s.ruleName}”</span>
        <span className="tsb-dbg__muted">
          wants to {sayAction(s.thenAction)}
          {s.elseAction && `, otherwise ${sayAction(s.elseAction)}`}
        </span>
      </div>

      <Journey s={s} warmup={warmup} taken={taken} />

      {!open && !warmup && s.outcome === 'NO_ACTION' && (
        <p className="tsb-dbg__lead">{closestLine(s)}</p>
      )}

      <details className="tsb-why" open={open}>
        <summary>What the rule checked</summary>
        <ConditionTree node={s.condition} highlight={s.closestChange?.span ?? null} />
      </details>
    </article>
  )
}

/** Checked → condition → order, with the step where it stopped. */
function Journey({
  s,
  warmup,
  taken,
}: {
  s: StatementExplanationDto
  warmup: boolean
  taken: string | null
}) {
  type Step = { label: string; value: string; state: 'ok' | 'stop' | 'idle' }
  const steps: Step[] = []
  if (warmup) {
    steps.push({ label: 'Rules checked?', value: 'Not yet (warm-up)', state: 'stop' })
    steps.push({ label: 'Condition', value: '—', state: 'idle' })
    steps.push({ label: 'Order', value: '—', state: 'idle' })
  } else {
    steps.push({ label: 'Rules checked?', value: 'Yes', state: 'ok' })
    steps.push({
      label: 'Condition',
      value: s.condition.passed ? 'True' : s.taken === 'ELSE' ? 'False → ELSE' : 'False',
      state: s.condition.passed || s.taken === 'ELSE' ? 'ok' : 'stop',
    })
    const order: Record<StatementExplanationDto['outcome'], Step> = {
      WARMUP: { label: 'Order', value: '—', state: 'idle' },
      NO_ACTION: { label: 'Order', value: 'Nothing to do', state: 'idle' },
      FILLED: {
        label: 'Order',
        value: `Filled at next open${s.fillPrice != null ? ` · ${num(s.fillPrice)}` : ''}`,
        state: 'ok',
      },
      IGNORED_ALREADY_LONG: { label: 'Order', value: 'Skipped: already holding', state: 'stop' },
      IGNORED_NOTHING_TO_SELL: { label: 'Order', value: 'Skipped: nothing to sell', state: 'stop' },
      REJECTED_TOO_SMALL: { label: 'Order', value: 'Too small for the exchange', state: 'stop' },
      NOT_FILLED_LAST_BAR: { label: 'Order', value: 'Last candle: no next open', state: 'stop' },
      SETTING_APPLIED: { label: 'Setting', value: `Applied: ${taken ? sayAction(taken) : ''}`, state: 'ok' },
    }
    steps.push(order[s.outcome])
  }
  return (
    <ol className="tsb-journey">
      {steps.map((st, i) => (
        <li key={i} className={`tsb-journey__step is-${st.state}`}>
          <span className="tsb-journey__dot" aria-hidden>
            {st.state === 'ok' ? '✓' : st.state === 'stop' ? '■' : '·'}
          </span>
          <span className="tsb-journey__label">{st.label}</span>
          <span className="tsb-journey__value">{st.value}</span>
        </li>
      ))}
    </ol>
  )
}

function closestLine(s: StatementExplanationDto): string {
  const c = s.closestChange
  if (c) {
    const node = findLeaf(s.condition, c.text)
    const what = node ? leaf(node.text, node.kind, node.op) : c.text
    return `Closest to happening: “${what}” was ${num(c.distance)} away${
      c.relativeDistance != null ? ` (${pct(c.relativeDistance, 1)})` : ''
    }. Everything else was already fine.`
  }
  if (s.condition.unknown) return 'Some indicators had no value yet on this candle.'
  const story = crossStory(s.condition)
  if (story) return story
  if (s.condition.children.length === 0) return 'No small change on this candle alone would have made it true.'
  return 'More than one part was false, so no single change would have been enough.'
}

function findLeaf(n: StatementExplanationDto['condition'], text: string): typeof n | null {
  if (n.text === text && n.children.length === 0) return n
  for (const c of n.children) {
    const f = findLeaf(c, text)
    if (f) return f
  }
  return null
}

// ── The headline ────────────────────────────────────────────────────────

type Verdict = { icon: string; title: string; sub: string; tone: 'go' | 'stop' | 'quiet' }

const isBuy = (a: string | null) => !!a && a.startsWith('BUY')
const isSell = (a: string | null) => !!a && a.startsWith('SELL')
const takenAction = (s: StatementExplanationDto) =>
  s.taken === 'THEN' ? s.thenAction : s.taken === 'ELSE' ? s.elseAction : null

/** The rule a person most likely means by "why didn't it trade here?". */
function mainStatement(d: BarExplanationDto): StatementExplanationDto | null {
  const pri: StatementExplanationDto['outcome'][] = [
    'FILLED',
    'IGNORED_ALREADY_LONG',
    'REJECTED_TOO_SMALL',
    'NOT_FILLED_LAST_BAR',
  ]
  for (const o of pri) {
    const s = d.statements.find(
      (x) => x.outcome === o && (isBuy(takenAction(x)) || isSell(takenAction(x))),
    )
    if (s) return s
  }
  // Nothing happened: holding → the selling rule matters; flat → the buying one.
  const want = d.inPosition ? isSell : isBuy
  return (
    d.statements.find((x) => want(x.thenAction)) ??
    d.statements.find((x) => isBuy(x.thenAction) || isSell(x.thenAction)) ??
    d.statements[0] ??
    null
  )
}

function verdictFor(d: BarExplanationDto): Verdict {
  if (d.warmup) {
    return {
      icon: '⏳',
      tone: 'quiet',
      title: 'Too early — still warming up',
      sub: `The strategy waits for the first ${d.warmupBars} candles so its indicators have enough history to be trusted. This is candle ${d.bar + 1}.`,
    }
  }
  const s = mainStatement(d)
  if (!s) return { icon: '•', tone: 'quiet', title: 'Nothing to check here', sub: '' }
  const act = takenAction(s)
  switch (s.outcome) {
    case 'FILLED':
      return isBuy(act)
        ? {
            icon: '🛒',
            tone: 'go',
            title: 'Bought here',
            sub: `Rule “${s.ruleName}” was true when this candle closed, so it bought at the next candle's open (${num(s.fillPrice)}). Orders always fill on the next candle, never on the one that gave the signal.`,
          }
        : {
            icon: '📤',
            tone: 'go',
            title: 'Sold here',
            sub: `Rule “${s.ruleName}” was true when this candle closed, so it sold at the next candle's open (${num(s.fillPrice)}).`,
          }
    case 'IGNORED_ALREADY_LONG':
      return {
        icon: '📦',
        tone: 'stop',
        title: 'Wanted to buy — but already holding',
        sub: `Rule “${s.ruleName}” said buy, but a position was already open. TSB holds one position at a time, so extra buy signals are skipped.`,
      }
    case 'REJECTED_TOO_SMALL':
      return {
        icon: '🪙',
        tone: 'stop',
        title: 'Wanted to trade — order too small',
        sub: `The order was below the exchange's minimum size, so a real exchange would have refused it. Try a larger position size.`,
      }
    case 'NOT_FILLED_LAST_BAR':
      return {
        icon: '🏁',
        tone: 'stop',
        title: 'Signal on the very last candle',
        sub: 'Orders fill at the next candle, and there is no next candle yet.',
      }
    default:
      return {
        icon: '✋',
        tone: 'quiet',
        title: d.inPosition ? 'Kept holding — no sell signal' : 'No trade here',
        sub: `Rule “${s.ruleName}” (${sayAction(s.thenAction)}) was not true. ${closestLine(s)}`,
      }
  }
}

function indexOf(times: number[], t: number): number {
  let lo = 0
  let hi = times.length - 1
  if (hi < 0 || t < times[0]) return -1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (times[mid] <= t) lo = mid
    else hi = mid - 1
  }
  return lo
}

