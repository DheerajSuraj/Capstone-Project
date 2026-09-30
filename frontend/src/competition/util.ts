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

/** "in 2h 5m", "3d 4h ago" */
export function relative(iso: string, now = Date.now()): string {
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

/** The rule set as short chips. */
export function ruleChips(r: CompetitionRulesDto): string[] {
  const out = [
    `${money(r.startingCapital, 0)} USDT each`,
    `Target +${r.profitTargetPct}%`,
    `Max drawdown ${r.maxDrawdownPct}%`,
  ]
  if (r.dailyLossLimitPct > 0) out.push(`Daily loss ${r.dailyLossLimitPct}%`)
  if (r.maxTradesPerDay > 0) out.push(`≤ ${r.maxTradesPerDay} trades/day`)
  if (r.minTradingDays > 0) out.push(`≥ ${r.minTradingDays} trading days`)
  out.push(`${r.feePercent}% fee`)
  return out
}
