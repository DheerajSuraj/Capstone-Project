import { useEffect, useMemo, useState } from 'react'
import {
  api,
  type PaperAccountDto,
  type PaperSide,
  type PaperType,
} from '../api'
import { pct, pnlClass, price, qty as fmtQty, signed, usd } from './format'
import { liveEquity, livePnl, livePnlPct, orderEffect } from './live'
import './paper.css'

const FEE = 0.001
const BASE: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH', SOLUSDT: 'SOL' }
/** Binance spot rules, as seeded in V2: quantities round DOWN to the step,
 *  and an order must be worth at least MIN_ORDER USDT. The server enforces
 *  both; they are repeated here only to warn before the click. */
const STEP: Record<string, number> = { BTCUSDT: 0.00001, ETHUSDT: 0.0001, SOLUSDT: 0.001 }
const MIN_ORDER = 5

/**
 * The order ticket beside the live chart — buy and sell with fake money.
 *
 * The price shown here is the chart's live tick, for information. The price
 * an order fills at is always the SERVER's own live price, never this one:
 * nothing the browser sends can set a fill price.
 */
export default function TradePanel({
  symbol,
  livePrice,
  account,
  onAccount,
  signedIn,
  onSignIn,
}: {
  symbol: string
  livePrice: number | null
  account: PaperAccountDto | null
  onAccount: (a: PaperAccountDto) => void
  signedIn: boolean
  onSignIn: () => void
}) {
  const base = BASE[symbol] ?? symbol.replace('USDT', '')
  const [side, setSide] = useState<PaperSide>('BUY')
  const [type, setType] = useState<PaperType>('MARKET')
  const [unit, setUnit] = useState<'USDT' | 'BASE'>('USDT')
  const [amount, setAmount] = useState('')
  const [limit, setLimit] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null)

  // A new symbol starts a fresh ticket.
  useEffect(() => {
    setAmount('')
    setLimit('')
    setMessage(null)
  }, [symbol])

  const position = account?.positions.find((p) => p.symbol === symbol) ?? null
  const power = account?.buyingPower ?? 0
  const buy = side === 'BUY'
  // Is this order going against the position (i.e. closing it)?
  const against = !!position && (position.side === 'LONG') !== buy

  const refPrice = type === 'LIMIT' ? Number(limit) || null : livePrice
  const amountNum = Number(amount) || 0

  // What this order would be, rounded the way the exchange rounds it.
  const step = STEP[symbol] ?? 0.00000001
  const estimate = useMemo(() => {
    if (!refPrice || amountNum <= 0) return null
    const raw = unit === 'BASE' ? amountNum : amountNum / (refPrice * (1 + FEE))
    const units = Math.floor(raw / step + 1e-9) * step
    const value = units * refPrice
    return { units, value, fee: value * FEE, tooSmall: value < MIN_ORDER }
  }, [refPrice, amountNum, unit, side, step])

  // Against a position, % means "of the position" (100% closes it);
  // otherwise it means "of your buying power".
  const fillPct = (share: number) => {
    if (!refPrice) return
    if (against && position) {
      setUnit('BASE')
      setAmount(trim(Math.floor((position.qty * share) / step + 1e-9) * step))
    } else {
      const spend = power * share
      setUnit('USDT')
      setAmount(spend > 0 ? (Math.floor(spend * 100) / 100).toString() : '')
    }
  }

  const switchType = (t: PaperType) => {
    setType(t)
    if (t === 'LIMIT' && !limit && livePrice) setLimit(livePrice.toFixed(2))
  }

  const submit = async () => {
    if (!signedIn) return onSignIn()
    setBusy(true)
    setMessage(null)
    try {
      const res = await api.paperOrder({
        symbol,
        side,
        type,
        ...(unit === 'USDT' ? { quoteAmount: amountNum } : { qty: amountNum }),
        ...(type === 'LIMIT' ? { limitPrice: Number(limit) } : {}),
      })
      onAccount(res.account)
      const o = res.order
      setMessage({
        kind: 'ok',
        text:
          o.status === 'FILLED'
            ? `${o.side === 'BUY' ? 'Bought' : 'Sold'} ${fmtQty(o.qty)} ${base} at ${price(o.fillPrice)}` +
              (o.realizedPnl != null ? ` · realized ${signed(o.realizedPnl)} USDT` : '')
            : o.status === 'OPEN'
              ? `Limit ${o.side.toLowerCase()} placed: ${fmtQty(o.qty)} ${base} at ${price(o.limitPrice)}. It fills when the price gets there.`
              : `Order ${o.status.toLowerCase()}: ${o.rejectReason ?? ''}`,
      })
      setAmount('')
    } catch (e) {
      setMessage({ kind: 'err', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
    }
  }

  const reset = async () => {
    if (!window.confirm('Reset your paper account to 10,000 USDT? Open orders are cancelled and positions closed.')) return
    try {
      onAccount(await api.paperReset())
      setMessage({ kind: 'ok', text: 'Account reset to 10,000 USDT.' })
    } catch (e) {
      setMessage({ kind: 'err', text: e instanceof Error ? e.message : String(e) })
    }
  }

  if (!signedIn) {
    return (
      <aside className="tsb-pt tsb-pt--ticket">
        <div className="tsb-pt__eyebrow">Paper trading</div>
        <div className="tsb-pt__hero">Practice with 10,000 fake USDT</div>
        <p className="tsb-pt__muted">
          Buy and sell on this live chart with fake money, at real prices. See your profit
          or loss as the market moves — no risk.
        </p>
        <button type="button" className="tsb-pt__cta" onClick={onSignIn}>
          Sign in to start
        </button>
      </aside>
    )
  }

  const equity = account ? liveEquity(account, symbol, livePrice) : 0
  const totalPnl = account ? equity - account.startingCash : 0
  const posPnl = position ? livePnl(position, livePrice) : 0

  const closeNow = async () => {
    if (!position) return
    setBusy(true)
    setMessage(null)
    try {
      const res = await api.paperClose(symbol)
      onAccount(res.account)
      setMessage({
        kind: 'ok',
        text: `Closed at ${price(res.order.fillPrice)} · realized ${signed(res.order.realizedPnl ?? 0)} USDT`,
      })
    } catch (e) {
      setMessage({ kind: 'err', text: e instanceof Error ? e.message : String(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <aside className="tsb-pt tsb-pt--ticket">
      <div className="tsb-pt__account">
        <div className="tsb-pt__eyebrow">Paper account · fake money</div>
        <div className="tsb-pt__equity">{usd(equity)} <span>USDT</span></div>
        <div className={`tsb-pt__pnl ${pnlClass(totalPnl)}`}>
          {signed(totalPnl)} ({account ? pct((equity / account.startingCash - 1) * 100) : '—'})
          <span className="tsb-pt__muted"> since start</span>
        </div>
        <div className="tsb-pt__cash" title="1× leverage: everything you hold, long and short, can be worth at most your equity">
          <span>Buying power</span>
          <b>{usd(power)}</b>
        </div>
      </div>

      <div className="tsb-pt__seg tsb-pt__seg--side" role="tablist" aria-label="Side">
        <button type="button" className={side === 'BUY' ? 'is-buy' : ''} onClick={() => setSide('BUY')}>
          Buy
        </button>
        <button type="button" className={side === 'SELL' ? 'is-sell' : ''} onClick={() => setSide('SELL')}>
          Sell
        </button>
      </div>

      <div className="tsb-pt__seg" role="tablist" aria-label="Order type">
        <button type="button" className={type === 'MARKET' ? 'is-on' : ''} onClick={() => switchType('MARKET')}>
          Market
        </button>
        <button type="button" className={type === 'LIMIT' ? 'is-on' : ''} onClick={() => switchType('LIMIT')}>
          Limit
        </button>
      </div>

      <label className="tsb-pt__field">
        <span>Price</span>
        {type === 'MARKET' ? (
          <div className="tsb-pt__static">
            {price(livePrice)} <em>live</em>
          </div>
        ) : (
          <input
            inputMode="decimal"
            value={limit}
            onChange={(e) => setLimit(e.target.value)}
            placeholder="Limit price"
          />
        )}
      </label>

      <label className="tsb-pt__field">
        <span>Amount</span>
        <div className="tsb-pt__amount">
          <input
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder={unit === 'USDT' ? 'Amount in USDT' : `${base} amount`}
          />
          <button
            type="button"
            className="tsb-pt__unit"
            onClick={() => setUnit(unit === 'USDT' ? 'BASE' : 'USDT')}
            title="Switch between USDT and coins"
          >
            {unit === 'USDT' ? 'USDT' : base} ⇄
          </button>
        </div>
      </label>

      <div className="tsb-pt__quick">
        {[0.25, 0.5, 0.75, 1].map((s) => (
          <button key={s} type="button" onClick={() => fillPct(s)}>
            {s * 100}%
          </button>
        ))}
      </div>

      <div className="tsb-pt__estimate">
        {estimate ? (
          <>
            ≈ {fmtQty(estimate.units)} {base} · {usd(estimate.value)} USDT
            <br />
            fee ≈ {usd(estimate.fee, 4)} USDT (0.1%)
            {estimate.units > 0 && (
              <div className="tsb-pt__effect">{orderEffect(position, buy, estimate.units, base)}</div>
            )}
            {estimate.tooSmall && (
              <div className="tsb-pt__warn">
                Minimum order is {MIN_ORDER} USDT. {base} is traded in steps of {step} {base}, so
                this rounds down to {usd(estimate.value)} USDT — try a little more.
              </div>
            )}
          </>
        ) : (
          <>
            {against && position
              ? `Enter an amount — 100% closes your ${position.side.toLowerCase()}`
              : buy
                ? 'Buy to go long (profit if the price rises)'
                : 'Sell to go short (profit if the price falls)'}
          </>
        )}
      </div>

      <button
        type="button"
        className={`tsb-pt__submit ${side === 'BUY' ? 'is-buy' : 'is-sell'}`}
        disabled={
          busy || amountNum <= 0 || !!estimate?.tooSmall || (type === 'LIMIT' && !(Number(limit) > 0))
        }
        onClick={submit}
      >
        {busy ? 'Placing…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${base}${type === 'LIMIT' ? ' (limit)' : ''}`}
      </button>

      {message && (
        <div className={`tsb-pt__msg ${message.kind === 'err' ? 'is-err' : ''}`} role="status">
          {message.text}
        </div>
      )}

      {position && (
        <div className="tsb-pt__holding">
          <div className="tsb-pt__holding-head">
            <span className={`tsb-pt__side is-${position.side.toLowerCase()}`}>{position.side}</span>
            <b>
              {fmtQty(position.qty)} {base}
            </b>
            <span className="tsb-pt__muted">@ {price(position.avgPrice)}</span>
          </div>
          <div className={pnlClass(posPnl)}>
            {signed(posPnl)} USDT ({pct(livePnlPct(position, livePrice))})
          </div>
          <button type="button" className="tsb-pt__close" onClick={closeNow} disabled={busy}>
            Close position
          </button>
        </div>
      )}

      <button type="button" className="tsb-pt__reset" onClick={reset}>
        Reset account
      </button>
    </aside>
  )
}

function trim(v: number): string {
  return Number(v.toFixed(8)).toString()
}
