import { useEffect, useState } from 'react'
import { api, type CompetitionListDto, type CompetitionSummaryDto } from '../api'
import { relative, ruleChips, STATUS_LABEL, when } from './util'

export default function CompetitionList({
  onOpen,
  onCreate,
}: {
  onOpen: (id: number) => void
  onCreate: () => void
}) {
  const [data, setData] = useState<CompetitionListDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [, tick] = useState(0)

  useEffect(() => {
    api.competitions().then(setData).catch((e) => setError(String(e.message ?? e)))
    const t = setInterval(() => tick((n) => n + 1), 1000) // live countdowns
    return () => clearInterval(t)
  }, [])

  const group = (s: CompetitionSummaryDto['status'][]) =>
    (data?.competitions ?? []).filter((c) => s.includes(c.status))

  return (
    <div className="tsb-cmp">
      <header className="tsb-cmp__top">
        <div>
          <h2>Competitions</h2>
          <p className="tsb-cmp__muted">
            Submit a strategy, and it trades live candles that did not exist when you entered —
            under the same rules and the same starting money as everyone else. Hit the profit
            target without breaking a rule to pass.
          </p>
        </div>
        {data?.canCreate && (
          <button type="button" onClick={onCreate}>
            Create competition
          </button>
        )}
      </header>

      {error && <p className="error-note">{error}</p>}
      {!data && !error && <p className="tsb-cmp__muted">Loading…</p>}

      {data && data.competitions.length === 0 && (
        <div className="tsb-cmp__empty">
          <div className="tsb-cmp__empty-icon" aria-hidden>🏁</div>
          <div className="tsb-cmp__empty-title">No competitions yet</div>
          <p>{data.canCreate ? 'Create the first one — set the rules and open it for entries.' : 'An admin will open one soon.'}</p>
        </div>
      )}

      <Section title="Live now" items={group(['RUNNING'])} onOpen={onOpen} />
      <Section title="Open for entries" items={group(['OPEN'])} onOpen={onOpen} />
      <Section title="Finished" items={group(['FINISHED', 'CANCELLED'])} onOpen={onOpen} />
    </div>
  )
}

function Section({
  title,
  items,
  onOpen,
}: {
  title: string
  items: CompetitionSummaryDto[]
  onOpen: (id: number) => void
}) {
  if (items.length === 0) return null
  return (
    <section className="tsb-cmp__section">
      <h3>{title}</h3>
      <div className="tsb-cmp__grid">
        {items.map((c) => (
          <button key={c.id} type="button" className={`tsb-cmp__card is-${c.status.toLowerCase()}`} onClick={() => onOpen(c.id)}>
            <div className="tsb-cmp__card-head">
              <span className={`tsb-cmp__status is-${c.status.toLowerCase()}`}>
                {c.status === 'RUNNING' && <span className="tsb-cmp__pulse" aria-hidden />}
                {STATUS_LABEL[c.status]}
              </span>
              <span className="tsb-cmp__muted">
                {c.symbol} · {c.timeframe}
              </span>
            </div>
            <div className="tsb-cmp__card-name">{c.name}</div>
            <div className="tsb-cmp__card-time">
              {c.status === 'OPEN' && <>Starts {relative(c.startsAt)} · {when(c.startsAt)}</>}
              {c.status === 'RUNNING' && <>Ends {relative(c.endsAt)} · {when(c.endsAt)}</>}
              {(c.status === 'FINISHED' || c.status === 'CANCELLED') && <>Ended {when(c.endsAt)}</>}
            </div>
            <div className="tsb-cmp__chips">
              {ruleChips(c.rules).slice(0, 4).map((r) => (
                <span key={r} className="tsb-cmp__chip">{r}</span>
              ))}
            </div>
            <div className="tsb-cmp__card-foot">
              <span>{c.entryCount} {c.entryCount === 1 ? 'entry' : 'entries'}</span>
              {c.myEntryCount > 0 && <span className="tsb-cmp__mine">You: {c.myEntryCount}</span>}
            </div>
          </button>
        ))}
      </div>
    </section>
  )
}
