/**
 * Starter strategies. Generated from the .tsl files in /strategies, which
 * are the originals: edit those and keep these in step.
 */
export type StarterKind = 'benchmark' | 'trend' | 'dip' | 'range'

export interface Starter {
  id: string
  name: string
  timeframe: string
  kind: StarterKind
  blurb: string
  source: string
}

export const STARTERS: Starter[] = [
  {
    id: 'buy-and-hold',
    name: 'Buy and Hold',
    timeframe: '4h',
    kind: 'benchmark',
    blurb: 'The yardstick. Buys once and holds. Everything else has to beat this.',
    source: `// Buy and Hold: buys on the first candle and never sells. This is the
// benchmark. A strategy is only "good" if it beats this, or matches it with
// much smaller drops along the way.
strategy "Buy and Hold" {
    symbol = BTCUSDT
    timeframe = 4h
    capital = 10000
    fee = 0.1%

    rule entry {
        IF CLOSE > 0 THEN BUY ALL
    }
}
`,
  },
  {
    id: 'trend-rider',
    name: 'Trend Rider',
    timeframe: '4h',
    kind: 'trend',
    blurb: 'Joins big uptrends when the 20 EMA crosses above the 50 EMA, only above the 200 average. 8% stop.',
    source: `// Trend Rider: join a trend when the fast average crosses above the slow one,
// but only while price is above the 200 average (the "big trend" filter).
// Idea: catch the big crypto moves, sit out the long bear phases.
strategy "Trend Rider" {
    symbol = BTCUSDT
    timeframe = 4h
    capital = 10000
    fee = 0.1%

    let fast = EMA(CLOSE, 20)
    let slow = EMA(CLOSE, 50)
    let trend = SMA(CLOSE, 200)

    rule entry {
        IF CROSSOVER(fast, slow) AND CLOSE > trend THEN BUY ALL
    }

    rule exit {
        IF CROSSUNDER(fast, slow) THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET STOPLOSS = 8%
    }
}
`,
  },
  {
    id: 'breakout',
    name: 'Breakout',
    timeframe: '4h',
    kind: 'trend',
    blurb: 'Buys a close above the 20-candle high when a trend is present; exits under the 10-candle low. 10% trailing stop.',
    source: `// Breakout (Turtle style): buy when price closes above the highest high of
// the last 20 candles; get out when it closes below the lowest low of the
// last 10. A trailing stop locks in part of a big run.
strategy "Breakout" {
    symbol = BTCUSDT
    timeframe = 4h
    capital = 10000
    fee = 0.1%

    let upper = DONCHIAN_UPPER(20)
    let lower = DONCHIAN_LOWER(10)

    rule entry {
        IF CLOSE > upper[1] AND ADX(14) > 20 THEN BUY ALL
    }

    rule exit {
        IF CLOSE < lower[1] THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET TRAILING = 10%
    }
}
`,
  },
  {
    id: 'supertrend',
    name: 'SuperTrend Follower',
    timeframe: '4h',
    kind: 'trend',
    blurb: 'Holds while price is above the SuperTrend line and the 100 EMA. 10% stop.',
    source: `// SuperTrend Follower: the SuperTrend line sits under price in an uptrend
// and above it in a downtrend. Hold while price is above the line.
strategy "SuperTrend Follower" {
    symbol = BTCUSDT
    timeframe = 4h
    capital = 10000
    fee = 0.1%

    let st = SUPERTREND(10, 3)

    rule entry {
        IF CROSSOVER(CLOSE, st) AND CLOSE > EMA(CLOSE, 100) THEN BUY ALL
    }

    rule exit {
        IF CROSSUNDER(CLOSE, st) THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET STOPLOSS = 10%
    }
}
`,
  },
  {
    id: 'rsi-dip',
    name: 'RSI Dip Buyer',
    timeframe: '1h',
    kind: 'dip',
    blurb: 'In an uptrend, buys sharp dips (RSI under 30) and sells the bounce. 4% stop, 6% target.',
    source: `// RSI Dip Buyer: in an uptrend, buy short, sharp dips (RSI below 30) and
// sell the bounce (RSI back above 55). Small stop and target because these
// are quick trades.
strategy "RSI Dip Buyer" {
    symbol = BTCUSDT
    timeframe = 1h
    capital = 10000
    fee = 0.1%

    let rsi = RSI(14)
    let trend = SMA(CLOSE, 200)

    rule entry {
        IF rsi < 30 AND CLOSE > trend THEN BUY ALL
    }

    rule exit {
        IF rsi > 55 THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET STOPLOSS = 4%
        IF CLOSE > 0 THEN SET TAKEPROFIT = 6%
    }
}
`,
  },
  {
    id: 'bollinger-bounce',
    name: 'Bollinger Bounce',
    timeframe: '1h',
    kind: 'range',
    blurb: 'In a sideways market, buys under the lower Bollinger band and sells at the middle. 3% stop.',
    source: `// Bollinger Bounce: when the market is going sideways (ADX below 25), buy
// a close under the lower band and sell when price gets back to the middle.
strategy "Bollinger Bounce" {
    symbol = BTCUSDT
    timeframe = 1h
    capital = 10000
    fee = 0.1%

    let lowerBand = BB_LOWER(CLOSE, 20, 2)
    let middle = SMA(CLOSE, 20)

    rule entry {
        IF CLOSE < lowerBand AND ADX(14) < 25 THEN BUY ALL
    }

    rule exit {
        IF CLOSE > middle THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET STOPLOSS = 3%
    }
}
`,
  },
  {
    id: 'macd-momentum',
    name: 'MACD Momentum',
    timeframe: '4h',
    kind: 'trend',
    blurb: 'Buys a MACD cross up when ADX shows a real trend and price is above the 100 EMA. 6% trailing stop.',
    source: `// MACD Momentum: buy when MACD crosses above its signal line while a real
// trend is present (ADX above 20) and price is above the 100 EMA.
strategy "MACD Momentum" {
    symbol = BTCUSDT
    timeframe = 4h
    capital = 10000
    fee = 0.1%

    let macd = MACD_LINE(12, 26)
    let signal = MACD_SIGNAL(12, 26, 9)

    rule entry {
        IF CROSSOVER(macd, signal) AND ADX(14) > 20 AND CLOSE > EMA(CLOSE, 100) THEN BUY ALL
    }

    rule exit {
        IF CROSSUNDER(macd, signal) THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET TRAILING = 6%
    }
}
`,
  },
  {
    id: '4h-trend-dip',
    name: '4h Trend Dip Buyer',
    timeframe: '1h',
    kind: 'dip',
    blurb: 'On 1h candles, buys RSI dips only while the 4h trend is up (above a rising 4h 50 EMA), using ON 4h. 3% stop.',
    source: `// 4h Trend Dip Buyer: on 1h candles, buy short dips (RSI under 35) only
// while the 4h trend points up: price above the 4h 50 EMA, and that EMA
// higher than on the 4h candle before. "ON 4h" reads the 4h chart, and only
// 4h candles that have finished count, never the one still forming.
strategy "4h Trend Dip Buyer" {
    symbol = BTCUSDT
    timeframe = 1h
    capital = 10000
    fee = 0.1%

    let trend = EMA(CLOSE, 50) ON 4h
    let trendBefore = EMA(CLOSE, 50)[1] ON 4h
    let rsi = RSI(14)

    rule entry {
        IF rsi < 35 AND CLOSE > trend AND trend > trendBefore THEN BUY ALL
    }

    rule exit {
        IF rsi > 60 OR CLOSE < trend THEN SELL ALL
    }

    rule protect {
        IF CLOSE > 0 THEN SET STOPLOSS = 3%
    }
}
`,
  },
]

/** Put a strategy on another coin: only the symbol line changes. */
export function withSymbol(source: string, symbol: string): string {
  return source.replace(/^(\s*symbol\s*=\s*)\w+/m, `$1${symbol}`)
}
