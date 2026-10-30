// The choices the blocks offer, in one place: the block definitions build
// their dropdowns from these, and turning code into blocks (fromTree.ts)
// checks the code against them.

/** The coins TSB has candles for (backend V2__seed_symbols.sql). */
export const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT']

/** A strategy's own timeframe: the ones TSB keeps candles for
 *  (backend IngestionScheduler). The compiler also accepts 1m and 1d,
 *  but there would be no candles to run them on. */
export const TIMEFRAMES = ['5m', '15m', '1h', '4h']

/** Bigger timeframes for "… on the 4h chart" (Timeframe.ON_TIMEFRAMES in
 *  the compiler; the engine builds them from the strategy's own candles). */
export const ON_TIMEFRAMES = ['15m', '30m', '1h', '2h', '4h', '6h', '8h', '12h', '1d', '1w']

/** The fee when a strategy leaves it out (Analyzer.DEFAULT_FEE), in percent. */
export const DEFAULT_FEE_PERCENT = 0.1
