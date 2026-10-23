import { useEffect, useMemo, useRef, useState } from 'react'
import { api, type StrategyDto } from '../api'
import {
  COINS,
  MIN_TRADES,
  VERDICT_TEXT,
  checkOne,
  splitDate,
  timeframeOf,
  type CellResult,
  type Verdict,
  type Window,
} from '../lab/check'
import { STARTERS } from '../lab/starters'
import { pct, pnlClass } from '../paper/format'
import '../lab/lab.css'

/** One strategy in the check: a starter (source known) or a saved one. */
interface Candidate {
  key: string
  name: string
  blurb: string
  tag: string
  source: string | null
  saved?: StrategyDto
}

interface Row {
  candidate: Candidate
  source: string
  cells: CellResult[]
}

const STARTER_CANDIDATES: Candidate[] = STARTERS.filter((s) => s.kind !== 'benchmark').map((s) => ({
  key: `starter:${s.id}`,
  name: s.name,
  blurb: s.blurb,
  tag: `${s.kind} · ${s.timeframe}`,
  source: s.source,
}))

const VERDICT_CLASS: Record<Verdict, string> = {
  beat: 'up',
  'lost-less': '',
  behind: '',
  lost: 'down',
  few: 'is-muted',
  error: 'is-muted',
}

/**
 * The Strategy lab: runs strategies on the past and on recent candles they
 * were not written against, on every coin, next to buy & hold. It answers
 * "does this actually make money?" as honestly as a backtest can, and says
 * what to do next: forward test the good ones live in a competition.
 */
export default function LabView({ onLoad }: { onLoad: (name: string, source: string) => void }) {
  const [saved, setSaved] = useState<StrategyDto[]>([])
  const [picked, setPicked] = useState<Set<string>>(() => new Set(STARTER_CANDIDATES.map((c) => c.key)))
  const [coins, setCoins] = useState<Set<string>>(() => new Set(COINS))
  const [unseenDays, setUnseenDays] = useState(90)
  const [rows, setRows] = useState<Row[]>([])
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [stopped, setStopped] = useState(false)
  const stop = useRef(false)

  useEffect(() => {
    api.listStrategies().then(setSaved).catch(() => setSaved([]))
    // Leaving the page stops the check: it used to keep sending dozens of
    // backtests for a page nobody was looking at.
    return () => {
      stop.current = true
    }
  }, [])

  const candidates = useMemo<Candidate[]>(
    () => [
      ...STARTER_CANDIDATES,
      ...saved.map((s) => ({
        key: `saved:${s.id}`,
        name: s.name,
        blurb: `Your strategy, version ${s.latestVersion}.`,
        tag: `yours · ${s.timeframe ?? '?'}`,
        source: null,
        saved: s,
      })),
    ],
    [saved],
  )

  const toggle = (set: Set<string>, key: string) => {
    const next = new Set(set)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    return next
  }

  const running = progress != null && progress.done < progress.total && !stopped

  const run = async () => {
    const chosen = candidates.filter((c) => picked.has(c.key))
    const coinList = COINS.filter((c) => coins.has(c))
    if (chosen.length === 0 || coinList.length === 0) {
      setError('Pick at least one strategy and one coin.')
      return
    }
    setError(null)
    setRows([])
    setStopped(false)
    stop.current = false
    const split = splitDate(unseenDays)
    const total = chosen.length * coinList.length
    let done = 0
    setProgress({ done, total })

    for (const c of chosen) {
      if (stop.current) break
      let source = c.source
      if (!source && c.saved) {
        try {
          source = await api.getVersionSource(c.saved.id, c.saved.latestVersion)
        } catch {
          source = null
        }
      }
      if (!source) {
        done += coinList.length
        setProgress({ done, total })
        continue
      }
      const row: Row = { candidate: c, source, cells: [] }
      setRows((r) => [...r, row])
      for (const coin of coinList) {
        if (stop.current) break
        const cell = await checkOne(source, timeframeOf(source), coin, split)
        row.cells = [...row.cells, cell]
        setRows((r) => r.map((x) => (x.candidate.key === c.key ? { ...row } : x)))
        done += 1
        setProgress({ done, total })
      }
    }
    if (stop.current) {
      setStopped(true)
      setProgress((p) => (p ? { done, total: p.total } : p))
      return
    }
    setProgress((p) => (p ? { done: p.total, total: p.total } : p))
  }

  const finished = progress != null && (progress.done >= progress.total || stopped)

  const ranked = useMemo(() => {
    const score = (r: Row) =>
      r.cells.filter((c) => c.verdict === 'beat').length * 10 + r.cells.filter((c) => c.consistent).length
    return running ? rows : [...rows].sort((a, b) => score(b) - score(a))
  }, [rows, running])

  const split = splitDate(unseenDays)

  return (
    <>
      <section className="panel tsb-lab">
        <h2>Strategy lab — does it actually make money?</h2>

        <ol className="tsb-lab__how">
          <li>
            <b>Past.</b> Each strategy runs on all history before{' '}
            {split.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}.
          </li>
          <li>
            <b>Unseen.</b> Then only on the last {unseenDays} days. This is the honest test: a strategy
            can look great on the past just because it was shaped to fit it.
          </li>
          <li>
            <b>Against holding.</b> Every result sits next to simply buying the coin and holding it, with
            the same candles and the same 0.1% fee. Beating that is the real bar.
          </li>
        </ol>

        <div className="tsb-lab__pick">
          {candidates.map((c) => (
            <label key={c.key} className={`tsb-lab__card ${picked.has(c.key) ? 'is-on' : ''}`}>
              <input
                type="checkbox"
                checked={picked.has(c.key)}
                onChange={() => setPicked((p) => toggle(p, c.key))}
                disabled={running}
              />
              <span className="tsb-lab__card-name">{c.name}</span>
              <span className="tsb-lab__tag">{c.tag}</span>
              <span className="tsb-lab__blurb">{c.blurb}</span>
            </label>
          ))}
        </div>

        <div className="tsb-lab__controls">
          <div className="tsb-lab__coins" role="group" aria-label="Coins">
            {COINS.map((coin) => (
              <button
                key={coin}
                type="button"
                className={`ghost ${coins.has(coin) ? 'is-on' : ''}`}
                aria-pressed={coins.has(coin)}
                onClick={() => setCoins((s) => toggle(s, coin))}
                disabled={running}
              >
                {coin.replace('USDT', '')}
              </button>
            ))}
          </div>
          <span className="tsb-lab__unseen">
            Unseen part
            <select value={unseenDays} onChange={(e) => setUnseenDays(Number(e.target.value))} disabled={running}>
              <option value={30}>last 30 days</option>
              <option value={60}>last 60 days</option>
              <option value={90}>last 90 days</option>
              <option value={180}>last 180 days</option>
            </select>
          </span>
          {running ? (
            <button type="button" className="ghost" onClick={() => (stop.current = true)}>
              Stop
            </button>
          ) : (
            <button type="button" onClick={run}>
              Run the check
            </button>
          )}
        </div>

        {progress && (
          <div className="tsb-lab__progress" aria-live="polite">
            <div style={{ width: `${(progress.done / Math.max(1, progress.total)) * 100}%` }} />
            <span>
              {running
                ? `Running ${progress.done + 1} of ${progress.total}…`
                : stopped
                  ? `Stopped after ${progress.done} of ${progress.total} runs`
                  : `Done: ${progress.total} runs`}
            </span>
          </div>
        )}
        {error && <p className="error-note">{error}</p>}
      </section>

      {ranked.map((row) => (
        <StrategyResult key={row.candidate.key} row={row} onLoad={onLoad} unseenDays={unseenDays} />
      ))}

      {rows.length > 0 && finished && (
        <section className="panel tsb-lab tsb-lab__next">
          <h2>What next</h2>
          <p>
            A backtest, even on unseen candles, still only looks backwards. The real test is forward: on
            the <b>Competitions</b> page an admin creates a competition (up to 30 days) with the same coin
            and timeframe. Save the strategies that did best here (<b>Open in builder</b>, then Save), enter
            them, and enter <b>Buy and Hold</b> too (from the Builder's ready-made list) as the yardstick.
            They then trade every new candle live, under the competition's drawdown and daily-loss rules,
            and nobody can change them once entered.
          </p>
        </section>
      )}
    </>
  )
}

function StrategyResult({
  row,
  onLoad,
  unseenDays,
}: {
  row: Row
  onLoad: (name: string, source: string) => void
  unseenDays: number
}) {
  const beat = row.cells.filter((c) => c.verdict === 'beat').length
  const consistent = row.cells.filter((c) => c.consistent).length
  const ran = row.cells.filter((c) => c.verdict !== 'error').length

  return (
    <section className="panel tsb-lab tsb-lab__result">
      <header className="tsb-lab__result-head">
        <div>
          <h3>{row.candidate.name}</h3>
          <p className="tsb-lab__summary">
            {ran === 0
              ? 'Not run yet.'
              : `Made money and beat holding on the unseen ${unseenDays} days on ${beat} of ${ran} coin${ran === 1 ? '' : 's'}` +
                (consistent > 0 ? ` — and on the past too for ${consistent}.` : '.')}
          </p>
        </div>
        <button type="button" className="ghost" onClick={() => onLoad(row.candidate.name, row.source)}>
          Open in builder
        </button>
      </header>

      <div className="tsb-lab__scroll">
        <table className="tsb-lab__table">
          <thead>
            <tr>
              <th>Coin</th>
              <th className="num" title="The strategy on all history before the split">
                Past
              </th>
              <th className="num">Hold (past)</th>
              <th className="num" title={`The strategy on the last ${unseenDays} days`}>
                Unseen
              </th>
              <th className="num">Hold (unseen)</th>
              <th className="num" title="Biggest fall from a high, unseen part">
                Worst drop
              </th>
              <th className="num">Trades</th>
              <th>Verdict (unseen)</th>
            </tr>
          </thead>
          <tbody>
            {row.cells.map((c) => (
              <tr key={c.coin}>
                <td>{c.coin.replace('USDT', '')}</td>
                {c.verdict === 'error' ? (
                  <td colSpan={7} className="tsb-lab__err">
                    {c.error}
                  </td>
                ) : (
                  <>
                    <Pct w={c.past} field="returnPct" />
                    <Pct w={c.past} field="holdPct" muted />
                    <Pct w={c.unseen} field="returnPct" strong />
                    <Pct w={c.unseen} field="holdPct" muted />
                    <td className="num">{c.unseen ? `−${c.unseen.maxDrawdownPct.toFixed(1)}%` : '—'}</td>
                    <td className="num">
                      {c.unseen?.trades ?? '—'}
                      {c.unseen && c.unseen.trades > 0 && (
                        <span className="tsb-lab__muted"> · {Math.round(c.unseen.winRate)}% won</span>
                      )}
                    </td>
                    <td className={VERDICT_CLASS[c.verdict]}>
                      {VERDICT_TEXT[c.verdict]}
                      {c.consistent && <span className="tsb-lab__badge">past too</span>}
                    </td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {row.cells.some((c) => c.verdict === 'few') && (
        <p className="tsb-lab__muted">
          Fewer than {MIN_TRADES} trades means luck can explain the result either way. Try a longer unseen part.
        </p>
      )}
    </section>
  )
}

function Pct({
  w,
  field,
  muted,
  strong,
}: {
  w: Window | null
  field: 'returnPct' | 'holdPct'
  muted?: boolean
  strong?: boolean
}) {
  const v = w?.[field]
  if (v == null) return <td className="num">—</td>
  return (
    <td className={`num ${muted ? 'tsb-lab__muted' : pnlClass(v)}`}>
      {strong ? <b>{pct(v)}</b> : pct(v)}
    </td>
  )
}
