import { useMemo, useState } from 'react'
import { api, type CreateCompetitionRequest } from '../api'
import { when } from './util'

type StartPreset = 'now' | '10m' | '1h' | 'tomorrow' | 'custom'

const DURATIONS: { label: string; hours: number }[] = [
  { label: '1 hour', hours: 1 },
  { label: '6 hours', hours: 6 },
  { label: '1 day', hours: 24 },
  { label: '3 days', hours: 72 },
  { label: '1 week', hours: 168 },
  { label: '30 days', hours: 720 },
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
  const [f, setF] = useState({
    name: '',
    description: '',
    symbol: 'BTCUSDT',
    timeframe: '5m',
    startingCapital: 10000,
    feePercent: 0.1,
    profitTargetPct: 5,
    maxDrawdownPct: 10,
    dailyLossLimitPct: 5,
    maxTradesPerDay: 20,
    minTradingDays: 1,
    maxEntriesPerUser: 3,
    durationHours: 24,
  })
  const [start, setStart] = useState<StartPreset>('10m')
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((x) => ({ ...x, [k]: v }))

  const startsAt = useMemo(() => {
    const now = Date.now()
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
  }, [start, custom])

  const endsAt = startsAt ? new Date(startsAt.getTime() + f.durationHours * 3_600_000) : null

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      const body: CreateCompetitionRequest = { ...f, startsAt: startsAt ? startsAt.toISOString() : null }
      const c = await api.createCompetition(body)
      onDone(c.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const num = (k: keyof typeof f, label: string, help: string, step = 1, suffix = '') => (
    <label className="tsb-cmp__field">
      <span className="tsb-cmp__label">{label}</span>
      <span className="tsb-cmp__input">
        <input
          type="number"
          step={step}
          value={f[k] as number}
          onChange={(e) => set(k, Number(e.target.value) as never)}
        />
        {suffix && <em>{suffix}</em>}
      </span>
      <span className="tsb-cmp__help">{help}</span>
    </label>
  )

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
        {num('startingCapital', 'Starting capital', 'Every entry starts with exactly this much.', 100, 'USDT')}
        {num('profitTargetPct', 'Profit target', 'Return needed by the end to pass.', 0.5, '%')}
        {num('maxDrawdownPct', 'Max drawdown', 'Fall this far from its best equity and the entry is eliminated.', 0.5, '%')}
        {num('dailyLossLimitPct', 'Daily loss limit', 'Lose this much in one UTC day and it cannot open new trades until tomorrow. 0 = off.', 0.5, '%')}
        {num('maxTradesPerDay', 'Max trades per day', 'Opening and closing each count as one. 0 = no limit.', 1)}
        {num('minTradingDays', 'Min trading days', 'Days with at least one trade needed to pass. 0 = none.', 1)}
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
