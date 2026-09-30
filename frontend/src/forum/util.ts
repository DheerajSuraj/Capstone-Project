import type { ForumCategory } from '../api'

export const CATEGORIES: { id: ForumCategory; label: string; icon: string }[] = [
  { id: 'STRATEGIES', label: 'Strategies', icon: '📈' },
  { id: 'MARKET', label: 'Market talk', icon: '💬' },
  { id: 'HELP', label: 'Help', icon: '🛟' },
  { id: 'COMPETITIONS', label: 'Competitions', icon: '🏁' },
]

export const categoryLabel = (c: ForumCategory) =>
  CATEGORIES.find((x) => x.id === c)?.label ?? c

export const categoryIcon = (c: ForumCategory) =>
  CATEGORIES.find((x) => x.id === c)?.icon ?? '•'

/** "5m ago", "3h ago", "2d ago", then a date. */
export function ago(iso: string): string {
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000)
  if (s < 60) return 'just now'
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  if (s < 7 * 86400) return `${Math.floor(s / 86400)}d ago`
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export const signedPct = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)}%`
export const pnlClass = (v: number) => (v > 0 ? 'up' : v < 0 ? 'down' : '')
