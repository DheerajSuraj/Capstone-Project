import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ColorType,
  createChart,
  HistogramSeries,
  LineSeries,
  LineStyle,
  LineType,
  type AutoscaleInfo,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type LineData,
  type Time,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { BookPointDto } from '../api'
import './charts.css'

/** Where low cash starts to matter, in percent of the starting capital. */
export type CashLevels = { warningPct: number; dangerPct: number }
export const DEFAULT_LEVELS: CashLevels = { warningPct: 30, dangerPct: 10 }

/** A stretch of time on screen (ms), and which chart moved it. */
export type TimeRange = { from: number; to: number; source: string }

/** Colours of the cash-usage view, after TradAO. */
export const CASH_COLORS = {
  nav: '#5b9cf6',
  cash: '#3fb6a8',
  warning: '#e8a33c',
  danger: '#e5544b',
  capital: '#8b909a',
  hold: '#6b7280',
} as const

/** The colour of the cash line at one amount. */
export function cashTone(cash: number, capital: number, levels: CashLevels): 'ok' | 'warning' | 'danger' {
  if (cash < (capital * levels.dangerPct) / 100) return 'danger'
  if (cash < (capital * levels.warningPct) / 100) return 'warning'
  return 'ok'
}

const sec = (ms: number) => Math.floor(ms / 1000) as UTCTimestamp
const money = (v: number | null | undefined) =>
  v == null || !Number.isFinite(v)
    ? '—'
    : v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * The cash-usage view of TradAO (Tsang et al., "TradAO: A Visual
 * Analytics System for Trading Algorithm Optimization", IEEE VIS 2020):
 * the net asset value (blue) and the cash still free (its colour turns
 * orange, then red, as it falls under the warning and danger levels), the
 * starting capital as a grey dotted line, and the two levels as dotted
 * lines. ⚙ moves the levels. Buy & hold rides along as the line to beat.
 *
 * It is linked to the price chart above: zooming or scrolling one moves the
 * other to the same stretch of time, a band marks the candle being
 * explained, and clicking here explains that candle.
 */
export default function CashUsageChart({
  book,
  initialCapital,
  levels,
  onLevels,
  selectedTime,
  onPick,
  range,
  onRange,
}: {
  book: BookPointDto[]
  initialCapital: number
  levels: CashLevels
  onLevels: (l: CashLevels) => void
  /** Open time (ms) of the candle being explained: marked with a band. */
  selectedTime?: number | null
  /** A click on the chart: the time (ms) of the point clicked. */
  onPick?: (timeMillis: number) => void
  range?: TimeRange | null
  onRange?: (r: TimeRange) => void
}) {
  const host = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const cashRef = useRef<ISeriesApi<'Line'> | null>(null)
  const navRef = useRef<ISeriesApi<'Line'> | null>(null)
  const cursorRef = useRef<ISeriesApi<'Histogram'> | null>(null)
  const levelLines = useRef<IPriceLine[]>([])
  const onPickRef = useRef(onPick)
  onPickRef.current = onPick
  const onRangeRef = useRef(onRange)
  onRangeRef.current = onRange
  // Ranges set from the other chart must not be echoed back to it.
  const quietUntil = useRef(0)
  const [showLevels, setShowLevels] = useState(false)
  const [hover, setHover] = useState<BookPointDto | null>(null)

  const hasHold = useMemo(() => book.some((p) => p.benchmark != null), [book])
  const byTime = useMemo(() => new Map(book.map((p) => [sec(p.t) as number, p])), [book])

  /* ── The chart: rebuilt only when the run changes ──────────────────── */
  useEffect(() => {
    if (!host.current || book.length === 0) return
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
        vertLine: { color: '#8b909a', labelBackgroundColor: '#2a2f38' },
        horzLine: { color: '#8b909a', labelBackgroundColor: '#2a2f38' },
      },
    })
    chartRef.current = chart

    // The band behind everything: a one-bar histogram on its own hidden
    // scale, as tall as the chart.
    const cursor = chart.addSeries(HistogramSeries, {
      priceScaleId: 'cursor',
      lastValueVisible: false,
      priceLineVisible: false,
    })
    chart.priceScale('cursor').applyOptions({ visible: false, scaleMargins: { top: 0, bottom: 0 } })
    cursorRef.current = cursor

    if (hasHold) {
      const hold = chart.addSeries(LineSeries, {
        color: CASH_COLORS.hold,
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      })
      hold.setData(
        book
          .filter((p) => p.benchmark != null)
          .map((p) => ({ time: sec(p.t), value: p.benchmark as number })),
      )
    }

    const cash = chart.addSeries(LineSeries, {
      color: CASH_COLORS.cash,
      lineWidth: 2,
      lineType: LineType.WithSteps, // cash only moves when a trade fills
      priceLineVisible: false,
      lastValueVisible: true,
      // The scale always reaches down to zero cash, so the warning and
      // danger lines are on screen even while cash stays far above them.
      autoscaleInfoProvider: (original: () => AutoscaleInfo | null) => {
        const r = original()
        return {
          priceRange: { minValue: 0, maxValue: Math.max(r?.priceRange?.maxValue ?? 0, initialCapital) },
        }
      },
    })
    cashRef.current = cash

    const nav = chart.addSeries(LineSeries, {
      color: CASH_COLORS.nav,
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
    })
    nav.setData(book.map((p) => ({ time: sec(p.t), value: p.equity })))
    nav.createPriceLine({
      price: initialCapital,
      color: CASH_COLORS.capital,
      lineWidth: 1,
      lineStyle: LineStyle.Dotted,
      axisLabelVisible: true,
      title: 'start',
    })
    navRef.current = nav

    chart.timeScale().fitContent()
    // The first ranges come from fitting the content, not from a person.
    quietUntil.current = performance.now() + 400

    chart.subscribeClick((param) => {
      if (param.time === undefined) return
      onPickRef.current?.(Number(param.time) * 1000)
    })
    chart.subscribeCrosshairMove((param) => {
      setHover(param.time === undefined ? null : (byTime.get(Number(param.time)) ?? null))
    })
    chart.timeScale().subscribeVisibleTimeRangeChange((r) => {
      if (!r || performance.now() < quietUntil.current) return
      onRangeRef.current?.({
        from: (r.from as number) * 1000,
        to: (r.to as number) * 1000,
        source: 'cash',
      })
    })

    return () => {
      chartRef.current = null
      cashRef.current = null
      navRef.current = null
      cursorRef.current = null
      levelLines.current = []
      chart.remove()
    }
  }, [book, initialCapital, hasHold, byTime])

  /* ── Cash line colours and the two levels ──────────────────────────── */
  useEffect(() => {
    const cash = cashRef.current
    if (!cash) return
    const color = { ok: CASH_COLORS.cash, warning: CASH_COLORS.warning, danger: CASH_COLORS.danger }
    cash.setData(
      book.map(
        (p): LineData<Time> => ({
          time: sec(p.t),
          value: p.cash,
          color: color[cashTone(p.cash, initialCapital, levels)],
        }),
      ),
    )
    for (const l of levelLines.current) cash.removePriceLine(l)
    levelLines.current = [
      cash.createPriceLine({
        price: (initialCapital * levels.warningPct) / 100,
        color: CASH_COLORS.warning,
        lineWidth: 1,
        lineStyle: LineStyle.Dotted,
        axisLabelVisible: false,
        title: `warning ${levels.warningPct}%`,
      }),
      cash.createPriceLine({
        price: (initialCapital * levels.dangerPct) / 100,
        color: CASH_COLORS.danger,
        lineWidth: 1,
        lineStyle: LineStyle.Dotted,
        axisLabelVisible: false,
        title: `danger ${levels.dangerPct}%`,
      }),
    ]
  }, [book, initialCapital, levels])

  /* ── The candle being explained ────────────────────────────────────── */
  useEffect(() => {
    const cursor = cursorRef.current
    if (!cursor) return
    cursor.setData(
      selectedTime == null
        ? []
        : [{ time: sec(selectedTime), value: 1, color: 'rgba(230, 232, 236, 0.16)' }],
    )
  }, [selectedTime, book])

  /* ── Following the price chart ─────────────────────────────────────── */
  useEffect(() => {
    const chart = chartRef.current
    if (!chart || !range || range.source === 'cash' || !(range.to > range.from)) return
    quietUntil.current = performance.now() + 250
    try {
      chart.timeScale().setVisibleRange({ from: sec(range.from), to: sec(range.to) })
    } catch {
      /* a range outside the data: keep the view */
    }
  }, [range, book])

  if (book.length === 0) return null

  const last = book[book.length - 1]
  const shown = hover ?? last
  const tone = cashTone(shown.cash, initialCapital, levels)
  const usage = shown.equity > 0 ? (shown.positionValue / shown.equity) * 100 : 0

  return (
    <div className="tsb-cash">
      <div className="tsb-cash__head">
        <div>
          <div className="tsb-cash__title">Cash usage</div>
          <div className="tsb-cash__legend">
            <span><i style={{ background: CASH_COLORS.nav }} /> Net asset value</span>
            <span><i style={{ background: CASH_COLORS.cash }} /> Available cash</span>
            <span><i className="is-dotted" style={{ borderColor: CASH_COLORS.capital }} /> Starting capital</span>
            <span><i className="is-dotted" style={{ borderColor: CASH_COLORS.warning }} /> Warning</span>
            <span><i className="is-dotted" style={{ borderColor: CASH_COLORS.danger }} /> Danger</span>
            {hasHold && <span><i className="is-dashed" style={{ borderColor: CASH_COLORS.hold }} /> Buy &amp; hold</span>}
          </div>
        </div>
        <button
          type="button"
          className="ghost tsb-cash__gear"
          aria-expanded={showLevels}
          onClick={() => setShowLevels((v) => !v)}
          title="Set the warning and danger levels"
        >
          ⚙ Levels
        </button>
      </div>

      {showLevels && (
        <div className="tsb-cash__levels">
          <label>
            <span>Warning when cash is under</span>
            <input
              type="range"
              min={2}
              max={95}
              value={levels.warningPct}
              onChange={(e) => {
                const w = Number(e.target.value)
                onLevels({ warningPct: w, dangerPct: Math.min(levels.dangerPct, w - 1) })
              }}
            />
            <b style={{ color: CASH_COLORS.warning }}>{levels.warningPct}%</b>
          </label>
          <label>
            <span>Danger when cash is under</span>
            <input
              type="range"
              min={1}
              max={Math.max(1, levels.warningPct - 1)}
              value={levels.dangerPct}
              onChange={(e) => onLevels({ ...levels, dangerPct: Number(e.target.value) })}
            />
            <b style={{ color: CASH_COLORS.danger }}>{levels.dangerPct}%</b>
          </label>
          <p className="tsb-cash__note">
            Of the starting capital ({money(initialCapital)}). A spot strategy has no leverage, so
            low cash is not a debt — it means the money is in the coin, with nothing left to buy
            more or to cover a dip. The same levels colour the candle panel.
          </p>
        </div>
      )}

      <div className="tsb-cash__read" aria-live="off">
        <span>{new Date(shown.t).toISOString().slice(0, 16).replace('T', ' ')} UTC</span>
        <span style={{ color: CASH_COLORS.nav }}>NAV {money(shown.equity)}</span>
        <span className={`tsb-cash__cash is-${tone}`}>Cash {money(shown.cash)}</span>
        <span>{usage.toFixed(0)}% in the coin</span>
        {shown.benchmark != null && <span style={{ color: '#9aa0aa' }}>Buy &amp; hold {money(shown.benchmark)}</span>}
      </div>

      <div ref={host} className="tsb-cash__chart" />
      <p className="tsb-cash__hint">
        Zoom or scroll here or on the price chart — the other follows. Click to explain that candle.
      </p>
    </div>
  )
}
