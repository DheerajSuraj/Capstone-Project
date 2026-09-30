import { useEffect, useRef, useState } from 'react'
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  LineStyle,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type ISeriesMarkersPluginApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'
import IndicatorPicker from '../indicators/IndicatorPicker'
import { useChartIndicators } from '../indicators/useChartIndicators'
import type { ActiveIndicator } from '../indicators/catalog'

// Interaction happens on a transparent DOM <div> layer, NOT a <canvas>:
// a div reliably receives clicks and can't be swallowed by the chart's
// internal canvas (the bug that ate every earlier attempt). Drawings are
// painted on a separate canvas beneath the interaction layer, and each
// anchors to DATA space ({time, price}) so it stays welded to its candles
// through pan and zoom.

type Tool = 'cursor' | 'hline' | 'trend' | 'erase'

interface Point { time: UTCTimestamp; price: number }
interface HLine { kind: 'hline'; a: Point }
interface Trend { kind: 'trend'; a: Point; b: Point }
type Drawing = HLine | Trend

/** Things the page wants painted on the chart — paper-trading positions,
 *  orders and fills. The chart only draws them; it owns none of them. */
export interface ChartOverlays {
  lines: { price: number; color: string; title: string; dashed?: boolean }[]
  markers: { timeMillis: number; side: 'BUY' | 'SELL'; text: string }[]
  /** Clickable labels pinned to a price, like TradingView's position and
   *  order tags: text, live P&L and a ✕ that closes or cancels. */
  tags?: ChartTag[]
}

export interface ChartTag {
  id: string
  price: number
  label: string
  pnl?: string
  tone: 'up' | 'down' | 'flat' | 'order'
  onAction: () => void
  actionTitle: string
}

export default function DrawableChart({
  symbol,
  timeframe,
  height,
  onPrice,
  overlays,
  onSnapshot,
}: {
  symbol: string
  timeframe: string
  height: number | string
  /** Every live tick's last price, for the order ticket. */
  onPrice?: (price: number) => void
  overlays?: ChartOverlays
  /** When given, a 📷 button hands back a PNG of the chart with drawings. */
  onSnapshot?: (png: Blob) => void
}) {
  const onPriceRef = useRef(onPrice)
  onPriceRef.current = onPrice
  const markersApi = useRef<ISeriesMarkersPluginApi<Time> | null>(null)
  const priceLines = useRef<IPriceLine[]>([])
  // Where each tag sits on screen. Re-read every frame while tags exist, so
  // they follow the line through pans, zooms and price-scale changes.
  const [tagY, setTagY] = useState<Record<string, number | null>>({})
  const [scaleWidth, setScaleWidth] = useState(60)
  const host = useRef<HTMLDivElement>(null)
  const paint = useRef<HTMLCanvasElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const drawings = useRef<Drawing[]>([])
  const pending = useRef<Point | null>(null)
  const hover = useRef<{ x: number; y: number } | null>(null)
  const toolRef = useRef<Tool>('cursor')
  const [tool, setTool] = useState<Tool>('cursor')
  const [status, setStatus] = useState('connecting…')

  // The chart lives in STATE as well as in a ref. The ref is for the mouse
  // handlers, which need the current value without re-rendering; the state is
  // for useChartIndicators, which is a hook and must re-run when the chart
  // appears. A ref alone would hand the hook null on its first run and never
  // trigger a second one.
  const [chartApi, setChartApi] = useState<IChartApi | null>(null)
  const [indicators, setIndicators] = useState<ActiveIndicator[]>([])
  const [indicatorError, setIndicatorError] = useState<string | null>(null)

  useChartIndicators(chartApi, indicators, symbol, timeframe, setIndicatorError)

  useEffect(() => {
    toolRef.current = tool
  }, [tool])

  useEffect(() => {
    if (!host.current) return
    const chart = createChart(host.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: '#1c2027' },
        textColor: '#8b909a',
        fontFamily: "'IBM Plex Mono', monospace",
        fontSize: 11,
      },
      grid: { vertLines: { color: '#2a2f38' }, horzLines: { color: '#2a2f38' } },
      timeScale: { borderColor: '#2a2f38', timeVisible: true },
      rightPriceScale: { borderColor: '#2a2f38' },
      crosshair: {
        vertLine: { color: '#e8b44c', labelBackgroundColor: '#e8b44c' },
        horzLine: { color: '#e8b44c', labelBackgroundColor: '#e8b44c' },
      },
    })
    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#4cc38a', downColor: '#e5544b',
      borderUpColor: '#4cc38a', borderDownColor: '#e5544b',
      wickUpColor: '#4cc38a', wickDownColor: '#e5544b',
    })
    chartRef.current = chart
    seriesRef.current = series
    markersApi.current = createSeriesMarkers(series, [])
    priceLines.current = []
    setChartApi(chart)

    let disposed = false
    let socket: WebSocket | null = null

    // ── history from OUR backend (independent of Binance) ───────────────
    fetch(`/api/candles?symbol=${symbol}&timeframe=${timeframe}`)
      .then((r) => r.json())
      .then((cols: { t: number[]; o: number[]; h: number[]; l: number[]; c: number[] }) => {
        if (disposed || !cols.t) return
        series.setData(
          cols.t.map((t, i) => ({
            time: Math.floor(t / 1000) as UTCTimestamp,
            open: cols.o[i], high: cols.h[i], low: cols.l[i], close: cols.c[i],
          })),
        )
        const n = cols.t.length
        if (n > 0) onPriceRef.current?.(cols.c[n - 1])
        chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, n - 700), to: n + 5 })
        redraw()
      })
      .catch(() => { if (!disposed) setStatus('history unavailable') })

    // ── live forming candle via Binance ws ──────────────────────────────
    // Open on the next tick: StrictMode's synchronous unmount runs first and
    // sets disposed=true, so the throwaway first mount never opens a socket.
    // Only the surviving mount reaches here. This is what stops the
    // open/close churn ("ping received after close" / "closed before
    // established").
    const openTimer = setTimeout(() => {
      if (disposed) return
      const s = new WebSocket(
        `wss://stream.binance.com:9443/ws/${symbol.toLowerCase()}@kline_${timeframe}`,
      )
      socket = s
      s.onopen = () => {
        if (!disposed) setStatus('live')
      }
      s.onmessage = (ev) => {
        if (disposed) return
        try {
          const k = JSON.parse(ev.data).k
          seriesRef.current?.update({
            time: Math.floor(k.t / 1000) as UTCTimestamp,
            open: parseFloat(k.o), high: parseFloat(k.h),
            low: parseFloat(k.l), close: parseFloat(k.c),
          })
          onPriceRef.current?.(parseFloat(k.c))
        } catch {
          /* ignore a malformed frame */
        }
      }
      s.onerror = () => {
        if (!disposed) setStatus('offline — history only, drawings still work')
      }
    }, 0)

    chart.timeScale().subscribeVisibleLogicalRangeChange(() => redraw())

    const resize = () => {
      if (!paint.current || !host.current) return
      const w = host.current.clientWidth
      const h = host.current.clientHeight
      paint.current.width = w
      paint.current.height = h
      redraw()
    }
    const obs = new ResizeObserver(resize)
    obs.observe(host.current)
    resize()

    return () => {
      disposed = true
      clearTimeout(openTimer)
      obs.disconnect()
      if (socket) {
        // detach handlers BEFORE closing so a late ping/close can't fire
        // onmessage/onerror against a torn-down chart
        socket.onopen = null
        socket.onmessage = null
        socket.onerror = null
        socket.onclose = null
        // only close a socket that actually finished opening; closing a
        // CONNECTING socket is what logged "closed before established"
        if (socket.readyState === WebSocket.OPEN) socket.close(1000)
      }
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
      markersApi.current = null
      priceLines.current = []
      // Tells useChartIndicators its series are gone, so it does not try to
      // remove them from a chart that no longer exists.
      setChartApi(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, timeframe])

  // ── Paper-trading overlays: price lines + fill markers ──────────────
  useEffect(() => {
    const series = seriesRef.current
    if (!series || !chartApi) return
    for (const l of priceLines.current) series.removePriceLine(l)
    priceLines.current = (overlays?.lines ?? []).map((l) =>
      series.createPriceLine({
        price: l.price,
        color: l.color,
        lineWidth: 1,
        lineStyle: l.dashed ? LineStyle.Dashed : LineStyle.Solid,
        axisLabelVisible: true,
        title: l.title,
      }),
    )
    // Markers snap to the candle their fill happened in.
    const barMs = TF_MS[timeframe] ?? 3_600_000
    const markers: SeriesMarker<Time>[] = (overlays?.markers ?? [])
      .map((m) => ({
        time: (Math.floor(m.timeMillis / barMs) * barMs / 1000) as UTCTimestamp,
        position: m.side === 'BUY' ? ('belowBar' as const) : ('aboveBar' as const),
        shape: m.side === 'BUY' ? ('arrowUp' as const) : ('arrowDown' as const),
        color: '#e8b44c',
        text: m.text,
      }))
      .sort((a, b) => (a.time as number) - (b.time as number))
    markersApi.current?.setMarkers(markers)
  }, [overlays, chartApi, timeframe])

  const tags = overlays?.tags
  useEffect(() => {
    if (!tags || tags.length === 0 || !chartApi) {
      setTagY({})
      return
    }
    let raf = 0
    const tick = () => {
      const series = seriesRef.current
      if (series) {
        const next: Record<string, number | null> = {}
        for (const t of tags) next[t.id] = series.priceToCoordinate(t.price)
        setTagY((prev) => (sameY(prev, next) ? prev : next))
        const w = chartApi.priceScale('right').width()
        setScaleWidth((prev) => (prev === w ? prev : w))
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [tags, chartApi])

  const toData = (x: number, y: number): Point | null => {
    const chart = chartRef.current
    const series = seriesRef.current
    if (!chart || !series) return null
    const time = chart.timeScale().coordinateToTime(x)
    const price = series.coordinateToPrice(y)
    if (time === null || price === null) return null
    return { time: time as UTCTimestamp, price }
  }

  const project = (p: Point): { x: number; y: number } | null => {
    const chart = chartRef.current
    const series = seriesRef.current
    if (!chart || !series) return null
    const x = chart.timeScale().timeToCoordinate(p.time)
    const y = series.priceToCoordinate(p.price)
    if (x === null || y === null) return null
    return { x, y }
  }

  // ── Interaction on the DOM layer ──────────────────────────────────────
  const localXY = (e: React.MouseEvent) => {
    const rect = e.currentTarget.getBoundingClientRect()
    return { x: e.clientX - rect.left, y: e.clientY - rect.top }
  }

  const onLayerClick = (e: React.MouseEvent) => {
    const t = toolRef.current
    if (t === 'cursor') return
    const { x, y } = localXY(e)
    const pt = toData(x, y)
    if (!pt) return
    if (t === 'hline') {
      drawings.current.push({ kind: 'hline', a: pt })
    } else if (t === 'trend') {
      if (!pending.current) pending.current = pt
      else {
        drawings.current.push({ kind: 'trend', a: pending.current, b: pt })
        pending.current = null
      }
    } else if (t === 'erase') {
      eraseNear(x, y)
    }
    redraw()
  }

  const onLayerMove = (e: React.MouseEvent) => {
    if (toolRef.current === 'trend' && pending.current) {
      hover.current = localXY(e)
      redraw()
    }
  }

  const redraw = () => {
    const cv = paint.current
    if (!cv) return
    const ctx = cv.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, cv.width, cv.height)
    for (const d of drawings.current) {
      if (d.kind === 'hline') {
        const p = project(d.a)
        if (!p) continue
        ctx.strokeStyle = '#8b909a'; ctx.lineWidth = 1; ctx.setLineDash([5, 4])
        ctx.beginPath(); ctx.moveTo(0, p.y); ctx.lineTo(cv.width, p.y); ctx.stroke()
        ctx.setLineDash([])
        ctx.fillStyle = '#8b909a'; ctx.font = "11px 'IBM Plex Mono', monospace"
        ctx.fillText(d.a.price.toFixed(2), 6, p.y - 4)
      } else {
        const a = project(d.a); const b = project(d.b)
        if (!a || !b) continue
        ctx.strokeStyle = '#e8b44c'; ctx.lineWidth = 1.5; ctx.setLineDash([])
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke()
      }
    }
    if (pending.current && hover.current) {
      const a = project(pending.current)
      if (a) {
        ctx.strokeStyle = 'rgba(232,180,76,0.5)'; ctx.lineWidth = 1; ctx.setLineDash([4, 4])
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(hover.current.x, hover.current.y); ctx.stroke()
        ctx.setLineDash([])
      }
    }
  }

  const eraseNear = (x: number, y: number) => {
    const HIT = 8
    drawings.current = drawings.current.filter((d) => {
      if (d.kind === 'hline') {
        const p = project(d.a)
        return !p || Math.abs(p.y - y) > HIT
      }
      const a = project(d.a); const b = project(d.b)
      if (!a || !b) return true
      return distToSegment(x, y, a.x, a.y, b.x, b.y) > HIT
    })
  }

  /** The chart plus the drawings layer, flattened into one PNG. */
  const snapshot = () => {
    const chart = chartRef.current
    if (!chart || !onSnapshot) return
    const base = chart.takeScreenshot()
    const out = document.createElement('canvas')
    out.width = base.width
    out.height = base.height
    const ctx = out.getContext('2d')
    if (!ctx) return
    ctx.drawImage(base, 0, 0)
    // The drawings canvas is in CSS pixels; the screenshot may be in device
    // pixels. Both cover the same area, so scale one onto the other.
    if (paint.current) ctx.drawImage(paint.current, 0, 0, out.width, out.height)
    const scale = out.width / Math.max(1, host.current?.clientWidth ?? out.width)
    ctx.fillStyle = 'rgba(139,144,154,0.9)'
    ctx.font = `${Math.round(12 * scale)}px 'IBM Plex Mono', monospace`
    ctx.fillText(`${symbol} · ${timeframe} · TSB`, 10 * scale, out.height - 10 * scale)
    out.toBlob((b) => b && onSnapshot(b), 'image/png')
  }

  const clearAll = () => {
    drawings.current = []
    pending.current = null
    hover.current = null
    redraw()
  }

  const drawingActive = tool !== 'cursor'

  const toolBtn = (t: Tool, label: string) => (
    <button
      className="ghost"
      style={tool === t ? { background: 'rgba(232,180,76,0.18)' } : undefined}
      onClick={() => setTool(t)}
    >
      {label}
    </button>
  )

  return (
    <div>
      <div className="draw-toolbar">
        {toolBtn('cursor', 'Cursor')}
        {toolBtn('hline', 'H-Line')}
        {toolBtn('trend', 'Trend')}
        {toolBtn('erase', 'Erase')}
        <button className="ghost" onClick={clearAll}>Clear</button>
        {onSnapshot && (
          <button className="ghost" onClick={snapshot} title="Post this chart, with your drawings, to the forum">
            📷 Snapshot
          </button>
        )}
        <span className="note" style={{ marginLeft: 6 }}>
          <span style={{ color: status === 'live' ? 'var(--up)' : 'var(--muted)' }}>●</span>{' '}
          {status}
          {tool === 'trend' && ' · click two points'}
          {tool === 'hline' && ' · click a price level'}
          {tool === 'erase' && ' · click a drawing to remove'}
        </span>
      </div>

      {/* Indicators get their own row: the legend grows as lines are added,
          and sharing the toolbar would shove the drawing tools around. */}
      <div style={{ margin: '0 0 8px' }}>
        <IndicatorPicker active={indicators} onChange={setIndicators} />
        {indicatorError && (
          <div className="tsb-ind__error">{indicatorError}</div>
        )}
      </div>

      <div style={{ position: 'relative', height }}>
        {/* chart */}
        <div ref={host} style={{ position: 'absolute', inset: 0, borderRadius: 6, overflow: 'hidden', border: '1px solid #2a2f38' }} />
        {/* painted drawings — never intercepts pointer events */}
        <canvas ref={paint} style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }} />
        {/* interaction layer — a DOM div, only present when drawing, so
            the chart keeps full pan/zoom with the cursor tool */}
        {drawingActive && (
          <div
            onClick={onLayerClick}
            onMouseMove={onLayerMove}
            style={{ position: 'absolute', inset: 0, cursor: 'crosshair', background: 'transparent' }}
          />
        )}
        {/* position / order tags — above the drawing layer so ✕ always works */}
        {tags?.map((t) => {
          const y = tagY[t.id]
          const h = host.current?.clientHeight ?? 0
          if (y == null || y < 8 || y > h - 30) return null
          return (
            <div key={t.id} className={`tsb-tag tsb-tag--${t.tone}`} style={{ top: y, right: scaleWidth + 6 }}>
              <span className="tsb-tag__label">{t.label}</span>
              {t.pnl && <span className="tsb-tag__pnl">{t.pnl}</span>}
              <button type="button" className="tsb-tag__x" onClick={t.onAction} title={t.actionTitle} aria-label={t.actionTitle}>
                ✕
              </button>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function sameY(a: Record<string, number | null>, b: Record<string, number | null>): boolean {
  const ka = Object.keys(a)
  const kb = Object.keys(b)
  if (ka.length !== kb.length) return false
  return kb.every((k) => a[k] != null && b[k] != null ? Math.abs((a[k] as number) - (b[k] as number)) < 0.5 : a[k] === b[k])
}

const TF_MS: Record<string, number> = {
  '5m': 300_000,
  '15m': 900_000,
  '1h': 3_600_000,
  '4h': 14_400_000,
}

function distToSegment(px: number, py: number, x1: number, y1: number, x2: number, y2: number): number {
  const dx = x2 - x1, dy = y2 - y1
  const lenSq = dx * dx + dy * dy
  if (lenSq === 0) return Math.hypot(px - x1, py - y1)
  let t = ((px - x1) * dx + (py - y1) * dy) / lenSq
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}
