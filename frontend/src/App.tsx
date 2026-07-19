import { useState } from 'react'
import HomeView from './views/HomeView'
import BuilderView from './views/BuilderView'
import StrategiesView from './views/StrategiesView'
import CompetitionView from './views/CompetitionView'

const STARTER = `strategy "My Strategy" {
    symbol = BTCUSDT
    timeframe = 1h
    capital = 10000
    fee = 0.1%

    let rsi = RSI(14)

    rule entry {
        IF rsi < 30 AND CLOSE > SMA(CLOSE, 200)
        THEN BUY qty = 25% OF EQUITY
    }

    rule exit {
        IF rsi > 70 THEN SELL ALL
    }
}
`

type View = 'home' | 'builder' | 'strategies' | 'competition'

const NAV: { view: View; glyph: string; label: string }[] = [
  { view: 'home', glyph: '▥', label: 'Live chart' },
  { view: 'builder', glyph: '⬚', label: 'Builder' },
  { view: 'strategies', glyph: '☰', label: 'My strategies' },
  { view: 'competition', glyph: '⚑', label: 'Competitions' },
]

export default function App() {
  const [view, setView] = useState<View>('home')
  const [source, setSource] = useState(STARTER)
  const [name, setName] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [authNote, setAuthNote] = useState(false)

  const editStrategy = (id: number, strategyName: string, src: string) => {
    setEditingId(id)
    setName(strategyName)
    setSource(src)
    setView('builder')
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-name">TSB</span>
          <span className="tag">Trading Strategy Builder</span>
        </div>
        <div>
          <button className="ghost" onClick={() => setAuthNote(!authNote)}>
            Log in / Sign up
          </button>
          {authNote && <span className="note" style={{ marginLeft: 10 }}>accounts coming soon</span>}
        </div>
      </header>

      <nav className="rail" aria-label="Main">
        {NAV.map((item) => (
          <button
            key={item.view}
            className={`rail-item ${view === item.view ? 'active' : ''}`}
            onClick={() => {
              if (item.view === 'builder' && view !== 'builder') {
                setEditingId(null)
              }
              setView(item.view)
            }}
          >
            <span className="glyph">{item.glyph}</span>
            <span className="label">{item.label}</span>
          </button>
        ))}
      </nav>

      <main className="content">
        {view === 'home' && <HomeView />}
        {view === 'builder' && (
          <BuilderView
            source={source}
            setSource={setSource}
            name={name}
            setName={setName}
            editingId={editingId}
            onSaved={() => setEditingId(null)}
          />
        )}
        {view === 'strategies' && <StrategiesView onEdit={editStrategy} />}
        {view === 'competition' && <CompetitionView />}
      </main>
    </div>
  )
}