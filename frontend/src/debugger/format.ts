import type { DecisionOutcome } from '../api'

/** Up to 6 significant digits, no exponent for normal prices. */
export function num(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return '—'
  if (v === 0) return '0'
  return Number(v.toPrecision(6)).toLocaleString('en-US', {
    maximumFractionDigits: 8,
  })
}

export function pct(fraction: number | null | undefined, digits = 2): string {
  if (fraction == null || !Number.isFinite(fraction)) return '—'
  return `${(fraction * 100).toFixed(digits)}%`
}

export function utc(ms: number): string {
  return new Date(ms).toISOString().slice(0, 16).replace('T', ' ') + ' UTC'
}

export const OUTCOME_LABEL: Record<DecisionOutcome, string> = {
  WARMUP: 'Not checked (warm-up)',
  NO_ACTION: 'No action',
  FILLED: 'Filled at next open',
  IGNORED_ALREADY_LONG: 'Ignored: already in a position',
  IGNORED_NOTHING_TO_SELL: 'Ignored: nothing to sell',
  REJECTED_TOO_SMALL: 'Rejected: below exchange minimum',
  NOT_FILLED_LAST_BAR: 'Last bar: no next open',
  SETTING_APPLIED: 'Setting applied',
}

/** Outcomes where the strategy asked for something and did NOT get it —
 *  the answers people are usually hunting for. */
export const BLOCKED: DecisionOutcome[] = [
  'IGNORED_ALREADY_LONG',
  'IGNORED_NOTHING_TO_SELL',
  'REJECTED_TOO_SMALL',
  'NOT_FILLED_LAST_BAR',
]
