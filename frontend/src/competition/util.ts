import type { CompetitionRulesDto, CompetitionStatus, EntryStatus } from '../api'

export const money = (v: number, digits = 2) =>
  v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })

export const signedMoney = (v: number) => `${v >= 0 ? '+' : '−'}${money(Math.abs(v))}`
export const signedPct = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)}%`
export const pnlClass = (v: number) => (v > 0 ? 'up' : v < 0 ? 'down' : '')

export const when = (iso: string) =>
  new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

/**
 * The server's clock, as far as the browser can tell: the difference
 * between the serverTime of the last answer and this computer's clock at
 * that moment. Countdowns ("entries close in 4m") use it, so a laptop whose
 * clock is off by minutes still counts down to the moment the server acts.
 */
let clockOffset = 0

export function syncClock(serverTime: string | null | undefined): void {
  const t = serverTime ? Date.parse(serverTime) : NaN
  if (Number.isFinite(t)) clockOffset = t - Date.now()
}

export const serverNow = () => Date.now() + clockOffset

/** "in 2h 5m", "3d 4h ago" */
export function relative(iso: string, now = serverNow()): string {
  const diff = Date.parse(iso) - now
  const a = Math.abs(diff)
  const d = Math.floor(a / 86_400_000)
  const h = Math.floor((a % 86_400_000) / 3_600_000)
  const m = Math.floor((a % 3_600_000) / 60_000)
  const s = Math.floor((a % 60_000) / 1000)
  const text = d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m ${s}s` : `${s}s`
  return diff >= 0 ? `in ${text}` : `${text} ago`
}

export const STATUS_LABEL: Record<CompetitionStatus, string> = {
  OPEN: 'Open for entries',
  RUNNING: 'Live',
  FINISHED: 'Finished',
  CANCELLED: 'Cancelled',
}

export const ENTRY_LABEL: Record<EntryStatus, string> = {
  ACTIVE: 'In the running',
  ELIMINATED: 'Eliminated',
  PASSED: 'Passed',
  FAILED: 'Failed',
}

/** What each status means, for a tooltip. */
export const ENTRY_HELP: Record<EntryStatus, string> = {
  ACTIVE: 'Still trading under the rules.',
  ELIMINATED: 'Broke a limit (max drawdown or a daily loss that ends the entry) and was stopped there.',
  PASSED: 'Met the profit target and the trading days.',
  FAILED: 'Finished without meeting the target or the trading days, or could not be run.',
}

type Ranked = { rank: number | null; status: EntryStatus; equity: number }

// The same groups as the server's Standings: passed first, then still in,
// then failed, then eliminated.
const GROUP: Record<EntryStatus, number> = { PASSED: 0, ACTIVE: 1, FAILED: 2, ELIMINATED: 3 }

/**
 * Places to show. Before the first candle every entry has the same money,
 * so nobody has earned a place yet: none are shown. A cancelled competition
 * has no winner either. Otherwise entries level on money (in the same
 * group) share a place — 1, 1, 3 — instead of the earlier submission
 * quietly getting the medal.
 */
export function shownRanks<T extends Ranked>(
  entries: T[],
  started: boolean,
  cancelled: boolean,
): Map<T, number | null> {
  const out = new Map<T, number | null>()
  if (!started || cancelled) {
    entries.forEach((e) => out.set(e, null))
    return out
  }
  const order = [...entries].sort((a, b) => (a.rank ?? 1e9) - (b.rank ?? 1e9))
  order.forEach((e, i) => {
    const prev = order[i - 1]
    const tied =
      prev != null &&
      GROUP[prev.status] === GROUP[e.status] &&
      Math.abs(prev.equity - e.equity) < 0.005
    out.set(e, tied ? (out.get(prev) ?? i + 1) : e.rank == null ? null : i + 1)
  })
  return out
}

/** When the daily rules start a new day (00:00 UTC), on this computer's clock. */
export const dayStartsHere = () =>
  new Date(Date.UTC(2000, 0, 1, 0, 0)).toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  })

/** The rule set as short chips. */
export function ruleChips(r: CompetitionRulesDto): string[] {
  const out = [
    `${money(r.startingCapital, 0)} virtual USDT each`,
    `Target +${r.profitTargetPct}%${r.passOnTarget ? ', pass at once' : ''}`,
    r.drawdownMode === 'STATIC'
      ? `Max loss ${r.maxDrawdownPct}% (fixed)`
      : `Max drawdown ${r.maxDrawdownPct}% (trailing)`,
  ]
  if (r.dailyLossLimitPct > 0)
    out.push(`Daily loss ${r.dailyLossLimitPct}% ${r.dailyLossAction === 'FAIL' ? '= out' : '= pause'}`)
  if (r.maxTradesPerDay > 0) out.push(`≤ ${r.maxTradesPerDay} trades/day`)
  if (r.minTradingDays > 0) out.push(`≥ ${r.minTradingDays} trading day${r.minTradingDays === 1 ? '' : 's'}`)
  out.push(`${r.feePercent}% fee`)
  return out
}
