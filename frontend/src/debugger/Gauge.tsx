import type { ConditionNodeDto } from '../api'
import { num, pct } from './format'
import { expr, fixedScale, say } from './humanize'

const clamp = (x: number) => Math.min(1, Math.max(0, x))

/**
 * A comparison drawn as a slider: the shaded band is where the value needed
 * to be, the dot is where it actually was. "Close but not quite" is visible
 * at a glance, without reading a single number.
 */
export function CompareGauge({ node }: { node: ConditionNodeDto }) {
  const L = node.left
  const R = node.right
  const op = node.op
  if (L == null || R == null || !op) return null

  const leftText = node.text.slice(0, node.text.indexOf(` ${op} `)) || node.text
  let [lo, hi] = fixedScale(leftText) ?? [Math.min(L, R), Math.max(L, R)]
  if (!fixedScale(leftText)) {
    const span = hi - lo || Math.abs(R) * 0.02 || 1
    lo -= span * 0.7
    hi += span * 0.7
  }
  const pos = (v: number) => clamp((v - lo) / (hi - lo)) * 100

  const t = pos(R)
  const zone =
    op === '<' || op === '<='
      ? { left: 0, width: t }
      : op === '>' || op === '>='
        ? { left: t, width: 100 - t }
        : op === '=='
          ? { left: Math.max(0, t - 1), width: 2 }
          : null

  const need =
    op === '<' ? 'below' : op === '<=' ? 'at or below' : op === '>' ? 'above'
      : op === '>=' ? 'at or above' : op === '==' ? 'exactly' : 'not'

  return (
    <div className="tsb-g">
      <div className="tsb-g__track">
        {zone && (
          <div
            className="tsb-g__zone"
            style={{ left: `${zone.left}%`, width: `${zone.width}%` }}
          />
        )}
        <div className="tsb-g__tick" style={{ left: `${t}%` }} />
        <div
          className={`tsb-g__dot${node.passed ? ' is-pass' : ''}`}
          style={{ left: `${pos(L)}%` }}
        />
      </div>
      <div className="tsb-g__legend">
        <span>
          <b>{say(leftText)}</b> was <b>{num(L)}</b>
        </span>
        <span>
          needed {need} <b>{num(R)}</b>
        </span>
        <span className={node.passed ? 'tsb-g__ok' : 'tsb-g__gap'}>
          {node.distance == null
            ? ''
            : node.passed
              ? `${num(node.distance)} to spare`
              : `${num(node.distance)} away${
                  node.relativeDistance != null ? ` (${pct(node.relativeDistance, 1)})` : ''
                }`}
        </span>
      </div>
    </div>
  )
}

/**
 * A crossing drawn as "before → now": two little lanes showing which line was
 * on top on the previous candle and on this one.
 */
export function CrossGauge({ node }: { node: ConditionNodeDto }) {
  const { previousLeft: pl, previousRight: pr, left: l, right: r } = node
  if (pl == null || pr == null || l == null || r == null) return null
  const m = node.text.match(/^CROSS(?:OVER|UNDER)\((.*)\)$/)
  const [a, b] = m ? splitTop(m[1]) : ['A', 'B']
  const rel = (x: number, y: number) => (x > y ? 'above' : x < y ? 'below' : 'level with')
  return (
    <div className="tsb-x">
      <div className="tsb-x__step">
        <div className="tsb-x__when">Previous candle</div>
        <div>
          {say(a)} {rel(pl, pr)} {expr(b)}
        </div>
        <div className="tsb-x__nums">
          {num(pl)} vs {num(pr)}
        </div>
      </div>
      <div className="tsb-x__arrow" aria-hidden>
        →
      </div>
      <div className="tsb-x__step">
        <div className="tsb-x__when">This candle</div>
        <div>
          {say(a)} {rel(l, r)} {expr(b)}
        </div>
        <div className="tsb-x__nums">
          {num(l)} vs {num(r)}
        </div>
      </div>
    </div>
  )
}

/** Why a crossing did or didn't happen, in words. */
export function crossStory(node: ConditionNodeDto): string | null {
  const { previousLeft: pl, previousRight: pr, left: l, right: r } = node
  if (pl == null || pr == null || l == null || r == null) return null
  const m = node.text.match(/^CROSS(?:OVER|UNDER)\((.*)\)$/)
  if (!m) return null
  const [a, b] = splitTop(m[1])
  const over = node.kind === 'CROSSOVER'
  const dir = over ? 'above' : 'below'
  const farNow = over ? l > r : l < r
  const farBefore = over ? pl > pr : pl < pr
  if (node.passed) return `${say(a)} crossed ${dir} ${expr(b)} on this candle.`
  if (farNow && farBefore)
    return `${say(a)} was already ${dir} ${expr(b)} on the previous candle. A cross only counts on the candle where it happens.`
  if (farBefore)
    return `${say(a)} went the other way on this candle, moving ${over ? 'below' : 'above'} ${expr(b)}.`
  return `${say(a)} stayed ${over ? 'below' : 'above'} ${expr(b)}. It needed to move ${num(Math.abs(l - r))} to cross.`
}

function splitTop(s: string): [string, string] {
  let d = 0
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '(') d++
    else if (s[i] === ')') d--
    else if (s[i] === ',' && d === 0) return [s.slice(0, i).trim(), s.slice(i + 1).trim()]
  }
  return [s, '']
}
