import type { EntryViewDto } from '../api'
import { ENTRY_LABEL, money, pnlClass, signedPct } from './util'

/**
 * "Your rule dashboard": how close an entry is to each rule, as meters.
 * Target fills toward passing; the limits fill toward trouble.
 */
export default function EntryDashboard({ e }: { e: EntryViewDto }) {
  const progress = Math.max(0, (e.returnPct / e.profitTargetPct) * 100)
  return (
    <div className={`tsb-dash is-${e.status.toLowerCase()}`}>
      <div className="tsb-dash__head">
        <div>
          <div className="tsb-dash__name">{e.strategy}</div>
          <div className="tsb-cmp__muted tsb-small">
            Locked {new Date(e.submittedAt).toLocaleString()} · hash {e.sourceHash.slice(0, 12)}…
          </div>
        </div>
        <span className={`tsb-dash__status is-${e.status.toLowerCase()}`}>{ENTRY_LABEL[e.status]}</span>
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
          <div className="tsb-dash__big">{e.rank ?? '—'}</div>
        </div>
        <div>
          <div className="tsb-cmp__muted tsb-small">Now</div>
          <div className="tsb-dash__big tsb-dash__now">{e.inTrade ? `In a trade @ ${money(e.entryPrice)}` : 'Waiting'}</div>
        </div>
      </div>

      {e.statusReason && <p className="tsb-dash__reason">{e.statusReason}</p>}
      {e.halted && <p className="tsb-dash__reason">⏸ Halted for today: {e.haltReason}</p>}

      <div className="tsb-dash__meters">
        <Meter label="Profit target" good value={progress}
          text={`${signedPct(e.returnPct)} of +${e.profitTargetPct}%`} />
        <Meter label="Drawdown used" value={(e.drawdownPct / e.maxDrawdownPct) * 100}
          text={`${e.drawdownPct.toFixed(2)}% of ${e.maxDrawdownPct}% (worst ${e.maxDrawdownSeen.toFixed(2)}%)`} />
        {e.dailyLossLimitPct > 0 && (
          <Meter label="Today's loss" value={(e.dailyLossPct / e.dailyLossLimitPct) * 100}
            text={`${e.dailyLossPct.toFixed(2)}% of ${e.dailyLossLimitPct}%`} />
        )}
        {e.maxTradesPerDay > 0 && (
          <Meter label="Trades today" value={(e.tradesToday / e.maxTradesPerDay) * 100}
            text={`${e.tradesToday} of ${e.maxTradesPerDay}`} />
        )}
        {e.minTradingDays > 0 && (
          <Meter label="Trading days" good value={(e.tradingDays / e.minTradingDays) * 100}
            text={`${e.tradingDays} of ${e.minTradingDays}`} />
        )}
      </div>
    </div>
  )
}

/** good = filling it is progress (amber); otherwise filling it is danger. */
function Meter({ label, value, text, good = false }: { label: string; value: number; text: string; good?: boolean }) {
  const v = Math.max(0, Math.min(100, value))
  const tone = good ? (v >= 100 ? 'done' : 'good') : v >= 80 ? 'danger' : v >= 50 ? 'warn' : 'calm'
  return (
    <div className="tsb-meter">
      <div className="tsb-meter__top">
        <span>{label}</span>
        <span className="tsb-meter__text">{text}</span>
      </div>
      <div className="tsb-meter__track">
        <div className={`tsb-meter__fill is-${tone}`} style={{ width: `${v}%` }} />
      </div>
    </div>
  )
}
