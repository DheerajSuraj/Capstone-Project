import { useState } from 'react'
import DrawableChart from '../charts/DrawableChart'

/** The front door: a full-height live market chart with drawing tools,
 *  no login required. Building & backtesting live behind the sidebar. */
export default function HomeView() {
  const [symbol, setSymbol] = useState('BTCUSDT')
  const [timeframe, setTimeframe] = useState('1h')

  return (
    <div className="home">
      <div className="home-controls">
        <select value={symbol} onChange={(e) => setSymbol(e.target.value)}>
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
        <span className="note">Live market · draw on the chart · build from the sidebar</span>
      </div>
      <DrawableChart symbol={symbol} timeframe={timeframe} height="calc(100vh - 172px)" />
    </div>
  )
}