import { useCallback, useMemo, useState } from 'react'
import DrawableChart, { type ChartOverlays, type ChartTag } from '../charts/DrawableChart'
import { api } from '../api'
import { livePnl, livePnlPct } from '../paper/live'
import { useAuth } from '../auth'
import TradePanel from '../paper/TradePanel'
import PaperActivity from '../paper/PaperActivity'
import { usePaperAccount } from '../paper/usePaperAccount'
import { price as fmtPrice, qty as fmtQty } from '../paper/format'

/** The front door: the live market chart with drawing tools, and paper
 *  trading beside it — buy and sell with fake money at real prices. */
export default function HomeView({
  onSignIn,
  onSnapshot,
}: {
  onSignIn: () => void
  onSnapshot: (png: Blob) => void
}) {
  const { status } = useAuth()
  const signedIn = status === 'authenticated'
  const [symbol, setSymbol] = useState('BTCUSDT')
  const [timeframe, setTimeframe] = useState('1h')
  const [livePrice, setLivePrice] = useState<number | null>(null)
  const { account, setAccount } = usePaperAccount(signedIn)

  const changeSymbol = (s: string) => {
    setLivePrice(null)
    setSymbol(s)
  }

  const onPrice = useCallback((p: number) => setLivePrice(p), [])

  const closePosition = useCallback(
    async (sym: string) => {
      try {
        setAccount((await api.paperClose(sym)).account)
      } catch (e) {
        window.alert(e instanceof Error ? e.message : String(e))
      }
    },
    [setAccount],
  )
  const cancelOrder = useCallback(
    async (id: number) => {
      try {
        setAccount(await api.paperCancel(id))
      } catch (e) {
        window.alert(e instanceof Error ? e.message : String(e))
      }
    },
    [setAccount],
  )

  // Your position and orders on the chart, like TradingView: a line at the
  // entry price with a tag showing side, size and live P&L, and ✕ to close.
  const overlays = useMemo<ChartOverlays | undefined>(() => {
    if (!account) return undefined
    const lines: ChartOverlays['lines'] = []
    const tags: ChartTag[] = []
    const pos = account.positions.find((p) => p.symbol === symbol)
    if (pos) {
      const pnl = livePnl(pos, livePrice)
      const color = pnl > 0 ? '#4cc38a' : pnl < 0 ? '#e5544b' : '#8b909a'
      lines.push({ price: pos.avgPrice, color, title: '' })
      tags.push({
        id: `pos-${pos.symbol}`,
        price: pos.avgPrice,
        label: `${pos.side} ${fmtQty(pos.qty)}`,
        pnl: `${pnl >= 0 ? '+' : '−'}${Math.abs(pnl).toFixed(2)} (${livePnlPct(pos, livePrice).toFixed(2)}%)`,
        tone: pnl > 0 ? 'up' : pnl < 0 ? 'down' : 'flat',
        onAction: () => closePosition(pos.symbol),
        actionTitle: 'Close position at market',
      })
    }
    for (const o of account.openOrders.filter((x) => x.symbol === symbol)) {
      if (o.limitPrice == null) continue
      lines.push({ price: o.limitPrice, color: '#8b909a', dashed: true, title: '' })
      tags.push({
        id: `ord-${o.id}`,
        price: o.limitPrice,
        label: `Limit ${o.side === 'BUY' ? 'buy' : 'sell'} ${fmtQty(o.qty)}`,
        tone: 'order',
        onAction: () => cancelOrder(o.id),
        actionTitle: 'Cancel this order',
      })
    }
    const markers = account.history
      .filter((o) => o.symbol === symbol && o.status === 'FILLED' && o.filledAt)
      .map((o) => ({
        timeMillis: Date.parse(o.filledAt as string),
        side: o.side,
        text: `${o.side === 'BUY' ? 'B' : 'S'} ${fmtPrice(o.fillPrice)}`,
      }))
    return { lines, markers, tags }
  }, [account, symbol, livePrice, closePosition, cancelOrder])

  return (
    <div className="home">
      <div className="home-controls">
        <select value={symbol} onChange={(e) => changeSymbol(e.target.value)}>
          <option>BTCUSDT</option>
          <option>ETHUSDT</option>
          <option>SOLUSDT</option>
        </select>
        <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)}>
          <option>5m</option>
          <option>15m</option>
          <option>1h</option>
          <option>4h</option>
        </select>
        <span className="note">Live market · draw on the chart · paper trade with fake money</span>
      </div>

      <div className="home-trade">
        <div className="home-trade__chart">
          <DrawableChart
            symbol={symbol}
            timeframe={timeframe}
            height={signedIn ? 'max(360px, calc(100vh - 420px))' : 'calc(100vh - 172px)'}
            onPrice={onPrice}
            overlays={overlays}
            onSnapshot={onSnapshot}
          />
          {account && (
            <PaperActivity
              account={account}
              onAccount={setAccount}
              symbol={symbol}
              livePrice={livePrice}
            />
          )}
        </div>
        <TradePanel
          symbol={symbol}
          livePrice={livePrice}
          account={account}
          onAccount={setAccount}
          signedIn={signedIn}
          onSignIn={onSignIn}
        />
      </div>
    </div>
  )
}
