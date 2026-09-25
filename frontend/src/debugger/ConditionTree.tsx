import type { ConditionNodeDto, SpanDto } from '../api'
import { num, pct } from './format'

const sameSpan = (a: SpanDto, b: SpanDto) =>
  a.startLine === b.startLine &&
  a.startCol === b.startCol &&
  a.endLine === b.endLine &&
  a.endCol === b.endCol

const HEAD: Partial<Record<ConditionNodeDto['kind'], string>> = {
  AND: 'ALL of',
  OR: 'ANY of',
  NOT: 'NOT',
}

/**
 * A condition, piece by piece, as the engine saw it at one bar.
 *
 * Pass/fail is shown with ✓ / ✗ in amber and grey, never green/red: in this
 * app green and red only ever mean profit and loss.
 */
export default function ConditionTree({
  node,
  highlight,
}: {
  node: ConditionNodeDto
  /** Span of the closest-change leaf, emphasised. */
  highlight?: SpanDto | null
}) {
  return (
    <ul className="tsb-dbg__tree">
      <Node node={node} highlight={highlight ?? null} />
    </ul>
  )
}

function Node({
  node,
  highlight,
}: {
  node: ConditionNodeDto
  highlight: SpanDto | null
}) {
  const state = node.passed ? 'pass' : node.unknown ? 'unknown' : 'fail'
  const icon = node.passed ? '✓' : node.unknown ? '?' : '✗'
  const isLeaf =
    node.kind === 'COMPARE' ||
    node.kind === 'CROSSOVER' ||
    node.kind === 'CROSSUNDER'
  const closest = isLeaf && highlight != null && sameSpan(node.span, highlight)

  return (
    <li className={`tsb-dbg__node tsb-dbg__node--${state}${closest ? ' is-closest' : ''}`}>
      <div className="tsb-dbg__row">
        <span className="tsb-dbg__icon" aria-label={state}>
          {icon}
        </span>
        {isLeaf ? (
          <code className="tsb-dbg__code">{node.text}</code>
        ) : node.kind === 'LET' ? (
          <span className="tsb-dbg__head">
            <code className="tsb-dbg__code">{node.text}</code>
            <span className="tsb-dbg__muted"> (named condition)</span>
          </span>
        ) : (
          <span className="tsb-dbg__head">{HEAD[node.kind]}</span>
        )}
        {isLeaf && !node.unknown && <Values node={node} />}
        {closest && <span className="tsb-dbg__tag">closest</span>}
      </div>
      {/* A comparison's values already say it; crossings and undefined values need words. */}
      {node.note && isLeaf && (node.kind !== 'COMPARE' || node.unknown) && (
        <div className="tsb-dbg__note">{node.note}</div>
      )}
      {node.children.length > 0 && (
        <ul className="tsb-dbg__tree">
          {node.children.map((c, i) => (
            <Node key={i} node={c} highlight={highlight} />
          ))}
        </ul>
      )}
    </li>
  )
}

function Values({ node }: { node: ConditionNodeDto }) {
  const cross = node.kind !== 'COMPARE'
  return (
    <span className="tsb-dbg__values">
      {cross ? (
        <>
          {num(node.previousLeft)} / {num(node.previousRight)} →{' '}
          {num(node.left)} / {num(node.right)}
        </>
      ) : (
        <>
          {num(node.left)} {node.op} {num(node.right)}
        </>
      )}
      {node.distance != null && (
        <span className="tsb-dbg__muted">
          {' '}
          · {node.passed ? 'margin' : 'off by'} {num(node.distance)}
          {node.relativeDistance != null && ` (${pct(node.relativeDistance)})`}
        </span>
      )}
    </span>
  )
}
