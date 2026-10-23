import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AreaSeries,
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
import { api, type CandleColumns, type TradeDto, type UsedIndicatorDto } from '../api'
import IndicatorPicker from '../indicators/IndicatorPicker'
import { useChartIndicators, type ChartView } from '../indicators/useChartIndicators'
import {
  fromUsedIndicators,
  loadCatalog,
  type ActiveIndicator,
} from '../indicators/catalog'
import {
  TF_MS,
  decisionViewTime,
  lastAtOrBefore,
  resample,
  strategyCandleForView,
  tradeMarkers,
  viewChoices,
  viewKind,
  type MarkerSpec,
} from './viewTimeframe'
import { inventoryAt } from './inventory'
import type { TimeRange } from './CashUsageChart'
import './charts.css'

/** Smaller candles fetched from the server, per timeframe. */
type Loaded =
  | { status: 'loading' }
  | { status: 'ok'; candles: CandleColumns }
  | { status: 'error'; message: string }

/** What the chart is drawing: which candles, on which timeframe, for which run. */
type Shown = { tf: string; candles: CandleColumns; run: CandleColumns }

const toLw = (m: MarkerSpec): SeriesMarker<Time> => ({
  ...m,
  time: (m.time / 1000) as UTCTimestamp,
})
const byTime = (a: SeriesMarker<Time>, b: SeriesMarker<Time>) =>
  (a.time as number) - (b.time as number)
const day = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/**
 * The backtest, painted on the market: real candles for the run window,
 * with ▲ entry markers (amber = an action) and ▼ exit markers colored by
 * the trade's PnL — the app-wide rule that green/red only ever mean
 * profit/loss, extended to the chart. Candle up/down coloring is per-bar
 * PnL semantics, so the rule holds there too.
 *
 * The candles on screen can be switched to another timeframe. That changes
 * the VIEW only: the run, its rules and its numbers stay on the strategy's
 * own timeframe. Bigger candles are joined from the run's own candles;
 * smaller ones are loaded from the server. Trades, indicator values and
 * the debugger's "why?" mark are moved onto whatever is shown, never
 * earlier than the strategy could have acted (see viewTimeframe.ts).
 */
export default function PriceChart({
  candles,
  trades,
  symbol,
  timeframe,
  usedIndicators,
  onBarClick,
  selectedTime,
  range,
  onRange,
}: {
  candles: CandleColumns
  trades: TradeDto[]
  /** Needed to ask the backend for indicator values over the same candles. */
  symbol: string
  /** The strategy's own timeframe: the one its rules ran on. */
  timeframe: string
  /** The manifest the run reported. Seeds the chart; the user can add more. */
  usedIndicators?: UsedIndicatorDto[]
  /** Called with the open time (epoch ms) of the STRATEGY candle a click
   *  belongs to — the debugger. */
  onBarClick?: (timeMillis: number) => void
  /** Open time (epoch ms) of the strategy candle being explained. */
  selectedTime?: number | null
  /** The stretch of time another chart moved to, to follow it. */
  range?: TimeRange | null
  /** Told when a person zooms or scrolls this chart. */
  onRange?: (r: TimeRange) => void
}) {
  const ref = useRef<HTMLDivElement>(null)

  // Held in refs so a new callback or selection does not rebuild the chart.
  const onBarClickRef = useRef(onBarClick)
  onBarClickRef.current = onBarClick
  const onRangeRef = useRef(onRange)
  onRangeRef.current = onRange
  // Ranges set to follow the other chart are not reported back to it.
  const quietUntil = useRef(0)
  const tradeMarkersRef = useRef<SeriesMarker<Time>[]>([])
  const markersApi = useRef<ISeriesMarkersPluginApi<Time> | null>(null)

  // The stretch of time on screen — from the first visible candle's open to
  // the last one's END, in ms — carried over when the timeframe changes so
  // the same part of the run stays in view. `all`: it was the whole run,
  // so the next view is fitted whole too.
  const visibleRange = useRef<{ from: number; to: number; all: boolean } | null>(null)
  const keepRange = useRef(false)

  // The chart and the candles it shows live in state together: the
  // indicator hook has to re-run when a chart appears (a ref would hand it
  // null once and never fire again), and it must never see a new view with
  // an old chart.
  const [drawn, setDrawn] = useState<{ chart: IChartApi; view: ChartView } | null>(null)
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

  /* ── Which candles to show ─────────────────────────────────────────── */

  const choices = useMemo(() => viewChoices(timeframe), [timeframe])
  const [picked, setPicked] = useState(timeframe)
  // Bumped on every click, so clicking a timeframe that failed to load
  // tries again.
  const [asked, setAsked] = useState(0)
  const kind = viewKind(picked, timeframe) ?? 'same'
  const want = kind === 'same' ? timeframe : picked

  // Bigger: joined from the run's own candles, in the same UTC buckets the
  // language's ON uses.
  const joined = useMemo(
    () => (kind === 'bigger' ? resample(candles, want) : null),
    [candles, kind, want],
  )

  // Smaller: loaded from the server once per timeframe per run.
  const [smaller, setSmaller] = useState<{ run: CandleColumns; byTf: Record<string, Loaded> }>(
    () => ({ run: candles, byTf: {} }),
  )
  const wanted = kind === 'smaller' && smaller.run === candles ? smaller.byTf[want] : undefined

  useEffect(() => {
    if (kind !== 'smaller' || candles.t.length === 0) return
    if (wanted && wanted.status !== 'error') return
    const run = candles
    const put = (state: Loaded) =>
      setSmaller((s) =>
        s.run === run
          ? { run, byTf: { ...s.byTf, [want]: state } }
          : state.status === 'loading'
            ? { run, byTf: { [want]: state } }
            : s, // an answer for a run that is no longer on screen
      )
    put({ status: 'loading' })
    // Every small candle inside the run: from its first open to its last
    // close (the server's `to` is exclusive).
    const from = new Date(run.t[0]).toISOString()
    const to = new Date(run.t[run.t.length - 1] + (TF_MS[timeframe] ?? 0)).toISOString()
    api.getCandles(symbol, want, from, to).then(
      (c) => put({ status: 'ok', candles: c }),
      (e: unknown) => put({ status: 'error', message: e instanceof Error ? e.message : String(e) }),
    )
    // `wanted` is read, not watched: watching it would ask again the
    // moment it turns to 'loading'.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, want, candles, symbol, timeframe, asked])

  const ready = useMemo<Shown | null>(() => {
    if (kind === 'same') return { tf: timeframe, candles, run: candles }
    if (kind === 'bigger') {
      return joined && joined.t.length > 0 ? { tf: want, candles: joined, run: candles } : null
    }
    return wanted?.status === 'ok' && wanted.candles.t.length > 0
      ? { tf: want, candles: wanted.candles, run: candles }
      : null
  }, [kind, timeframe, candles, joined, want, wanted])

  // While other candles load, or if they cannot be had, the chart keeps
  // showing what it has.
  const [shown, setShown] = useState<Shown>(() => ({ tf: timeframe, candles, run: candles }))
  useEffect(() => {
    if (!ready) return
    // Same candles as already on screen: keep the object, so the chart is
    // not torn down and rebuilt for nothing.
    setShown((s) =>
      s.tf === ready.tf && s.candles === ready.candles && s.run === ready.run ? s : ready,
    )
  }, [ready])
  const display = useMemo<Shown>(
    () => (shown.run === candles ? shown : { tf: timeframe, candles, run: candles }),
    [shown, candles, timeframe],
  )

  const ownMs = TF_MS[timeframe] ?? 0
  const shownMs = TF_MS[display.tf] ?? ownMs
  const marks = useMemo(
    () => tradeMarkers(trades, timeframe, display.candles, display.tf),
    [trades, timeframe, display],
  )

  const pick = (tf: string) => {
    keepRange.current = true
    setPicked(tf)
    setAsked((n) => n + 1)
  }

  useChartIndicators(
    drawn?.chart ?? null,
    indicators,
    symbol,
    timeframe,
    setIndicatorError,
    drawn?.view ?? null,
  )

  /* ── The chart ─────────────────────────────────────────────────────── */

  useEffect(() => {
    if (!ref.current) return
    // Read before the new chart exists: it reports its own range as soon
    // as it gets data.
    const keep = keepRange.current ? visibleRange.current : null

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

    const c = display.candles
    series.setData(
      c.t.map((t, i) => ({
        time: Math.floor(t / 1000) as UTCTimestamp,
        open: c.o[i],
        high: c.h[i],
        low: c.l[i],
        close: c.c[i],
      })),
    )

    const markers = marks.markers.map(toLw)
    tradeMarkersRef.current = markers
    markersApi.current = createSeriesMarkers(series, markers)

    // The inventory, as TradAO shades it under its trading history: the
    // coins held at each candle's close, a green band along the bottom on
    // its own scale (green: a long position).
    const held = inventoryAt(c.t, TF_MS[display.tf] ?? ownMs, trades, candles.t)
    if (held.some((q) => q > 0)) {
      series.priceScale().applyOptions({ scaleMargins: { top: 0.06, bottom: 0.2 } })
      const inventory = chart.addSeries(AreaSeries, {
        priceScaleId: 'inventory',
        lineColor: 'rgba(76, 195, 138, 0.75)',
        topColor: 'rgba(76, 195, 138, 0.30)',
        bottomColor: 'rgba(76, 195, 138, 0.04)',
        lineWidth: 1,
        lineType: 1, // steps: holdings change only at trades
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
        title: 'held',
      })
      chart.priceScale('inventory').applyOptions({
        scaleMargins: { top: 0.84, bottom: 0 },
        visible: false,
      })
      inventory.setData(
        c.t.map((t, i) => ({ time: Math.floor(t / 1000) as UTCTimestamp, value: held[i] })),
      )
    }

    // Clicking a candle asks the debugger about the STRATEGY candle it
    // belongs to: the rules ran on those, whatever is on screen. param.time
    // is the clicked candle's open in seconds; undefined when the click
    // missed every bar.
    const ownTimes = candles.t
    const span = TF_MS[display.tf] ?? ownMs
    chart.subscribeClick((param) => {
      if (param.time === undefined) return
      const own = strategyCandleForView(Number(param.time) * 1000, span, ownTimes)
      if (own != null) onBarClickRef.current?.(own)
    })

    const scale = chart.timeScale()
    if (keep && !keep.all && c.t.length > 0) {
      // From the candle holding the old start to the candle holding the old
      // end: a part-covered big candle stays in, a cut-off small one does
      // not go missing.
      const i = Math.max(0, lastAtOrBefore(c.t, keep.from))
      const j = Math.max(i, lastAtOrBefore(c.t, keep.to - 1))
      try {
        scale.setVisibleRange({
          from: (c.t[i] / 1000) as UTCTimestamp,
          to: (c.t[j] / 1000) as UTCTimestamp,
        })
      } catch {
        scale.fitContent()
      }
    } else {
      scale.fitContent()
    }
    // The first ranges come from fitting the content, not from a person.
    quietUntil.current = performance.now() + 400
    scale.subscribeVisibleTimeRangeChange((r) => {
      if (!r || c.t.length === 0) return
      const from = (r.from as number) * 1000
      const to = (r.to as number) * 1000 + span
      visibleRange.current = {
        from,
        to,
        all: from <= c.t[0] && to >= c.t[c.t.length - 1] + span,
      }
      if (performance.now() >= quietUntil.current) {
        onRangeRef.current?.({ from, to: (r.to as number) * 1000, source: 'price' })
      }
    })

    setDrawn({
      chart,
      view: { timeframe: display.tf, times: c.t.map((t) => Math.floor(t / 1000)) },
    })
    return () => {
      // Tell the indicator hook its series are gone before the chart is.
      setDrawn(null)
      markersApi.current = null
      chart.remove()
    }
  }, [display, marks, candles, ownMs, trades])

  // Follow the cash chart when a person zooms or scrolls it.
  useEffect(() => {
    if (!drawn || !range || range.source === 'price' || !(range.to > range.from)) return
    quietUntil.current = performance.now() + 250
    try {
      drawn.chart.timeScale().setVisibleRange({
        from: Math.floor(range.from / 1000) as UTCTimestamp,
        to: Math.floor(range.to / 1000) as UTCTimestamp,
      })
    } catch {
      /* outside the candles on screen: keep the view */
    }
  }, [range, drawn])

  // Mark the candle being explained, alongside the trade markers. Its rules
  // ran when it closed, so the mark sits on the candle on screen that was
  // closing then.
  useEffect(() => {
    const plugin = markersApi.current
    if (!plugin) return
    const all = [...tradeMarkersRef.current]
    if (selectedTime != null) {
      const at = decisionViewTime(selectedTime, ownMs, display.candles.t, shownMs)
      if (at != null) {
        all.push({
          time: (at / 1000) as UTCTimestamp,
          position: 'aboveBar',
          shape: 'circle',
          color: '#e6e8ec',
          text: 'why?',
        })
        all.sort(byTime)
      }
    }
    plugin.setMarkers(all)
  }, [selectedTime, drawn, display, ownMs, shownMs])

  /* ── Words ─────────────────────────────────────────────────────────── */

  const loading =
    kind === 'smaller' && (!wanted || wanted.status === 'loading')
      ? `Loading ${want} candles…`
      : null
  const problem =
    kind === 'smaller' && wanted?.status === 'error'
      ? `Could not load ${want} candles (${wanted.message}). Still showing ${display.tf}.`
      : kind === 'smaller' && wanted?.status === 'ok' && wanted.candles.t.length === 0
        ? `No ${want} candles are stored for this period. Still showing ${display.tf}.`
        : null

  const shownKind = viewKind(display.tf, timeframe) ?? 'same'
  const ratio = ownMs > 0 ? Math.round(shownMs / ownMs) : 0
  const explain =
    shownKind === 'bigger'
      ? `Your rules still run on ${timeframe} candles. Only the view changed. Each ${display.tf} candle ` +
        `joins ${ratio} ${timeframe} candles, and its arrows add up every trade inside it. Click one to ` +
        `see the last ${timeframe} decision in it, then use ◀ ▶ for the others.`
      : shownKind === 'smaller'
        ? `Your rules still run on ${timeframe} candles. Only the view changed. They decide when a ` +
          `${timeframe} candle closes and buy or sell at the next ${timeframe} open, so indicator lines ` +
          `only move once per ${timeframe} candle. A stop or take-profit sits on the first ` +
          `${display.tf} candle that reached its price.`
        : null
  const dc = display.candles
  const coverage =
    shownKind === 'smaller' && dc.t.length > 0 && candles.t.length > 0 && dc.t[0] >= candles.t[0] + ownMs
      ? `${display.tf} candles here only cover ${day(dc.t[0])} → ${day(dc.t[dc.t.length - 1])}` +
        (marks.hidden > 0
          ? `; ${marks.hidden} earlier trade arrow${marks.hidden === 1 ? '' : 's'} only show on ${timeframe} and bigger.`
          : '.')
      : null

  return (
    <div style={{ margin: '16px 0' }}>
      {choices.length > 1 && (
        <div className="tsb-tf" role="group" aria-label="Chart timeframe">
          <span className="tsb-tf__label">Chart</span>
          {choices.map((tf) => {
            const own = tf === timeframe
            const on = tf === display.tf
            return (
              <button
                key={tf}
                type="button"
                className={`ghost tsb-tf__btn${on ? ' is-on' : ''}`}
                aria-pressed={on}
                title={
                  own
                    ? `Your rules run on ${tf} candles`
                    : `See this run on ${tf} candles. The rules still run on ${timeframe}.`
                }
                onClick={() => pick(tf)}
              >
                {tf}
                {own && <span className="tsb-tf__own"> · rules</span>}
              </button>
            )
          })}
          {loading && <span className="tsb-tf__status">{loading}</span>}
        </div>
      )}
      {problem && <p className="tsb-tf__note tsb-tf__note--warn">{problem}</p>}

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
      {explain && <p className="tsb-tf__note">{explain}</p>}
      {coverage && <p className="tsb-tf__note">{coverage}</p>}
      {onBarClick && (
        <div className="tsb-dbg__hint">
          👆 Click any candle to ask “why did (or didn’t) it trade here?”
        </div>
      )}
    </div>
  )
}
