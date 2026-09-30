/**
 * TSL → everyday words, for people who don't read code.
 *
 *   SMA(CLOSE, 50)         → the 50-candle average
 *   RSI(14) < 30           → RSI is below 30
 *   CROSSOVER(EMA(CLOSE, 9), EMA(CLOSE, 21))
 *                          → 9-candle EMA crosses above 21-candle EMA
 *
 * Only for display. The exact TSL is always shown beside it, so nothing is
 * hidden, and anything we don't recognise is left as written.
 */

const INDICATOR: Record<string, (args: string[]) => string> = {
  SMA: (a) => `${a[1]}-candle average${src(a[0])}`,
  EMA: (a) => `${a[1]}-candle EMA${src(a[0])}`,
  WMA: (a) => `${a[1]}-candle WMA${src(a[0])}`,
  HMA: (a) => `${a[1]}-candle HMA${src(a[0])}`,
  RSI: () => 'RSI',
  ATR: () => 'volatility (ATR)',
  ADX: () => 'trend strength (ADX)',
  PLUS_DI: () => '+DI',
  MINUS_DI: () => '−DI',
  VWAP: () => 'VWAP',
  OBV: () => 'OBV',
  CCI: () => 'CCI',
  MFI: () => 'money flow (MFI)',
  WILLR: () => 'Williams %R',
  STOCH_K: () => 'Stochastic %K',
  STOCH_D: () => 'Stochastic %D',
  MACD_LINE: () => 'MACD',
  MACD_SIGNAL: () => 'MACD signal line',
  BB_UPPER: () => 'upper Bollinger band',
  BB_LOWER: () => 'lower Bollinger band',
  DONCHIAN_UPPER: (a) => `${a[0]}-candle high`,
  DONCHIAN_LOWER: (a) => `${a[0]}-candle low`,
  HIGHEST: (a) => `highest ${word(a[0])} of ${a[1]} candles`,
  LOWEST: (a) => `lowest ${word(a[0])} of ${a[1]} candles`,
  SUPERTREND: () => 'Supertrend',
  STDDEV: (a) => `${a[1]}-candle std. deviation`,
  ROC: (a) => `${a[1]}-candle rate of change`,
  MOM: (a) => `${a[1]}-candle momentum`,
  MAX: (a) => `the larger of ${expr(a[0])} and ${expr(a[1])}`,
  MIN: (a) => `the smaller of ${expr(a[0])} and ${expr(a[1])}`,
  ABS: (a) => `size of ${expr(a[0])}`,
}

const PRICE: Record<string, string> = {
  CLOSE: 'price',
  OPEN: 'open price',
  HIGH: 'high',
  LOW: 'low',
  VOLUME: 'volume',
}

const OP: Record<string, string> = {
  '<': 'is below',
  '>': 'is above',
  '<=': 'is at or below',
  '>=': 'is at or above',
  '==': 'equals',
  '!=': 'is not',
}

function word(s: string): string {
  return PRICE[s.trim()] ?? s.trim()
}

/** " of volume" for SMA(VOLUME, 20); nothing for the usual close. */
function src(s: string): string {
  const w = s.trim()
  return w === 'CLOSE' ? '' : ` of ${word(w)}`
}

/** Splits "a, b(c, d), e" at top-level commas. */
function splitArgs(s: string): string[] {
  const out: string[] = []
  let depth = 0
  let cur = ''
  for (const ch of s) {
    if (ch === '(') depth++
    if (ch === ')') depth--
    if (ch === ',' && depth === 0) {
      out.push(cur.trim())
      cur = ''
    } else cur += ch
  }
  if (cur.trim()) out.push(cur.trim())
  return out
}

/** A numeric expression in words, best effort. */
export function expr(raw: string): string {
  const s = raw.trim()
  if (/^-?\d+(\.\d+)?$/.test(s)) return s

  // X[3] → X 3 candles ago
  const lb = s.match(/^(.*)\[(\d+)\]$/)
  if (lb && balanced(lb[1])) {
    const n = Number(lb[2])
    return `${expr(lb[1])} ${n === 1 ? '1 candle ago' : `${n} candles ago`}`
  }

  const call = s.match(/^([A-Z_]+)\((.*)\)$/)
  if (call && balanced(call[2]) && INDICATOR[call[1]]) {
    return INDICATOR[call[1]](splitArgs(call[2]))
  }
  if (PRICE[s]) return PRICE[s]
  return s // a let name, or arithmetic — leave as written
}

function balanced(s: string): boolean {
  let d = 0
  for (const ch of s) {
    if (ch === '(') d++
    if (ch === ')') d--
    if (d < 0) return false
  }
  return d === 0
}

/** One comparison or crossing, as a sentence fragment. */
export function leaf(text: string, kind: string, op: string | null): string {
  if (kind === 'CROSSOVER' || kind === 'CROSSUNDER') {
    const m = text.match(/^CROSS(?:OVER|UNDER)\((.*)\)$/)
    const args = m ? splitArgs(m[1]) : []
    if (args.length === 2) {
      return `${say(args[0])} crosses ${
        kind === 'CROSSOVER' ? 'above' : 'below'
      } ${expr(args[1])}`
    }
    return text
  }
  if (op) {
    const i = text.indexOf(` ${op} `)
    if (i > 0) {
      const l = text.slice(0, i)
      const r = text.slice(i + op.length + 2)
      return `${say(l)} ${OP[op] ?? op} ${expr(r)}`
    }
  }
  return text
}

/** Words for a numeric side, capitalised only if we translated it —
 *  a let the user named "rsi" stays "rsi". */
export function say(raw: string): string {
  const e = expr(raw)
  return e === raw.trim() ? e : cap(e)
}

export function cap(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s
}

/** "BUY qty = 25% OF EQUITY" → "buy with 25% of your money". */
export function action(a: string): string {
  const m = a.match(/^(BUY|SELL) (.*)$/)
  if (m) {
    const verb = m[1] === 'BUY' ? 'buy' : 'sell'
    const size = m[2]
    if (size === 'ALL') return m[1] === 'BUY' ? 'buy with all your money' : 'sell everything'
    const pct = size.match(/^qty = ([\d.]+)% OF (EQUITY|POSITION)$/)
    if (pct) {
      return pct[2] === 'EQUITY'
        ? `${verb} with ${pct[1]}% of your money`
        : `${verb} ${pct[1]}% of what you hold`
    }
    return `${verb} ${size.replace(/^qty = /, '')}`
  }
  const set = a.match(/^SET (STOPLOSS|TAKEPROFIT|TRAILING) = (.*)$/)
  if (set) {
    const what = { STOPLOSS: 'stop-loss', TAKEPROFIT: 'take-profit', TRAILING: 'trailing stop' }[
      set[1] as 'STOPLOSS' | 'TAKEPROFIT' | 'TRAILING'
    ]
    return `set a ${set[2]} ${what}`
  }
  return a
}

/** Oscillators live on a fixed 0–100 scale; the gauge uses it. */
export function fixedScale(leftText: string): [number, number] | null {
  if (/^(RSI|STOCH_K|STOCH_D|MFI|ADX|PLUS_DI|MINUS_DI)\(/.test(leftText)) return [0, 100]
  if (/^rsi\b/i.test(leftText)) return [0, 100]
  if (/^WILLR\(/.test(leftText)) return [-100, 0]
  return null
}
