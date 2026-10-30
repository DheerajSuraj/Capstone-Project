import { useEffect, useState, type ReactNode } from 'react'
import { api, type CreateCompetitionRequest } from '../api'
import { dayStartsHere, money, serverNow, when } from './util'

type StartPreset = 'now' | '10m' | '1h' | 'tomorrow' | 'custom'

const DURATIONS: { label: string; hours: number }[] = [
  { label: '1 hour', hours: 1 },
  { label: '6 hours', hours: 6 },
  { label: '1 day', hours: 24 },
  { label: '3 days', hours: 72 },
  { label: '1 week', hours: 168 },
  { label: '30 days', hours: 720 },
  { label: '60 days', hours: 1440 },
]

type Form = Omit<CreateCompetitionRequest, 'startsAt'>

type RuleFields = Pick<
  Form,
  | 'profitTargetPct'
  | 'passOnTarget'
  | 'maxDrawdownPct'
  | 'drawdownMode'
  | 'dailyLossLimitPct'
  | 'dailyLossAction'
  | 'maxTradesPerDay'
  | 'minTradingDays'
>

/**
 * Starting points. The two challenge steps follow the usual two-step
 * funding challenge: +10% then +5%, a fixed 10% max loss, a 5% daily loss
 * that ends the attempt, at least 4 trading days, and a pass the moment the
 * target is reached. "TSB classic" is what the form always started with.
 */
const PRESETS: { key: string; label: string; summary: string; rules: RuleFields; hours: number }[] = [
  {
    key: 'step1',
    label: 'Prop challenge · step 1',
    summary:
      'Like step 1 of a funding challenge: +10% to pass, 10% max loss from the start, a 5% daily loss ends it, 4 trading days, 30 days.',
    rules: {
      profitTargetPct: 10,
      passOnTarget: true,
      maxDrawdownPct: 10,
      drawdownMode: 'STATIC',
      dailyLossLimitPct: 5,
      dailyLossAction: 'FAIL',
      maxTradesPerDay: 0,
      minTradingDays: 4,
    },
    hours: 720,
  },
  {
    key: 'step2',
    label: 'Prop challenge · step 2',
    summary: 'Like step 2: the same rules with a +5% target, 60 days.',
    rules: {
      profitTargetPct: 5,
      passOnTarget: true,
      maxDrawdownPct: 10,
      drawdownMode: 'STATIC',
      dailyLossLimitPct: 5,
      dailyLossAction: 'FAIL',
      maxTradesPerDay: 0,
      minTradingDays: 4,
    },
    hours: 1440,
  },
  {
    key: 'classic',
    label: 'TSB classic',
    summary:
      'The original TSB rules: +5% by the end, 10% drawdown from the best equity, a 5% daily loss pauses the day, 20 trades a day, 1 day.',
    rules: {
      profitTargetPct: 5,
      passOnTarget: false,
      maxDrawdownPct: 10,
      drawdownMode: 'TRAILING',
      dailyLossLimitPct: 5,
      dailyLossAction: 'HALT',
      maxTradesPerDay: 20,
      minTradingDays: 1,
    },
    hours: 24,
  },
]

/**
 * Setup, as the admin sees it: every rule from the sequence diagram, with a
 * sentence saying what it means. The rule set is locked once saved.
 */
export default function CreateCompetition({
  onDone,
  onCancel,
}: {
  onDone: (id: number) => void
  onCancel: () => void
}) {
  const [f, setF] = useState<Form>({
    name: '',
    description: '',
    symbol: 'BTCUSDT',
    timeframe: '5m',
    startingCapital: 10000,
    feePercent: 0.1,
    maxEntriesPerUser: 3,
    durationHours: 24,
    ...PRESETS[2].rules,
  })
  const [start, setStart] = useState<StartPreset>('10m')
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const set = <K extends keyof Form>(k: K, v: Form[K]) => setF((x) => ({ ...x, [k]: v }))

  // A preset is "on" while every rule it sets still has its value, so
  // changing one field afterwards shows the rules are now your own.
  const preset = PRESETS.find((p) =>
    (Object.keys(p.rules) as (keyof RuleFields)[]).every((k) => f[k] === p.rules[k]),
  )
  const applyPreset = (p: (typeof PRESETS)[number]) =>
    setF((x) => ({ ...x, ...p.rules, durationHours: p.hours }))

  // "In 10 minutes" means ten minutes from when Create is pressed, not from
  // when the option was picked: a form left open for a while used to send a
  // start time already in the past, and the competition began at once.
  const [, tick] = useState(0)
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 30_000)
    return () => clearInterval(t)
  }, [])
  const startFor = (now: number): Date | null => {
    switch (start) {
      case 'now':
        return new Date(now)
      case '10m':
        return new Date(now + 10 * 60_000)
      case '1h':
        return new Date(now + 60 * 60_000)
      case 'tomorrow': {
        const d = new Date(now)
        d.setUTCHours(24, 0, 0, 0)
        return d
      }
      case 'custom':
        return custom ? new Date(custom) : null
    }
  }
  const startsAt = startFor(serverNow())

  const endsAt = startsAt ? new Date(startsAt.getTime() + f.durationHours * 3_600_000) : null

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      const at = startFor(serverNow())
      const body: CreateCompetitionRequest = { ...f, startsAt: at ? at.toISOString() : null }
      const c = await api.createCompetition(body)
      onDone(c.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  type NumKey = { [K in keyof Form]: Form[K] extends number ? K : never }[keyof Form]

  const num = (k: NumKey, label: string, help: ReactNode, step = 1, suffix = '', extra?: ReactNode) => {
    const input = (
      <>
        <span className="tsb-cmp__label">{label}</span>
        <span className="tsb-cmp__input">
          <input type="number" step={step} value={f[k]} onChange={(e) => set(k, Number(e.target.value))} />
          {suffix && <em>{suffix}</em>}
        </span>
      </>
    )
    // A choice sits under its number; buttons do not belong inside a label.
    return extra ? (
      <div className="tsb-cmp__field">
        <label className="tsb-cmp__field">{input}</label>
        {extra}
        <span className="tsb-cmp__help">{help}</span>
      </div>
    ) : (
      <label className="tsb-cmp__field">
        {input}
        <span className="tsb-cmp__help">{help}</span>
      </label>
    )
  }

  const choice = <K extends 'passOnTarget' | 'drawdownMode' | 'dailyLossAction'>(
    k: K,
    label: string,
    options: [Form[K], string][],
    disabled = false,
  ) => (
    <div className="tsb-cmp__presets tsb-cmp__choice" role="group" aria-label={label}>
      {options.map(([v, text]) => (
        <button
          key={String(v)}
          type="button"
          aria-pressed={f[k] === v}
          className={f[k] === v ? 'is-on' : ''}
          disabled={disabled}
          onClick={() => set(k, v)}
        >
          {text}
        </button>
      ))}
    </div>
  )

  const floor = money(f.startingCapital * (1 - f.maxDrawdownPct / 100), 0)
  const dayStart = `Days start at 00:00 UTC (${dayStartsHere()} your time).`

  return (
    <div className="tsb-cmp">
      <header className="tsb-cmp__top">
        <div>
          <button type="button" className="ghost" onClick={onCancel}>
            ← Competitions
          </button>
          <h2>Create a competition</h2>
          <p className="tsb-cmp__muted">
            Set the rules, then open it for entries. Once saved, the rules are locked — the
            database itself refuses any change, so nobody can move the goalposts after people join.
            Everyone trades virtual money on live prices.
          </p>
        </div>
      </header>

      <section className="tsb-cmp__form">
        <h3>1 · What and where</h3>
        <label className="tsb-cmp__field tsb-cmp__field--wide">
          <span className="tsb-cmp__label">Name</span>
          <input value={f.name} onChange={(e) => set('name', e.target.value)} placeholder="BTC Weekly Challenge" />
        </label>
        <label className="tsb-cmp__field tsb-cmp__field--wide">
          <span className="tsb-cmp__label">Description (optional)</span>
          <textarea rows={2} value={f.description} onChange={(e) => set('description', e.target.value)} />
        </label>
        <label className="tsb-cmp__field">
          <span className="tsb-cmp__label">Market</span>
          <select value={f.symbol} onChange={(e) => set('symbol', e.target.value)}>
            <option>BTCUSDT</option>
            <option>ETHUSDT</option>
            <option>SOLUSDT</option>
          </select>
          <span className="tsb-cmp__help">Entries must be strategies written for this symbol…</span>
        </label>
        <label className="tsb-cmp__field">
          <span className="tsb-cmp__label">Candles</span>
          <select value={f.timeframe} onChange={(e) => set('timeframe', e.target.value)}>
            <option>5m</option>
            <option>15m</option>
            <option>1h</option>
            <option>4h</option>
          </select>
          <span className="tsb-cmp__help">…and this timeframe. Each new candle is one step of the competition.</span>
        </label>

        <h3>2 · The rules</h3>
        <div className="tsb-cmp__field tsb-cmp__field--wide">
          <span className="tsb-cmp__label">Start from</span>
          <div className="tsb-cmp__presets">
            {PRESETS.map((p) => (
              <button
                key={p.key}
                type="button"
                className={preset === p ? 'is-on' : ''}
                onClick={() => applyPreset(p)}
              >
                {p.label}
              </button>
            ))}
          </div>
          <span className="tsb-cmp__help">
            {preset ? preset.summary : 'Your own mix of rules.'} Change anything below.
          </span>
        </div>
        {num('startingCapital', 'Starting capital', 'Every entry starts with exactly this much virtual money.', 100, 'USDT')}
        {num(
          'profitTargetPct',
          'Profit target',
          f.passOnTarget
            ? 'Reach it after fees, with the trading days done, and the entry passes right then. It stops trading.'
            : 'Return needed by the end, after fees, to pass.',
          0.5,
          '%',
          choice('passOnTarget', 'When the target counts', [
            [false, 'Judged at the end'],
            [true, 'Pass on reaching it'],
          ]),
        )}
        {num(
          'maxDrawdownPct',
          f.drawdownMode === 'STATIC' ? 'Max loss' : 'Max drawdown',
          f.drawdownMode === 'STATIC'
            ? `Equity below ${floor} USDT, at any moment with open trades counted, and the entry is out. The line never moves.`
            : 'Fall this far from the best equity reached and the entry is out. The line rises with every new high.',
          0.5,
          '%',
          choice('drawdownMode', 'Measured from', [
            ['TRAILING', 'From the best equity'],
            ['STATIC', 'From the start'],
          ]),
        )}
        {num(
          'dailyLossLimitPct',
          'Daily loss limit',
          f.dailyLossLimitPct <= 0
            ? `Off. ${dayStart}`
            : f.dailyLossAction === 'FAIL'
              ? `Lose this much from where the day started, open trades counted, and the entry is out. ${dayStart} 0 = off.`
              : `Lose this much from where the day started and it cannot open new trades until the next day; closing is allowed. ${dayStart} 0 = off.`,
          0.5,
          '%',
          choice(
            'dailyLossAction',
            'When it is hit',
            [
              ['HALT', 'Pause for the day'],
              ['FAIL', 'End the entry'],
            ],
            f.dailyLossLimitPct <= 0,
          ),
        )}
        {num('maxTradesPerDay', 'Max trades per day', 'Every buy and every sell counts, so a round trip is 2. At the cap it can still close. 0 = no limit.', 1)}
        {num('minTradingDays', 'Min trading days', 'Days (UTC) with at least one buy or sell, needed to pass. 0 = none.', 1)}
        {num('feePercent', 'Fee per fill', 'The same for everyone. Binance spot is 0.1%.', 0.01, '%')}
        {num('maxEntriesPerUser', 'Entries per trader', 'Each trader can submit up to this many strategies.', 1)}

        <h3>3 · When</h3>
        <div className="tsb-cmp__field tsb-cmp__field--wide">
          <span className="tsb-cmp__label">Entries close and trading starts</span>
          <div className="tsb-cmp__presets">
            {(
              [
                ['now', 'Right away'],
                ['10m', 'In 10 minutes'],
                ['1h', 'In 1 hour'],
                ['tomorrow', 'Midnight UTC'],
                ['custom', 'Pick a time'],
              ] as [StartPreset, string][]
            ).map(([k, label]) => (
              <button key={k} type="button" className={start === k ? 'is-on' : ''} onClick={() => setStart(k)}>
                {label}
              </button>
            ))}
          </div>
          {start === 'custom' && (
            <input type="datetime-local" value={custom} onChange={(e) => setCustom(e.target.value)} />
          )}
          <span className="tsb-cmp__help">
            Traders can join until then. "Right away" leaves no time to enter — useful only for testing.
          </span>
        </div>
        <div className="tsb-cmp__field tsb-cmp__field--wide">
          <span className="tsb-cmp__label">Duration</span>
          <div className="tsb-cmp__presets">
            {DURATIONS.map((d) => (
              <button key={d.hours} type="button" className={f.durationHours === d.hours ? 'is-on' : ''} onClick={() => set('durationHours', d.hours)}>
                {d.label}
              </button>
            ))}
          </div>
        </div>

        <div className="tsb-cmp__preview">
          {startsAt && endsAt ? (
            <>
              Starts <b>{when(startsAt.toISOString())}</b> · ends <b>{when(endsAt.toISOString())}</b>{' '}
              <span className="tsb-cmp__muted">(both snap to the next {f.timeframe} candle)</span>
            </>
          ) : (
            'Pick a start time'
          )}
        </div>

        {error && <p className="error-note">{error}</p>}
        <div className="tsb-cmp__actions">
          <button type="button" onClick={submit} disabled={busy || f.name.trim().length < 3 || !startsAt}>
            {busy ? 'Saving…' : 'Save rules and open for entries'}
          </button>
          <button type="button" className="ghost" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </section>
    </div>
  )
}
