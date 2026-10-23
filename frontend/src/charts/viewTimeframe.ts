import type { CandleColumns, TradeDto } from '../api'

/**
 * Looking at a backtest on a different timeframe from the one it ran on.
 *
 * The strategy's own timeframe never changes here: its rules ran when ITS
 * candles closed, and every helper below only decides where on the chosen
 * view to draw what already happened. Nothing is re-run and nothing is
 * recomputed — candles are only joined or loaded, indicator values only
 * re-indexed.
 *
 * Pure functions, so the rules of the view can be tested without a chart.
 */

/** Length of every timeframe the app knows, in milliseconds. */
export const TF_MS: Record<string, number> = {
  '1m': 60_000,
  '5m': 300_000,
  '15m': 900_000,
  '30m': 1_800_000,
  '1h': 3_600_000,
  '2h': 7_200_000,
  '4h': 14_400_000,
  '6h': 21_600_000,
  '8h': 28_800_000,
  '12h': 43_200_000,
  '1d': 86_400_000,
  '1w': 604_800_000,
}

/** The views the backtest chart offers, smallest first. */
export const VIEW_TIMEFRAMES = ['5m', '15m', '1h', '4h', '1d', '1w'] as const

/** Timeframes the server keeps candles for (IngestionScheduler.TIMEFRAMES).
 *  A view smaller than the strategy's own has to come from these. */
const STORED = new Set(['5m', '15m', '1h', '4h'])

const DAY = 86_400_000
/** Weeks start on Monday 00:00 UTC, as on the exchange — the same shift the
 *  server's Resampler uses, so a 1w view lines up with `ON 1w`. */
const WEEK_SHIFT = 3 * DAY

export type ViewKind = 'same' | 'smaller' | 'bigger'

/**
 * How a view relates to the strategy's own timeframe, or null when it
 * cannot be shown: a smaller view needs stored candles, and either way one
 * timeframe has to fit a whole number of times into the other.
 */
export function viewKind(view: string, own: string): ViewKind | null {
  const v = TF_MS[view]
  const o = TF_MS[own]
  if (!v || !o) return null
  if (v === o) return 'same'
  if (v < o) return STORED.has(view) && o % v === 0 ? 'smaller' : null
  return v % o === 0 ? 'bigger' : null
}

/** The views offered for a strategy on `own`, smallest first. Its own
 *  timeframe is always among them. */
export function viewChoices(own: string): string[] {
  const list: string[] = VIEW_TIMEFRAMES.filter((tf) => viewKind(tf, own) != null)
  if (!list.includes(own)) list.push(own)
  return list.sort((a, b) => (TF_MS[a] ?? 0) - (TF_MS[b] ?? 0))
}

/** Open time of the bigger candle that `t` falls in: UTC-aligned, weeks on
 *  Monday. Mirrors Resampler.bucketStart on the server. */
export function bucketStart(t: number, tf: string): number {
  const ms = TF_MS[tf]
  if (tf.endsWith('w')) return Math.floor((t + WEEK_SHIFT) / ms) * ms - WEEK_SHIFT
  return Math.floor(t / ms) * ms
}

/**
 * Joins the run's candles into bigger ones for display: first open, highest
 * high, lowest low, last close, summed volume. A part-filled candle at
 * either end is kept — on screen it is simply the candle as far as the run
 * went — whereas `ON` in the language only ever reads finished ones.
 */
export function resample(c: CandleColumns, tf: string): CandleColumns {
  const out: CandleColumns = { t: [], o: [], h: [], l: [], c: [], v: [] }
  let current = Number.NaN
  for (let i = 0; i < c.t.length; i++) {
    const start = bucketStart(c.t[i], tf)
    const vol = c.v?.[i] ?? 0
    if (start !== current) {
      current = start
      out.t.push(start)
      out.o.push(c.o[i])
      out.h.push(c.h[i])
      out.l.push(c.l[i])
      out.c.push(c.c[i])
      out.v.push(vol)
    } else {
      const k = out.t.length - 1
      if (c.h[i] > out.h[k]) out.h[k] = c.h[i]
      if (c.l[i] < out.l[k]) out.l[k] = c.l[i]
      out.c[k] = c.c[i]
      out.v[k] += vol
    }
  }
  return out
}

/* ------------------------------------------------------------- lookups */

/** Index of the last candle that opened at or before `t`, or -1. */
export function lastAtOrBefore(times: number[], t: number): number {
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

/** Index of the candle holding instant `t` (open <= t < open + span), or -1. */
function containing(times: number[], t: number, span: number): number {
  const i = lastAtOrBefore(times, t)
  return i >= 0 && t < times[i] + span ? i : -1
}

/** Index of the first candle that opened in [from, to), or -1. */
function firstIn(times: number[], from: number, to: number): number {
  const i = lastAtOrBefore(times, from - 1) + 1
  return i < times.length && times[i] < to ? i : -1
}

/** Index of the last candle that opened in [from, to), or -1. */
function lastIn(times: number[], from: number, to: number): number {
  const i = lastAtOrBefore(times, to - 1)
  return i >= 0 && times[i] >= from ? i : -1
}

/** The first candle in [from, to) whose range reached `price`, or -1. */
function firstTouch(c: CandleColumns, from: number, to: number, price: number): number {
  for (let i = firstIn(c.t, from, to); i >= 0 && i < c.t.length && c.t[i] < to; i++) {
    if (c.l[i] <= price && price <= c.h[i]) return i
  }
  return -1
}

/* --------------------------------------------------------- the debugger */

/**
 * Which of the strategy's own candles a click on the view asks about: the
 * last one that opened before the clicked candle ends. On a smaller view
 * that is the candle it sits inside; on a bigger view, the last decision
 * inside it (◀ ▶ then step through the others).
 */
export function strategyCandleForView(
  viewOpen: number,
  viewMs: number,
  ownTimes: number[],
): number | null {
  const i = lastAtOrBefore(ownTimes, viewOpen + viewMs - 1)
  return i < 0 ? null : ownTimes[i]
}

/**
 * Where to put the "why?" mark for the strategy candle that opened at
 * `ownOpen`. Its rules ran when it CLOSED, so the mark goes on the view
 * candle that was closing then: on a smaller view the last one inside it,
 * on a bigger view the one containing it. Null when the view has no candle
 * inside it (not loaded).
 */
export function decisionViewTime(
  ownOpen: number,
  ownMs: number,
  viewTimes: number[],
  viewMs: number,
): number | null {
  const i = lastAtOrBefore(viewTimes, ownOpen + ownMs - 1)
  if (i < 0 || viewTimes[i] + viewMs <= ownOpen) return null
  return viewTimes[i]
}

/* ----------------------------------------------------------- indicators */

/**
 * Puts the strategy's own indicator values onto the view's candles. Each
 * view candle shows the latest value whose candle had CLOSED by the time the
 * view candle closed — exactly the rule `ON` uses for bigger candles, so the
 * chart never shows a value before the strategy could have known it.
 *
 * On a smaller view the line therefore steps once per strategy candle, on
 * the small candle that closes with it; on a bigger view each candle shows
 * the value at its end. All times in one unit (the chart's seconds).
 */
export function remapToView(
  ownTimes: number[],
  values: (number | null)[],
  ownSpan: number,
  viewTimes: number[],
  viewSpan: number,
): { time: number; value: number }[] {
  const out: { time: number; value: number }[] = []
  let i = -1
  for (let k = 0; k < viewTimes.length; k++) {
    const viewClose = viewTimes[k] + viewSpan
    while (i + 1 < ownTimes.length && ownTimes[i + 1] + ownSpan <= viewClose) i++
    if (i < 0) continue
    const v = values[i]
    if (v == null || !Number.isFinite(v)) continue
    out.push({ time: viewTimes[k], value: v })
  }
  return out
}

/* --------------------------------------------------------------- trades */

export interface MarkerSpec {
  /** Open time (ms) of the view candle the marker sits on. */
  time: number
  position: 'aboveBar' | 'belowBar'
  shape: 'arrowUp' | 'arrowDown'
  color: string
  text: string
}

const AMBER = '#e8b44c'
const UP = '#4cc38a'
const DOWN = '#e5544b'
/** Exits that happen inside a candle, at a price, rather than at an open. */
const INSIDE_CANDLE = new Set(['STOPLOSS', 'TAKEPROFIT', 'TRAILING'])

const money = (n: number) => `${n >= 0 ? '+' : ''}${n.toFixed(2)}`
const amount = (n: number) => String(Number(n.toFixed(8)))

/**
 * Trade arrows for the view. `hidden` counts the entries and exits that fall
 * outside the loaded candles (a smaller view only loads so many).
 *
 * Where each one goes, given that the run fills BUY/SELL at the open of a
 * strategy candle:
 *  - same view: on that candle, as always.
 *  - smaller view: a fill at an open sits on the small candle starting
 *    there; a stop, take-profit or trailing exit on the first small candle
 *    that reached its price; an end-of-data exit on the last small candle,
 *    whose close is the run's last close.
 *  - bigger view: every entry and exit inside a big candle is added up into
 *    one arrow each, so a week with nine trades stays readable.
 */
export function tradeMarkers(
  trades: TradeDto[],
  ownTf: string,
  view: CandleColumns,
  viewTf: string,
): { markers: MarkerSpec[]; hidden: number } {
  const kind = viewKind(viewTf, ownTf) ?? 'same'
  const own = TF_MS[ownTf] ?? 0
  const span = kind === 'same' ? own : TF_MS[viewTf] ?? own
  const T = view.t
  let hidden = 0

  // A partial sell splits one position into several trade rows that share
  // the entry: draw that BUY once, with the full amount bought.
  const entries = new Map<number, number>()
  for (const tr of trades) {
    const t = Date.parse(tr.entryTime)
    entries.set(t, (entries.get(t) ?? 0) + tr.qty)
  }

  const entryIndex = (t: number) =>
    kind === 'smaller' ? firstIn(T, t, t + own) : containing(T, t, span)

  const exitIndex = (tr: TradeDto) => {
    const t = Date.parse(tr.exitTime)
    if (kind !== 'smaller') return containing(T, t, span)
    if (tr.exitReason === 'END_OF_DATA') return lastIn(T, t, t + own)
    if (INSIDE_CANDLE.has(tr.exitReason)) {
      const touched = firstTouch(view, t, t + own, tr.exitPrice)
      if (touched >= 0) return touched
    }
    return firstIn(T, t, t + own)
  }

  const markers: MarkerSpec[] = []

  if (kind !== 'bigger') {
    for (const [t, qty] of entries) {
      const i = entryIndex(t)
      if (i < 0) {
        hidden++
        continue
      }
      markers.push({ time: T[i], position: 'belowBar', shape: 'arrowUp', color: AMBER, text: `BUY ${amount(qty)}` })
    }
    for (const tr of trades) {
      const i = exitIndex(tr)
      if (i < 0) {
        hidden++
        continue
      }
      markers.push({
        time: T[i],
        position: 'aboveBar',
        shape: 'arrowDown',
        color: tr.pnl >= 0 ? UP : DOWN,
        text: `${tr.exitReason} ${money(tr.pnl)}`,
      })
    }
  } else {
    const buys = new Map<number, { n: number; qty: number }>()
    for (const [t, qty] of entries) {
      const i = entryIndex(t)
      if (i < 0) {
        hidden++
        continue
      }
      const b = buys.get(i) ?? { n: 0, qty: 0 }
      b.n += 1
      b.qty += qty
      buys.set(i, b)
    }
    const sells = new Map<number, { n: number; pnl: number; reason: string }>()
    for (const tr of trades) {
      const i = exitIndex(tr)
      if (i < 0) {
        hidden++
        continue
      }
      const s = sells.get(i) ?? { n: 0, pnl: 0, reason: tr.exitReason }
      s.n += 1
      s.pnl += tr.pnl
      sells.set(i, s)
    }
    for (const [i, b] of buys) {
      markers.push({
        time: T[i],
        position: 'belowBar',
        shape: 'arrowUp',
        color: AMBER,
        text: b.n === 1 ? `BUY ${amount(b.qty)}` : `BUY ×${b.n}`,
      })
    }
    for (const [i, s] of sells) {
      markers.push({
        time: T[i],
        position: 'aboveBar',
        shape: 'arrowDown',
        color: s.pnl >= 0 ? UP : DOWN,
        text: s.n === 1 ? `${s.reason} ${money(s.pnl)}` : `${s.n} exits ${money(s.pnl)}`,
      })
    }
  }

  // The marker API wants ascending time.
  markers.sort((a, b) => a.time - b.time)
  return { markers, hidden }
}
