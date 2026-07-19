import { useEffect, useRef, useState } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'

/**
 * The live market: recent history via Binance's public REST, then the
 * FORMING candle streamed over their public websocket and painted with
 * series.update() — the exact API this library ships for this purpose.
 *
 * Architecture note (deliberate): this component talks to Binance
 * DIRECTLY from the browser. The TSB backend is not involved — no relay,
 * no state, nothing in any request path. If Binance is unreachable, this
 * panel degrades alone; backtests (which read our own frozen candles)
 * are untouched. Display and truth are separate systems on purpose.
 */
export default function LiveChart({
  symbol,
  timeframe,
  height = 420,
}: {
  symbol: string
  timeframe: string
  height?: number | string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [status, setStatus] = useState<'connecting' | 'live' | 'error'>(
    'connecting',
  )

  useEffect(() => {
    if (!ref.current) return
    setStatus('connecting')

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
    })
    const series: ISeriesApi<'Candlestick'> = chart.addSeries(
      CandlestickSeries,
      {
        upColor: '#4cc38a',
        downColor: '#e5544b',
        borderUpColor: '#4cc38a',
        borderDownColor: '#e5544b',
        wickUpColor: '#4cc38a',
        wickDownColor: '#e5544b',
      },
    )

    let ws: WebSocket | null = null
    let closed = false

    // 1. Seed with recent history (public REST, CORS-open, no key).
    fetch(
      `https://api.binance.com/api/v3/klines?symbol=${symbol}` +
        `&interval=${timeframe}&limit=300`,
    )
      .then((r) => r.json())
      .then((rows: (string | number)[][]) => {
        if (closed) return
        series.setData(
          rows.map((k) => ({
            time: Math.floor(Number(k[0]) / 1000) as UTCTimestamp,
            open: Number(k[1]),
            high: Number(k[2]),
            low: Number(k[3]),
            close: Number(k[4]),
          })),
        )
        chart.timeScale().fitContent()

        // 2. Stream the forming candle.
        ws = new WebSocket(
          `wss://stream.binance.com:9443/ws/${symbol.toLowerCase()}` +
            `@kline_${timeframe}`,
        )
        ws.onopen = () => setStatus('live')
        ws.onerror = () => setStatus('error')
        ws.onmessage = (msg) => {
          const k = JSON.parse(msg.data).k
          // update() mutates the last bar in place until its open time
          // advances — new bars append automatically. isFinal (k.x) needs
          // no special handling for display.
          series.update({
            time: Math.floor(k.t / 1000) as UTCTimestamp,
            open: Number(k.o),
            high: Number(k.h),
            low: Number(k.l),
            close: Number(k.c),
          })
        }
      })
      .catch(() => setStatus('error'))

    return () => {
      closed = true
      ws?.close()
      chart.remove()
    }
  }, [symbol, timeframe])

  return (
    <div>
      <div
        style={{
          fontFamily: "'IBM Plex Mono', monospace",
          fontSize: 10,
          letterSpacing: '0.14em',
          textTransform: 'uppercase',
          color:
            status === 'live'
              ? '#e8b44c'
              : status === 'error'
                ? '#e5544b'
                : '#8b909a',
          marginBottom: 6,
        }}
      >
        {status === 'live'
          ? '● live — binance websocket'
          : status === 'error'
            ? 'binance unreachable — backtests unaffected'
            : 'connecting…'}
      </div>
      <div
        ref={ref}
        style={{
          height: 320,
          border: '1px solid #2a2f38',
          borderRadius: 6,
          overflow: 'hidden',
        }}
      />
    </div>
  )
}