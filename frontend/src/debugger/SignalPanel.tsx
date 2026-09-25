import { useState } from 'react'
import {
  api,
  type ConfluenceFindingDto,
  type ConfluenceReportDto,
  type RunContext,
  type SignalStatisticsDto,
  type StatementStatsDto,
} from '../api'
import './debugger.css'

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const p1 = (v: number) => `${v.toFixed(1)}%`
const count = (v: number) => v.toLocaleString('en-US')

/**
 * Module 4: how the conditions behaved over the whole run, and whether the
 * winning trades had something in common.
 *
 * On demand rather than with every backtest: it walks every bar of every
 * condition, which is worth doing when asked, not on every run.
 */
export default function SignalPanel({ run }: { run: RunContext }) {
  const [stats, setStats] = useState<SignalStatisticsDto | null>(null)
  const [confluence, setConfluence] = useState<ConfluenceReportDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

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

  return (
    <section className="tsb-dbg tsb-dbg__panel">
      <header className="tsb-dbg__header">
        <div>
          <div className="tsb-dbg__eyebrow">Signal statistics</div>
          <div className="tsb-dbg__title">How often each condition fired, and what the winners shared</div>
        </div>
        <button type="button" onClick={analyse} disabled={busy}>
          {busy ? 'Analysing…' : stats ? 'Re-analyse' : 'Analyse signals'}
        </button>
      </header>

      {error && <p className="error-note">{error}</p>}

      {stats && (
        <>
          <p className="tsb-dbg__muted">
            Over {count(stats.barsEvaluated)} bars after warm-up. A near miss is a
            bar where one part, moved by at most{' '}
            {(stats.nearMissThreshold * 100).toFixed(0)}%, would have made the
            condition true.
          </p>
          {stats.statements.map((s) => (
            <StatementCard
              key={`${s.ruleIndex}-${s.statementIndex}`}
              s={s}
              intraday={stats.intraday}
            />
          ))}
        </>
      )}

      {confluence && <ConfluenceCard report={confluence} />}
    </section>
  )
}

function StatementCard({
  s,
  intraday,
}: {
  s: StatementStatsDto
  intraday: boolean
}) {
  const filled = s.outcomes.FILLED ?? 0
  return (
    <article className="tsb-dbg__stmt">
      <div className="tsb-dbg__stmt-head">
        <span className="tsb-dbg__rule">rule {s.ruleName}</span>
        <code className="tsb-dbg__code">
          IF {s.condition} THEN {s.thenAction}
        </code>
      </div>
      <p className="tsb-dbg__lead">{s.sentence}</p>

      <div className="tsb-dbg__metrics">
        <Metric k="True on" v={p1(s.truePct)} />
        <Metric k="Orders asked for" v={count(s.actionBars)} />
        <Metric k="Filled" v={count(filled)} />
        <Metric k="Near misses" v={count(s.nearMisses)} />
      </div>

      {s.parts.length > 1 && (
        <div className="tsb-dbg__scroll">
        <table className="tsb-dbg__table">
          <thead>
            <tr>
              <th>Part</th>
              <th className="num">True on</th>
              <th className="num">Only thing in the way</th>
            </tr>
          </thead>
          <tbody>
            {s.parts.map((p, i) => (
              <tr key={i} className={p.text === s.bottleneck ? 'is-bottleneck' : undefined}>
                <td>
                  <code className="tsb-dbg__code">{p.text}</code>
                </td>
                <td className="num">{p1(p.truePct)}</td>
                <td className="num">{count(p.soleBlockerBars)} bars</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}

      <div className="tsb-dbg__hists">
        {intraday && (
          <Histogram
            title="True rate by hour (UTC)"
            hits={s.trueByHour}
            totals={s.barsByHour}
            labels={s.trueByHour.map((_, h) => (h % 6 === 0 ? String(h) : ''))}
          />
        )}
        <Histogram
          title="True rate by weekday (UTC)"
          hits={s.trueByWeekday}
          totals={s.barsByWeekday}
          labels={WEEKDAYS}
        />
      </div>
    </article>
  )
}

function Metric({ k, v }: { k: string; v: string }) {
  return (
    <div className="tsb-dbg__metric">
      <div className="tsb-dbg__metric-k">{k}</div>
      <div className="tsb-dbg__metric-v">{v}</div>
    </div>
  )
}

/** Share of bars where the condition was true, per bucket. */
function Histogram({
  title,
  hits,
  totals,
  labels,
}: {
  title: string
  hits: number[]
  totals: number[]
  labels: string[]
}) {
  const rates = hits.map((h, i) => (totals[i] ? h / totals[i] : 0))
  const max = Math.max(...rates, 1e-9)
  return (
    <figure className="tsb-dbg__hist">
      <figcaption>{title}</figcaption>
      <div className="tsb-dbg__hist-bars">
        {rates.map((r, i) => (
          <div
            key={i}
            className="tsb-dbg__hist-col"
            title={`${labels[i] || i}: ${(r * 100).toFixed(1)}% of ${totals[i]} bars`}
          >
            <div className="tsb-dbg__hist-bar" style={{ height: `${(r / max) * 100}%` }} />
            <span className="tsb-dbg__hist-label">{labels[i]}</span>
          </div>
        ))}
      </div>
    </figure>
  )
}

function ConfluenceCard({ report }: { report: ConfluenceReportDto }) {
  const significant = report.findings.filter((f) => f.significant)
  const others = report.findings.filter((f) => !f.significant)
  return (
    <article className="tsb-dbg__stmt">
      <div className="tsb-dbg__stmt-head">
        <span className="tsb-dbg__rule">Confluence</span>
        <span className="tsb-dbg__muted">
          {report.trades} trades · {p1(report.winRate)} won
        </span>
      </div>
      <p className="tsb-dbg__lead">{report.message}</p>

      {significant.map((f) => (
        <FindingRow key={f.feature.id} f={f} />
      ))}

      {others.length > 0 && (
        <details className="tsb-dbg__details">
          <summary>All {report.findings.length} conditions checked</summary>
          <div className="tsb-dbg__scroll">
        <table className="tsb-dbg__table">
            <thead>
              <tr>
                <th>At the signal bar</th>
                <th className="num">When true</th>
                <th className="num">When false</th>
                <th className="num">p</th>
                <th className="num">Corrected p</th>
              </tr>
            </thead>
            <tbody>
              {others.map((f) => (
                <tr key={f.feature.id} className={f.tested ? undefined : 'is-untested'}>
                  <td>{f.feature.label}</td>
                  <td className="num">
                    {p1(f.winRateWith)} of {f.tradesWith}
                  </td>
                  <td className="num">
                    {p1(f.winRateWithout)} of {f.tradesWithout}
                  </td>
                  <td className="num">{f.pValue == null ? 'not tested' : f.pValue.toFixed(3)}</td>
                  <td className="num">{f.adjustedP == null ? '—' : f.adjustedP.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        </details>
      )}
    </article>
  )
}

function FindingRow({ f }: { f: ConfluenceFindingDto }) {
  const [copied, setCopied] = useState(false)
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
    <div className="tsb-dbg__finding">
      <p>{f.sentence}</p>
      {f.suggestion && (
        <div className="tsb-dbg__snippet">
          <span className="tsb-dbg__muted">To test it, add to the entry condition:</span>
          <code className="tsb-dbg__code">{f.suggestion}</code>
          <button type="button" className="ghost" onClick={copy}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}
    </div>
  )
}
