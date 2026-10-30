import type { CompetitionRulesDto, EntryViewDto } from '../api'
import { ENTRY_HELP, ENTRY_LABEL, money, pnlClass, signedPct, when } from './util'

/**
 * "Your rule dashboard": how close an entry is to each rule, as meters.
 * Target fills toward passing; the limits fill toward trouble. With the
 * competition's rules at hand, each limit is also shown as the equity it
 * stands for, so "4.1% of 5%" comes with the number that ends it.
 */
export default function EntryDashboard({
  e,
  rank,
  rules,
}: {
  e: EntryViewDto
  rank?: number | null
  rules?: CompetitionRulesDto
}) {
  // The target is judged after the fee for selling what is still held, so
  // the meter counts that fee too (before, it could read 100% while the
  // entry would still fail by the fee).
  const afterFee = e.returnAfterExitFeePct ?? e.returnPct
  const progress = Math.max(0, (afterFee / e.profitTargetPct) * 100)
  const active = e.status === 'ACTIVE'

  const fixed = (e.drawdownMode ?? rules?.drawdownMode) === 'STATIC'
  const capital = rules?.startingCapital
  const ddFloor = fixed
    ? capital != null ? capital * (1 - e.maxDrawdownPct / 100) : null
    : e.peakEquity != null ? e.peakEquity * (1 - e.maxDrawdownPct / 100) : null
  const dayFloor = e.dayStartEquity != null ? e.dayStartEquity * (1 - e.dailyLossLimitPct / 100) : null
  const dailyEnds = rules?.dailyLossAction === 'FAIL'

  const now = !active
    ? e.status === 'ELIMINATED' ? 'Out' : 'Finished'
    : e.inTrade
      ? `In a trade @ ${money(e.entryPrice)}`
      : e.halted
        ? 'Paused today'
        : 'Waiting for a signal'

  return (
    <div className={`tsb-dash is-${e.status.toLowerCase()}`}>
      <div className="tsb-dash__head">
        <div>
          <div className="tsb-dash__name">{e.strategy}</div>
          <div className="tsb-cmp__muted tsb-small">
            Locked in {when(e.submittedAt)} ·{' '}
            <span title="A fingerprint of the strategy as it was locked in. It cannot change during the competition.">
              code {e.sourceHash.slice(0, 12)}…
            </span>
          </div>
        </div>
        <span className={`tsb-dash__status is-${e.status.toLowerCase()}`} title={ENTRY_HELP[e.status]}>
          {ENTRY_LABEL[e.status]}
        </span>
      </div>

      <div className="tsb-dash__money">
        <div>
          <div className="tsb-cmp__muted tsb-small">Equity</div>
          <div className="tsb-dash__big">{money(e.equity)}</div>
        </div>
        <div>
          <div className="tsb-cmp__muted tsb-small">Return</div>
          <div className={`tsb-dash__big ${pnlClass(e.returnPct)}`}>{signedPct(e.returnPct)}</div>
        </div>
        <div>
          <div className="tsb-cmp__muted tsb-small">Rank</div>
          <div className="tsb-dash__big">{(rank === undefined ? e.rank : rank) ?? '—'}</div>
        </div>
        <div>
          <div className="tsb-cmp__muted tsb-small">Now</div>
          <div className="tsb-dash__big tsb-dash__now">{now}</div>
        </div>
      </div>

      {e.statusReason && (
        <p className="tsb-dash__reason">
          {e.statusReason}
          {e.statusCandle && <span className="tsb-cmp__muted"> · {when(e.statusCandle)}</span>}
        </p>
      )}
      {active && e.halted && (
        <p className="tsb-dash__reason">⏸ Paused until 00:00 UTC: {e.haltReason} Closing a trade is still allowed.</p>
      )}

      <div className="tsb-dash__meters">
        <Meter label="Profit target" good value={progress}
          text={`${signedPct(afterFee)} of +${e.profitTargetPct}%`}
          note={active && e.inTrade ? 'If sold now, after the fee.' : undefined} />
        <Meter label={fixed ? 'Max loss used' : 'Drawdown used'} value={(e.drawdownPct / e.maxDrawdownPct) * 100}
          text={`${e.drawdownPct.toFixed(2)}% of ${e.maxDrawdownPct}%`}
          note={`${ddFloor != null && active ? `Out below ${money(ddFloor)} · ` : ''}worst so far ${e.maxDrawdownSeen.toFixed(2)}%`} />
        {active && e.dailyLossLimitPct > 0 && (
          <Meter label="Today's loss" value={(e.dailyLossPct / e.dailyLossLimitPct) * 100}
            text={`${e.dailyLossPct.toFixed(2)}% of ${e.dailyLossLimitPct}%`}
            note={`${dayFloor != null ? `${dailyEnds ? 'Out' : 'Paused'} at ${money(dayFloor)} · ` : ''}new day at 00:00 UTC`} />
        )}
        {active && e.maxTradesPerDay > 0 && (
          <Meter label="Trades today" value={(e.tradesToday / e.maxTradesPerDay) * 100}
            text={`${e.tradesToday} of ${e.maxTradesPerDay}`} note="A buy and a sell: a round trip is 2." />
        )}
        {e.minTradingDays > 0 && (
          <Meter label="Trading days" good value={(e.tradingDays / e.minTradingDays) * 100}
            text={`${e.tradingDays} of ${e.minTradingDays}`} />
        )}
      </div>
    </div>
  )
}

/**
 * good = filling it is progress (amber); otherwise filling it is danger.
 * Danger is also said in words, so it never rests on the bar's shade alone.
 */
function Meter({
  label,
  value,
  text,
  note,
  good = false,
}: {
  label: string
  value: number
  text: string
  note?: string
  good?: boolean
}) {
  const v = Math.max(0, Math.min(100, value))
  const tone = good ? (v >= 100 ? 'done' : 'good') : v >= 80 ? 'danger' : v >= 50 ? 'warn' : 'calm'
  const warning = good ? null : v >= 100 ? 'Limit reached.' : v >= 80 ? 'Close to the limit.' : null
  return (
    <div className="tsb-meter">
      <div className="tsb-meter__top">
        <span>{label}</span>
        <span className="tsb-meter__text">{text}</span>
      </div>
      <div className="tsb-meter__track">
        <div className={`tsb-meter__fill is-${tone}`} style={{ width: `${v}%` }} />
      </div>
      {(warning || note) && (
        <div className="tsb-meter__note">
          {warning && <b className="tsb-meter__warn">⚠ {warning} </b>}
          {note}
        </div>
      )}
    </div>
  )
}
