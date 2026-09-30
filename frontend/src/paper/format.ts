export const usd = (v: number | null | undefined, digits = 2) =>
  v == null || !Number.isFinite(v)
    ? '—'
    : v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })

/** Prices: 2 decimals above 1, more below (so SOL and BTC both read well). */
export const price = (v: number | null | undefined) =>
  v == null || !Number.isFinite(v)
    ? '—'
    : v.toLocaleString('en-US', {
        minimumFractionDigits: 2,
        maximumFractionDigits: v >= 1 ? 2 : 6,
      })

export const qty = (v: number | null | undefined) =>
  v == null || !Number.isFinite(v)
    ? '—'
    : v.toLocaleString('en-US', { maximumFractionDigits: 8 })

export const signed = (v: number, digits = 2) => `${v >= 0 ? '+' : '−'}${usd(Math.abs(v), digits)}`

export const pct = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(2)}%`

/** Green/red are profit/loss colours — used for exactly that. */
export const pnlClass = (v: number) => (v > 0 ? 'up' : v < 0 ? 'down' : '')

export const time = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—'
