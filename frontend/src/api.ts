// Typed client for the TSB backend. The shapes mirror the Java DTOs —
// this file IS the frontend's copy of the contract, so a backend DTO
// change should be mirrored here deliberately, not discovered at runtime.

import { getAccessToken, refresh } from './auth/api'

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

/** One indicator the strategy used, as reported by the backtest.
 *  Only the identity travels — the chart fetches values from
 *  /api/indicators, which runs the same IndicatorBank over the same
 *  candles, so what it draws is what the run saw. */
export interface UsedIndicatorDto {
  name: string
  source: string | null
  args: number[]
  /** The engine's own cache key, e.g. "SMA(CLOSE,200)". */
  key: string
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
  indicators: UsedIndicatorDto[]
}

export interface RunResponse {
  ok: boolean
  runId?: number | null
  diagnostics: DiagnosticDto[]
  runError: string | null
  result: BacktestResultDto | null
}

export interface CompileResponse {
  ok: boolean
  diagnostics: DiagnosticDto[]
}

// ── Decision debugger (Module 3) and signal statistics (Module 4) ──────

/** The inputs that pin down one backtest exactly. Explaining a bar or
 *  measuring signals re-runs precisely this — same source, same bars. */
export interface RunContext {
  source: string
  from: string | null
  to: string | null
}

export type ConditionKind =
  | 'COMPARE'
  | 'CROSSOVER'
  | 'CROSSUNDER'
  | 'AND'
  | 'OR'
  | 'NOT'
  | 'LET'

export interface ConditionNodeDto {
  kind: ConditionKind
  text: string
  span: SpanDto
  passed: boolean
  unknown: boolean
  left: number | null
  right: number | null
  op: string | null
  previousLeft: number | null
  previousRight: number | null
  distance: number | null
  relativeDistance: number | null
  note: string | null
  children: ConditionNodeDto[]
}

export type DecisionOutcome =
  | 'WARMUP'
  | 'NO_ACTION'
  | 'FILLED'
  | 'IGNORED_ALREADY_LONG'
  | 'IGNORED_NOTHING_TO_SELL'
  | 'REJECTED_TOO_SMALL'
  | 'NOT_FILLED_LAST_BAR'
  | 'SETTING_APPLIED'

export interface ClosestChangeDto {
  text: string
  span: SpanDto
  distance: number
  relativeDistance: number | null
  note: string | null
}

export interface StatementExplanationDto {
  ruleIndex: number
  ruleName: string
  statementIndex: number
  span: SpanDto
  condition: ConditionNodeDto
  taken: 'THEN' | 'ELSE' | 'NONE'
  thenAction: string
  elseAction: string | null
  outcome: DecisionOutcome
  fillPrice: number | null
  fillTimeMillis: number | null
  closestChange: ClosestChangeDto | null
  sentence: string
}

export interface BarExplanationDto {
  bar: number
  openTimeMillis: number
  open: number
  high: number
  low: number
  close: number
  warmupBars: number
  warmup: boolean
  lastBar: boolean
  inPosition: boolean | null
  statements: StatementExplanationDto[]
  summary: string
}

export interface ExplainResponse {
  ok: boolean
  diagnostics: DiagnosticDto[]
  runError: string | null
  explanation: BarExplanationDto | null
}

export interface LeafStatsDto {
  text: string
  span: SpanDto
  trueBars: number
  truePct: number
  unknownBars: number
  soleBlockerBars: number
}

export interface StatementStatsDto {
  ruleIndex: number
  ruleName: string
  statementIndex: number
  span: SpanDto
  condition: string
  thenAction: string
  trueBars: number
  truePct: number
  unknownBars: number
  actionBars: number
  outcomes: Partial<Record<DecisionOutcome, number>>
  nearMisses: number
  parts: LeafStatsDto[]
  bottleneck: string | null
  trueByHour: number[]
  barsByHour: number[]
  trueByWeekday: number[]
  barsByWeekday: number[]
  sentence: string
}

export interface SignalStatisticsDto {
  barsEvaluated: number
  nearMissThreshold: number
  intraday: boolean
  statements: StatementStatsDto[]
}

export interface ConfluenceFeatureDto {
  id: string
  group: string
  label: string
  tsl: string | null
}

export interface ConfluenceFindingDto {
  feature: ConfluenceFeatureDto
  tradesWith: number
  winsWith: number
  winRateWith: number
  avgReturnWith: number
  tradesWithout: number
  winsWithout: number
  winRateWithout: number
  avgReturnWithout: number
  tradesUnknown: number
  tested: boolean
  pValue: number | null
  adjustedP: number | null
  significant: boolean
  suggestion: string | null
  sentence: string
}

export interface ConfluenceReportDto {
  status: 'OK' | 'TOO_FEW_TRADES'
  message: string
  trades: number
  wins: number
  winRate: number
  testsRun: number
  threshold: number
  findings: ConfluenceFindingDto[]
}

export interface SignalResponse {
  ok: boolean
  diagnostics: DiagnosticDto[]
  runError: string | null
  statistics: SignalStatisticsDto | null
  confluence: ConfluenceReportDto | null
}

/**
 * Several requests can be in flight at once, so several can hit an expired
 * token at once. Each must NOT refresh independently: the first would
 * rotate the refresh token and the rest would replay a spent one, which the
 * backend treats as theft and answers by revoking the whole session. One
 * shared refresh, however many callers ask for it.
 */
let refreshInFlight: Promise<unknown> | null = null

function refreshOnce(): Promise<unknown> {
  if (!refreshInFlight) {
    refreshInFlight = refresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

/**
 * Every backend call goes through here so three things are always true:
 * the access token is attached, the refresh cookie travels, and an expired
 * token is renewed once and the request replayed rather than surfacing as
 * a failure the user sees.
 *
 * That last part matters more than it looks. Access tokens last fifteen
 * minutes, so without it the app works fine and then appears to break at
 * random, mid-backtest, with no pattern to reproduce.
 */
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

  const res = await fetch(path, {
    ...init,
    headers,
    // Carries the httpOnly refresh cookie. Harmless on the public
    // endpoints, essential on /api/auth/refresh.
    credentials: 'include',
  })

  if (res.status === 401 && allowRetry && token) {
    try {
      await refreshOnce()
    } catch {
      throw new Error('Your session has expired. Please sign in again.')
    }
    return request<T>(path, init, false)
  }

  if (!res.ok) {
    const text = await res.text()
    // The backend reports errors as {code, message}. Prefer that message
    // over a bare status line — it was written for a person to read.
    let message = `${res.status}: ${text || res.statusText}`
    try {
      const body = JSON.parse(text)
      if (typeof body?.message === 'string') message = body.message
    } catch {
      /* not JSON — keep the status line */
    }
    throw new Error(message)
  }

  return res.json() as Promise<T>
}

export const api = {
  compile: (source: string): Promise<CompileResponse> =>
    request<CompileResponse>('/api/compile', {
      method: 'POST',
      body: JSON.stringify({ source }),
    }),

  listStrategies: (): Promise<StrategyDto[]> =>
    request<StrategyDto[]>('/api/strategies'),

  createStrategy: (name: string, source: string): Promise<SaveResponse> =>
    request<SaveResponse>('/api/strategies', {
      method: 'POST',
      body: JSON.stringify({ name, source }),
    }),

  addVersion: (strategyId: number, source: string): Promise<SaveResponse> =>
    request<SaveResponse>(`/api/strategies/${strategyId}/versions`, {
      method: 'POST',
      body: JSON.stringify({ source }),
    }),

  getVersionSource: (strategyId: number, version: number): Promise<string> =>
    request<{ source: string }>(
      `/api/strategies/${strategyId}/versions/${version}`,
    ).then((v) => v.source),

  runVersion: (strategyId: number, version: number): Promise<RunResponse> =>
    request<RunResponse>(
      `/api/strategies/${strategyId}/versions/${version}/run`,
      { method: 'POST', body: JSON.stringify({}) },
    ),

  runAdhocBacktest: (source: string): Promise<RunResponse> =>
    request<RunResponse>('/api/backtest', {
      method: 'POST',
      body: JSON.stringify({ source }),
    }),

  /** Why the strategy did what it did at one bar (epoch-ms open time). */
  explainBar: (run: RunContext, timeMillis: number): Promise<ExplainResponse> =>
    request<ExplainResponse>('/api/debug/explain', {
      method: 'POST',
      body: JSON.stringify({ ...run, time: timeMillis }),
    }),

  /** Signal statistics and confluence for the run. */
  signals: (run: RunContext, nearMiss?: number): Promise<SignalResponse> =>
    request<SignalResponse>('/api/signals', {
      method: 'POST',
      body: JSON.stringify({ ...run, nearMiss }),
    }),

  // Public endpoint — works signed out, which is what the landing chart needs.
  getCandles: (
    symbol: string,
    timeframe: string,
    from: string,
    to: string,
  ): Promise<CandleColumns> =>
    request<CandleColumns>(
      `/api/candles?symbol=${symbol}&timeframe=${timeframe}` +
        `&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ),
}