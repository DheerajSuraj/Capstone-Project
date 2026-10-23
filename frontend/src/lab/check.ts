import { api, type BacktestResultDto } from '../api'
import { withSymbol } from './starters'

/**
 * The honest check for "does this actually make money?".
 *
 * A strategy is run twice on each coin:
 *  - PAST: all history up to the split date. This is where ideas are
 *    usually tuned, so a good number here proves little on its own.
 *  - UNSEEN: only the most recent days. If a strategy still beats simply
 *    holding the coin here, that is real evidence.
 *
 * Both runs are compared with buy & hold over exactly the same candles and
 * fee (the backend works that number out from the same series).
 */

export const COINS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT'] as const

const TF_MS: Record<string, number> = {
  '5m': 5 * 60_000,
  '15m': 15 * 60_000,
  '1h': 60 * 60_000,
  '4h': 4 * 60 * 60_000,
}

/** Fewer closed trades than this in the unseen window and we say so. */
export const MIN_TRADES = 3

export interface Window {
  returnPct: number
  holdPct: number | null
  maxDrawdownPct: number
  trades: number
  winRate: number
}

export type Verdict = 'beat' | 'lost-less' | 'behind' | 'lost' | 'few' | 'error'

export interface CellResult {
  coin: string
  past: Window | null
  unseen: Window | null
  verdict: Verdict
  /** Beat holding on the unseen data AND on the past: the result is consistent. */
  consistent: boolean
  error?: string
}

function toWindow(r: BacktestResultDto): Window {
  return {
    returnPct: r.totalReturnPct,
    holdPct: r.buyHoldReturnPct ?? null,
    maxDrawdownPct: r.maxDrawdownPct,
    // Positions, not rows: a position sold in three parts is three rows of
    // the trade list but one decision, and "too few to tell" is about
    // decisions.
    trades: new Set(r.trades.map((t) => t.entryTime)).size,
    winRate: r.winRate,
  }
}

export function verdictOf(w: Window | null): Verdict {
  if (!w) return 'error'
  if (w.trades < MIN_TRADES) return 'few'
  const hold = w.holdPct ?? 0
  if (w.returnPct > hold && w.returnPct > 0) return 'beat'
  if (w.returnPct > hold) return 'lost-less'
  if (w.returnPct > 0) return 'behind'
  return 'lost'
}

export const VERDICT_TEXT: Record<Verdict, string> = {
  beat: 'Made money and beat holding',
  'lost-less': 'Lost money, but less than holding',
  behind: 'Made money, but holding made more',
  lost: 'Lost money',
  few: `Under ${MIN_TRADES} trades — too few to tell`,
  error: 'Could not run',
}

/** Beat holding with enough trades to mean something (zero trades on a
 *  falling market "beat" it by doing nothing). */
function beats(w: Window | null) {
  return !!w && w.trades >= MIN_TRADES && w.returnPct > (w.holdPct ?? 0)
}

async function once(source: string, from: Date | null, to: Date | null) {
  const res = await api.runBacktestRange(
    source,
    from ? from.toISOString() : null,
    to ? to.toISOString() : null,
  )
  if (res.ok && res.result) return res.result
  if (res.runError) throw new Error(res.runError)
  throw new Error(res.diagnostics[0]?.message ?? 'The strategy did not compile.')
}

/**
 * One strategy on one coin. The unseen run starts early by the strategy's
 * warm-up, so its indicators are ready and trading starts right at the
 * split instead of weeks into the window.
 */
export async function checkOne(
  source: string,
  timeframe: string,
  coin: string,
  split: Date,
): Promise<CellResult> {
  const src = withSymbol(source, coin)
  try {
    const past = await once(src, null, split)
    if (past.symbol !== coin) {
      // The coin could not be swapped in (it is not written as a plain
      // `symbol = ...` line), so this would be another coin's result
      // under this coin's name.
      throw new Error(
        `This strategy kept trading ${past.symbol}: the lab could not switch it to ${coin}. ` +
          'Write the coin as a line like  symbol = BTCUSDT  to test it on other coins.',
      )
    }
    const bar = TF_MS[timeframe] ?? TF_MS['1h']
    // The last warm-up candle closes exactly at the split, so the first
    // order fills at the split's open: the unseen part has no candle of
    // the past in it. (+2 made it start one candle early.)
    const unseenFrom = new Date(split.getTime() - (past.warmupBars + 1) * bar)
    const unseen = await once(src, unseenFrom, null)
    const p = toWindow(past)
    const u = toWindow(unseen)
    const verdict = verdictOf(u)
    return { coin, past: p, unseen: u, verdict, consistent: verdict === 'beat' && beats(p) }
  } catch (e) {
    return {
      coin,
      past: null,
      unseen: null,
      verdict: 'error',
      consistent: false,
      error: e instanceof Error ? e.message : String(e),
    }
  }
}

export function splitDate(unseenDays: number, now = new Date()): Date {
  const d = new Date(now.getTime() - unseenDays * 86_400_000)
  d.setUTCHours(0, 0, 0, 0)
  return d
}

/** Read the timeframe a strategy declares (for the warm-up offset). */
export function timeframeOf(source: string): string {
  return /^\s*timeframe\s*=\s*(\w+)/m.exec(source)?.[1] ?? '1h'
}
