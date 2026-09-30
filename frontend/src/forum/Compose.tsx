import { useEffect, useState } from 'react'
import { api, type ForumCategory, type StrategyDto } from '../api'
import RichText from './RichText'
import { CATEGORIES } from './util'

const MAX_IMAGES = 4
const MAX_BYTES = 5 * 1024 * 1024
const OK_TYPES = ['image/png', 'image/jpeg']

/**
 * Write a post: title, category, text, up to 4 pictures (including a chart
 * snapshot handed over from the main chart), and optionally one of your
 * strategies. Files are checked here for size and type for a quick message,
 * but the server re-checks and re-draws every picture regardless.
 */
export default function Compose({
  initialImage,
  onPosted,
  onCancel,
}: {
  initialImage: Blob | null
  onPosted: (id: number) => void
  onCancel: () => void
}) {
  const [title, setTitle] = useState('')
  const [category, setCategory] = useState<ForumCategory>(initialImage ? 'MARKET' : 'STRATEGIES')
  const [body, setBody] = useState('')
  const [images, setImages] = useState<Blob[]>(initialImage ? [initialImage] : [])
  const [strategies, setStrategies] = useState<StrategyDto[]>([])
  const [strategyId, setStrategyId] = useState<number | null>(null)
  const [preview, setPreview] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [drag, setDrag] = useState(false)

  useEffect(() => {
    api.listStrategies().then(setStrategies).catch(() => setStrategies([]))
  }, [])

  // Preview URLs are made and freed by the same effect, so React's
  // StrictMode (which runs effects twice in development) cannot free a URL
  // that is still on screen.
  const [urls, setUrls] = useState<string[]>([])
  useEffect(() => {
    const made = images.map((b) => URL.createObjectURL(b))
    setUrls(made)
    return () => made.forEach((u) => URL.revokeObjectURL(u))
  }, [images])

  const add = (files: FileList | File[]) => {
    setError(null)
    const next = [...images]
    for (const f of Array.from(files)) {
      if (next.length >= MAX_IMAGES) {
        setError(`A post can have at most ${MAX_IMAGES} pictures.`)
        break
      }
      if (!OK_TYPES.includes(f.type)) {
        setError(`${f.name}: only PNG or JPEG pictures.`)
        continue
      }
      if (f.size > MAX_BYTES) {
        setError(`${f.name}: pictures must be 5 MB or smaller.`)
        continue
      }
      next.push(f)
    }
    setImages(next)
  }

  const chosen = strategies.find((s) => s.id === strategyId) ?? null

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      const r = await api.forumCreate({
        category,
        title,
        body,
        images,
        ...(chosen ? { strategyId: chosen.id, versionNumber: chosen.latestVersion } : {}),
      })
      onPosted(r.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="tsb-f">
      <button type="button" className="ghost" onClick={onCancel}>
        ← Forum
      </button>
      <h2>New post</h2>

      <div className="tsb-f__form">
        <label className="tsb-f__field">
          <span>Title</span>
          <input value={title} maxLength={150} onChange={(e) => setTitle(e.target.value)} placeholder="What's it about?" />
        </label>

        <div className="tsb-f__field">
          <span>Category</span>
          <div className="tsb-f__cats">
            {CATEGORIES.map((c) => (
              <button key={c.id} type="button" className={category === c.id ? 'is-on' : ''} onClick={() => setCategory(c.id)}>
                {c.icon} {c.label}
              </button>
            ))}
          </div>
        </div>

        <div className="tsb-f__field">
          <span className="tsb-f__row">
            Text
            <button type="button" className="tsb-f__link" onClick={() => setPreview((x) => !x)}>
              {preview ? 'edit' : 'preview'}
            </button>
          </span>
          {preview ? (
            <div className="tsb-f__preview">{body ? <RichText text={body} /> : <span className="tsb-f__muted">Nothing to preview.</span>}</div>
          ) : (
            <textarea
              rows={8}
              maxLength={20000}
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder={'Say what you think.\n\n**bold**  *italic*  `code`  ```code block```  - list  https://links'}
            />
          )}
        </div>

        <div className="tsb-f__field">
          <span>Pictures ({images.length}/{MAX_IMAGES})</span>
          <div
            className={`tsb-f__drop${drag ? ' is-drag' : ''}`}
            onDragOver={(e) => {
              e.preventDefault()
              setDrag(true)
            }}
            onDragLeave={() => setDrag(false)}
            onDrop={(e) => {
              e.preventDefault()
              setDrag(false)
              add(e.dataTransfer.files)
            }}
          >
            {images.map((_, i) => (
              <div key={i} className="tsb-f__chip-img">
                <img src={urls[i]} alt="" />
                <button type="button" aria-label="Remove picture" onClick={() => setImages(images.filter((__, j) => j !== i))}>
                  ×
                </button>
              </div>
            ))}
            {images.length < MAX_IMAGES && (
              <label className="tsb-f__add-img">
                <input type="file" accept="image/png,image/jpeg" multiple hidden onChange={(e) => e.target.files && add(e.target.files)} />
                + Add pictures
                <span className="tsb-f__muted tsb-f__small">or drop them here · PNG/JPEG, 5 MB</span>
              </label>
            )}
          </div>
          <span className="tsb-f__muted tsb-f__small">
            Tip: the 📷 Snapshot button on the Live chart puts your chart (with drawings) straight into a new post.
          </span>
        </div>

        <label className="tsb-f__field">
          <span>Share a strategy (optional)</span>
          <select value={strategyId ?? ''} onChange={(e) => setStrategyId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">— none —</option>
            {strategies.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name} v{s.latestVersion} · {s.symbol} {s.timeframe}
              </option>
            ))}
          </select>
          {chosen && (
            <span className="tsb-f__muted tsb-f__small">
              Everyone will see v{chosen.latestVersion}'s code and its backtest results, and can copy it.
              Versions you save later stay private.
            </span>
          )}
        </label>

        {error && <p className="error-note">{error}</p>}
        <div className="tsb-f__row">
          <button type="button" onClick={submit} disabled={busy || title.trim().length < 3}>
            {busy ? (chosen ? 'Backtesting and posting…' : 'Posting…') : 'Post'}
          </button>
          <button type="button" className="ghost" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}
