import { useEffect, useRef, useState } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  type IChartApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { CandleColumns, TradeDto, UsedIndicatorDto } from '../api'
import IndicatorPicker from '../indicators/IndicatorPicker'
import { useChartIndicators } from '../indicators/useChartIndicators'
import {
  fromUsedIndicators,
  loadCatalog,
  type ActiveIndicator,
} from '../indicators/catalog'

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
  symbol,
  timeframe,
  usedIndicators,
  onBarClick,
  selectedTime,
}: {
  candles: CandleColumns
  trades: TradeDto[]
  /** Needed to ask the backend for indicator values over the same candles. */
  symbol: string
  timeframe: string
  /** The manifest the run reported. Seeds the chart; the user can add more. */
  usedIndicators?: UsedIndicatorDto[]
  /** Called with the clicked candle's open time (epoch ms) — the debugger. */
  onBarClick?: (timeMillis: number) => void
  /** Open time (epoch ms) of the candle being explained, marked on the chart. */
  selectedTime?: number | null
}) {
  const ref = useRef<HTMLDivElement>(null)

  // Held in refs so a new callback or selection does not rebuild the chart.
  const onBarClickRef = useRef(onBarClick)
  onBarClickRef.current = onBarClick
  const tradeMarkers = useRef<SeriesMarker<UTCTimestamp>[]>([])
  const markersApi = useRef<ISeriesMarkersPluginApi<Time> | null>(null)

  // The chart lives in state because useChartIndicators is a hook and has to
  // re-run when the chart appears; a ref would hand it null once and never
  // fire again.
  const [chartApi, setChartApi] = useState<IChartApi | null>(null)
  const [indicators, setIndicators] = useState<ActiveIndicator[]>([])
  const [indicatorError, setIndicatorError] = useState<string | null>(null)

  // Seed the chart with exactly the indicators the strategy used. The user
  // can then add others to investigate — "why did it not trade here" is often
  // answered by an indicator the strategy does not itself reference.
  useEffect(() => {
    if (!usedIndicators || usedIndicators.length === 0) {
      setIndicators([])
      return
    }
    let cancelled = false
    loadCatalog().then((catalog) => {
      if (!cancelled) setIndicators(fromUsedIndicators(usedIndicators, catalog))
    })
    return () => {
      cancelled = true
    }
  }, [usedIndicators])

  useChartIndicators(
    chartApi,
    indicators,
    symbol,
    timeframe,
    setIndicatorError,
  )

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
    tradeMarkers.current = markers
    markersApi.current = createSeriesMarkers(series, markers)

    // Clicking a candle asks the debugger about it. param.time is the bar's
    // open time in seconds; undefined when the click missed every bar.
    chart.subscribeClick((param) => {
      if (param.time !== undefined) {
        onBarClickRef.current?.(Number(param.time) * 1000)
      }
    })

    chart.timeScale().fitContent()
    setChartApi(chart)
    return () => {
      // Tell the indicator hook its series are gone before the chart is.
      setChartApi(null)
      markersApi.current = null
      chart.remove()
    }
  }, [candles, trades])

  // Mark the candle being explained, alongside the trade markers.
  useEffect(() => {
    const api = markersApi.current
    if (!api) return
    const all = [...tradeMarkers.current]
    if (selectedTime != null) {
      all.push({
        time: Math.floor(selectedTime / 1000) as UTCTimestamp,
        position: 'aboveBar',
        shape: 'circle',
        color: '#e6e8ec',
        text: 'why?',
      })
      all.sort((a, b) => (a.time as number) - (b.time as number))
    }
    api.setMarkers(all)
  }, [selectedTime, chartApi])

  return (
    <div style={{ margin: '16px 0' }}>
      <div style={{ marginBottom: 8 }}>
        <IndicatorPicker active={indicators} onChange={setIndicators} />
        {indicatorError && (
          <div className="tsb-ind__error">{indicatorError}</div>
        )}
      </div>
      <div
        ref={ref}
        style={{
          height: 380,
          border: '1px solid #2a2f38',
          borderRadius: 6,
          overflow: 'hidden',
        }}
      />
      {onBarClick && (
        <div className="tsb-dbg__hint">
          Click any candle to see why the strategy did or didn't trade there.
        </div>
      )}
    </div>
  )
}