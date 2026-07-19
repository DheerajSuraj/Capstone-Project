import { useEffect, useRef } from 'react'
import {
  AreaSeries,
  ColorType,
  createChart,
  LineSeries,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { CurvePoint } from '../api'

/**
 * The money's journey — the strategy's equity as the amber area, and the
 * humbling baseline underneath it: BUY & HOLD of the same capital over the
 * same window, in muted grey. The most pedagogically important line on the
 * page: a strategy that underperforms just-holding needs to know it.
 */
export default function EquityChart({
  curve,
  benchmark,
}: {
  curve: CurvePoint[]
  benchmark: CurvePoint[]
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
      timeScale: { borderColor: '#2a2f38' },
      rightPriceScale: { borderColor: '#2a2f38' },
    })

    const equity = chart.addSeries(AreaSeries, {
      lineColor: '#e8b44c',
      topColor: 'rgba(232, 180, 76, 0.25)',
      bottomColor: 'rgba(232, 180, 76, 0.02)',
      lineWidth: 2,
    })
    equity.setData(
      curve.map((p) => ({
        time: Math.floor(p.t / 1000) as UTCTimestamp,
        value: p.equity,
      })),
    )

    const hold = chart.addSeries(LineSeries, {
      color: '#8b909a',
      lineWidth: 1,
      lineStyle: 1, // dotted
      priceLineVisible: false,
      lastValueVisible: true,
    })
    hold.setData(
      benchmark.map((p) => ({
        time: Math.floor(p.t / 1000) as UTCTimestamp,
        value: p.equity,
      })),
    )

    chart.timeScale().fitContent()
    return () => chart.remove()
  }, [curve, benchmark])

  return (
    <div
      ref={ref}
      style={{
        height: 220,
        border: '1px solid #2a2f38',
        borderRadius: 6,
        overflow: 'hidden',
        margin: '16px 0',
      }}
    />
  )
}