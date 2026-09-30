import type { PaperAccountDto, PaperPositionDto } from '../api'

/**
 * Live profit or loss of a position at a price, net of the fees paid to open
 * it — the same formula as the server's PaperMath.unrealized, so the chart,
 * the table and the ticket never disagree with the account.
 */
export function livePnl(p: PaperPositionDto, price: number | null): number {
  const mark = price ?? p.price ?? p.avgPrice
  const perUnit = p.side === 'LONG' ? mark - p.avgPrice : p.avgPrice - mark
  return perUnit * p.qty - p.entryFees
}

export function livePnlPct(p: PaperPositionDto, price: number | null): number {
  const entry = p.qty * p.avgPrice
  return entry ? (livePnl(p, price) / entry) * 100 : 0
}

/** Equity with the current symbol re-marked at the chart's live tick. */
export function liveEquity(a: PaperAccountDto, symbol: string, price: number | null): number {
  const p = a.positions.find((x) => x.symbol === symbol)
  if (!p || price == null || p.price == null) return a.equity
  const sign = p.side === 'LONG' ? 1 : -1
  return a.equity + sign * p.qty * (price - p.price)
}

/** What an order will do to the position, in words. */
export function orderEffect(
  p: PaperPositionDto | null,
  buy: boolean,
  qty: number,
  base: string,
): string {
  const fmt = (v: number) => Number(v.toFixed(8)).toString()
  if (!p) return buy ? `Opens a long of ${fmt(qty)} ${base}` : `Opens a short of ${fmt(qty)} ${base}`
  const same = (p.side === 'LONG') === buy
  const word = p.side === 'LONG' ? 'long' : 'short'
  if (same) return `Adds ${fmt(qty)} ${base} to your ${word}`
  const eps = 1e-12
  if (qty < p.qty - eps) return `Reduces your ${word} to ${fmt(p.qty - qty)} ${base}`
  if (qty <= p.qty + eps) return `Closes your ${word}`
  return `Closes your ${word} and opens a ${word === 'long' ? 'short' : 'long'} of ${fmt(qty - p.qty)} ${base}`
}
