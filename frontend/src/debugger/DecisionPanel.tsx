import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api,
  type BarExplanationDto,
  type BookDto,
  type FillDto,
  type RunContext,
  type StatementExplanationDto,
} from '../api'
import { CASH_COLORS, cashTone, DEFAULT_LEVELS, type CashLevels } from '../charts/CashUsageChart'
import ConditionTree from './ConditionTree'
import { crossStory } from './Gauge'
import { num, pct, utc } from './format'
import { action as sayAction, leaf } from './humanize'
import './debugger.css'

/**
 * What happened at one candle — opened by clicking it on the chart.
 *
 * Laid out after TradAO (Tsang et al., "TradAO: A Visual Analytics System
 * for Trading Algorithm Optimization", IEEE VIS 2020), the paper this project
 * builds on, at the scale of one candle:
 *
 *  1. the verdict — what the strategy did here, in one line;
 *  2. the account at this close — TradAO's cash-usage view at a point: net
 *     asset value, the cash still free (coloured by the same warning and
 *     danger levels as the chart), the coins held, the drop from the peak;
 *  3. the orders on this candle — TradAO's trading history at a point: each
 *     buy and sell with its price and size, and the stop or target waiting;
 *  4. why — every rule as the engine evaluated it;
 *  5. the market — this candle's prices, and the strategy against simply
 *     holding the coin over the same stretch.
 *
 * Every true/false comes from the backend interpreter, every fill from the
 * engine's own record of the run, every number from the run's own curves —
 * this panel only puts them into words and pictures.
 */
export default function DecisionPanel({
  run,
  timeMillis,
  times,
  onNavigate,
  onClose,
  timeframe,
  levels = DEFAULT_LEVELS,
}: {
  run: RunContext
  timeMillis: number
  /** Every candle's open time (ms), for stepping ◀ ▶. */
  times: number[]
  onNavigate: (timeMillis: number) => void
  onClose: () => void
  /** The strategy's timeframe: the candles explained here are always its
   *  own, whatever the chart above is showing. */
  timeframe?: string
  /** Cash warning and danger levels, shared with the cash-usage chart. */
  levels?: CashLevels
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
    const abort = new AbortController()
    setLoading(true)
    setError(null)
    // Each explanation re-runs the whole backtest on the server. Holding an
    // arrow key down used to send one per candle passed; now only the candle
    // the stepping stops on is asked for, and a request already on its way
    // for a candle left behind is cancelled.
    const wait = setTimeout(() => {
      api
        .explainBar(run, timeMillis, abort.signal)
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
          if (cancelled || (e instanceof DOMException && e.name === 'AbortError')) return
          setData(null)
          setError(e instanceof Error ? e.message : String(e))
        })
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }, 180)
    return () => {
      cancelled = true
      clearTimeout(wait)
      abort.abort()
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

  const base = baseOf(run.source)
  const verdict = data ? verdictFor(data, base) : null
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
            <div className="tsb-dbg__eyebrow">
              This candle{timeframe ? ` · ${timeframe}` : ''}
            </div>
            <div className="tsb-dbg__title">
              {utc(data && !loading ? data.openTimeMillis : timeMillis)}
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

          {data.book && <Account book={data.book} levels={levels} base={base} />}

          <Orders fills={data.fills ?? []} book={data.book} base={base} warmup={data.warmup} />

          <h3 className="tsb-sec">Why — the rules at this close</h3>
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

          <Market data={data} base={base} />

          <p className="tsb-dbg__hint">
            Tip: use ◀ ▶ or your arrow keys to step candle by candle.
          </p>
        </div>
      )}
    </section>
  )
}

// ── The account (TradAO: cash usage at this candle) ─────────────────────

const money = (v: number | null | undefined, digits = 2) =>
  v == null || !Number.isFinite(v)
    ? '—'
    : v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })
const signed = (v: number | null | undefined, digits = 2) =>
  v == null || !Number.isFinite(v) ? '—' : `${v >= 0 ? '+' : '−'}${money(Math.abs(v), digits)}`
const signedPct = (v: number | null | undefined) =>
  v == null || !Number.isFinite(v) ? '—' : `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)}%`
const pnl = (v: number | null | undefined) => (v == null ? '' : v > 0 ? 'up' : v < 0 ? 'down' : '')

/** "BTC" from the strategy's own `symbol = BTCUSDT` line. */
function baseOf(source: string): string {
  const s = /^\s*symbol\s*=\s*(\w+)/m.exec(source)?.[1] ?? ''
  return s.endsWith('USDT') ? s.slice(0, -4) : s || 'coin'
}

function Account({ book: b, levels, base }: { book: BookDto; levels: CashLevels; base: string }) {
  const tone = cashTone(b.cash, b.initialCapital, levels)
  const cashPct = b.initialCapital > 0 ? (b.cash / b.initialCapital) * 100 : 0
  const toneColor =
    tone === 'danger' ? CASH_COLORS.danger : tone === 'warning' ? CASH_COLORS.warning : CASH_COLORS.cash
  const vsHold = b.buyHoldPct == null ? null : b.returnPct - b.buyHoldPct
  // Where on the "in the coin" bar the cash would cross a level: the levels
  // are shares of the STARTING capital, the bar is a share of today's value.
  const markAt = (levelPct: number) =>
    b.equity > 0
      ? Math.max(0, Math.min(100, (1 - (b.initialCapital * levelPct) / 100 / b.equity) * 100))
      : 100
  return (
    <>
      <h3 className="tsb-sec">The account at this close</h3>
      <div className="tsb-book">
        <div className="tsb-book__tile" title="Net asset value: the cash plus the coins at this close">
          <div className="tsb-book__k" style={{ color: CASH_COLORS.nav }}>Net asset value</div>
          <div className="tsb-book__v">{money(b.equity)}</div>
          <div className={`tsb-book__s ${pnl(b.returnPct)}`}>
            {signedPct(b.returnPct)} since the start
          </div>
          {vsHold != null && (
            <div className="tsb-book__s">
              {vsHold >= 0 ? 'ahead of' : 'behind'} buy &amp; hold by {Math.abs(vsHold).toFixed(2)} pts
            </div>
          )}
        </div>

        <div className="tsb-book__tile" title="Cash not in the coin, against the warning and danger levels">
          <div className="tsb-book__k" style={{ color: toneColor }}>Available cash</div>
          <div className="tsb-book__v">{money(b.cash)}</div>
          <div className="tsb-usage" aria-label={`${b.cashUsagePct.toFixed(0)}% of the account in the coin`}>
            <div className="tsb-usage__fill" style={{ width: `${Math.min(100, Math.max(0, b.cashUsagePct))}%` }} />
            <div className="tsb-usage__mark" style={{ left: `${markAt(levels.warningPct)}%`, borderColor: CASH_COLORS.warning }} />
            <div className="tsb-usage__mark" style={{ left: `${markAt(levels.dangerPct)}%`, borderColor: CASH_COLORS.danger }} />
          </div>
          <div className="tsb-book__s">
            {b.cashUsagePct.toFixed(0)}% in the coin ·{' '}
            <span style={{ color: toneColor }}>
              {tone === 'danger'
                ? `under the ${levels.dangerPct}% danger level`
                : tone === 'warning'
                  ? `under the ${levels.warningPct}% warning level`
                  : `${cashPct.toFixed(0)}% of the start in cash`}
            </span>
          </div>
        </div>

        <div className="tsb-book__tile" title="The coins held at this close (the inventory)">
          <div className="tsb-book__k">Position</div>
          {b.qty > 0 ? (
            <>
              <div className="tsb-book__v">
                {num(b.qty)} <small>{base}</small>
              </div>
              <div className="tsb-book__s">
                bought at {money(b.entryPrice)} · worth {money(b.positionValue)}
              </div>
              <div className={`tsb-book__s ${pnl(b.unrealizedPnl)}`}>
                {signed(b.unrealizedPnl)} ({signedPct(b.unrealizedPct)}) not yet sold
              </div>
            </>
          ) : (
            <>
              <div className="tsb-book__v tsb-book__v--quiet">Nothing held</div>
              <div className="tsb-book__s">all of the account is cash</div>
            </>
          )}
        </div>

        <div className="tsb-book__tile" title="How far the net asset value is below its best so far">
          <div className="tsb-book__k">Below the peak</div>
          <div className={`tsb-book__v ${b.drawdownPct > 0 ? 'down' : ''}`}>
            {b.drawdownPct > 0 ? `−${b.drawdownPct.toFixed(2)}%` : 'At the peak'}
          </div>
          <div className="tsb-book__s">best so far {money(b.peakEquity)}</div>
          <div className={`tsb-book__s ${pnl(b.realizedPnl)}`}>
            {b.closedTrades} trade{b.closedTrades === 1 ? '' : 's'} closed · {signed(b.realizedPnl)} banked
          </div>
        </div>
      </div>
    </>
  )
}

// ── Orders on this candle (TradAO: trading history at this candle) ──────

const REASON: Record<FillDto['reason'], string> = {
  SIGNAL: 'rule order',
  STOPLOSS: 'stop-loss',
  TAKEPROFIT: 'take-profit',
  TRAILING: 'trailing stop',
  END_OF_DATA: 'end of the data',
}

function whenText(f: FillDto): string {
  if (f.when === 'CLOSE') return 'at the close (the data ends here)'
  if (f.when === 'INSIDE') return 'inside the candle'
  return f.reason === 'SIGNAL'
    ? 'at the open, from the last close’s signal'
    : 'at the open (the price opened past the level)'
}

function Orders({
  fills,
  book,
  base,
  warmup,
}: {
  fills: FillDto[]
  book?: BookDto
  base: string
  warmup: boolean
}) {
  const levels: string[] = []
  if (book?.stopPrice != null) levels.push(`stop-loss at ${money(book.stopPrice)}`)
  if (book?.trailingPrice != null) levels.push(`trailing stop at ${money(book.trailingPrice)}`)
  if (book?.takeProfitPrice != null) levels.push(`take-profit at ${money(book.takeProfitPrice)}`)
  return (
    <>
      <h3 className="tsb-sec">Orders on this candle</h3>
      {fills.length === 0 ? (
        <p className="tsb-dbg__muted tsb-small">
          {warmup ? 'None — the strategy was still warming up.' : 'No buy or sell happened during this candle.'}
        </p>
      ) : (
        <ul className="tsb-fills">
          {fills.map((f, i) => (
            <li key={i} className={`tsb-fill is-${f.side.toLowerCase()}`}>
              <span className="tsb-fill__glyph" aria-hidden>
                {f.side === 'BUY' ? '▲' : '▼'}
              </span>
              <div className="tsb-fill__main">
                <div>
                  <b>{f.side === 'BUY' ? 'Bought' : 'Sold'}</b> {num(f.qty)} {base} at{' '}
                  <b>{money(f.price)}</b> = {money(f.qty * f.price)} USDT
                </div>
                <div className="tsb-dbg__muted tsb-small">
                  {REASON[f.reason]}, {whenText(f)} · fee {money(f.fee, 4)}
                </div>
              </div>
              {f.pnl != null && <span className={`tsb-fill__pnl ${pnl(f.pnl)}`}>{signed(f.pnl)}</span>}
            </li>
          ))}
        </ul>
      )}
      {levels.length > 0 && (
        <p className="tsb-dbg__muted tsb-small tsb-levels">
          🛡 Waiting from the next candle on: {levels.join(' · ')}.
        </p>
      )}
    </>
  )
}

// ── The market (TradAO: compared with the market) ──────────────────────

function Market({ data: d, base }: { data: BarExplanationDto; base: string }) {
  const range = d.low > 0 ? ((d.high - d.low) / d.low) * 100 : null
  const b = d.book
  return (
    <>
      <h3 className="tsb-sec">The market on this candle</h3>
      <div className="tsb-candle">
        <span>Open {num(d.open)}</span>
        <span>High {num(d.high)}</span>
        <span>Low {num(d.low)}</span>
        <span>Close {num(d.close)}</span>
        {d.volume != null && (
          <span>
            Volume {num(d.volume)} {base}
          </span>
        )}
      </div>
      <div className="tsb-candle">
        {d.changePct != null && (
          <span className={pnl(d.changePct)}>{signedPct(d.changePct)} vs the previous close</span>
        )}
        {range != null && <span>{range.toFixed(2)}% from low to high</span>}
      </div>
      {b && b.buyHoldPct != null && (
        <div className="tsb-vs">
          <div>
            <span>Strategy since the start</span>
            <b className={pnl(b.returnPct)}>{signedPct(b.returnPct)}</b>
          </div>
          <div>
            <span>Buy &amp; hold over the same candles</span>
            <b className={pnl(b.buyHoldPct)}>{signedPct(b.buyHoldPct)}</b>
          </div>
        </div>
      )}
    </>
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
      value: s.condition.passed
        ? 'True'
        : s.condition.unknown
          ? s.taken === 'ELSE' ? 'No answer → ELSE' : 'No answer yet'
          : s.taken === 'ELSE' ? 'False → ELSE' : 'False',
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
      REJECTED_NOT_ENOUGH_CASH: { label: 'Order', value: 'Refused: more than the cash', state: 'stop' },
      NOT_FILLED_LAST_BAR: { label: 'Order', value: 'Last candle: no next open', state: 'stop' },
      SETTING_APPLIED: { label: 'Setting', value: `Applied: ${taken ? sayAction(taken) : ''}`, state: 'ok' },
    }
    steps.push(order[s.outcome] ?? { label: 'Order', value: s.outcome, state: 'idle' })
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
    'REJECTED_NOT_ENOUGH_CASH',
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

const PROTECTIVE: Partial<Record<FillDto['reason'], { icon: string; title: string; why: string }>> = {
  STOPLOSS: { icon: '🛑', title: 'Stop-loss hit inside this candle', why: 'the price fell to the stop-loss level' },
  TRAILING: { icon: '🛑', title: 'Trailing stop hit inside this candle', why: 'the price fell back to the trailing stop' },
  TAKEPROFIT: { icon: '🎯', title: 'Take-profit reached inside this candle', why: 'the price rose to the take-profit level' },
}

function verdictFor(d: BarExplanationDto, base: string): Verdict {
  if (d.warmup) {
    return {
      icon: '⏳',
      tone: 'quiet',
      title: 'Too early — still warming up',
      sub: `The strategy waits for the first ${d.warmupBars} candles so its indicators have enough history to be trusted. This is candle ${d.bar + 1}.`,
    }
  }
  const s = mainStatement(d)
  // A stop or target that fired inside the candle is the event of the
  // candle, whatever the rules said at its close.
  const exit = (d.fills ?? []).find((f) => f.side === 'SELL' && PROTECTIVE[f.reason])
  if (exit) {
    const p = PROTECTIVE[exit.reason]!
    const atOpen = exit.when === 'OPEN'
    const then = s && s.outcome === 'FILLED' ? ` At the close, rule “${s.ruleName}” then asked to ${sayAction(takenAction(s) ?? s.thenAction)} at the next open.` : ''
    return {
      icon: p.icon,
      tone: 'go',
      title: p.title,
      sub:
        `Sold ${num(exit.qty)} ${base} at ${money(exit.price)}: ${p.why}` +
        (atOpen ? ' — the candle opened past it, so it filled at the open, the first price there was.' : '.') +
        (exit.pnl != null ? ` The trade made ${signed(exit.pnl)} after fees.` : '') +
        then,
    }
  }
  // An order from the last close that filled at this candle's open: this is
  // the candle the chart's ▲/▼ sits on, so say so here — not only on the
  // candle before, where the rule fired.
  const opened = (d.fills ?? []).find((f) => f.when === 'OPEN' && f.reason === 'SIGNAL')
  const next =
    s && s.outcome === 'FILLED'
      ? ` At this close, rule “${s.ruleName}” asked to ${sayAction(takenAction(s) ?? s.thenAction)}, which fills at the next open (${num(s.fillPrice)}).`
      : ''
  if (opened) {
    return {
      icon: opened.side === 'BUY' ? '🛒' : '📤',
      tone: 'go',
      title: opened.side === 'BUY' ? "Bought at this candle's open" : "Sold at this candle's open",
      sub:
        `A rule was true when the previous candle closed, so the order filled at this open: ` +
        `${num(opened.qty)} ${base} at ${money(opened.price)}` +
        (opened.pnl != null ? `, making ${signed(opened.pnl)} after fees` : '') +
        `. Orders always fill on the candle after the signal.` +
        next,
    }
  }
  if (!s) return { icon: '•', tone: 'quiet', title: 'Nothing to check here', sub: '' }
  const act = takenAction(s)
  switch (s.outcome) {
    case 'FILLED':
      return isBuy(act)
        ? {
            icon: '🛒',
            tone: 'go',
            title: 'Buy signal at this close',
            sub: `Rule “${s.ruleName}” was true when this candle closed, so it buys at the next candle's open (${num(s.fillPrice)}) — the ▲ on the next candle. Orders never fill on the candle that gave the signal.`,
          }
        : {
            icon: '📤',
            tone: 'go',
            title: 'Sell signal at this close',
            sub: `Rule “${s.ruleName}” was true when this candle closed, so it sells at the next candle's open (${num(s.fillPrice)}) — the ▼ on the next candle.`,
          }
    case 'IGNORED_ALREADY_LONG':
      return {
        icon: '📦',
        tone: 'stop',
        title: 'Wanted to buy — but already holding',
        sub: `Rule “${s.ruleName}” said buy, but a position was already open. TSB holds one position at a time, so extra buy signals are skipped.`,
      }
    case 'REJECTED_NOT_ENOUGH_CASH':
      return {
        icon: '💸',
        tone: 'stop',
        title: 'Wanted to buy — but not enough cash',
        sub: `Rule “${s.ruleName}” asked for a fixed amount that cost more than the ${money(d.book?.cash)} USDT in cash, so it was refused, as a spot exchange would. Buy a share instead (e.g. 25% OF EQUITY) or a smaller amount.`,
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
