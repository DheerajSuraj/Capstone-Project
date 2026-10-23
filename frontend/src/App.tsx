import { useCallback, useEffect, useRef, useState } from 'react'
import HomeView from './views/HomeView'
import BuilderView from './views/BuilderView'
import LabView from './views/LabView'
import StrategiesView from './views/StrategiesView'
import CompetitionView from './views/CompetitionView'
import ForumView from './views/ForumView'
import { useAuth } from './auth'
import AuthOverlay from './auth/AuthOverlay'

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

type View = 'home' | 'builder' | 'strategies' | 'lab' | 'competition' | 'forum'

const NAV: { view: View; glyph: string; label: string }[] = [
  { view: 'home', glyph: '▥', label: 'Live chart' },
  { view: 'builder', glyph: '⬚', label: 'Builder' },
  { view: 'strategies', glyph: '☰', label: 'My strategies' },
  { view: 'lab', glyph: '⚗', label: 'Strategy lab' },
  { view: 'competition', glyph: '⚑', label: 'Competitions' },
  { view: 'forum', glyph: '❝', label: 'Forum' },
]

/** The live chart reads public candle data; everything else is owned. */
const isPublic = (view: View) => view === 'home' || view === 'forum'

export default function App() {
  const { status, user, signOut } = useAuth()

  const [view, setView] = useState<View>('home')
  const [source, setSource] = useState(STARTER)
  const [name, setName] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  // What the Blocks view last produced, and its blocks: the Builder opens on
  // Code whenever the source is anything else, and coming back to Blocks
  // shows the user's blocks rather than the starter.
  const [blocksSource, setBlocksSource] = useState<string | null>(null)
  const [blocksState, setBlocksState] = useState<object | null>(null)
  const [authOpen, setAuthOpen] = useState(false)
  const [pendingView, setPendingView] = useState<View | null>(null)
  // A chart snapshot on its way from the Live chart to a new forum post.
  const [snapshot, setSnapshot] = useState<Blob | null>(null)
  const clearSnapshot = useCallback(() => setSnapshot(null), [])

  // Who is using the app. When that changes — signing out, or someone else
  // signing in — nothing of the last person may stay on screen: their
  // strategy in the editor, their lists, their liked posts. The views that
  // hold someone's own data are keyed by this, so they start fresh; the
  // editor's state lives here and is reset by hand. (The live chart and the
  // forum follow sign-in and sign-out themselves, keeping your place.)
  const person = status === 'authenticated' && user ? `user:${user.id}` : 'anonymous'
  const lastPerson = useRef(person)
  useEffect(() => {
    if (lastPerson.current === person) return
    const someoneLeft = lastPerson.current !== 'anonymous'
    lastPerson.current = person
    if (!someoneLeft) return // signing in keeps what an anonymous visitor had
    setSource(STARTER)
    setName('')
    setEditingId(null)
    setBlocksSource(null)
    setBlocksState(null)
    setSnapshot(null)
    setView((v) => (isPublic(v) ? v : 'home'))
  }, [person])

  // Send them where they were headed once they sign in, rather than making
  // them find the button again.
  useEffect(() => {
    if (status === 'authenticated' && pendingView) {
      setView(pendingView)
      setPendingView(null)
    }
  }, [status, pendingView])

  const editStrategy = (id: number, strategyName: string, src: string) => {
    setEditingId(id)
    setName(strategyName)
    setSource(src)
    setView('builder')
  }

  const go = (target: View) => {
    if (!isPublic(target) && status !== 'authenticated') {
      setPendingView(target)
      setAuthOpen(true)
      return
    }
    if (target === 'builder' && view !== 'builder') {
      setEditingId(null)
    }
    setView(target)
  }

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-name">TSB</span>
          <span className="tag">Trading Strategy Builder</span>
        </div>
        <div>
          {status === 'authenticated' ? (
            <>
              <span className="note" style={{ marginRight: 10 }}>
                {user?.username}
              </span>
              <button className="ghost" onClick={signOut}>
                Sign out
              </button>
            </>
          ) : (
            <button className="ghost" onClick={() => setAuthOpen(true)}>
              Log in / Sign up
            </button>
          )}
        </div>
      </header>

      <nav className="rail" aria-label="Main">
        {NAV.map((item) => (
          <button
            key={item.view}
            className={`rail-item ${view === item.view ? 'active' : ''}`}
            onClick={(e) => {
              go(item.view)
              // The rail opens while it has focus (for keyboard users). After
              // a mouse click that kept it open over the page's left edge —
              // on top of buttons like "Run backtest" — until something
              // else was clicked.
              if (e.detail > 0) e.currentTarget.blur()
            }}
          >
            <span className="glyph">{item.glyph}</span>
            <span className="label">{item.label}</span>
          </button>
        ))}
      </nav>

      <main className="content">
        {view === 'home' && (
          <HomeView
            onSignIn={() => setAuthOpen(true)}
            onSnapshot={(png) => {
              setSnapshot(png)
              if (status === 'authenticated') setView('forum')
              else {
                setPendingView('forum')
                setAuthOpen(true)
              }
            }}
          />
        )}
        {view === 'builder' && (
          <BuilderView
            key={person}
            source={source}
            setSource={setSource}
            name={name}
            setName={setName}
            editingId={editingId}
            onSaved={(id) => setEditingId(id)}
            startInCode={editingId != null || (source !== STARTER && source !== blocksSource)}
            blocksSource={blocksSource}
            onBlocksSource={setBlocksSource}
            blocksState={blocksState}
            onBlocksState={setBlocksState}
          />
        )}
        {view === 'strategies' && <StrategiesView key={person} onEdit={editStrategy} />}
        {view === 'lab' && (
          <LabView
            key={person}
            onLoad={(strategyName, src) => {
              setEditingId(null)
              setName(strategyName)
              setSource(src)
              setView('builder')
            }}
          />
        )}
        {view === 'competition' && <CompetitionView key={person} />}
        {view === 'forum' && (
          <ForumView
            signedIn={status === 'authenticated'}
            onSignIn={() => setAuthOpen(true)}
            snapshot={snapshot}
            onSnapshotUsed={clearSnapshot}
            onOpenStrategies={() => go('strategies')}
          />
        )}
      </main>

      <AuthOverlay
        open={authOpen}
        onClose={() => {
          setAuthOpen(false)
          setPendingView(null)
        }}
      />
    </div>
  )
}