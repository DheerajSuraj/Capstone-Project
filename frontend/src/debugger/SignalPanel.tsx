import { useState } from 'react'
import {
  api,
  type DiscoveryReportDto,
  type RunContext,
  type SignalStatisticsDto,
  type StatementStatsDto,
} from '../api'
import DiscoveryPanel from './DiscoveryPanel'
import { leaf } from './humanize'
import './debugger.css'

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
const WD_SHORT = ['M', 'T', 'W', 'T', 'F', 'S', 'S']
const n = (v: number) => v.toLocaleString('en-US')
const p0 = (v: number) => `${Math.round(v)}%`

/**
 * Module 4 as a "health check": how often the rules fire, what stops them,
 * and whether winning trades had something in common — told as a story,
 * with the statistics one click away for anyone who wants them.
 */
export default function SignalPanel({
  run,
  onApply,
}: {
  run: RunContext
  /** Adds an idea's filter to the strategy in the editor (builder only). */
  onApply?: (source: string, label: string) => void
}) {
  const [stats, setStats] = useState<SignalStatisticsDto | null>(null)
  const [discovery, setDiscovery] = useState<DiscoveryReportDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [tab, setTab] = useState<'rules' | 'winners'>('rules')

  const analyse = async () => {
    setBusy(true)
    setError(null)
    try {
      const res = await api.signals(run)
      if (res.ok && res.statistics && res.discovery) {
        setStats(res.statistics)
        setDiscovery(res.discovery)
      } else {
        setError(
          res.runError ||
            res.diagnostics.map((d) => d.message).join('; ') ||
            'Could not analyse this run.',
        )
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const tradeRules = stats?.statements.filter(
    (s) => s.thenAction === 'BUY' || s.thenAction === 'SELL',
  )

  return (
    <section className="tsb-dbg tsb-dbg__panel">
      <header className="tsb-dbg__header">
        <div>
          <div className="tsb-dbg__eyebrow">Strategy health check</div>
          <div className="tsb-dbg__title">
            How often your rules fire, what blocks them, and what your winners had in common
          </div>
        </div>
        <button type="button" onClick={analyse} disabled={busy}>
          {busy ? 'Checking…' : stats ? 'Check again' : 'Run health check'}
        </button>
      </header>

      {error && <p className="error-note">{error}</p>}

      {stats && discovery && (
        <>
          <div className="tsb-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'rules'}
              className={tab === 'rules' ? 'is-on' : ''}
              onClick={() => setTab('rules')}
            >
              How your rules behave
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'winners'}
              className={tab === 'winners' ? 'is-on' : ''}
              onClick={() => setTab('winners')}
            >
              What your winners had in common
            </button>
          </div>

          {tab === 'rules' &&
            (tradeRules ?? []).map((s) => (
              <RuleHealth
                key={`${s.ruleIndex}-${s.statementIndex}`}
                s={s}
                evaluated={stats.barsEvaluated}
                nearMiss={stats.nearMissThreshold}
                intraday={stats.intraday}
              />
            ))}

          {tab === 'winners' && <DiscoveryPanel report={discovery} onApply={onApply} />}
        </>
      )}
    </section>
  )
}

// ── Tab 1: one rule's health ────────────────────────────────────────────

function pickiness(pct: number): string {
  if (pct === 0) return 'never true'
  if (pct < 1) return 'very picky'
  if (pct < 5) return 'picky'
  if (pct < 25) return 'moderately picky'
  return 'true often'
}

function RuleHealth({
  s,
  evaluated,
  nearMiss,
  intraday,
}: {
  s: StatementStatsDto
  evaluated: number
  nearMiss: number
  intraday: boolean
}) {
  const filled = s.outcomes.FILLED ?? 0
  const long = s.outcomes.IGNORED_ALREADY_LONG ?? 0
  const flat = s.outcomes.IGNORED_NOTHING_TO_SELL ?? 0
  const small = s.outcomes.REJECTED_TOO_SMALL ?? 0
  const broke = s.outcomes.REJECTED_NOT_ENOUGH_CASH ?? 0
  const oneIn = s.trueBars > 0 ? Math.round(evaluated / s.trueBars) : 0
  const blocker = s.parts.find((p) => p.text === s.bottleneck)
  const falseBars = evaluated - s.trueBars

  return (
    <article className="tsb-dbg__stmt">
      <div className="tsb-dbg__stmt-head">
        <span className="tsb-dbg__rule">Rule “{s.ruleName}”</span>
        <span className="tsb-dbg__muted">
          {s.thenAction === 'BUY' ? 'your buying rule' : 'your selling rule'}
        </span>
      </div>

      <p className="tsb-big">
        This rule is <b>{pickiness(s.truePct)}</b>
        {s.trueBars > 0 && (
          <>
            {' '}
            — true on about <b>1 in {n(oneIn)}</b> candles
          </>
        )}
        .
      </p>

      <Funnel
        rows={[
          { label: 'Candles checked', value: evaluated },
          { label: 'Rule was true', value: s.trueBars },
          { label: s.thenAction === 'BUY' ? 'Actually bought' : 'Actually sold', value: filled },
        ]}
      />
      {(long > 0 || flat > 0 || small > 0 || broke > 0) && (
        <div className="tsb-chips">
          {long > 0 && (
            <span className="tsb-chip" title="TSB holds one position at a time">
              📦 {n(long)} skipped — already holding
            </span>
          )}
          {flat > 0 && <span className="tsb-chip">💤 {n(flat)} skipped — nothing to sell</span>}
          {small > 0 && <span className="tsb-chip">🪙 {n(small)} too small for the exchange</span>}
          {broke > 0 && (
            <span className="tsb-chip" title="A fixed-size buy that cost more than the cash in the account">
              💸 {n(broke)} refused — more than the cash available
            </span>
          )}
        </div>
      )}

      {blocker && s.parts.length > 1 && blocker.soleBlockerBars > 0 && (
        <div className="tsb-callout">
          <div className="tsb-callout__icon" aria-hidden>🚧</div>
          <div>
            <div className="tsb-callout__title">
              Biggest blocker: “{leaf(blocker.text, kindOf(blocker.text), opOf(blocker.text))}”
            </div>
            <div>
              On <b>{n(blocker.soleBlockerBars)}</b> candles
              {falseBars > 0 && ` (${p0((blocker.soleBlockerBars / falseBars) * 100)} of the misses)`}
              , this was the <b>only</b> thing stopping the rule. If you want more trades, this is
              the part to loosen.
            </div>
            <code className="tsb-leaf__code">{blocker.text}</code>
          </div>
        </div>
      )}

      {s.nearMisses > 0 && (
        <div className="tsb-callout tsb-callout--soft">
          <div className="tsb-callout__icon" aria-hidden>🎯</div>
          <div>
            <b>So close {n(s.nearMisses)} times.</b> On these candles one part was within{' '}
            {Math.round(nearMiss * 100)}% of passing and everything else was fine.
          </div>
        </div>
      )}

      {s.parts.length > 1 && (
        <div className="tsb-parts">
          <div className="tsb-sub">How often each part is true on its own</div>
          {s.parts.map((p, i) => (
            <div key={i} className="tsb-part">
              <div className="tsb-part__label">
                {leaf(p.text, kindOf(p.text), opOf(p.text))}
              </div>
              <div className="tsb-part__bar">
                <div style={{ width: `${Math.max(1, p.truePct)}%` }} />
              </div>
              <div className="tsb-part__pct">{p0(p.truePct)}</div>
            </div>
          ))}
        </div>
      )}

      <When s={s} intraday={intraday} />
    </article>
  )
}

/** Widths on a square-root scale: 159 of 7,950 is still a visible bar. */
function Funnel({ rows }: { rows: { label: string; value: number }[] }) {
  const top = Math.max(rows[0]?.value ?? 1, 1)
  return (
    <div className="tsb-funnel">
      {rows.map((r, i) => (
        <div key={i} className="tsb-funnel__row">
          <div className="tsb-funnel__bar">
            <div
              style={{ width: `${Math.max(3, Math.sqrt(r.value / top) * 100)}%` }}
              className={i === rows.length - 1 ? 'is-last' : undefined}
            />
          </div>
          <div className="tsb-funnel__num">{n(r.value)}</div>
          <div className="tsb-funnel__label">{r.label}</div>
        </div>
      ))}
    </div>
  )
}

function When({ s, intraday }: { s: StatementStatsDto; intraday: boolean }) {
  const rate = (hits: number[], totals: number[]) =>
    hits.map((h, i) => (totals[i] ? h / totals[i] : 0))
  const hr = rate(s.trueByHour, s.barsByHour)
  const wd = rate(s.trueByWeekday, s.barsByWeekday)
  if (s.trueBars === 0) return null
  const best = (xs: number[]) => xs.indexOf(Math.max(...xs))
  const worst = (xs: number[]) => xs.indexOf(Math.min(...xs))
  const pad = (h: number) => `${String(h).padStart(2, '0')}:00`
  return (
    <div className="tsb-when">
      <div className="tsb-sub">
        🕐 When is it true?{' '}
        {intraday && (
          <>
            Most around <b>{pad(best(hr))} UTC</b>, least around {pad(worst(hr))}.{' '}
          </>
        )}
        Busiest day: <b>{WEEKDAYS[best(wd)]}</b>.
      </div>
      <div className="tsb-when__charts">
        {intraday && (
          <Bars values={hr} labels={hr.map((_, h) => (h % 6 === 0 ? String(h) : ''))} tips={hr.map((v, h) => `${pad(h)} UTC: true on ${(v * 100).toFixed(1)}% of candles`)} />
        )}
        <Bars values={wd} labels={WD_SHORT} tips={wd.map((v, i) => `${WEEKDAYS[i]}: true on ${(v * 100).toFixed(1)}% of candles`)} />
      </div>
    </div>
  )
}

function Bars({ values, labels, tips }: { values: number[]; labels: string[]; tips: string[] }) {
  const max = Math.max(...values, 1e-9)
  return (
    <div className="tsb-dbg__hist-bars">
      {values.map((v, i) => (
        <div key={i} className="tsb-dbg__hist-col" title={tips[i]}>
          <div className="tsb-dbg__hist-bar" style={{ height: `${(v / max) * 100}%` }} />
          <span className="tsb-dbg__hist-label">{labels[i]}</span>
        </div>
      ))}
    </div>
  )
}

// The stats endpoint sends a leaf's TSL text only; recover op/kind for wording.
function opOf(text: string): string | null {
  for (const op of ['<=', '>=', '==', '!=', '<', '>']) {
    if (text.includes(` ${op} `)) return op
  }
  return null
}
function kindOf(text: string): string {
  if (text.startsWith('CROSSOVER(')) return 'CROSSOVER'
  if (text.startsWith('CROSSUNDER(')) return 'CROSSUNDER'
  return 'COMPARE'
}
