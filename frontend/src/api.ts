// Typed client for the TSB backend. The shapes mirror the Java DTOs —
// this file IS the frontend's copy of the contract, so a backend DTO
// change should be mirrored here deliberately, not discovered at runtime.

import { getAccessToken, refreshShared, setAccessToken } from './auth/api'

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
  /** True when the range held more candles than one answer carries and
   *  only the newest came back. */
  truncated?: boolean
}

/** One indicator the strategy used, as reported by the backtest.
 *  Only the identity travels — the chart fetches values from
 *  /api/indicators, which runs the same IndicatorBank over the same
 *  candles, so what it draws is what the run saw. */
export interface UsedIndicatorDto {
  name: string
  source: string | null
  args: number[]
  /** The engine's own cache key, e.g. "SMA(CLOSE,200)" or "EMA(CLOSE,50)@4h". */
  key: string
  /** null on the strategy's own candles; "4h" for EMA(CLOSE, 50) ON 4h. */
  timeframe?: string | null
  /** Lookback counted in those bigger candles (CLOSE[1] ON 1d = 1). */
  offset?: number
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
  /** Buying at the first tradable candle and holding, same fee. The bar to beat. */
  buyHoldReturnPct: number | null
  /** Fee per fill as a fraction (0.001 = 0.1%). */
  feeRate: number
  /** TradAO's measures (see MeasuresDto); absent from older servers. */
  measures?: MeasuresDto
  /** The account over time, for the cash-usage chart. */
  book?: BookPointDto[]
  /** The run by period, top down; null when nothing ran after the warm-up,
   *  absent from older servers. */
  report?: PeriodReportDto | null
}

/**
 * The run cut into calendar periods (UTC, by candle open time): the whole
 * run, its years, months, weeks (Monday to Sunday, cut at the month's end)
 * and days. Periods chain exactly: a period starts at the equity of the
 * close before it and ends at its last close, open positions included.
 */
export interface PeriodReportDto {
  timeZone: string
  all: PeriodNodeDto
}

export type PeriodLevel = 'ALL' | 'YEAR' | 'MONTH' | 'WEEK' | 'DAY'

export interface PeriodNodeDto {
  level: PeriodLevel
  /** "all", "2025", "2025-03", or the first day ("2025-03-03") of a week or day. */
  key: string
  /** First and last UTC day with a candle, ISO dates. */
  firstDay: string
  lastDay: string
  /** Open time of the first candle and close time of the last, epoch ms. */
  start: number
  end: number
  stats: PeriodStatsDto
  /** When the money was made: the run, years and months only. */
  breakdown: PeriodBreakdownDto | null
  /** Years of the run, months of a year, weeks of a month, days of a week. */
  children: PeriodNodeDto[]
}

export interface PeriodStatsDto {
  /** UTC days with at least one candle. */
  days: number
  candles: number
  startEquity: number
  endEquity: number
  /** null when there was nothing to start from. */
  returnPct: number | null
  /** endEquity - startEquity, closed trades or not. */
  pnl: number
  /** Buy & hold over the same candles; null if it never got to buy. */
  buyHoldPct: number | null
  /** Worst fall from a high inside the period, counting from its start. */
  maxDrawdownPct: number
  tradesOpened: number
  /** Trades are counted on the day they close. */
  tradesClosed: number
  wins: number
  winRatePct: number | null
  realizedPnl: number
  /** Fees paid by the fills in the period. */
  fees: number
  timeInMarketPct: number
  greenDays: number
  redDays: number
}

export interface PeriodBreakdownDto {
  /** Monday first, seven rows. */
  weekdays: WeekdayRowDto[]
  /** 24 rows, hours UTC; null when a candle is a day or longer. */
  hours: HourRowDto[] | null
  /** January first, twelve rows; the whole run only. */
  monthsOfYear: MonthRowDto[] | null
}

export interface WeekdayRowDto {
  /** 1 = Monday … 7 = Sunday. */
  weekday: number
  days: number
  avgReturnPct: number | null
  greenDays: number
  pnl: number
  tradesEntered: number
  entryWins: number
}

export interface HourRowDto {
  /** 0-23 UTC; a candle counts at the hour it opens. */
  hour: number
  /** Hours of that kind (one per day) that had candles. */
  slots: number
  avgReturnPct: number | null
  greenSlots: number
  pnl: number
  tradesEntered: number
  entryWins: number
}

export interface MonthRowDto {
  /** 1 = January … 12 = December. */
  month: number
  months: number
  avgReturnPct: number | null
  greenMonths: number
  pnl: number
}

/**
 * The performance measures of TradAO (Tsang et al., IEEE VIS 2020), in
 * its six categories. Sharpe, Sortino (consistency) and the win rate
 * (prediction) are in MetricsDto / the result itself. Null = undefined
 * for this run (e.g. fewer than two days).
 */
export interface MeasuresDto {
  /** Profitability: mean daily return x 365, percent. */
  yieldPct: number | null
  maxDrawdownPct: number | null
  /** Recovery: longest and average time below a previous peak, days. */
  maxDrawdownDays: number | null
  avgDrawdownDays: number | null
  /** Robustness: 1-day 99% value at risk (a loss, percent) and annualized volatility. */
  var99Pct: number | null
  volatilityPct: number | null
  /** Activeness. */
  tradesPer30Days: number | null
  timeInMarketPct: number | null
  /** Daily returns measured. */
  days: number
}

/** One point of the account over time. */
export interface BookPointDto {
  t: number
  /** Net asset value: cash + coins x close. */
  equity: number
  /** Cash not in the coin. */
  cash: number
  /** What the coins held are worth at the close. */
  positionValue: number
  /** Buy & hold over the same candles; null if it never got to buy. */
  benchmark: number | null
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
  /** Parts that read a bigger timeframe: which finished candle the value came from. */
  timeframeNote?: string | null
}

export type DecisionOutcome =
  | 'WARMUP'
  | 'NO_ACTION'
  | 'FILLED'
  | 'IGNORED_ALREADY_LONG'
  | 'IGNORED_NOTHING_TO_SELL'
  | 'REJECTED_TOO_SMALL'
  | 'REJECTED_NOT_ENOUGH_CASH'
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
  /** The candle's volume, in the coin. */
  volume?: number
  /** Close against the previous close, percent (null on the first candle). */
  changePct?: number | null
  /** The account at this close. */
  book?: BookDto
  /** Every buy and sell during this candle, in order. */
  fills?: FillDto[]
}

/** The account at one candle's close (TradAO's cash usage, at a point). */
export interface BookDto {
  initialCapital: number
  equity: number
  cash: number
  qty: number
  positionValue: number
  /** Share of the net asset value in the coin, percent. */
  cashUsagePct: number
  entryPrice: number | null
  entryTimeMillis: number | null
  unrealizedPnl: number | null
  unrealizedPct: number | null
  peakEquity: number
  drawdownPct: number
  returnPct: number
  buyHoldPct: number | null
  realizedPnl: number
  closedTrades: number
  /** Protective levels from the next candle on (null = not set). */
  stopPrice: number | null
  takeProfitPrice: number | null
  trailingPrice: number | null
}

export interface FillDto {
  side: 'BUY' | 'SELL'
  /** OPEN: at the open (an order from the last close, or a stop the price
   *  gapped through); INSIDE: a stop or target hit during the candle;
   *  CLOSE: the data ended and the position was valued out at the close. */
  when: 'OPEN' | 'INSIDE' | 'CLOSE'
  price: number
  qty: number
  fee: number
  reason: 'SIGNAL' | 'STOPLOSS' | 'TAKEPROFIT' | 'TRAILING' | 'END_OF_DATA'
  /** SELL only: profit of what was sold, fees included. */
  pnl: number | null
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

// ── Paper trading ─────────────────────────────────────────────────────

export type PaperSide = 'BUY' | 'SELL'
export type PaperType = 'MARKET' | 'LIMIT'

export interface PaperPositionDto {
  symbol: string
  baseAsset: string
  side: 'LONG' | 'SHORT'
  qty: number
  /** Average entry price, fees not included. */
  avgPrice: number
  /** Fees paid to open it, charged when it closes. */
  entryFees: number
  price: number | null
  value: number
  unrealizedPnl: number
  unrealizedPct: number
  priceLive: boolean
}

export interface PaperOrderDto {
  id: number
  symbol: string
  side: PaperSide
  type: PaperType
  qty: number
  limitPrice: number | null
  status: 'OPEN' | 'FILLED' | 'CANCELLED' | 'REJECTED'
  fillPrice: number | null
  fee: number | null
  realizedPnl: number | null
  rejectReason: string | null
  createdAt: string
  filledAt: string | null
}

export interface PaperAccountDto {
  startingCash: number
  cash: number
  equity: number
  /** What more can be opened now: 1× leverage. */
  buyingPower: number
  /** Promised to open limit orders. */
  reserved: number
  realizedPnl: number
  unrealizedPnl: number
  totalPnl: number
  returnPct: number
  feesPaid: number
  resets: number
  resetAt: string
  positions: PaperPositionDto[]
  openOrders: PaperOrderDto[]
  history: PaperOrderDto[]
  prices: Record<string, number>
  serverTime: string
}

export interface PaperOrderRequest {
  symbol: string
  side: PaperSide
  type: PaperType
  qty?: number
  quoteAmount?: number
  limitPrice?: number
}

// ── Competitions ──────────────────────────────────────────────────────

export type CompetitionStatus = 'OPEN' | 'RUNNING' | 'FINISHED' | 'CANCELLED'
export type EntryStatus = 'ACTIVE' | 'ELIMINATED' | 'PASSED' | 'FAILED'

export interface CompetitionRulesDto {
  startingCapital: number
  feePercent: number
  profitTargetPct: number
  maxDrawdownPct: number
  dailyLossLimitPct: number
  maxTradesPerDay: number
  minTradingDays: number
}

export interface CompetitionSummaryDto {
  id: number
  name: string
  symbol: string
  timeframe: string
  status: CompetitionStatus
  startsAt: string
  endsAt: string
  lastCandle: string | null
  entryCount: number
  myEntryCount: number
  maxEntriesPerUser: number
  rules: CompetitionRulesDto
}

export interface CompetitionListDto {
  canCreate: boolean
  serverTime: string
  competitions: CompetitionSummaryDto[]
}

export interface EntryViewDto {
  id: number
  rank: number | null
  username: string
  strategy: string
  mine: boolean
  status: EntryStatus
  statusReason: string | null
  statusCandle: string | null
  submittedAt: string
  sourceHash: string
  equity: number
  returnPct: number
  profitTargetPct: number
  drawdownPct: number
  maxDrawdownSeen: number
  maxDrawdownPct: number
  dailyLossPct: number
  dailyLossLimitPct: number
  tradesToday: number
  maxTradesPerDay: number
  tradingDays: number
  minTradingDays: number
  halted: boolean
  haltReason: string | null
  inTrade: boolean
  qty: number
  entryPrice: number
  tradeCount: number
  lastCandle: string | null
  /** The return if the open position were sold now, after the exit fee —
   *  what the profit target is judged on at the end. */
  returnAfterExitFeePct: number
}

export interface CompetitionDetailDto {
  competition: CompetitionSummaryDto
  description: string
  isAdmin: boolean
  /** The creator or an admin, while it is open or running. */
  canCancel: boolean
  canEnter: boolean
  serverTime: string
  myEntries: EntryViewDto[]
}

export interface TraderViewDto {
  rank: number
  username: string
  entries: number
  passed: number
  eliminated: number
  cumulativePnl: number
  cumulativeReturnPct: number
  mine: boolean
}

export interface LeaderboardDto {
  status: CompetitionStatus
  lastCandle: string | null
  serverTime: string
  entries: EntryViewDto[]
  traders: TraderViewDto[]
}

export interface CompetitionTradeDto {
  entryTime: string
  entryPrice: number
  qty: number
  exitTime: string | null
  exitPrice: number | null
  fees: number | null
  pnl: number | null
  exitReason: string | null
}

export interface CreateCompetitionRequest {
  name: string
  description: string
  symbol: string
  timeframe: string
  startingCapital: number
  feePercent: number
  profitTargetPct: number
  maxDrawdownPct: number
  dailyLossLimitPct: number
  maxTradesPerDay: number
  minTradingDays: number
  maxEntriesPerUser: number
  startsAt: string | null
  durationHours: number
}

// ── Forum ─────────────────────────────────────────────────────────────

export type ForumCategory = 'STRATEGIES' | 'MARKET' | 'HELP' | 'COMPETITIONS'

export interface ForumSummaryDto {
  id: number
  author: string
  category: ForumCategory
  title: string
  excerpt: string
  createdAt: string
  likes: number
  comments: number
  liked: boolean
  mine: boolean
  thumbnail: string | null
  imageCount: number
  strategyName: string | null
  strategyReturnPct: number | null
  hidden: boolean
}

export interface ForumListDto {
  posts: ForumSummaryDto[]
  hasMore: boolean
  signedIn: boolean
  moderator: boolean
}

export interface ForumImageDto {
  id: number
  url: string
  width: number
  height: number
}

export interface SharedStrategyDto {
  name: string
  version: number
  symbol: string
  timeframe: string
  source: string | null
  results: {
    returnPct?: number
    maxDrawdownPct?: number
    winRate?: number
    trades?: number
    sharpe?: number | null
    profitFactor?: number | null
    from?: string
    to?: string
    curve?: { t: number; equity: number }[]
    error?: string
  } | null
}

export interface ForumCommentDto {
  id: number
  author: string
  body: string
  createdAt: string
  mine: boolean
}

export interface ForumPostDto {
  post: ForumSummaryDto
  body: string
  images: ForumImageDto[]
  strategy: SharedStrategyDto | null
  comments: ForumCommentDto[]
  reportedByMe: boolean
  moderator: boolean
  hidden: boolean
  hiddenReason: string | null
}

export interface NewPost {
  category: ForumCategory
  title: string
  body: string
  strategyId?: number
  versionNumber?: number
  images: Blob[]
}

/**
 * Several requests can be in flight at once, so several can hit an expired
 * token at once. Each must NOT refresh independently: the first would
 * rotate the refresh token and the rest would replay a spent one, which the
 * backend treats as theft. One shared refresh — the same one the sign-in
 * code uses, across tabs too (see refreshShared).
 */
const refreshOnce = refreshShared

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
/**
 * A file (a picture) fetched with the sign-in token, as a URL the page can
 * show. For the few things an <img src> cannot fetch itself because they
 * are only for some people — a hidden post's pictures, seen by its author
 * and the moderators. Revoke the URL when done with it.
 */
export async function authorizedObjectUrl(path: string, allowRetry = true): Promise<string> {
  const token = getAccessToken()
  const res = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    credentials: 'include',
  })
  if (res.status === 401 && allowRetry && token) {
    await refreshOnce()
    return authorizedObjectUrl(path, false)
  }
  if (!res.ok) throw new Error(`${res.status}: could not load ${path}`)
  return URL.createObjectURL(await res.blob())
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  allowRetry = true,
): Promise<T> {
  const token = getAccessToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  // FormData (picture uploads) sets its own multipart Content-Type with the
  // boundary; labelling it JSON would break the upload.
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
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
      // Signed out for real: drop the token so the whole app shows it,
      // not just this one request.
      setAccessToken(null)
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

  /** Same as above over a chosen range (ISO instants; null = open-ended). */
  runBacktestRange: (source: string, from: string | null, to: string | null): Promise<RunResponse> =>
    request<RunResponse>('/api/backtest', {
      method: 'POST',
      body: JSON.stringify({ source, from, to }),
    }),

  /** Why the strategy did what it did at one bar (epoch-ms open time). */
  explainBar: (run: RunContext, timeMillis: number, signal?: AbortSignal): Promise<ExplainResponse> =>
    request<ExplainResponse>('/api/debug/explain', {
      method: 'POST',
      body: JSON.stringify({ ...run, time: timeMillis }),
      signal,
    }),

  /** Signal statistics and confluence for the run. */
  signals: (run: RunContext, nearMiss?: number): Promise<SignalResponse> =>
    request<SignalResponse>('/api/signals', {
      method: 'POST',
      body: JSON.stringify({ ...run, nearMiss }),
    }),

  // ── Paper trading ──
  paperAccount: (): Promise<PaperAccountDto> =>
    request<PaperAccountDto>('/api/paper/account'),

  paperOrder: (
    body: PaperOrderRequest,
  ): Promise<{ order: PaperOrderDto; account: PaperAccountDto }> =>
    request('/api/paper/orders', { method: 'POST', body: JSON.stringify(body) }),

  paperClose: (
    symbol: string,
  ): Promise<{ order: PaperOrderDto; account: PaperAccountDto }> =>
    request(`/api/paper/positions/${symbol}/close`, { method: 'POST' }),

  paperCancel: (id: number): Promise<PaperAccountDto> =>
    request<PaperAccountDto>(`/api/paper/orders/${id}/cancel`, { method: 'POST' }),

  paperReset: (): Promise<PaperAccountDto> =>
    request<PaperAccountDto>('/api/paper/reset', { method: 'POST' }),

  // ── Competitions ──
  competitions: (): Promise<CompetitionListDto> =>
    request<CompetitionListDto>('/api/competitions'),

  competition: (id: number): Promise<CompetitionDetailDto> =>
    request<CompetitionDetailDto>(`/api/competitions/${id}`),

  createCompetition: (body: CreateCompetitionRequest): Promise<CompetitionSummaryDto> =>
    request<CompetitionSummaryDto>('/api/competitions', {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  cancelCompetition: (id: number): Promise<CompetitionSummaryDto> =>
    request<CompetitionSummaryDto>(`/api/competitions/${id}/cancel`, { method: 'POST' }),

  enterCompetition: (
    id: number,
    strategyId: number,
    versionNumber: number,
  ): Promise<EntryViewDto> =>
    request<EntryViewDto>(`/api/competitions/${id}/entries`, {
      method: 'POST',
      body: JSON.stringify({ strategyId, versionNumber }),
    }),

  leaderboard: (id: number): Promise<LeaderboardDto> =>
    request<LeaderboardDto>(`/api/competitions/${id}/leaderboard`),

  entryTrades: (id: number, entryId: number): Promise<CompetitionTradeDto[]> =>
    request<CompetitionTradeDto[]>(`/api/competitions/${id}/entries/${entryId}/trades`),

  // ── Forum ──
  forumPosts: (p: { category?: string; q?: string; sort?: 'new' | 'top'; page?: number }): Promise<ForumListDto> => {
    const qs = new URLSearchParams()
    if (p.category) qs.set('category', p.category)
    if (p.q) qs.set('q', p.q)
    if (p.sort) qs.set('sort', p.sort)
    if (p.page) qs.set('page', String(p.page))
    return request<ForumListDto>(`/api/forum/posts?${qs}`)
  },

  forumPost: (id: number): Promise<ForumPostDto> =>
    request<ForumPostDto>(`/api/forum/posts/${id}`),

  forumCreate: (p: NewPost): Promise<{ id: number }> => {
    const form = new FormData()
    form.set('category', p.category)
    form.set('title', p.title)
    form.set('body', p.body)
    if (p.strategyId != null && p.versionNumber != null) {
      form.set('strategyId', String(p.strategyId))
      form.set('versionNumber', String(p.versionNumber))
    }
    p.images.forEach((img, i) => form.append('images', img, `image-${i}`))
    return request<{ id: number }>('/api/forum/posts', { method: 'POST', body: form })
  },

  forumDelete: (id: number): Promise<{ ok: boolean }> =>
    request(`/api/forum/posts/${id}`, { method: 'DELETE' }),

  forumLike: (id: number): Promise<{ liked: boolean; likes: number }> =>
    request(`/api/forum/posts/${id}/like`, { method: 'POST' }),

  forumComment: (id: number, text: string): Promise<ForumCommentDto> =>
    request(`/api/forum/posts/${id}/comments`, { method: 'POST', body: JSON.stringify({ text }) }),

  forumDeleteComment: (id: number): Promise<{ ok: boolean }> =>
    request(`/api/forum/comments/${id}`, { method: 'DELETE' }),

  forumReport: (id: number, text: string): Promise<{ ok: boolean }> =>
    request(`/api/forum/posts/${id}/report`, { method: 'POST', body: JSON.stringify({ text }) }),

  forumHide: (id: number, hidden: boolean): Promise<{ hidden: boolean }> =>
    request(`/api/forum/posts/${id}/hide`, { method: 'POST', body: JSON.stringify({ hidden }) }),

  forumCopyStrategy: (id: number): Promise<{ ok: boolean; strategyId: number | null; versionNumber: number | null }> =>
    request(`/api/forum/posts/${id}/copy-strategy`, { method: 'POST' }),

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