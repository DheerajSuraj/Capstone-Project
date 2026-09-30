import { useEffect, useState } from 'react'
import ForumList from '../forum/ForumList'
import PostPage from '../forum/PostPage'
import Compose from '../forum/Compose'
import '../forum/forum.css'

type Screen = { kind: 'list' } | { kind: 'post'; id: number } | { kind: 'compose' }

/**
 * The forum. Anyone can read; posting, liking, commenting and reporting ask
 * the reader to sign in first. A chart snapshot handed over from the Live
 * chart opens straight into a new post.
 */
export default function ForumView({
  signedIn,
  onSignIn,
  snapshot,
  onSnapshotUsed,
  onOpenStrategies,
}: {
  signedIn: boolean
  onSignIn: () => void
  snapshot: Blob | null
  onSnapshotUsed: () => void
  onOpenStrategies: () => void
}) {
  const [screen, setScreen] = useState<Screen>(snapshot ? { kind: 'compose' } : { kind: 'list' })
  const [pending] = useState<Blob | null>(snapshot)

  useEffect(() => {
    if (snapshot) onSnapshotUsed()
  }, [snapshot, onSnapshotUsed])

  const compose = () => (signedIn ? setScreen({ kind: 'compose' }) : onSignIn())

  return (
    <section className="panel">
      {screen.kind === 'list' && <ForumList onOpen={(id) => setScreen({ kind: 'post', id })} onCompose={compose} />}
      {screen.kind === 'post' && (
        <PostPage
          id={screen.id}
          signedIn={signedIn}
          onSignIn={onSignIn}
          onBack={() => setScreen({ kind: 'list' })}
          onOpenStrategies={onOpenStrategies}
        />
      )}
      {screen.kind === 'compose' &&
        (signedIn ? (
          <Compose
            initialImage={pending}
            onPosted={(id) => setScreen({ kind: 'post', id })}
            onCancel={() => setScreen({ kind: 'list' })}
          />
        ) : (
          <div className="tsb-f">
            <p>Sign in to post.</p>
            <button type="button" onClick={onSignIn}>Sign in</button>
          </div>
        ))}
    </section>
  )
}
