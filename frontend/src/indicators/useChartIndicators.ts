import { useEffect, useRef } from 'react'
import {
  LineSeries,
  LineType,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { computeIndicators, type ActiveIndicator, type ComputeResponse } from './catalog'
import { TF_MS, remapToView } from '../charts/viewTimeframe'

/**
 * Draws the active indicators onto an existing chart and keeps them in sync.
 *
 * The hook owns every series it creates and nothing else, so the chart
 * component needs to know only that it should pass the list in.
 *
 * Three behaviours worth knowing:
 *
 * The indicator line stops at the last CLOSED candle. The forming candle
 * streaming from Binance gets no point, because an indicator computed on a
 * half-finished candle changes under the user as the candle fills — the same
 * reason a forming candle is never stored.
 *
 * Oscillators go in their own pane. An RSI of 70 plotted against a Bitcoin
 * price axis is a flat line pinned to the bottom of the chart.
 *
 * With a `view` (the backtest chart showing other candles than the strategy
 * ran on), the values are still the strategy's own — computed on
 * `timeframe`, the numbers its rules read — and only moved onto the view's
 * candles, never earlier than the strategy could have known them.
 */

type Drawn = {
  series: ISeriesApi<'Line'>
  priceLines: IPriceLine[]
  paneIndex: number
}

/** The candles on screen, when they are not the strategy's own. */
export type ChartView = {
  timeframe: string
  /** Open times of the candles on screen, in seconds. */
  times: number[]
}

export function useChartIndicators(
  chart: IChartApi | null,
  indicators: ActiveIndicator[],
  symbol: string,
  timeframe: string,
  onError?: (message: string | null) => void,
  view?: ChartView | null,
) {
  const drawn = useRef<Map<string, Drawn>>(new Map())
  /** Stops a slow early response landing after a fast later one. */
  const requestSeq = useRef(0)
  /** A backtest's values do not change while you flip between views, so
   *  the last answer is reused instead of asking the server again. Only
   *  with a view: the live chart always asks, as its candles keep closing. */
  const lastAnswer = useRef<{ key: string; res: ComputeResponse } | null>(null)

  useEffect(() => {
    if (!chart) return

    // Remove what the user has taken off the list before fetching — deleting
    // should feel instant, not wait on a round trip.
    const wanted = new Set(indicators.filter((i) => i.visible).map((i) => i.id))
    for (const [id, entry] of drawn.current) {
      if (!wanted.has(id)) {
        try {
          chart.removeSeries(entry.series)
        } catch {
          /* already gone — the chart was rebuilt under us */
        }
        drawn.current.delete(id)
      }
    }

    const visible = indicators.filter((i) => i.visible)
    if (visible.length === 0) {
      onError?.(null)
      return
    }

    const seq = ++requestSeq.current
    let cancelled = false

    const key = view
      ? JSON.stringify([
          symbol,
          timeframe,
          visible.map((i) => [i.id, i.spec.name, i.source, i.args, i.timeframe ?? null, i.offset ?? 0]),
        ])
      : null
    const reuse = key != null && lastAnswer.current?.key === key ? lastAnswer.current.res : null

    // Seconds, like the chart. A view on a timeframe the app does not know
    // falls back to drawing the values where they are.
    const ownSpan = (TF_MS[timeframe] ?? 0) / 1000
    const viewSpan = view ? (TF_MS[view.timeframe] ?? 0) / 1000 : 0
    const onView = view != null && ownSpan > 0 && viewSpan > 0 ? view : null
    const otherCandles = onView != null && onView.timeframe !== timeframe
    // On smaller candles a value only changes when a strategy candle closes.
    const stepped = onView != null && viewSpan < ownSpan

    ;(reuse ? Promise.resolve(reuse) : computeIndicators({ symbol, timeframe, indicators: visible }))
      .then((res) => {
        // A later request is already out; this answer is for settings the
        // user has moved on from.
        if (cancelled || seq !== requestSeq.current) return
        if (key != null) lastAnswer.current = { key, res }
        onError?.(null)

        for (const active of visible) {
          const found = res.series.find((s) => s.id === active.id)
          if (!found) continue

          let entry = drawn.current.get(active.id)

          if (!entry) {
            // Overlays share pane 0 with the candles; each oscillator gets a
            // pane of its own below.
            const paneIndex =
              active.spec.placement === 'price' ? 0 : nextFreePane(drawn.current)

            const series = chart.addSeries(
              LineSeries,
              {
                color: active.color,
                lineWidth: 2,
                priceLineVisible: false,
                lastValueVisible: true,
                // The candles a value comes from, whenever they are not the
                // ones on screen: "EMA 4h" for ON 4h, "EMA 1h" for a 1h
                // strategy viewed on 15m or 1d candles.
                title: active.timeframe
                  ? `${active.spec.name} ${active.timeframe}`
                  : otherCandles
                    ? `${active.spec.name} ${timeframe}`
                    : active.spec.name,
                // A bigger-timeframe value only changes when a bigger candle
                // closes, so it is drawn as steps, not a smooth line.
                lineType: active.timeframe || stepped ? LineType.WithSteps : LineType.Simple,
              },
              paneIndex,
            )

            // Reference levels: RSI's 30 and 70, MACD's zero. Drawn once, as
            // price lines on the series rather than as extra series, so they
            // never appear in the legend or the crosshair readout.
            const priceLines = (active.spec.guides ?? []).map((price) =>
              series.createPriceLine({
                price,
                color: '#3a4049',
                lineWidth: 1,
                lineStyle: 2,
                axisLabelVisible: true,
                title: '',
              }),
            )

            // A bounded oscillator should keep its full range visible even
            // when the values sit in the middle of it — otherwise RSI hovering
            // around 50 fills the pane and looks far more dramatic than it is.
            if (
              active.spec.scaleMin !== undefined &&
              active.spec.scaleMax !== undefined
            ) {
              series.priceScale().applyOptions({ autoScale: false })
              series.applyOptions({
                autoscaleInfoProvider: () => ({
                  priceRange: {
                    minValue: active.spec.scaleMin!,
                    maxValue: active.spec.scaleMax!,
                  },
                }),
              })
            }

            entry = { series, priceLines, paneIndex }
            drawn.current.set(active.id, entry)
          }

          const points: { time: UTCTimestamp; value: number }[] = []
          if (onView) {
            // Onto the candles on screen, and only those: one point per
            // candle, none past the end of the run.
            for (const p of remapToView(res.t, found.values, ownSpan, onView.times, viewSpan)) {
              points.push({ time: p.time as UTCTimestamp, value: p.value })
            }
          } else {
            for (let i = 0; i < found.values.length; i++) {
              const v = found.values[i]
              // null is the warm-up prefix: the indicator genuinely has no
              // value yet. Skipping leaves a gap rather than drawing a flat
              // zero that looks like real data.
              if (v === null || !Number.isFinite(v)) continue
              points.push({ time: res.t[i] as UTCTimestamp, value: v })
            }
          }
          entry.series.setData(points)
        }
      })
      .catch((e: Error) => {
        if (cancelled || seq !== requestSeq.current) return
        onError?.(e.message)
      })

    return () => {
      cancelled = true
    }
    // onError is excluded on purpose: a fresh callback identity each render
    // would refetch every indicator on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chart, indicators, symbol, timeframe, view])

  // When the chart goes, everything on it goes with it. Clearing the map stops
  // us holding series belonging to a destroyed chart, which would throw on the
  // next removal.
  useEffect(() => {
    return () => {
      drawn.current.clear()
    }
  }, [chart])
}

/**
 * Panes are numbered from 0 (the price). Reuse the lowest free number, so
 * removing the middle of three oscillators leaves no blank pane behind.
 */
function nextFreePane(drawn: Map<string, Drawn>): number {
  const taken = new Set<number>()
  for (const entry of drawn.values()) {
    if (entry.paneIndex > 0) taken.add(entry.paneIndex)
  }
  let i = 1
  while (taken.has(i)) i++
  return i
}