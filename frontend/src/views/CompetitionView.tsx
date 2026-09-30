import { useState } from 'react'
import CompetitionList from '../competition/CompetitionList'
import CompetitionDetail from '../competition/CompetitionDetail'
import CreateCompetition from '../competition/CreateCompetition'
import '../competition/competition.css'

/**
 * Competitions, following the sequence diagram:
 * Setup (admin creates, sets the rules) → Join (traders submit, entries
 * lock) → Run (every new candle, the live leaderboard and your rule
 * dashboard) → End (passed / failed, final cumulative ranks).
 */
export default function CompetitionView() {
  const [screen, setScreen] = useState<{ kind: 'list' } | { kind: 'create' } | { kind: 'detail'; id: number }>({
    kind: 'list',
  })

  if (screen.kind === 'create') {
    return (
      <section className="panel">
        <CreateCompetition
          onDone={(id) => setScreen({ kind: 'detail', id })}
          onCancel={() => setScreen({ kind: 'list' })}
        />
      </section>
    )
  }
  if (screen.kind === 'detail') {
    return (
      <section className="panel">
        <CompetitionDetail id={screen.id} onBack={() => setScreen({ kind: 'list' })} />
      </section>
    )
  }
  return (
    <section className="panel">
      <CompetitionList
        onOpen={(id) => setScreen({ kind: 'detail', id })}
        onCreate={() => setScreen({ kind: 'create' })}
      />
    </section>
  )
}
