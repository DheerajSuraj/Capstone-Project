import { useEffect, useRef } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  type SeriesMarker,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { CandleColumns, TradeDto } from '../api'

/**
 * The backtest, painted on the market: real candles for the run window,
 * with ▲ entry markers (amber = an action) and ▼ exit markers colored by
 * the trade's PnL — the app-wide rule that green/red only ever mean
 * profit/loss, extended to the chart. Candle up/down coloring is per-bar
 * PnL semantics, so the rule holds there too.
 */
export default function PriceChart({
  candles,
  trades,
}: {
  candles: CandleColumns
  trades: TradeDto[]
}) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ref.current) return
    const chart = createChart(ref.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: '#1c2027' },
        textColor: '#8b909a',
        fontFamily: "'IBM Plex Mono', monospace",
        fontSize: 11,
      },
      grid: {
        vertLines: { color: '#2a2f38' },
        horzLines: { color: '#2a2f38' },
      },
      timeScale: { borderColor: '#2a2f38', timeVisible: true },
      rightPriceScale: { borderColor: '#2a2f38' },
      crosshair: {
        vertLine: { color: '#e8b44c', labelBackgroundColor: '#e8b44c' },
        horzLine: { color: '#e8b44c', labelBackgroundColor: '#e8b44c' },
      },
    })

    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#4cc38a',
      downColor: '#e5544b',
      borderUpColor: '#4cc38a',
      borderDownColor: '#e5544b',
      wickUpColor: '#4cc38a',
      wickDownColor: '#e5544b',
    })

    series.setData(
      candles.t.map((t, i) => ({
        time: Math.floor(t / 1000) as UTCTimestamp,
        open: candles.o[i],
        high: candles.h[i],
        low: candles.l[i],
        close: candles.c[i],
      })),
    )

    // Trade markers: entries and exits interleave, and the marker API
    // requires ascending time order — build both, then sort once.
    const markers: SeriesMarker<UTCTimestamp>[] = []
    for (const t of trades) {
      markers.push({
        time: Math.floor(Date.parse(t.entryTime) / 1000) as UTCTimestamp,
        position: 'belowBar',
        shape: 'arrowUp',
        color: '#e8b44c',
        text: `BUY ${t.qty}`,
      })
      markers.push({
        time: Math.floor(Date.parse(t.exitTime) / 1000) as UTCTimestamp,
        position: 'aboveBar',
        shape: 'arrowDown',
        color: t.pnl >= 0 ? '#4cc38a' : '#e5544b',
        text: `${t.exitReason} ${t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}`,
      })
    }
    markers.sort((a, b) => (a.time as number) - (b.time as number))
    createSeriesMarkers(series, markers)

    chart.timeScale().fitContent()
    return () => chart.remove()
  }, [candles, trades])

  return (
    <div
      ref={ref}
      style={{
        height: 380,
        border: '1px solid #2a2f38',
        borderRadius: 6,
        overflow: 'hidden',
        margin: '16px 0',
      }}
    />
  )
}