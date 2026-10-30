import { useState } from 'react'
import type {
  DiscoveryIdeaDto,
  DiscoveryReportDto,
  DiscoverySideDto,
  DiscoveryVerdict,
} from '../api'

// "What your winners had in common": which conditions the winning trades
// shared, and whether adding one as a filter would really help. Every idea
// says how it did on the trades it was found in AND on later trades the
// search never saw, because a pattern judged only on the trades it was
// found in always looks better than it is.

const pct = (v: number) => `${Math.round(v)}%`
const pts = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(Math.round(v))} pts`
const ret = (v: number) => `${v >= 0 ? '+' : '−'}${Math.abs(v).toFixed(1)}%`
const day = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleDateString('en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
        timeZone: 'UTC',
      })
    : ''

const TAG: Record<DiscoveryVerdict, string> = {
  HELD_UP: 'Held up on later trades',
  MIXED: 'Partly held up',
  DID_NOT_HOLD: 'Didn’t hold up: overfit',
  TOO_FEW_LATER_TRADES: 'Too few later trades to check',
  WITHIN_LUCK: 'Could be luck',
}

export default function DiscoveryPanel({
  report,
  onApply,
}: {
  report: DiscoveryReportDto
  onApply?: (source: string, label: string) => void
}) {
  // The idea last added to the editor, to mark its button.
  const [added, setAdded] = useState<string | null>(null)
  if (report.status === 'TOO_FEW_TRADES') {
    return (
      <div className="tsb-empty">
        <div className="tsb-empty__icon" aria-hidden>
          🧪
        </div>
        <div className="tsb-empty__title">Not enough trades to look for patterns</div>
        <p>
          You have <b>{report.trades}</b> completed trades; we need at least <b>30</b>, so that a
          third of them can be kept back to check each idea on. With fewer, any “pattern” is
          almost certainly luck. Try a longer date range or a looser entry rule.
        </p>
      </div>
    )
  }

  const held = report.ideas.filter((i) => i.verdict === 'HELD_UP').length
  const beat = report.ideas.filter((i) => i.beatsLuck).length
  const tone = held > 0 ? 'go' : 'stop'
  const title =
    held > 0
      ? `${held} idea${held > 1 ? 's' : ''} held up on trades the search never saw`
      : beat > 0
        ? 'Something looked good, but it didn’t hold up'
        : 'Nothing beat luck, and that’s an honest answer'

  return (
    <div>
      <div className={`tsb-verdict tsb-verdict--${tone}`}>
        <div className="tsb-verdict__icon" aria-hidden>
          {held > 0 ? '🔍' : beat > 0 ? '⚠️' : '🎲'}
        </div>
        <div>
          <div className="tsb-verdict__title">{title}</div>
          <div className="tsb-verdict__sub">{report.message}</div>
        </div>
      </div>

      <ol className="tsb-steps" aria-label="How the ideas were checked">
        <li>
          <b>Searched</b> {report.filtersTried} filters on your first {report.foundTrades} trades
          (before {day(report.laterFrom)}).
        </li>
        <li>
          <b>Luck bar:</b> we shuffled which trades won {report.shuffles} times and searched again.
          An idea had to beat what luck reached.
        </li>
        <li>
          <b>Checked</b> each idea on your last {report.laterTrades} trades ({day(report.laterFrom)} to
          the end), which the search never saw, and re-ran your backtest with the filter added.
        </li>
      </ol>

      {report.ideas.length === 0 && (
        <p className="tsb-dbg__muted">
          No filter raised both the win rate and the average return on the earlier trades.
        </p>
      )}
      {report.ideas.map((idea) => (
        <IdeaCard
          key={idea.id}
          idea={idea}
          laterFrom={report.laterFrom}
          added={added === idea.id}
          onApply={
            onApply && idea.appliedSource
              ? () => {
                  onApply(idea.appliedSource!, idea.label)
                  setAdded(idea.id)
                }
              : undefined
          }
        />
      ))}

      {report.rerunNote && <p className="tsb-small tsb-dbg__muted">{report.rerunNote}</p>}

      {report.topTried.length > 0 && (
        <details className="tsb-why">
          <summary>Show the strongest filters we tried</summary>
          <p className="tsb-small tsb-dbg__muted">
            On your first {report.foundTrades} trades ({pct(report.winRate)} of all{' '}
            {report.trades} trades won). Lift: win rate with the filter minus without. Luck
            reaches: the lift that shuffled wins and losses reach 1 time in 20 when{' '}
            {report.filtersTried} filters are tried. It is higher for a filter that keeps few
            trades, because a small group swings more by chance.
          </p>
          <div className="tsb-dbg__scroll">
            <table className="tsb-dbg__table">
              <thead>
                <tr>
                  <th>Only take trades when…</th>
                  <th className="num">Trades kept</th>
                  <th className="num">Win rate</th>
                  <th className="num">Lift</th>
                  <th className="num">Luck reaches</th>
                </tr>
              </thead>
              <tbody>
                {report.topTried.map((t) => (
                  <tr key={t.label}>
                    <td>
                      {t.label}
                      {t.beatsLuck && <span className="tsb-dbg__muted"> ✓</span>}
                    </td>
                    <td className="num">{t.kept}</td>
                    <td className="num">{pct(t.winRateKept)}</td>
                    <td className="num">{pts(t.lift)}</td>
                    <td className="num">{pts(t.luckLift)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </div>
  )
}

function IdeaCard({
  idea,
  laterFrom,
  added,
  onApply,
}: {
  idea: DiscoveryIdeaDto
  laterFrom: string | null
  added: boolean
  onApply?: () => void
}) {
  const [copied, setCopied] = useState(false)
  const strong = idea.verdict === 'HELD_UP'
  const recommended = idea.verdict === 'HELD_UP' || idea.verdict === 'MIXED'
  const offer = idea.snippet && (recommended || onApply)
  const copy = async () => {
    if (!idea.snippet) return
    try {
      await navigator.clipboard.writeText(idea.snippet)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* clipboard blocked: the snippet is on screen to copy by hand */
    }
  }
  const r = idea.rerun
  return (
    <article className={`tsb-find${strong ? ' is-strong' : ''}`}>
      <div className="tsb-find__head">
        <span className="tsb-find__label">
          Only take trades when: <b>{idea.label}</b>
        </span>
        <span className={`tsb-find__tag${strong ? ' is-real' : ''}`}>{TAG[idea.verdict]}</span>
      </div>

      <p className="tsb-ideasum">
        <b>{pct(idea.winnersWithPct)}</b> of the earlier winners had this, against{' '}
        <b>{pct(idea.losersWithPct)}</b> of the losers.
      </p>

      <div className="tsb-ba">
        <BeforeAfter label="Trades it was found in" side={idea.found} />
        <BeforeAfter label="Later trades (unseen)" side={idea.later} />
      </div>
      <div className="tsb-small tsb-dbg__muted">
        On the earlier trades it lifted the win rate {pts(idea.lift)}; luck alone reaches{' '}
        {pts(idea.luckLift)} for a filter keeping {idea.found.kept} trades.
      </div>

      {r && (
        <div className="tsb-rerun">
          <div className="tsb-rerun__title">Your backtest re-run with this filter added</div>
          <div className="tsb-rerun__row">
            <span className="tsb-rerun__label">
              Later period{laterFrom ? `, ${day(laterFrom)} to the end` : ''}
              <span className="tsb-dbg__muted"> (trades the search never saw)</span>
            </span>
            <span>
              return <b>{ret(r.returnBefore)}</b> → <b>{ret(r.returnAfter)}</b>, trades{' '}
              {r.tradesBefore} → {r.tradesAfter}, win rate {pct(r.winRateBefore)} →{' '}
              {pct(r.winRateAfter)}
            </span>
          </div>
          <div className="tsb-rerun__row">
            <span className="tsb-rerun__label">
              Whole backtest
              <span className="tsb-dbg__muted"> (the return in your report)</span>
            </span>
            <span>
              return <b>{ret(r.fullReturnBefore)}</b> → <b>{ret(r.fullReturnAfter)}</b>, trades{' '}
              {r.fullTradesBefore} → {r.fullTradesAfter}
            </span>
          </div>
          <div className="tsb-small tsb-dbg__muted">
            Go by the later period. The whole-backtest figure includes the trades this filter was
            picked on, so it makes the filter look better than it is.
          </div>
        </div>
      )}

      <p className="tsb-small tsb-advice">{advice(idea)}</p>
      {!idea.snippet && idea.family === 'time' && idea.beatsLuck && (
        <p className="tsb-small tsb-dbg__muted">
          The strategy language has no time-of-day check, so this one can’t be added as a line or
          re-run here.
        </p>
      )}

      {offer && (
        <div className="tsb-dbg__snippet">
          <span className="tsb-dbg__muted">
            {onApply
              ? 'Adds this to the condition of every rule that buys:'
              : 'Add to your entry condition, then test on dates you haven’t looked at:'}
          </span>
          <code className="tsb-dbg__code">{idea.snippet}</code>
          {onApply && (
            <button
              type="button"
              className={recommended ? undefined : 'ghost'}
              onClick={onApply}
              title={
                recommended
                  ? 'Puts the filter into your strategy. Run the backtest again to see the result.'
                  : 'This idea did not pass the checks. You can still add it to try it out.'
              }
            >
              {added ? 'Added ✓' : recommended ? 'Add to my strategy' : 'Add anyway'}
            </button>
          )}
          <button type="button" className="ghost" onClick={copy}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}
    </article>
  )
}

/** Win rate before → after the filter, and how many trades it keeps. */
function BeforeAfter({ label, side }: { label: string; side: DiscoverySideDto }) {
  return (
    <div className="tsb-ba__row">
      <div className="tsb-ba__label">{label}</div>
      <div className="tsb-ba__rates">
        win rate {pct(side.winRateAll)} → <b>{side.kept ? pct(side.winRateKept) : '—'}</b>
      </div>
      <div className="tsb-ba__bar" title={`${pct(side.winRateKept)} of ${side.kept} kept trades won`}>
        <div className="tsb-win__won" style={{ width: `${side.kept ? side.winRateKept : 0}%` }} />
      </div>
      <div className="tsb-ba__kept">
        keeps {side.kept} of {side.trades}
      </div>
    </div>
  )
}

function advice(i: DiscoveryIdeaDto): string {
  switch (i.verdict) {
    case 'HELD_UP':
      return 'Worth trying. It beat luck, and on the later trades it raised the win rate and the average return' +
        (i.rerun ? ' and the re-run made more money.' : '.') +
        ' Still one slice of one market: test it on other dates before relying on it.'
    case 'MIXED': {
      const fails: string[] = []
      if (i.later.winRateKept <= i.later.winRateAll) fails.push('the win rate')
      if (i.later.avgReturnKept <= i.later.avgReturnAll) fails.push('the average return')
      if (i.rerun && i.rerun.returnAfter <= i.rerun.returnBefore) fails.push('the re-run’s profit')
      return `Partly held up: on the later trades ${fails.join(' and ')} did not improve. Test more before relying on it.`
    }
    case 'DID_NOT_HOLD':
      return 'It looked good on the trades it was found in, but not on the later ones: the classic sign of overfitting. Don’t add it.'
    case 'TOO_FEW_LATER_TRADES':
      return `It beat luck, but only ${i.later.kept} of the later trades had it, too few to check. Re-run the health check on a longer date range.`
    case 'WITHIN_LUCK':
      return 'A difference this size is what luck produces when this many filters are tried. Don’t add it on this evidence.'
  }
}
