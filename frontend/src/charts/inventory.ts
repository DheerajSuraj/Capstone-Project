import type { TradeDto } from '../api'

/**
 * Coins held at the close of each candle on screen — the inventory that
 * TradAO shades under its trading-history view (Tsang et al., IEEE VIS
 * 2020; green for a long position).
 *
 * Worked out from the trades, on the strategy's own candles: a trade holds
 * its coins from the open it bought at until the candle it was sold in.
 * A candle on screen shows what was held when the strategy candle its close
 * falls in closed — so on bigger candles it is the last of their strategy
 * candles, and on smaller ones every small candle inside one strategy
 * candle shows the same amount.
 *
 * @param times    open times (ms) of the candles on screen, ascending
 * @param spanMs   length of one of them
 * @param ownTimes open times (ms) of the strategy's candles, ascending
 */
export function inventoryAt(
  times: number[],
  spanMs: number,
  trades: TradeDto[],
  ownTimes: number[],
): number[] {
  // +qty when a trade's coins arrive, -qty when they leave. Partial sells
  // are separate trades with the same entry, so they add up on their own.
  const events: { t: number; d: number }[] = []
  for (const tr of trades) {
    const from = Date.parse(tr.entryTime)
    const to = Date.parse(tr.exitTime)
    if (!Number.isFinite(from) || !Number.isFinite(to) || !(tr.qty > 0)) continue
    events.push({ t: from, d: tr.qty }, { t: to, d: -tr.qty })
  }
  events.sort((a, b) => a.t - b.t || a.d - b.d)

  const out = new Array<number>(times.length).fill(0)
  if (ownTimes.length === 0) return out
  let k = 0 // next event
  let j = 0 // strategy candle pointer
  let held = 0
  for (let i = 0; i < times.length; i++) {
    const close = times[i] + spanMs - 1
    while (j + 1 < ownTimes.length && ownTimes[j + 1] <= close) j++
    if (ownTimes[j] > close) continue // before the run: nothing held
    const bar = ownTimes[j]
    // Coins held at that strategy candle's close: bought at or before its
    // open, not sold at or before it.
    while (k < events.length && events[k].t <= bar) {
      held += events[k].d
      k++
    }
    out[i] = held > 1e-12 ? held : 0
  }
  return out
}
