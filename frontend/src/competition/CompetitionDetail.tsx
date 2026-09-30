import { Fragment, useCallback, useEffect, useState } from 'react'
import {
  api,
  type CompetitionDetailDto,
  type CompetitionTradeDto,
  type LeaderboardDto,
  type StrategyDto,
} from '../api'
import EntryDashboard from './EntryDashboard'
import {
  ENTRY_LABEL,
  money,
  pnlClass,
  relative,
  signedMoney,
  signedPct,
  STATUS_LABEL,
  when,
} from './util'

export default function CompetitionDetail({ id, onBack }: { id: number; onBack: () => void }) {
  const [detail, setDetail] = useState<CompetitionDetailDto | null>(null)
  const [board, setBoard] = useState<LeaderboardDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<'entries' | 'traders'>('entries')
  const [openEntry, setOpenEntry] = useState<number | null>(null)
  const [, tick] = useState(0)

  const load = useCallback(async () => {
    try {
      const [d, b] = await Promise.all([api.competition(id), api.leaderboard(id)])
      setDetail(d)
      setBoard(b)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }, [id])

  useEffect(() => {
    load()
    const refresh = setInterval(() => {
      if (document.visibilityState === 'visible') load()
    }, 30_000) // the server processes new candles every 30s
    const clock = setInterval(() => tick((n) => n + 1), 1000)
    return () => {
      clearInterval(refresh)
      clearInterval(clock)
    }
  }, [load])

  if (error && !detail) return <p className="error-note">{error}</p>
  if (!detail) return <p className="tsb-cmp__muted">Loading…</p>

  const c = detail.competition
  const r = c.rules

  const cancel = async () => {
    if (!window.confirm('Cancel this competition? Nobody will be able to enter.')) return
    try {
      await api.cancelCompetition(c.id)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <div className="tsb-cmp">
      <header className="tsb-cmp__top">
        <div>
          <button type="button" className="ghost" onClick={onBack}>
            ← Competitions
          </button>
          <h2>{c.name}</h2>
          <div className="tsb-cmp__meta">
            <span className={`tsb-cmp__status is-${c.status.toLowerCase()}`}>
              {c.status === 'RUNNING' && <span className="tsb-cmp__pulse" aria-hidden />}
              {STATUS_LABEL[c.status]}
            </span>
            <span>{c.symbol} · {c.timeframe} candles</span>
            <span>
              {c.status === 'OPEN' && <>Entries close {relative(c.startsAt)}</>}
              {c.status === 'RUNNING' && <>Ends {relative(c.endsAt)}</>}
              {c.status === 'FINISHED' && <>Finished {when(c.endsAt)}</>}
              {c.status === 'CANCELLED' && <>Cancelled</>}
            </span>
            <span>{c.entryCount} entries</span>
          </div>
          {detail.description && <p className="tsb-cmp__muted">{detail.description}</p>}
        </div>
        {detail.isAdmin && c.status === 'OPEN' && (
          <button type="button" className="ghost" onClick={cancel}>
            Cancel competition
          </button>
        )}
      </header>

      {error && <p className="error-note">{error}</p>}

      <section className="tsb-cmp__rules">
        <h3>The rules</h3>
        <div className="tsb-cmp__rule-grid">
          <Rule icon="💰" title={`${money(r.startingCapital, 0)} USDT`} text="Every entry starts with exactly this." />
          <Rule icon="🎯" title={`+${r.profitTargetPct}% to pass`} text="Needed by the end, after fees." />
          <Rule icon="📉" title={`${r.maxDrawdownPct}% max drawdown`} text="Fall this far from your best equity and you are out." />
          {r.dailyLossLimitPct > 0 && (
            <Rule icon="⏸" title={`${r.dailyLossLimitPct}% daily loss`} text="Hit it and no new trades until the next UTC day." />
          )}
          {r.maxTradesPerDay > 0 && (
            <Rule icon="🔁" title={`${r.maxTradesPerDay} trades a day`} text="Opening and closing each count once." />
          )}
          {r.minTradingDays > 0 && (
            <Rule icon="📅" title={`${r.minTradingDays} trading days`} text="Days with at least one trade, needed to pass." />
          )}
          <Rule icon="🧾" title={`${r.feePercent}% per fill`} text="Same fee for everyone." />
        </div>
        <p className="tsb-cmp__muted tsb-small">
          {when(c.startsAt)} → {when(c.endsAt)}. Every new {c.timeframe} candle, each entry's
          strategy is checked; an order fills at the next candle's open, the same way backtests work.
        </p>
      </section>

      {c.status === 'OPEN' && <Join detail={detail} onJoined={load} />}

      {detail.myEntries.length > 0 && (
        <section>
          <h3>Your entries</h3>
          <div className="tsb-cmp__dashes">
            {detail.myEntries.map((e) => {
              const live = board?.entries.find((x) => x.id === e.id) ?? e
              return <EntryDashboard key={e.id} e={live} />
            })}
          </div>
        </section>
      )}

      {board && (
        <section className="tsb-cmp__board">
          <div className="tsb-cmp__board-head">
            <h3>Leaderboard</h3>
            <div className="tsb-cmp__tabs" role="tablist">
              <button type="button" role="tab" aria-selected={tab === 'entries'} className={tab === 'entries' ? 'is-on' : ''} onClick={() => setTab('entries')}>
                Entries
              </button>
              <button type="button" role="tab" aria-selected={tab === 'traders'} className={tab === 'traders' ? 'is-on' : ''} onClick={() => setTab('traders')}>
                Traders (all entries added up)
              </button>
            </div>
            <span className="tsb-cmp__muted tsb-small">
              {board.lastCandle ? `Through the ${when(board.lastCandle)} candle` : 'Starts with the first candle'}
            </span>
          </div>

          {board.entries.length === 0 ? (
            <p className="tsb-cmp__muted">No entries yet.</p>
          ) : tab === 'entries' ? (
            <div className="tsb-cmp__scroll">
              <table className="tsb-cmp__table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Trader</th>
                    <th>Strategy</th>
                    <th>Status</th>
                    <th className="num">Equity</th>
                    <th className="num">Return</th>
                    <th className="num">Worst drawdown</th>
                    <th className="num">Trades</th>
                    <th className="num">Days</th>
                  </tr>
                </thead>
                <tbody>
                  {board.entries.map((e) => (
                    <Fragment key={e.id}>
                      <tr
                        className={`${e.mine ? 'is-mine' : ''} is-clickable`}
                        onClick={() => setOpenEntry(openEntry === e.id ? null : e.id)}
                      >
                        <td>{medal(e.rank)}</td>
                        <td>{e.username}{e.mine && ' (you)'}</td>
                        <td>{e.strategy}</td>
                        <td>
                          <span className={`tsb-dash__status is-${e.status.toLowerCase()}`}>{ENTRY_LABEL[e.status]}</span>
                          {e.halted && <span className="tsb-cmp__muted"> · halted</span>}
                        </td>
                        <td className="num">{money(e.equity)}</td>
                        <td className={`num ${pnlClass(e.returnPct)}`}>{signedPct(e.returnPct)}</td>
                        <td className="num">{e.maxDrawdownSeen.toFixed(2)}%</td>
                        <td className="num">{e.tradeCount}</td>
                        <td className="num">{e.tradingDays}</td>
                      </tr>
                      {openEntry === e.id && (
                        <tr className="tsb-cmp__expand">
                          <td colSpan={9}>
                            <Trades competitionId={c.id} entryId={e.id} reason={e.statusReason} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="tsb-cmp__scroll">
              <table className="tsb-cmp__table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Trader</th>
                    <th className="num">Entries</th>
                    <th className="num">Passed</th>
                    <th className="num">Eliminated</th>
                    <th className="num">Cumulative P&amp;L</th>
                  </tr>
                </thead>
                <tbody>
                  {board.traders.map((t) => (
                    <tr key={t.username} className={t.mine ? 'is-mine' : undefined}>
                      <td>{medal(t.rank)}</td>
                      <td>{t.username}{t.mine && ' (you)'}</td>
                      <td className="num">{t.entries}</td>
                      <td className="num">{t.passed}</td>
                      <td className="num">{t.eliminated}</td>
                      <td className={`num ${pnlClass(t.cumulativePnl)}`}>
                        {signedMoney(t.cumulativePnl)} ({signedPct(t.cumulativeReturnPct)})
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  )
}

function medal(rank: number | null) {
  if (rank == null) return '—'
  return rank === 1 ? '🥇' : rank === 2 ? '🥈' : rank === 3 ? '🥉' : rank
}

function Rule({ icon, title, text }: { icon: string; title: string; text: string }) {
  return (
    <div className="tsb-cmp__rule">
      <span className="tsb-cmp__rule-icon" aria-hidden>{icon}</span>
      <div>
        <div className="tsb-cmp__rule-title">{title}</div>
        <div className="tsb-cmp__muted tsb-small">{text}</div>
      </div>
    </div>
  )
}

/** Join: pick one of your strategies. Only matching symbol/timeframe can enter. */
function Join({ detail, onJoined }: { detail: CompetitionDetailDto; onJoined: () => void }) {
  const c = detail.competition
  const [strategies, setStrategies] = useState<StrategyDto[] | null>(null)
  const [pick, setPick] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    api.listStrategies().then(setStrategies).catch(() => setStrategies([]))
  }, [])

  const fits = (s: StrategyDto) => s.symbol === c.symbol && s.timeframe === c.timeframe

  const submit = async () => {
    const s = strategies?.find((x) => x.id === pick)
    if (!s) return
    setBusy(true)
    setMsg(null)
    try {
      const e = await api.enterCompetition(c.id, s.id, s.latestVersion)
      setMsg({ ok: true, text: `Entry confirmed and locked — ${e.strategy}, hash ${e.sourceHash.slice(0, 12)}…` })
      setPick(null)
      onJoined()
    } catch (err) {
      setMsg({ ok: false, text: err instanceof Error ? err.message : String(err) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="tsb-cmp__join">
      <h3>Join</h3>
      <p className="tsb-cmp__muted">
        Pick one of your saved strategies written for <b>{c.symbol} {c.timeframe}</b>. Its latest
        version is locked in when you submit — you cannot edit or withdraw it afterwards.
        You have {detail.myEntries.length} of {c.maxEntriesPerUser} entries.
      </p>
      {strategies && strategies.length === 0 && (
        <p className="tsb-cmp__muted">You have no saved strategies yet. Build one in the Builder and save it.</p>
      )}
      <div className="tsb-cmp__picks">
        {strategies?.map((s) => (
          <label key={s.id} className={`tsb-cmp__pick ${fits(s) ? '' : 'is-off'} ${pick === s.id ? 'is-on' : ''}`}>
            <input
              type="radio"
              name="pick"
              disabled={!fits(s)}
              checked={pick === s.id}
              onChange={() => setPick(s.id)}
            />
            <span>
              <b>{s.name}</b> v{s.latestVersion}
              <span className="tsb-cmp__muted"> · {s.symbol} {s.timeframe}</span>
              {!fits(s) && <span className="tsb-cmp__muted"> — wrong market or timeframe</span>}
            </span>
          </label>
        ))}
      </div>
      {msg && <p className={msg.ok ? 'tsb-cmp__ok' : 'error-note'}>{msg.text}</p>}
      <button type="button" onClick={submit} disabled={busy || pick == null || !detail.canEnter}>
        {busy ? 'Submitting…' : 'Submit and lock entry'}
      </button>
    </section>
  )
}

function Trades({ competitionId, entryId, reason }: { competitionId: number; entryId: number; reason: string | null }) {
  const [trades, setTrades] = useState<CompetitionTradeDto[] | null>(null)
  useEffect(() => {
    api.entryTrades(competitionId, entryId).then(setTrades).catch(() => setTrades([]))
  }, [competitionId, entryId])
  if (!trades) return <span className="tsb-cmp__muted">Loading trades…</span>
  return (
    <div>
      {reason && <p className="tsb-cmp__muted tsb-small">{reason}</p>}
      {trades.length === 0 ? (
        <span className="tsb-cmp__muted">No trades yet.</span>
      ) : (
        <table className="tsb-cmp__table tsb-cmp__table--inner">
          <thead>
            <tr>
              <th>Opened</th>
              <th className="num">At</th>
              <th>Closed</th>
              <th className="num">At</th>
              <th>Why</th>
              <th className="num">P&amp;L</th>
            </tr>
          </thead>
          <tbody>
            {trades.map((t, i) => (
              <tr key={i}>
                <td>{when(t.entryTime)}</td>
                <td className="num">{money(t.entryPrice)}</td>
                <td>{t.exitTime ? when(t.exitTime) : 'still open'}</td>
                <td className="num">{t.exitPrice == null ? '—' : money(t.exitPrice)}</td>
                <td>{t.exitReason?.replaceAll('_', ' ').toLowerCase() ?? '—'}</td>
                <td className={`num ${t.pnl == null ? '' : pnlClass(t.pnl)}`}>{t.pnl == null ? '—' : signedMoney(t.pnl)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
