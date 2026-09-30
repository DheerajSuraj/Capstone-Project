import { useState } from 'react'
import { api, type PaperAccountDto } from '../api'
import { pct, pnlClass, price, qty, signed, time, usd } from './format'
import { livePnl, livePnlPct } from './live'
import './paper.css'

/**
 * Positions, open orders and history under the chart, TradingView-style.
 * The current symbol's P&L uses the chart's live tick so it moves with the
 * candles; other symbols use the server's price from the last refresh.
 */
export default function PaperActivity({
  account,
  onAccount,
  symbol,
  livePrice,
}: {
  account: PaperAccountDto
  onAccount: (a: PaperAccountDto) => void
  symbol: string
  livePrice: number | null
}) {
  const [tab, setTab] = useState<'positions' | 'open' | 'history'>('positions')
  const [error, setError] = useState<string | null>(null)

  const cancel = async (id: number) => {
    setError(null)
    try {
      onAccount(await api.paperCancel(id))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const mark = (sym: string, serverPrice: number | null) =>
    sym === symbol && livePrice ? livePrice : serverPrice

  const [closing, setClosing] = useState<string | null>(null)
  const close = async (sym: string) => {
    setError(null)
    setClosing(sym)
    try {
      onAccount((await api.paperClose(sym)).account)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setClosing(null)
    }
  }

  return (
    <section className="tsb-pt tsb-pt--activity">
      <div className="tsb-pt__tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'positions'} className={tab === 'positions' ? 'is-on' : ''} onClick={() => setTab('positions')}>
          Positions ({account.positions.length})
        </button>
        <button type="button" role="tab" aria-selected={tab === 'open'} className={tab === 'open' ? 'is-on' : ''} onClick={() => setTab('open')}>
          Open orders ({account.openOrders.length})
        </button>
        <button type="button" role="tab" aria-selected={tab === 'history'} className={tab === 'history' ? 'is-on' : ''} onClick={() => setTab('history')}>
          History
        </button>
        <span className="tsb-pt__summary">
          Realized <b className={pnlClass(account.realizedPnl)}>{signed(account.realizedPnl)}</b> · Fees{' '}
          <b>{usd(account.feesPaid)}</b>
        </span>
      </div>

      {error && <p className="error-note">{error}</p>}

      <div className="tsb-pt__scroll">
        {tab === 'positions' &&
          (account.positions.length === 0 ? (
            <p className="tsb-pt__empty">No positions yet. Buy to go long or Sell to go short from the ticket on the right.</p>
          ) : (
            <table className="tsb-pt__table">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>Side</th>
                  <th className="num">Amount</th>
                  <th className="num">Entry</th>
                  <th className="num">Price</th>
                  <th className="num">Value</th>
                  <th className="num">P&amp;L</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {account.positions.map((p) => {
                  const m = mark(p.symbol, p.price)
                  const pnl = livePnl(p, m)
                  return (
                    <tr key={p.symbol}>
                      <td>{p.symbol}</td>
                      <td>
                        <span className={`tsb-pt__side is-${p.side.toLowerCase()}`}>{p.side}</span>
                      </td>
                      <td className="num">{qty(p.qty)} {p.baseAsset}</td>
                      <td className="num">{price(p.avgPrice)}</td>
                      <td className="num">{price(m)}{!p.priceLive && p.symbol !== symbol && ' *'}</td>
                      <td className="num">{usd(m != null ? p.qty * m : p.value)}</td>
                      <td className={`num ${pnlClass(pnl)}`}>
                        {signed(pnl)} ({pct(livePnlPct(p, m))})
                      </td>
                      <td className="num">
                        <button
                          type="button"
                          className="ghost"
                          onClick={() => close(p.symbol)}
                          disabled={closing === p.symbol}
                          title="Close the whole position at the market price"
                        >
                          {closing === p.symbol ? 'Closing…' : 'Close'}
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          ))}

        {tab === 'open' &&
          (account.openOrders.length === 0 ? (
            <p className="tsb-pt__empty">No open orders. A limit order waits here until the price reaches it.</p>
          ) : (
            <table className="tsb-pt__table">
              <thead>
                <tr>
                  <th>Placed</th>
                  <th>Symbol</th>
                  <th>Side</th>
                  <th className="num">Amount</th>
                  <th className="num">Limit</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {account.openOrders.map((o) => (
                  <tr key={o.id}>
                    <td>{time(o.createdAt)}</td>
                    <td>{o.symbol}</td>
                    <td>{o.side === 'BUY' ? 'Buy' : 'Sell'}</td>
                    <td className="num">{qty(o.qty)}</td>
                    <td className="num">{price(o.limitPrice)}</td>
                    <td className="num">
                      <button type="button" className="ghost" onClick={() => cancel(o.id)}>
                        Cancel
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}

        {tab === 'history' &&
          (account.history.length === 0 ? (
            <p className="tsb-pt__empty">Nothing yet since the last reset.</p>
          ) : (
            <table className="tsb-pt__table">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Symbol</th>
                  <th>Order</th>
                  <th className="num">Amount</th>
                  <th className="num">Price</th>
                  <th className="num">Fee</th>
                  <th className="num">Realized</th>
                </tr>
              </thead>
              <tbody>
                {account.history.map((o) => (
                  <tr key={o.id} className={o.status !== 'FILLED' ? 'is-muted' : undefined}>
                    <td>{time(o.filledAt ?? o.createdAt)}</td>
                    <td>{o.symbol}</td>
                    <td>
                      {o.side === 'BUY' ? 'Buy' : 'Sell'} · {o.type.toLowerCase()}
                      {o.status !== 'FILLED' && ` · ${o.status.toLowerCase()}`}
                      {o.rejectReason && <div className="tsb-pt__muted">{o.rejectReason}</div>}
                    </td>
                    <td className="num">{qty(o.qty)}</td>
                    <td className="num">{price(o.fillPrice ?? o.limitPrice)}</td>
                    <td className="num">{o.fee == null ? '—' : usd(o.fee, 4)}</td>
                    <td className={`num ${o.realizedPnl == null ? '' : pnlClass(o.realizedPnl)}`}>
                      {o.realizedPnl == null ? '—' : signed(o.realizedPnl)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </div>
    </section>
  )
}
