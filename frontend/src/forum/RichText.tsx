import { Fragment, type ReactNode } from 'react'

/**
 * Post and comment text, with a little formatting — and no HTML, ever.
 *
 * Everything becomes React elements built from the text; nothing is handed
 * to innerHTML. So `<script>` or `<img onerror=…>` in a post is shown as
 * those characters, not run. Links are made only from text that starts with
 * http:// or https://, so a `javascript:` link cannot be made at all.
 *
 * Supported:
 *   **bold**   *italic*   `code`   ```code block```   - list item
 *   blank line = new paragraph   https://links
 */
export default function RichText({ text }: { text: string }) {
  const blocks = text.split(/```/)
  return (
    <div className="tsb-rt">
      {blocks.map((block, i) =>
        i % 2 === 1 ? (
          <pre key={i} className="tsb-rt__pre">
            <code>{block.replace(/^\n/, '').replace(/\n$/, '')}</code>
          </pre>
        ) : (
          <Fragment key={i}>{paragraphs(block)}</Fragment>
        ),
      )}
    </div>
  )
}

const BULLET = /^\s*[-*] /

/** Blank lines split paragraphs; runs of "- " lines inside one become a list. */
function paragraphs(text: string): ReactNode[] {
  const out: ReactNode[] = []
  let k = 0
  for (const para of text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean)) {
    let run: string[] = []
    let isList = false
    const flush = () => {
      if (run.length === 0) return
      out.push(
        isList ? (
          <ul key={k++}>
            {run.map((l, j) => (
              <li key={j}>{inline(l.replace(BULLET, ''))}</li>
            ))}
          </ul>
        ) : (
          <p key={k++}>
            {run.map((l, j) => (
              <Fragment key={j}>
                {j > 0 && <br />}
                {inline(l)}
              </Fragment>
            ))}
          </p>
        ),
      )
      run = []
    }
    for (const line of para.split('\n')) {
      const bullet = BULLET.test(line)
      if (run.length > 0 && bullet !== isList) flush()
      isList = bullet
      run.push(line)
    }
    flush()
  }
  return out
}

const TOKEN = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|https?:\/\/[^\s<>"')\]]+)/g

export function inline(line: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  let k = 0
  for (const m of line.matchAll(TOKEN)) {
    const t = m[0]
    const at = m.index ?? 0
    if (at > last) out.push(line.slice(last, at))
    if (t.startsWith('`')) out.push(<code key={k++}>{t.slice(1, -1)}</code>)
    else if (t.startsWith('**')) out.push(<strong key={k++}>{t.slice(2, -2)}</strong>)
    else if (t.startsWith('*')) out.push(<em key={k++}>{t.slice(1, -1)}</em>)
    else
      out.push(
        <a key={k++} href={t} target="_blank" rel="noopener noreferrer nofollow ugc">
          {t}
        </a>,
      )
    last = at + t.length
  }
  if (last < line.length) out.push(line.slice(last))
  return out
}
