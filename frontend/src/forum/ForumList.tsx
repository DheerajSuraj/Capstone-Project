import { useEffect, useState } from 'react'
import { api, type ForumCategory, type ForumSummaryDto } from '../api'
import { ago, CATEGORIES, categoryIcon, categoryLabel, pnlClass, signedPct } from './util'

export default function ForumList({
  onOpen,
  onCompose,
}: {
  onOpen: (id: number) => void
  onCompose: () => void
}) {
  const [category, setCategory] = useState<ForumCategory | null>(null)
  const [sort, setSort] = useState<'new' | 'top'>('new')
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [posts, setPosts] = useState<ForumSummaryDto[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Search after the typing stops, not on every key.
  useEffect(() => {
    const t = setTimeout(() => setQ(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    api
      .forumPosts({ category: category ?? undefined, q: q || undefined, sort, page })
      .then((r) => {
        if (cancelled) return
        setPosts((prev) => (page === 0 ? r.posts : [...prev, ...r.posts]))
        setHasMore(r.hasMore)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [category, q, sort, page])

  const pick = (c: ForumCategory | null) => {
    setPage(0)
    setCategory(c)
  }

  return (
    <div className="tsb-f">
      <header className="tsb-f__top">
        <div>
          <h2>Forum</h2>
          <p className="tsb-f__muted">
            Share strategies and chart ideas, ask for help, talk about the market.
          </p>
        </div>
        <button type="button" onClick={onCompose}>
          New post
        </button>
      </header>

      <div className="tsb-f__bar">
        <div className="tsb-f__cats" role="tablist">
          <button type="button" className={category === null ? 'is-on' : ''} onClick={() => pick(null)}>
            All
          </button>
          {CATEGORIES.map((c) => (
            <button key={c.id} type="button" className={category === c.id ? 'is-on' : ''} onClick={() => pick(c.id)}>
              {c.icon} {c.label}
            </button>
          ))}
        </div>
        <div className="tsb-f__tools">
          <input
            type="search"
            placeholder="Search posts…"
            value={search}
            onChange={(e) => {
              setPage(0)
              setSearch(e.target.value)
            }}
          />
          <select
            value={sort}
            onChange={(e) => {
              setPage(0)
              setSort(e.target.value as 'new' | 'top')
            }}
          >
            <option value="new">Newest</option>
            <option value="top">Most liked</option>
          </select>
        </div>
      </div>

      {error && <p className="error-note">{error}</p>}

      {!loading && posts.length === 0 && !error && (
        <div className="tsb-f__empty">
          <div className="tsb-f__empty-icon" aria-hidden>🗨️</div>
          <div className="tsb-f__empty-title">{q ? 'Nothing matches that search' : 'No posts yet'}</div>
          <p>{q ? 'Try other words.' : 'Be the first — share a strategy or a chart.'}</p>
        </div>
      )}

      <div className="tsb-f__list">
        {posts.map((p) => (
          <button key={p.id} type="button" className={`tsb-f__card${p.hidden ? ' is-hidden' : ''}`} onClick={() => onOpen(p.id)}>
            <div className="tsb-f__card-main">
              <div className="tsb-f__meta">
                <span className="tsb-f__cat">
                  {categoryIcon(p.category)} {categoryLabel(p.category)}
                </span>
                <span>@{p.author}</span>
                <span>{ago(p.createdAt)}</span>
                {p.hidden && <span className="tsb-f__flag">hidden</span>}
              </div>
              <div className="tsb-f__title">{p.title}</div>
              {p.excerpt && <div className="tsb-f__excerpt">{p.excerpt}</div>}
              <div className="tsb-f__foot">
                <span className={p.liked ? 'is-liked' : ''}>♥ {p.likes}</span>
                <span>💬 {p.comments}</span>
                {p.imageCount > 0 && <span>🖼 {p.imageCount}</span>}
                {p.strategyName && (
                  <span className="tsb-f__strat">
                    📈 {p.strategyName}
                    {p.strategyReturnPct != null && (
                      <b className={pnlClass(p.strategyReturnPct)}> {signedPct(p.strategyReturnPct)}</b>
                    )}
                  </span>
                )}
              </div>
            </div>
            {p.thumbnail && <img className="tsb-f__thumb" src={p.thumbnail} alt="" loading="lazy" />}
          </button>
        ))}
      </div>

      {hasMore && (
        <button type="button" className="ghost tsb-f__more" onClick={() => setPage((n) => n + 1)} disabled={loading}>
          {loading ? 'Loading…' : 'Load more'}
        </button>
      )}
    </div>
  )
}
