// Frontend mirror of the backend indicator Registry — the ONE table the
// 28 indicator blocks are generated from. Adding an indicator later:
// one row in the backend Registry, one row here, and the block exists.
//
// arg kinds: 'source' = a Number input socket (defaults to CLOSE via a
// shadow block); 'period' = an integer field; 'const' = a decimal field.

export interface IndicatorArg {
  kind: 'source' | 'period' | 'const'
  name: string
  default?: number
}

export interface IndicatorMeta {
  name: string
  args: IndicatorArg[]
  category: string
  tooltip: string
}

export const CATEGORIES: Record<string, { colour: number }> = {
  'Moving averages': { colour: 200 },
  Oscillators: { colour: 280 },
  Trend: { colour: 20 },
  'Channels & stats': { colour: 160 },
  Volume: { colour: 330 },
  Bands: { colour: 230 },
}

const src = (): IndicatorArg => ({ kind: 'source', name: 'SOURCE' })
const period = (name: string, def: number): IndicatorArg => ({
  kind: 'period',
  name,
  default: def,
})
const konst = (name: string, def: number): IndicatorArg => ({
  kind: 'const',
  name,
  default: def,
})

export const INDICATORS: IndicatorMeta[] = [
  // ── Moving averages ──
  { name: 'SMA', args: [src(), period('PERIOD', 20)], category: 'Moving averages', tooltip: 'Simple moving average' },
  { name: 'EMA', args: [src(), period('PERIOD', 20)], category: 'Moving averages', tooltip: 'Exponential moving average' },
  { name: 'WMA', args: [src(), period('PERIOD', 20)], category: 'Moving averages', tooltip: 'Linearly weighted moving average' },
  { name: 'HMA', args: [src(), period('PERIOD', 20)], category: 'Moving averages', tooltip: 'Hull moving average — fast and smooth' },
  { name: 'VWAP', args: [], category: 'Moving averages', tooltip: 'Volume-weighted average price, resets daily (UTC)' },

  // ── Oscillators ──
  { name: 'RSI', args: [period('PERIOD', 14)], category: 'Oscillators', tooltip: 'Relative Strength Index (0-100)' },
  { name: 'STOCH_K', args: [period('KPERIOD', 14)], category: 'Oscillators', tooltip: 'Stochastic %K' },
  { name: 'STOCH_D', args: [period('KPERIOD', 14), period('DSMOOTH', 3)], category: 'Oscillators', tooltip: 'Stochastic %D (smoothed %K)' },
  { name: 'WILLR', args: [period('PERIOD', 14)], category: 'Oscillators', tooltip: 'Williams %R (0 to -100)' },
  { name: 'CCI', args: [period('PERIOD', 20)], category: 'Oscillators', tooltip: 'Commodity Channel Index' },
  { name: 'MFI', args: [period('PERIOD', 14)], category: 'Oscillators', tooltip: 'Money Flow Index — volume-weighted RSI' },
  { name: 'ROC', args: [src(), period('PERIOD', 10)], category: 'Oscillators', tooltip: 'Rate of change, percent' },
  { name: 'MOM', args: [src(), period('PERIOD', 10)], category: 'Oscillators', tooltip: 'Momentum: value minus value N bars ago' },
  { name: 'MACD_LINE', args: [period('FAST', 12), period('SLOW', 26)], category: 'Oscillators', tooltip: 'MACD line: EMA(fast) - EMA(slow)' },
  { name: 'MACD_SIGNAL', args: [period('FAST', 12), period('SLOW', 26), period('SIGNAL', 9)], category: 'Oscillators', tooltip: 'MACD signal line' },

  // ── Trend ──
  { name: 'ADX', args: [period('PERIOD', 14)], category: 'Trend', tooltip: 'Trend strength (0-100); >25 = trending' },
  { name: 'PLUS_DI', args: [period('PERIOD', 14)], category: 'Trend', tooltip: '+DI: upward directional movement' },
  { name: 'MINUS_DI', args: [period('PERIOD', 14)], category: 'Trend', tooltip: '-DI: downward directional movement' },
  { name: 'SUPERTREND', args: [period('PERIOD', 10), konst('MULT', 3)], category: 'Trend', tooltip: 'SuperTrend line — below price in uptrends' },
  { name: 'ATR', args: [period('PERIOD', 14)], category: 'Trend', tooltip: 'Average True Range — volatility in price units' },

  // ── Channels & stats ──
  { name: 'DONCHIAN_UPPER', args: [period('PERIOD', 20)], category: 'Channels & stats', tooltip: 'Highest high of the last N bars' },
  { name: 'DONCHIAN_LOWER', args: [period('PERIOD', 20)], category: 'Channels & stats', tooltip: 'Lowest low of the last N bars' },
  { name: 'HIGHEST', args: [src(), period('PERIOD', 20)], category: 'Channels & stats', tooltip: 'Highest value of a series over N bars' },
  { name: 'LOWEST', args: [src(), period('PERIOD', 20)], category: 'Channels & stats', tooltip: 'Lowest value of a series over N bars' },
  { name: 'STDDEV', args: [src(), period('PERIOD', 20)], category: 'Channels & stats', tooltip: 'Standard deviation over N bars' },

  // ── Bands ──
  { name: 'BB_UPPER', args: [src(), period('PERIOD', 20), konst('K', 2)], category: 'Bands', tooltip: 'Bollinger upper band: SMA + K sigma' },
  { name: 'BB_LOWER', args: [src(), period('PERIOD', 20), konst('K', 2)], category: 'Bands', tooltip: 'Bollinger lower band: SMA - K sigma' },

  // ── Volume ──
  { name: 'OBV', args: [], category: 'Volume', tooltip: 'On-balance volume: cumulative signed volume' },
]