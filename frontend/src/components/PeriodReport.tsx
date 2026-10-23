import { useState, type ReactNode } from 'react'
import type {
  PeriodBreakdownDto,
  PeriodNodeDto,
  PeriodReportDto,
  PeriodStatsDto,
  TradeDto,
} from '../api'
import './report.css'

/* ── Numbers ──────────────────────────────────────────────────────────── */

const MINUS = '−'

const num = (v: number, digits = 2) =>
  v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })

const signed = (v: number, digits = 2) =>
  (v > 0 ? '+' : v < 0 ? MINUS : '') + num(Math.abs(v), digits)

const pct = (v: number | null | undefined, digits = 2) =>
  v == null || !Number.isFinite(v) ? '—' : `${signed(v, digits)}%`

/** Green/red only where they mean profit or loss (see index.css). */
const tone = (v: number | null | undefined) => (v == null ? '' : v > 0 ? 'up' : v < 0 ? 'down' : '')

const drawdown = (v: number) => (v > 0 ? `${MINUS}${num(v)}%` : '0.00%')

/* ── Calendar (UTC, ISO dates from the server) ────────────────────────── */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const MONTHS_LONG = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
]
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const WEEKDAYS_LONG = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

const ymd = (iso: string) => ({ y: +iso.slice(0, 4), m: +iso.slice(5, 7), d: +iso.slice(8, 10) })

/** 1 = Monday … 7 = Sunday, as the server counts them. */
const weekdayOf = (iso: string) => {
  const { y, m, d } = ymd(iso)
  return ((new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7) + 1
}

const daysInMonth = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate()

/** What a period is called in a heading or a breadcrumb. */
function name(n: PeriodNodeDto, short = false): string {
  const f = ymd(n.firstDay)
  const l = ymd(n.lastDay)
  switch (n.level) {
    case 'ALL':
      return 'All time'
    case 'YEAR':
      return n.key
    case 'MONTH':
      return short ? MONTHS_LONG[f.m - 1] : `${MONTHS_LONG[f.m - 1]} ${f.y}`
    case 'WEEK':
      return f.d === l.d ? `${f.d} ${MONTHS[f.m - 1]}` : `${f.d}–${l.d} ${MONTHS[f.m - 1]}`
    case 'DAY':
      return `${WEEKDAYS[weekdayOf(n.firstDay) - 1]} ${f.d} ${MONTHS[f.m - 1]}${short ? '' : ` ${f.y}`}`
  }
}

/** The small print under a period's name: which part of it the data covers. */
function coverage(n: PeriodNodeDto): string | null {
  const f = ymd(n.firstDay)
  const l = ymd(n.lastDay)
  switch (n.level) {
    case 'YEAR':
      return f.m === 1 && f.d === 1 && l.m === 12 && l.d === 31
        ? null
        : `${f.d} ${MONTHS[f.m - 1]} – ${l.d} ${MONTHS[l.m - 1]}`
    case 'MONTH':
      return f.d === 1 && l.d === daysInMonth(l.y, l.m) ? null : `${f.d}–${l.d} only`
    case 'WEEK': {
      const a = WEEKDAYS[weekdayOf(n.firstDay) - 1]
      const b = WEEKDAYS[weekdayOf(n.lastDay) - 1]
      return a === b ? a : `${a}–${b}`
    }
    default:
      return null
  }
}

const CHILDREN: Record<string, string> = {
  ALL: 'Years',
  YEAR: 'Months',
  MONTH: 'Weeks',
  WEEK: 'Days',
}

/* ── The tree ─────────────────────────────────────────────────────────── */

/** The periods from the top down to the one at `path` (child indexes). */
function chainTo(root: PeriodNodeDto, path: number[]): PeriodNodeDto[] {
  const chain = [root]
  for (const i of path) {
    const next = chain[chain.length - 1].children[i]
    if (!next) break
    chain.push(next)
  }
  return chain
}

type Found = { node: PeriodNodeDto; path: number[] }

function below(n: PeriodNodeDto, path: number[], out: Found[] = []): Found[] {
  n.children.forEach((c, i) => {
    const p = [...path, i]
    out.push({ node: c, path: p })
    below(c, p, out)
  })
  return out
}

/* ── The report ───────────────────────────────────────────────────────── */

/**
 * The run read from the top down: the whole run, then a year, a month, a
 * week, a day — each with the same numbers, and the best and worst of what
 * lies below. For the run, a year or a month it also shows when the money
 * was made: by weekday, by hour of the day and by month of the year.
 */
export default function PeriodReport({
  report,
  trades,
  onShow,
}: {
  report: PeriodReportDto
  trades: TradeDto[]
  /** Moves the charts to this stretch of time (ms); off when there is no chart. */
  onShow?: (from: number, to: number) => void
}) {
  // The place in the tree belongs to this report: a new run starts at the top.
  const [nav, setNav] = useState<{ of: PeriodReportDto; path: number[] }>({
    of: report,
    path: [],
  })
  const path = nav.of === report ? nav.path : []
  const go = (p: number[]) => setNav({ of: report, path: p })

  const chain = chainTo(report.all, path)
  const node = chain[chain.length - 1]
  const here = path.slice(0, chain.length - 1)

  return (
    <section className="tsb-report" id="tsb-report" aria-label="Report by period">
      <div className="tsb-report__head">
        <span className="tsb-report__title">Report · by period</span>
        <span className="tsb-report__sub">
          Days in {report.timeZone}. A week runs Monday to Sunday and stops at the end of its
          month. Trades count on the day they close.
        </span>
      </div>

      <div className="tsb-report__bar">
        <nav className="tsb-report__crumbs" aria-label="Period">
          {chain.map((n, depth) => (
            <span key={depth} className="tsb-report__crumb">
              {depth > 0 && <span className="tsb-report__sep" aria-hidden>›</span>}
              {depth === chain.length - 1 ? (
                <b aria-current="page">{name(n, true)}</b>
              ) : (
                <button type="button" className="tsb-report__link" onClick={() => go(here.slice(0, depth))}>
                  {name(n, true)}
                </button>
              )}
            </span>
          ))}
        </nav>
        {onShow && (
          <button type="button" className="ghost" onClick={() => onShow(node.start, node.end)}>
            Show on chart
          </button>
        )}
      </div>

      <Summary node={node} />

      {node.level === 'ALL' && node.children.length > 0 && (
        <MonthGrid all={node} onOpen={go} />
      )}
      {node.level === 'MONTH' && <Calendar month={node} path={here} onOpen={go} />}
      {node.children.length > 0 && (
        <Children node={node} path={here} onOpen={go} />
      )}
      {node.level !== 'DAY' && <BestWorst node={node} path={here} onOpen={go} />}
      {node.breakdown && <When b={node.breakdown} stats={node.stats} />}
      {node.level === 'DAY' && <DayTrades day={node} trades={trades} />}
    </section>
  )
}

/* ── One period's numbers ─────────────────────────────────────────────── */

function Summary({ node }: { node: PeriodNodeDto }) {
  const s = node.stats
  const vsHold = s.returnPct != null && s.buyHoldPct != null ? s.returnPct - s.buyHoldPct : null
  const cells: { k: string; v: ReactNode; cls?: string; note?: ReactNode; help: string }[] = [
    {
      k: 'Return',
      v: pct(s.returnPct),
      cls: tone(s.returnPct),
      note: <span className={tone(s.pnl)}>{signed(s.pnl)}</span>,
      help: 'From the equity at the close before this period to its last close, open positions included.',
    },
    {
      k: 'Buy & hold',
      v: pct(s.buyHoldPct),
      cls: tone(s.buyHoldPct),
      note:
        vsHold == null
          ? undefined
          : Math.abs(vsHold) < 0.005
            ? 'same as you'
            : vsHold > 0
              ? `you beat it by ${num(vsHold)} pts`
              : `you trail by ${num(-vsHold)} pts`,
      help: 'What simply holding the coin did over the same candles.',
    },
    {
      k: 'Max drawdown',
      v: drawdown(s.maxDrawdownPct),
      cls: s.maxDrawdownPct > 0 ? 'down' : '',
      help: 'The worst fall from a high inside this period, counting from its start.',
    },
    {
      k: 'Trades',
      v: s.tradesClosed,
      note: `${s.tradesOpened} opened`,
      help: 'Trades that closed in this period (and how many were opened in it).',
    },
    {
      k: 'Win rate',
      v: s.winRatePct == null ? '—' : `${num(s.winRatePct, 1)}%`,
      note: s.tradesClosed > 0 ? `${s.wins} won · ${s.tradesClosed - s.wins} lost` : 'no trade closed',
      help: 'Share of the trades that closed here that made money after fees.',
    },
    {
      k: 'Closed P&L',
      v: signed(s.realizedPnl),
      cls: tone(s.realizedPnl),
      help: 'What the trades that closed here made, fees included.',
    },
    {
      k: 'Fees',
      v: num(s.fees),
      help: 'Fees paid by the buys and sells that filled in this period.',
    },
    {
      k: 'In market',
      v: `${num(s.timeInMarketPct, 0)}%`,
      note: `${s.candles.toLocaleString('en-US')} candles`,
      help: 'Share of candles with a position open.',
    },
    node.level === 'DAY'
      ? {
          k: 'Equity',
          v: num(s.endEquity),
          note: `from ${num(s.startEquity)}`,
          help: 'The account at the last close of the day, and at the close before it.',
        }
      : {
          k: 'Green days',
          v: `${s.greenDays} / ${s.days}`,
          note: `${s.redDays} red`,
          help: 'Days that ended up, and down; days with nothing open are neither.',
        },
  ]
  return (
    <div className="tsb-report__stats">
      {cells.map((c) => (
        <div key={c.k} className="tsb-report__stat" title={c.help}>
          <div className="k">{c.k}</div>
          <div className={`v ${c.cls ?? ''}`}>{c.v}</div>
          {c.note != null && <div className="tsb-report__note">{c.note}</div>}
        </div>
      ))}
    </div>
  )
}

/* ── The periods below, as a table ────────────────────────────────────── */

function Children({
  node,
  path,
  onOpen,
}: {
  node: PeriodNodeDto
  path: number[]
  onOpen: (p: number[]) => void
}) {
  const scale = Math.max(1e-9, ...node.children.map((c) => Math.abs(c.stats.returnPct ?? 0)))
  return (
    <div className="tsb-report__block">
      <h4 className="tsb-report__h">{CHILDREN[node.level]}</h4>
      <div className="tsb-tablewrap">
        <table className="tsb-report__table">
          <thead>
            <tr>
              <th>Period</th>
              <th className="num">Return</th>
              <th aria-hidden />
              <th className="num">P&amp;L</th>
              <th className="num">Buy &amp; hold</th>
              <th className="num">Max DD</th>
              <th className="num">Trades</th>
              <th className="num">Win rate</th>
              <th className="num">Fees</th>
              <th className="num">In market</th>
            </tr>
          </thead>
          <tbody>
            {node.children.map((c, i) => {
              const s = c.stats
              const open = () => onOpen([...path, i])
              const note = coverage(c)
              return (
                <tr key={c.key} className="tsb-report__row" onClick={open}>
                  <td>
                    <button
                      type="button"
                      className="tsb-report__link"
                      onClick={(e) => {
                        e.stopPropagation()
                        open()
                      }}
                    >
                      {name(c, true)}
                    </button>
                    {note && <span className="tsb-report__cover">{note}</span>}
                  </td>
                  <td className={`num ${tone(s.returnPct)}`}>{pct(s.returnPct)}</td>
                  <td className="tsb-report__barcell" aria-hidden>
                    <Bar value={s.returnPct ?? 0} scale={scale} />
                  </td>
                  <td className={`num ${tone(s.pnl)}`}>{signed(s.pnl)}</td>
                  <td className="num">{pct(s.buyHoldPct)}</td>
                  <td className="num">{drawdown(s.maxDrawdownPct)}</td>
                  <td className="num">{s.tradesClosed}</td>
                  <td className="num">{s.winRatePct == null ? '—' : `${num(s.winRatePct, 0)}%`}</td>
                  <td className="num">{num(s.fees)}</td>
                  <td className="num">{num(s.timeInMarketPct, 0)}%</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** A bar out from a centre line: right for a gain, left for a loss. */
function Bar({ value, scale }: { value: number; scale: number }) {
  const w = Math.min(50, (Math.abs(value) / scale) * 50)
  return (
    <span className="tsb-report__bar-track">
      <span
        className={`tsb-report__bar-fill ${value >= 0 ? 'is-up' : 'is-down'}`}
        style={value >= 0 ? { left: '50%', width: `${w}%` } : { right: '50%', width: `${w}%` }}
      />
    </span>
  )
}

/* ── Every month of every year (the whole run) ────────────────────────── */

/** Background for a return: green or red, stronger for bigger moves. */
function tint(v: number | null | undefined, scale: number) {
  if (v == null || v === 0) return undefined
  const a = 0.12 + 0.5 * Math.min(1, Math.abs(v) / scale)
  return { background: v > 0 ? `rgba(76, 195, 138, ${a})` : `rgba(229, 84, 75, ${a})` }
}

function MonthGrid({ all, onOpen }: { all: PeriodNodeDto; onOpen: (p: number[]) => void }) {
  const months = all.children.flatMap((y) => y.children)
  const scale = Math.max(1e-9, ...months.map((m) => Math.abs(m.stats.returnPct ?? 0)))
  return (
    <div className="tsb-report__block">
      <h4 className="tsb-report__h">Every month</h4>
      <div className="tsb-tablewrap">
        <table className="tsb-report__grid">
          <thead>
            <tr>
              <th />
              {MONTHS.map((m) => (
                <th key={m}>{m}</th>
              ))}
              <th>Year</th>
            </tr>
          </thead>
          <tbody>
            {all.children.map((y, yi) => (
              <tr key={y.key}>
                <th scope="row">{y.key}</th>
                {MONTHS.map((_, mi) => {
                  const at = y.children.findIndex((m) => ymd(m.firstDay).m === mi + 1)
                  const m = at >= 0 ? y.children[at] : null
                  return (
                    <td key={mi}>
                      {m && (
                        <button
                          type="button"
                          className="tsb-report__cell"
                          style={tint(m.stats.returnPct, scale)}
                          title={`${name(m)}: ${pct(m.stats.returnPct)}, ${m.stats.tradesClosed} trades`}
                          onClick={() => onOpen([yi, at])}
                        >
                          {m.stats.returnPct ? pct(m.stats.returnPct, 1) : '·'}
                        </button>
                      )}
                    </td>
                  )
                })}
                <td>
                  <button
                    type="button"
                    className="tsb-report__cell is-total"
                    onClick={() => onOpen([yi])}
                    title={`${y.key}: ${pct(y.stats.returnPct)}`}
                  >
                    {pct(y.stats.returnPct, 1)}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/* ── A month as a calendar ────────────────────────────────────────────── */

function Calendar({
  month,
  path,
  onOpen,
}: {
  month: PeriodNodeDto
  path: number[]
  onOpen: (p: number[]) => void
}) {
  const days = month.children.flatMap((w) => w.children)
  const scale = Math.max(1e-9, ...days.map((d) => Math.abs(d.stats.returnPct ?? 0)))
  return (
    <div className="tsb-report__block">
      <h4 className="tsb-report__h">Calendar</h4>
      <div className="tsb-report__cal" aria-label={`${name(month)}, day by day`}>
        {WEEKDAYS.map((d) => (
          <div key={d} className="tsb-report__calhead" aria-hidden>
            {d}
          </div>
        ))}
        {month.children.map((w, wi) => {
          const cells: ReactNode[] = []
          for (let wd = 1; wd <= 7; wd++) {
            const di = w.children.findIndex((d) => weekdayOf(d.firstDay) === wd)
            const d = di >= 0 ? w.children[di] : null
            cells.push(
              d ? (
                <button
                  key={wd}
                  type="button"
                  className="tsb-report__day"
                  style={tint(d.stats.returnPct, scale)}
                  title={`${name(d)}: ${pct(d.stats.returnPct)}, ${d.stats.tradesClosed} trades closed`}
                  onClick={() => onOpen([...path, wi, di])}
                >
                  <span className="tsb-report__daynum">{ymd(d.firstDay).d}</span>
                  <span className="tsb-report__dayret">
                    {d.stats.returnPct ? pct(d.stats.returnPct, 1) : '·'}
                  </span>
                  {d.stats.tradesClosed > 0 && (
                    <span className="tsb-report__daytr">{d.stats.tradesClosed}×</span>
                  )}
                </button>
              ) : (
                <div key={wd} className="tsb-report__day is-empty" aria-hidden />
              ),
            )
          }
          return cells
        })}
      </div>
      <p className="tsb-report__hint">
        Each square is one day: its return, and how many trades closed (×). Click one to open it.
      </p>
    </div>
  )
}

/* ── The best and the worst below ─────────────────────────────────────── */

const LEVEL_TITLE: Record<string, string> = { YEAR: 'Year', MONTH: 'Month', WEEK: 'Week', DAY: 'Day' }

/** The longest run of days in a row that all ended up (or all down). */
function streak(days: PeriodNodeDto[], up: boolean) {
  let best = { length: 0, from: '', to: '' }
  let run = 0
  let from = ''
  for (const d of days) {
    const r = d.stats.returnPct ?? 0
    if (up ? r > 0 : r < 0) {
      if (run === 0) from = d.firstDay
      run++
      if (run > best.length) best = { length: run, from, to: d.firstDay }
    } else {
      run = 0
    }
  }
  return best
}

/** "3–10 Dec 2024", "28 Nov – 2 Dec 2024", "30 Dec 2024 – 2 Jan 2025". */
function dayRange(from: string, to: string) {
  const a = ymd(from)
  const b = ymd(to)
  if (from === to) return `${a.d} ${MONTHS[a.m - 1]} ${a.y}`
  if (a.y !== b.y) return `${a.d} ${MONTHS[a.m - 1]} ${a.y} – ${b.d} ${MONTHS[b.m - 1]} ${b.y}`
  if (a.m !== b.m) return `${a.d} ${MONTHS[a.m - 1]} – ${b.d} ${MONTHS[b.m - 1]} ${b.y}`
  return `${a.d}–${b.d} ${MONTHS[b.m - 1]} ${b.y}`
}

/** A period's full name, with its year wherever the name lacks one. */
const fullName = (n: PeriodNodeDto) => (n.level === 'WEEK' ? dayRange(n.firstDay, n.lastDay) : name(n))

function BestWorst({
  node,
  path,
  onOpen,
}: {
  node: PeriodNodeDto
  path: number[]
  onOpen: (p: number[]) => void
}) {
  const found = below(node, path)
  const levels = (['YEAR', 'MONTH', 'WEEK', 'DAY'] as const)
    .map((level) => ({
      level,
      ranked: found
        .filter((f) => f.node.level === level && f.node.stats.returnPct != null)
        .sort((a, b) => (b.node.stats.returnPct ?? 0) - (a.node.stats.returnPct ?? 0)),
    }))
    .filter((x) => x.ranked.length >= 2)
  const days = found.filter((f) => f.node.level === 'DAY').map((f) => f.node)
  if (levels.length === 0 && days.length < 2) return null
  const green = streak(days, true)
  const red = streak(days, false)
  return (
    <div className="tsb-report__block">
      <h4 className="tsb-report__h">Best and worst</h4>
      <div className="tsb-report__picks">
        {levels.map(({ level, ranked }) => (
          <div key={level} className="tsb-report__pick">
            <div className="tsb-report__pick-k">{LEVEL_TITLE[level]}</div>
            {(
              [
                ['Best', ranked[0]],
                ['Worst', ranked[ranked.length - 1]],
              ] as const
            ).map(([label, f]) => (
              <button
                key={label}
                type="button"
                className="tsb-report__pick-row"
                onClick={() => onOpen(f.path)}
                title={`Open ${fullName(f.node)}`}
              >
                <span className="tsb-report__pick-l">{label}</span>
                <span className="tsb-report__pick-n">{fullName(f.node)}</span>
                <span className={`tsb-report__pick-v ${tone(f.node.stats.returnPct)}`}>
                  {pct(f.node.stats.returnPct)}
                </span>
              </button>
            ))}
          </div>
        ))}
        {days.length > 1 && (
          <div className="tsb-report__pick">
            <div className="tsb-report__pick-k">Days in a row</div>
            {(
              [
                ['Green', green],
                ['Red', red],
              ] as const
            ).map(([label, st]) => (
              <div key={label} className="tsb-report__pick-row is-static">
                <span className="tsb-report__pick-l">{label}</span>
                <span className="tsb-report__pick-n">{st.length === 0 ? '—' : dayRange(st.from, st.to)}</span>
                <span className="tsb-report__pick-v">{st.length}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

/* ── When the money was made ──────────────────────────────────────────── */

type Column = {
  label: string
  /** Mean return, percent; null when there was nothing to measure. */
  value: number | null
  title: string
  lines: string[]
}

const won = (entered: number, wins: number) =>
  entered === 0 ? 'no trade entered' : `${entered} entered, ${num((wins * 100) / entered, 0)}% won`

function When({ b, stats }: { b: PeriodBreakdownDto; stats: PeriodStatsDto }) {
  const tabs: { id: string; label: string; columns: Column[]; unit: string; digits: number }[] = [
    {
      id: 'weekday',
      label: 'By weekday',
      unit: 'a day',
      digits: 2,
      columns: b.weekdays.map((w) => ({
        label: WEEKDAYS[w.weekday - 1],
        value: w.avgReturnPct,
        title: WEEKDAYS_LONG[w.weekday - 1],
        lines: [
          `${w.days} days, ${w.greenDays} green`,
          `P&L ${signed(w.pnl)}`,
          won(w.tradesEntered, w.entryWins),
        ],
      })),
    },
  ]
  if (b.hours) {
    tabs.push({
      id: 'hour',
      label: 'By hour (UTC)',
      unit: 'an hour',
      digits: 3,
      columns: b.hours.map((h) => ({
        label: String(h.hour).padStart(2, '0'),
        value: h.slots > 0 ? h.avgReturnPct : null,
        title: `${String(h.hour).padStart(2, '0')}:00–${String(h.hour).padStart(2, '0')}:59 UTC`,
        lines: [
          `${h.slots} hours, ${h.greenSlots} green`,
          `P&L ${signed(h.pnl)}`,
          won(h.tradesEntered, h.entryWins),
        ],
      })),
    })
  }
  if (b.monthsOfYear) {
    tabs.push({
      id: 'month',
      label: 'By month of the year',
      unit: 'a month',
      digits: 2,
      columns: b.monthsOfYear.map((m) => ({
        label: MONTHS[m.month - 1],
        value: m.months > 0 ? m.avgReturnPct : null,
        title: MONTHS_LONG[m.month - 1],
        lines: [`${m.months} of them, ${m.greenMonths} green`, `P&L ${signed(m.pnl)}`],
      })),
    })
  }
  const [tab, setTab] = useState('weekday')
  const [table, setTable] = useState(false)
  const t = tabs.find((x) => x.id === tab) ?? tabs[0]
  const quiet = stats.timeInMarketPct === 0 && stats.tradesClosed === 0

  return (
    <div className="tsb-report__block">
      <div className="tsb-report__whenhead">
        <h4 className="tsb-report__h">When it makes money</h4>
        <div className="tsb-report__tabs" role="tablist">
          {tabs.map((x) => (
            <button
              key={x.id}
              type="button"
              role="tab"
              aria-selected={x.id === t.id}
              className={x.id === t.id ? 'is-on' : ''}
              onClick={() => setTab(x.id)}
            >
              {x.label}
            </button>
          ))}
        </div>
      </div>
      {quiet ? (
        <p className="tsb-report__hint">Nothing was open in this period, so every part of it is flat.</p>
      ) : (
        <>
          <Columns columns={t.columns} digits={t.digits} unit={t.unit} />
          <Verdict columns={t.columns} digits={t.digits} unit={t.unit} />
          <button type="button" className="ghost tsb-report__toggle" onClick={() => setTable(!table)}>
            {table ? 'Hide the numbers' : 'Show the numbers'}
          </button>
          {table && <Numbers columns={t.columns} digits={t.digits} />}
        </>
      )}
    </div>
  )
}

/** One column per weekday / hour / month: up from the zero line for a mean
 *  gain, down for a mean loss. Hover or focus a column for its numbers. */
function Columns({ columns, digits, unit }: { columns: Column[]; digits: number; unit: string }) {
  const [hover, setHover] = useState<number | null>(null)
  const values = columns.map((c) => c.value ?? 0)
  const top = Math.max(0, ...values)
  const bottom = Math.max(0, ...values.map((v) => -v))
  const span = top + bottom || 1
  const zero = (top / span) * 100 // percent from the top
  const ranked = columns
    .map((c, i) => ({ v: c.value, i }))
    .filter((x) => x.v != null && x.v !== 0)
    .sort((a, b) => (b.v ?? 0) - (a.v ?? 0))
  const best = ranked.length > 0 && (ranked[0].v ?? 0) > 0 ? ranked[0].i : -1
  const worst = ranked.length > 0 && (ranked[ranked.length - 1].v ?? 0) < 0 ? ranked[ranked.length - 1].i : -1
  const dense = columns.length > 12
  const h = hover == null ? null : columns[hover]

  return (
    <div className="tsb-report__cols" style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(0, 1fr))` }}>
      <div className="tsb-report__plot" aria-hidden>
        <div className="tsb-report__zero" style={{ top: `${zero}%` }} />
      </div>
      {columns.map((c, i) => {
        const v = c.value ?? 0
        const size = (Math.abs(v) / span) * 100
        return (
          <div
            key={c.label}
            className={`tsb-report__col ${hover === i ? 'is-hover' : ''}`}
            role="img"
            tabIndex={0}
            aria-label={`${c.title}: ${pct(c.value, digits)} ${unit} on average. ${c.lines.join('. ')}`}
            onPointerEnter={() => setHover(i)}
            onPointerLeave={() => setHover(null)}
            onFocus={() => setHover(i)}
            onBlur={() => setHover(null)}
          >
            <span className="tsb-report__plot">
              {c.value != null && v !== 0 && (
                <span
                  className={`tsb-report__colbar ${v > 0 ? 'is-up' : 'is-down'}`}
                  style={v > 0 ? { bottom: `${100 - zero}%`, height: `${size}%` } : { top: `${zero}%`, height: `${size}%` }}
                />
              )}
              {(i === best || i === worst) && (
                <span
                  className="tsb-report__collabel"
                  style={i === best ? { bottom: `${100 - zero + size}%` } : { top: `${zero + size}%` }}
                >
                  {pct(c.value, digits)}
                </span>
              )}
            </span>
            {/* Every third hour on a wide screen; on a phone every sixth
                hour and every other month, so the labels never run together. */}
            <span
              className={`tsb-report__colx ${
                (columns.length >= 24 ? i % 6 !== 0 : columns.length >= 12 && i % 2 !== 0) ? 'is-skip-sm' : ''
              }`}
            >
              {dense && i % 3 !== 0 ? '' : c.label}
            </span>
          </div>
        )
      })}
      {h && hover != null && (
        <div
          className="tsb-report__tip"
          // Beside the column, never over it: right of it in the left half,
          // left of it in the right half.
          style={
            hover < columns.length / 2
              ? { left: `calc(${((hover + 1) / columns.length) * 100}% + 6px)` }
              : { left: `calc(${(hover / columns.length) * 100}% - 6px)`, transform: 'translateX(-100%)' }
          }
          role="tooltip"
        >
          <b>
            {pct(h.value, digits)} <span>{unit}</span>
          </b>
          <span className="tsb-report__tipname">{h.title}</span>
          {h.lines.map((l) => (
            <span key={l}>{l}</span>
          ))}
        </div>
      )}
    </div>
  )
}

/** The best and worst column, in words. */
function Verdict({ columns, digits, unit }: { columns: Column[]; digits: number; unit: string }) {
  const known = columns.filter((c) => c.value != null)
  if (known.length < 2) return null
  const sorted = [...known].sort((a, b) => (b.value ?? 0) - (a.value ?? 0))
  const best = sorted[0]
  const worst = sorted[sorted.length - 1]
  return (
    <p className="tsb-report__verdict">
      Best: <b>{best.title}</b>, {pct(best.value, digits)} {unit} on average. Worst:{' '}
      <b>{worst.title}</b>, {pct(worst.value, digits)}.
    </p>
  )
}

function Numbers({ columns, digits }: { columns: Column[]; digits: number }) {
  return (
    <div className="tsb-tablewrap">
      <table className="tsb-report__table">
        <thead>
          <tr>
            <th>When</th>
            <th className="num">Mean return</th>
            <th>Details</th>
          </tr>
        </thead>
        <tbody>
          {columns.map((c) => (
            <tr key={c.label}>
              <td>{c.title}</td>
              <td className={`num ${tone(c.value)}`}>{pct(c.value, digits)}</td>
              <td className="tsb-report__muted">{c.lines.join(' · ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/* ── A day's trades ───────────────────────────────────────────────────── */

function DayTrades({ day, trades }: { day: PeriodNodeDto; trades: TradeDto[] }) {
  const from = Date.parse(`${day.firstDay}T00:00:00Z`)
  const to = from + 86_400_000
  const rows = trades
    .map((t) => ({ t, entry: Date.parse(t.entryTime), exit: Date.parse(t.exitTime) }))
    .filter((x) => x.entry < to && x.exit >= from)
  return (
    <div className="tsb-report__block">
      <h4 className="tsb-report__h">Trades this day</h4>
      {rows.length === 0 ? (
        <p className="tsb-report__hint">No trade was open on this day.</p>
      ) : (
        <div className="tsb-tablewrap">
          <table className="tsb-report__table">
            <thead>
              <tr>
                <th>Today</th>
                <th>Entry</th>
                <th>Exit</th>
                <th className="num">In</th>
                <th className="num">Out</th>
                <th className="num">P&amp;L</th>
                <th className="num">P&amp;L %</th>
                <th>Exit reason</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ t, entry, exit }, i) => {
                const opened = entry >= from
                const closed = exit < to
                return (
                  <tr key={i}>
                    <td>{opened && closed ? 'Opened and closed' : opened ? 'Opened' : closed ? 'Closed' : 'Held'}</td>
                    <td>{t.entryTime.slice(0, 16).replace('T', ' ')}</td>
                    <td>{t.exitTime.slice(0, 16).replace('T', ' ')}</td>
                    <td className="num">{num(t.entryPrice)}</td>
                    <td className="num">{num(t.exitPrice)}</td>
                    <td className={`num ${tone(t.pnl)}`}>{signed(t.pnl)}</td>
                    <td className={`num ${tone(t.pnl)}`}>{pct(t.pnlPercent)}</td>
                    <td>{t.exitReason}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
