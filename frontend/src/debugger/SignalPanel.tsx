import { useState } from 'react'
import {
  api,
  type ConfluenceFindingDto,
  type ConfluenceReportDto,
  type RunContext,
  type SignalStatisticsDto,
  type StatementStatsDto,
} from '../api'
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
export default function SignalPanel({ run }: { run: RunContext }) {
  const [stats, setStats] = useState<SignalStatisticsDto | null>(null)
  const [confluence, setConfluence] = useState<ConfluenceReportDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [tab, setTab] = useState<'rules' | 'winners'>('rules')

  const analyse = async () => {
    setBusy(true)
    setError(null)
    try {
      const res = await api.signals(run)
      if (res.ok && res.statistics && res.confluence) {
        setStats(res.statistics)
        setConfluence(res.confluence)
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

      {stats && confluence && (
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

          {tab === 'winners' && <Winners report={confluence} />}
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
      {(long > 0 || flat > 0 || small > 0) && (
        <div className="tsb-chips">
          {long > 0 && (
            <span className="tsb-chip" title="TSB holds one position at a time">
              📦 {n(long)} skipped — already holding
            </span>
          )}
          {flat > 0 && <span className="tsb-chip">💤 {n(flat)} skipped — nothing to sell</span>}
          {small > 0 && <span className="tsb-chip">🪙 {n(small)} too small for the exchange</span>}
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

// ── Tab 2: what the winners had in common ──────────────────────────────

function Winners({ report }: { report: ConfluenceReportDto }) {
  if (report.status === 'TOO_FEW_TRADES') {
    return (
      <div className="tsb-empty">
        <div className="tsb-empty__icon" aria-hidden>🧪</div>
        <div className="tsb-empty__title">Not enough trades to look for patterns</div>
        <p>
          You have <b>{report.trades}</b> completed trades; we need at least <b>30</b>. With fewer,
          any “pattern” is almost certainly luck. Try a longer date range or a looser entry rule.
        </p>
      </div>
    )
  }

  const real = report.findings.filter((f) => f.significant)
  // Biggest differences first; only the top three are shown open.
  const luck = report.findings
    .filter((f) => f.tested && !f.significant)
    .sort(
      (a, b) =>
        Math.abs(b.winRateWith - b.winRateWithout) - Math.abs(a.winRateWith - a.winRateWithout),
    )
  const untested = report.findings.filter((f) => !f.tested)

  return (
    <div>
      <div className={`tsb-verdict tsb-verdict--${real.length ? 'go' : 'quiet'}`}>
        <div className="tsb-verdict__icon" aria-hidden>{real.length ? '🔍' : '🎲'}</div>
        <div>
          <div className="tsb-verdict__title">
            {real.length
              ? `Yes — ${real.length} pattern${real.length > 1 ? 's' : ''} stand${real.length > 1 ? '' : 's'} out`
              : 'No clear pattern — and that’s an honest answer'}
          </div>
          <div className="tsb-verdict__sub">
            We looked at {report.testsRun} things about the market at the moment each of your{' '}
            {report.trades} trades was signalled, and compared how often trades won with and
            without them. {report.wins} of {report.trades} trades won overall (
            {p0(report.winRate)}).
            {!real.length &&
              ' The differences we saw are small enough to happen by pure luck, so none is worth turning into a rule.'}
          </div>
        </div>
      </div>

      {real.map((f) => (
        <Finding key={f.feature.id} f={f} strong />
      ))}

      {luck.length > 0 && (
        <>
          <div className="tsb-sub tsb-sub--gap">
            {real.length ? 'Everything else we checked' : 'What we checked'} — differences here could be luck
          </div>
          {luck.slice(0, 3).map((f) => (
            <Finding key={f.feature.id} f={f} />
          ))}
          {luck.length > 3 && (
            <details className="tsb-others">
              <summary>Show the other {luck.length - 3}</summary>
              {luck.slice(3).map((f) => (
                <Finding key={f.feature.id} f={f} />
              ))}
            </details>
          )}
        </>
      )}

      {untested.length > 0 && (
        <p className="tsb-dbg__muted tsb-small">
          Not enough trades on both sides to check:{' '}
          {untested.map((f) => f.feature.label.toLowerCase()).join(', ')}.
        </p>
      )}

      <details className="tsb-why">
        <summary>Show the statistics</summary>
        <p className="tsb-small tsb-dbg__muted">
          Fisher’s exact test on each 2×2 table (won/lost × true/false), two-sided. Because{' '}
          {report.testsRun} tests were run, each must reach p &lt; {report.threshold.toFixed(4)}{' '}
          (Bonferroni: 0.05 ÷ {report.testsRun}). Only conditions with at least 10 trades on each
          side are tested.
        </p>
        <div className="tsb-dbg__scroll">
          <table className="tsb-dbg__table">
            <thead>
              <tr>
                <th>Condition at the signal candle</th>
                <th className="num">When true</th>
                <th className="num">When false</th>
                <th className="num">p</th>
                <th className="num">Corrected p</th>
              </tr>
            </thead>
            <tbody>
              {report.findings.map((f) => (
                <tr key={f.feature.id} className={f.tested ? undefined : 'is-untested'}>
                  <td>{f.feature.label}</td>
                  <td className="num">
                    {p0(f.winRateWith)} of {f.tradesWith}
                  </td>
                  <td className="num">
                    {p0(f.winRateWithout)} of {f.tradesWithout}
                  </td>
                  <td className="num">{f.pValue == null ? 'not tested' : f.pValue.toFixed(3)}</td>
                  <td className="num">{f.adjustedP == null ? '—' : f.adjustedP.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}

/** Two bars side by side: win rate when the condition was true vs false. */
function Finding({ f, strong = false }: { f: ConfluenceFindingDto; strong?: boolean }) {
  const [copied, setCopied] = useState(false)
  const better = f.winRateWith > f.winRateWithout
  const copy = async () => {
    if (!f.suggestion) return
    try {
      await navigator.clipboard.writeText(f.suggestion)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked — the snippet is visible to copy by hand */
    }
  }
  return (
    <div className={`tsb-find${strong ? ' is-strong' : ''}`}>
      <div className="tsb-find__head">
        <span className="tsb-find__label">{f.feature.label}</span>
        <span className={`tsb-find__tag${strong ? ' is-real' : ''}`}>
          {strong ? (better ? 'Winners had this' : 'Losers had this') : 'Could be luck'}
        </span>
      </div>
      <WinBar label="When true" rate={f.winRateWith} trades={f.tradesWith} />
      <WinBar label="When false" rate={f.winRateWithout} trades={f.tradesWithout} />
      {strong && f.suggestion && (
        <div className="tsb-dbg__snippet">
          <span className="tsb-dbg__muted">Try adding to your entry, then re-test on other dates:</span>
          <code className="tsb-dbg__code">{f.suggestion}</code>
          <button type="button" className="ghost" onClick={copy}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}
      {strong && !f.suggestion && (
        <div className="tsb-small tsb-dbg__muted">
          TSL can’t filter by time of day yet, so there’s no snippet for this one.
        </div>
      )}
    </div>
  )
}

/** Win share in green, loss share in red — profit/loss colours, used for exactly that. */
function WinBar({ label, rate, trades }: { label: string; rate: number; trades: number }) {
  return (
    <div className="tsb-win">
      <div className="tsb-win__label">{label}</div>
      <div className="tsb-win__bar" title={`${Math.round(rate)}% of ${trades} trades won`}>
        <div className="tsb-win__won" style={{ width: `${rate}%` }} />
      </div>
      <div className="tsb-win__num">
        {p0(rate)} won <span className="tsb-dbg__muted">of {trades}</span>
      </div>
    </div>
  )
}
