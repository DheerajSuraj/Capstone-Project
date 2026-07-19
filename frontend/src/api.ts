// Typed client for the TSB backend. The shapes mirror the Java DTOs —
// this file IS the frontend's copy of the contract, so a backend DTO
// change should be mirrored here deliberately, not discovered at runtime.

export interface SpanDto {
  startLine: number
  startCol: number
  endLine: number
  endCol: number
  blockId: string | null
}

export interface DiagnosticDto {
  severity: 'ERROR' | 'WARNING'
  code: string
  message: string
  span: SpanDto
}

export interface StrategyDto {
  id: number
  name: string
  latestVersion: number
  symbol: string | null
  timeframe: string | null
  updatedAt: string
}

export interface SaveResponse {
  ok: boolean
  strategyId: number | null
  versionNumber: number | null
  diagnostics: DiagnosticDto[]
}

export interface TradeDto {
  entryTime: string
  exitTime: string
  qty: number
  entryPrice: number
  exitPrice: number
  pnl: number
  pnlPercent: number
  fees: number
  exitReason: string
}

export interface MetricsDto {
  sharpeRatio: number | null
  sortinoRatio: number | null
  profitFactor: number | null
  avgTradePnl: number | null
  bestTradePnl: number | null
  worstTradePnl: number | null
}

export interface CurvePoint {
  t: number
  equity: number
}

/** Columnar OHLCV from /api/candles — six parallel arrays, index i across
 *  all six is one bar (mirrors the backend's CandleSeries). */
export interface CandleColumns {
  t: number[]
  o: number[]
  h: number[]
  l: number[]
  c: number[]
  v: number[]
}

export interface BacktestResultDto {
  strategyName: string
  symbol: string
  timeframe: string
  initialCapital: number
  finalEquity: number
  totalReturnPct: number
  maxDrawdownPct: number
  winRate: number
  tradeCount: number
  totalFees: number
  warmupBars: number
  barsProcessed: number
  firstBarTime: string
  lastBarTime: string
  metrics: MetricsDto
  trades: TradeDto[]
  equityCurve: CurvePoint[]
}

export interface RunResponse {
  ok: boolean
  runId?: number | null
  diagnostics: DiagnosticDto[]
  runError: string | null
  result: BacktestResultDto | null
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${res.status}: ${text || res.statusText}`)
  }
  return res.json() as Promise<T>
}

export const api = {
  listStrategies: (): Promise<StrategyDto[]> =>
    fetch('/api/strategies').then((r) => json<StrategyDto[]>(r)),

  createStrategy: (name: string, source: string): Promise<SaveResponse> =>
    fetch('/api/strategies', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, source }),
    }).then((r) => json<SaveResponse>(r)),

  addVersion: (strategyId: number, source: string): Promise<SaveResponse> =>
    fetch(`/api/strategies/${strategyId}/versions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source }),
    }).then((r) => json<SaveResponse>(r)),

  getVersionSource: (strategyId: number, version: number): Promise<string> =>
    fetch(`/api/strategies/${strategyId}/versions/${version}`)
      .then((r) => json<{ source: string }>(r))
      .then((v) => v.source),

  runVersion: (strategyId: number, version: number): Promise<RunResponse> =>
    fetch(`/api/strategies/${strategyId}/versions/${version}/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    }).then((r) => json<RunResponse>(r)),

  runAdhocBacktest: (source: string): Promise<RunResponse> =>
    fetch('/api/backtest', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source }),
    }).then((r) => json<RunResponse>(r)),

  getCandles: (
    symbol: string,
    timeframe: string,
    from: string,
    to: string,
  ): Promise<CandleColumns> =>
    fetch(
      `/api/candles?symbol=${symbol}&timeframe=${timeframe}` +
        `&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ).then((r) => json<CandleColumns>(r)),
}