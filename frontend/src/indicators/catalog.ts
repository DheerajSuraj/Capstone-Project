/**
 * Indicator catalog and value fetching.
 *
 * The values drawn on the chart are computed by the BACKEND, by the same
 * IndicatorBank the backtesting engine uses. That is the most important thing
 * about this file.
 *
 * Computing SMA and RSI in JavaScript instead would be about fifty lines and
 * no backend work — and a second implementation of every indicator. Wilder
 * smoothing is recursive, so two implementations drift on floating-point
 * rounding: the chart would show RSI 29.97 while the engine sees 30.01. The
 * decision debugger would then say "RSI was 30.01, needed below 30" while the
 * line on screen sits visibly under 30, and the user would be right to trust
 * neither of us.
 *
 * One implementation, one answer.
 */

import { getAccessToken, refresh } from '../auth/api'

/* ------------------------------------------------------------------ types */

export const PRICE_FIELDS = ['OPEN', 'HIGH', 'LOW', 'CLOSE', 'VOLUME'] as const
export type PriceField = (typeof PRICE_FIELDS)[number]

export type IndicatorParam = {
  name: string
  label: string
  /** 'series' is a price field dropdown; 'number' is a typed value. */
  kind: 'series' | 'number'
  defaultNumber?: number
  defaultSource?: PriceField
  /** Periods must be whole and positive; a Bollinger multiplier need not be. */
  positiveInt: boolean
}

/**
 * 'price'    — same vertical scale as the candles (moving averages, bands).
 * 'separate' — its own pane below. An RSI of 70 drawn against a Bitcoin price
 *              axis is a flat line pinned to the bottom of the chart.
 */
export type Placement = 'price' | 'separate'

export type IndicatorSpec = {
  /** Registry name, exactly as the compiler knows it, e.g. "BB_UPPER". */
  name: string
  label: string
  params: IndicatorParam[]
  placement: Placement
  scaleMin?: number
  scaleMax?: number
  /** Reference levels, e.g. RSI's 30 and 70. */
  guides?: number[]
  /**
   * Indicators to add alongside this one. The registry exposes multi-output
   * indicators as separate single-output names, so a Bollinger upper band on
   * its own would look broken.
   */
  companions?: string[]
}

/** One indicator the user has actually put on the chart. */
export type ActiveIndicator = {
  /** Our handle for this line. The server echoes it back. */
  id: string
  spec: IndicatorSpec
  /** Price field, when the indicator takes one. */
  source?: PriceField
  /** The numeric arguments, in declaration order. */
  args: number[]
  color: string
  visible: boolean
}

export type SeriesValues = {
  id: string
  /** The engine's own name for this line, e.g. "SMA(CLOSE,20)". */
  key: string
  /** One per candle. null is the warm-up prefix. */
  values: (number | null)[]
}

export type ComputeResponse = {
  /** Shared time axis, seconds. Closed candles only. */
  t: number[]
  series: SeriesValues[]
}

/* -------------------------------------------------------------- fallback */

/**
 * Used only until GET /api/indicators/catalog exists, so the picker is usable
 * today. The server's answer always wins — see loadCatalog.
 *
 * Names here match the registry exactly, including the deliberate split of
 * multi-output indicators into separate single-output names.
 */
export const FALLBACK_CATALOG: IndicatorSpec[] = [
  {
    name: 'SMA',
    label: 'Simple Moving Average',
    placement: 'price',
    params: [
      { name: 'source', label: 'Source', kind: 'series', defaultSource: 'CLOSE', positiveInt: false },
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 20, positiveInt: true },
    ],
  },
  {
    name: 'EMA',
    label: 'Exponential Moving Average',
    placement: 'price',
    params: [
      { name: 'source', label: 'Source', kind: 'series', defaultSource: 'CLOSE', positiveInt: false },
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 21, positiveInt: true },
    ],
  },
  {
    name: 'VWAP',
    label: 'Volume Weighted Average Price',
    placement: 'price',
    params: [],
  },
  {
    name: 'BB_UPPER',
    label: 'Bollinger Band (upper)',
    placement: 'price',
    companions: ['BB_LOWER'],
    params: [
      { name: 'source', label: 'Source', kind: 'series', defaultSource: 'CLOSE', positiveInt: false },
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 20, positiveInt: true },
      { name: 'k', label: 'Std dev', kind: 'number', defaultNumber: 2, positiveInt: false },
    ],
  },
  {
    name: 'BB_LOWER',
    label: 'Bollinger Band (lower)',
    placement: 'price',
    companions: ['BB_UPPER'],
    params: [
      { name: 'source', label: 'Source', kind: 'series', defaultSource: 'CLOSE', positiveInt: false },
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 20, positiveInt: true },
      { name: 'k', label: 'Std dev', kind: 'number', defaultNumber: 2, positiveInt: false },
    ],
  },
  {
    name: 'RSI',
    label: 'Relative Strength Index',
    placement: 'separate',
    scaleMin: 0,
    scaleMax: 100,
    guides: [30, 70],
    params: [
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 14, positiveInt: true },
    ],
  },
  {
    name: 'MACD_LINE',
    label: 'MACD Line',
    placement: 'separate',
    guides: [0],
    companions: ['MACD_SIGNAL'],
    params: [
      { name: 'fast', label: 'Fast', kind: 'number', defaultNumber: 12, positiveInt: true },
      { name: 'slow', label: 'Slow', kind: 'number', defaultNumber: 26, positiveInt: true },
    ],
  },
  {
    name: 'ATR',
    label: 'Average True Range',
    placement: 'separate',
    params: [
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 14, positiveInt: true },
    ],
  },
  {
    name: 'ADX',
    label: 'Average Directional Index',
    placement: 'separate',
    scaleMin: 0,
    scaleMax: 100,
    guides: [25],
    params: [
      { name: 'period', label: 'Length', kind: 'number', defaultNumber: 14, positiveInt: true },
    ],
  },
]

/* --------------------------------------------------------------- colours */

/**
 * Assigned in order as indicators are added. Chosen to stay apart from each
 * other on the dark background and away from the green and red of the candles
 * — a line the colour of an up candle disappears into it.
 */
export const LINE_COLORS = [
  '#e8b44c',
  '#5b9dd9',
  '#b07ad9',
  '#4ec9b0',
  '#e0864c',
  '#d96d9a',
  '#8fbf5f',
  '#6fa8d9',
]

export function nextColor(used: string[]): string {
  return LINE_COLORS.find((c) => !used.includes(c)) ?? LINE_COLORS[0]
}

/* ------------------------------------------------------------- requests */

async function request<T>(
  path: string,
  init: RequestInit = {},
  allowRetry = true,
): Promise<T> {
  const token = getAccessToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(path, { ...init, headers, credentials: 'include' })

  if (res.status === 401 && allowRetry && token) {
    try {
      await refresh()
    } catch {
      throw new Error('Your session has expired. Please sign in again.')
    }
    return request<T>(path, init, false)
  }

  if (!res.ok) {
    const text = await res.text()
    let message = `${res.status}: ${text || res.statusText}`
    try {
      const body = JSON.parse(text)
      if (typeof body?.message === 'string') message = body.message
    } catch {
      /* not JSON */
    }
    throw new Error(message)
  }

  return res.json() as Promise<T>
}

/**
 * The indicators the engine actually supports, generated from the registry.
 *
 * Asking the server rather than hardcoding means the picker can never offer
 * something the compiler does not know, and indicator #29 shows up on the
 * chart without a frontend change. Same argument as the registry itself: one
 * list, everybody reads it.
 */
export async function loadCatalog(): Promise<IndicatorSpec[]> {
  try {
    const specs = await request<IndicatorSpec[]>('/api/indicators/catalog')
    if (Array.isArray(specs) && specs.length > 0) return specs
  } catch {
    // Endpoint not built yet, or unreachable.
  }
  return FALLBACK_CATALOG
}

export async function computeIndicators(input: {
  symbol: string
  timeframe: string
  from?: string
  to?: string
  indicators: ActiveIndicator[]
}): Promise<ComputeResponse> {
  const { symbol, timeframe, from, to, indicators } = input

  return request<ComputeResponse>('/api/indicators', {
    method: 'POST',
    body: JSON.stringify({
      symbol,
      timeframe,
      from,
      to,
      specs: indicators.map((i) => ({
        id: i.id,
        name: i.spec.name,
        source: i.source,
        args: i.args,
      })),
    }),
  })
}

/* --------------------------------------------------------------- helpers */

export function numericParams(spec: IndicatorSpec): IndicatorParam[] {
  return spec.params.filter((p) => p.kind === 'number')
}

export function sourceParam(spec: IndicatorSpec): IndicatorParam | undefined {
  return spec.params.find((p) => p.kind === 'series')
}

export function defaultArgs(spec: IndicatorSpec): number[] {
  return numericParams(spec).map((p) => p.defaultNumber ?? 14)
}

export function defaultSource(spec: IndicatorSpec): PriceField | undefined {
  return sourceParam(spec)?.defaultSource ?? (sourceParam(spec) ? 'CLOSE' : undefined)
}

/**
 * "SMA(CLOSE,20)" — deliberately the same shape as the engine's own key, so
 * the legend, the manifest and a TSL strategy all call the line one thing.
 */
export function describe(
  spec: IndicatorSpec,
  source: PriceField | undefined,
  args: number[],
): string {
  const parts = [...(source ? [source] : []), ...args.map(String)]
  return parts.length === 0 ? spec.name : `${spec.name}(${parts.join(',')})`
}

export function describeActive(a: ActiveIndicator): string {
  return describe(a.spec, a.source, a.args)
}

/** Adding the same indicator twice should be a no-op, not a hidden duplicate. */
export function idFor(
  spec: IndicatorSpec,
  source: PriceField | undefined,
  args: number[],
): string {
  return describe(spec, source, args)
}
/* --------------------------------------------- from a backtest manifest */

/** Mirrors UsedIndicatorDto in ../api — what a backtest reports it used. */
export type UsedIndicator = {
  name: string
  source: string | null
  args: number[]
  key: string
}

/**
 * Turns the manifest a backtest reports into lines for the result chart.
 *
 * The id is the engine's own cache key, so a line on the chart and an entry
 * in the manifest are literally the same string. Anything the catalog does
 * not recognise is skipped rather than guessed at — that can only happen if
 * the registry has an indicator with no presentation entry, which the
 * backend's drift test exists to prevent.
 */
export function fromUsedIndicators(
  used: UsedIndicator[],
  catalog: IndicatorSpec[],
): ActiveIndicator[] {
  const out: ActiveIndicator[] = []
  for (const u of used) {
    const spec = catalog.find((s) => s.name === u.name)
    if (!spec) continue
    out.push({
      id: u.key,
      spec,
      source: (u.source ?? undefined) as PriceField | undefined,
      args: u.args,
      color: nextColor(out.map((a) => a.color)),
      visible: true,
    })
  }
  return out
}