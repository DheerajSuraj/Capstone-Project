import { useCallback, useEffect, useState } from 'react'
import { api, type ForumPostDto } from '../api'
import RichText from './RichText'
import StrategyCard from './StrategyCard'
import { ago, categoryIcon, categoryLabel } from './util'

export default function PostPage({
  id,
  signedIn,
  onSignIn,
  onBack,
  onOpenStrategies,
}: {
  id: number
  signedIn: boolean
  onSignIn: () => void
  onBack: () => void
  onOpenStrategies: () => void
}) {
  const [data, setData] = useState<ForumPostDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const [zoom, setZoom] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)

  const load = useCallback(() => {
    api
      .forumPost(id)
      .then((d) => {
        setData(d)
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [id])

  useEffect(load, [load])

  // Esc closes the full-size picture.
  useEffect(() => {
    if (!zoom) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setZoom(null)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoom])

  if (error && !data) {
    return (
      <div className="tsb-f">
        <button type="button" className="ghost" onClick={onBack}>← Forum</button>
        <p className="error-note">{error}</p>
      </div>
    )
  }
  if (!data) return <p className="tsb-f__muted">Loading…</p>

  const p = data.post
  const need = (fn: () => void) => () => (signedIn ? fn() : onSignIn())
  const act = async (f: () => Promise<unknown>, after?: () => void) => {
    setNote(null)
    try {
      await f()
      after ? after() : load()
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e))
    }
  }

  const like = need(() =>
    act(async () => {
      const r = await api.forumLike(p.id)
      setData((d) => (d ? { ...d, post: { ...d.post, liked: r.liked, likes: r.likes } } : d))
    }, () => {}),
  )
  const report = need(() => {
    const reason = window.prompt('What is wrong with this post? (spam, abuse, scam…)')
    if (reason && reason.trim()) {
      act(() => api.forumReport(p.id, reason.trim()), () => setNote('Thanks — a moderator will take a look.'))
    }
  })
  const remove = () => {
    if (window.confirm('Delete this post?')) act(() => api.forumDelete(p.id), onBack)
  }
  const hide = (hidden: boolean) => act(() => api.forumHide(p.id, hidden))

  const send = async () => {
    if (!signedIn) return onSignIn()
    if (!comment.trim()) return
    setBusy(true)
    try {
      await api.forumComment(p.id, comment.trim())
      setComment('')
      load()
    } catch (e) {
      setNote(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="tsb-f">
      <button type="button" className="ghost" onClick={onBack}>
        ← Forum
      </button>

      <article className="tsb-f__post">
        <div className="tsb-f__meta">
          <span className="tsb-f__cat">
            {categoryIcon(p.category)} {categoryLabel(p.category)}
          </span>
          <span>@{p.author}</span>
          <span>{ago(p.createdAt)}</span>
        </div>
        <h2 className="tsb-f__post-title">{p.title}</h2>

        {data.hidden && (
          <p className="tsb-f__hidden">🙈 {data.hiddenReason ?? 'This post is hidden.'} Only you and moderators can see it.</p>
        )}

        {data.body && <RichText text={data.body} />}

        {data.images.length > 0 && (
          <div className={`tsb-f__gallery n${Math.min(data.images.length, 4)}`}>
            {data.images.map((img) => (
              <button key={img.id} type="button" className="tsb-f__img" onClick={() => setZoom(img.url)}>
                <img src={img.url} alt="" loading="lazy" width={img.width} height={img.height} />
              </button>
            ))}
          </div>
        )}

        {data.strategy && (
          <StrategyCard
            postId={p.id}
            s={data.strategy}
            signedIn={signedIn}
            onSignIn={onSignIn}
            onOpenStrategies={onOpenStrategies}
          />
        )}

        <div className="tsb-f__actions">
          <button type="button" className={`ghost tsb-f__like${p.liked ? ' is-liked' : ''}`} onClick={like}>
            ♥ {p.liked ? 'Liked' : 'Like'} · {p.likes}
          </button>
          {!p.mine && !data.reportedByMe && (
            <button type="button" className="ghost" onClick={report}>
              ⚑ Report
            </button>
          )}
          {data.reportedByMe && <span className="tsb-f__muted tsb-f__small">You reported this post</span>}
          {(p.mine || data.moderator) && (
            <button type="button" className="ghost" onClick={remove}>
              Delete
            </button>
          )}
          {data.moderator && (
            <button type="button" className="ghost" onClick={() => hide(!data.hidden)}>
              {data.hidden ? 'Unhide (moderator)' : 'Hide (moderator)'}
            </button>
          )}
        </div>
        {note && <p className="tsb-f__note">{note}</p>}
      </article>

      <section className="tsb-f__comments">
        <h3>{data.comments.length} {data.comments.length === 1 ? 'comment' : 'comments'}</h3>
        {data.comments.map((c) => (
          <div key={c.id} className="tsb-f__comment">
            <div className="tsb-f__meta">
              <b>@{c.author}</b>
              <span>{ago(c.createdAt)}</span>
              {(c.mine || data.moderator) && (
                <button
                  type="button"
                  className="tsb-f__link"
                  onClick={() => window.confirm('Delete this comment?') && act(() => api.forumDeleteComment(c.id))}
                >
                  delete
                </button>
              )}
            </div>
            <RichText text={c.body} />
          </div>
        ))}
        <div className="tsb-f__reply">
          <textarea
            rows={3}
            placeholder={signedIn ? 'Write a comment…  (**bold**, `code`, links work)' : 'Sign in to comment'}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            onFocus={() => !signedIn && onSignIn()}
            maxLength={5000}
          />
          <button type="button" onClick={send} disabled={busy || (signedIn && !comment.trim())}>
            {busy ? 'Posting…' : signedIn ? 'Comment' : 'Sign in to comment'}
          </button>
        </div>
      </section>

      {zoom && (
        <div className="tsb-f__zoom" role="dialog" aria-label="Picture" onClick={() => setZoom(null)}>
          <img src={zoom} alt="" />
        </div>
      )}
    </div>
  )
}
