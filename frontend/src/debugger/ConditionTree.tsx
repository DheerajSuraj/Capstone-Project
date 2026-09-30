import type { ConditionNodeDto, SpanDto } from '../api'
import { CompareGauge, CrossGauge, crossStory } from './Gauge'
import { leaf } from './humanize'

const sameSpan = (a: SpanDto, b: SpanDto) =>
  a.startLine === b.startLine &&
  a.startCol === b.startCol &&
  a.endLine === b.endLine &&
  a.endCol === b.endCol

const isLeaf = (n: ConditionNodeDto) =>
  n.kind === 'COMPARE' || n.kind === 'CROSSOVER' || n.kind === 'CROSSUNDER'

/**
 * A condition as a checklist: each part is a card with a plain-English
 * sentence and a gauge; AND / OR become "all of these" / "at least one".
 *
 * Pass/fail is amber ✓ and grey ✗, never green/red — in this app green and
 * red only ever mean profit and loss.
 */
export default function ConditionTree({
  node,
  highlight,
}: {
  node: ConditionNodeDto
  highlight?: SpanDto | null
}) {
  return <Part node={node} highlight={highlight ?? null} />
}

function Part({
  node,
  highlight,
}: {
  node: ConditionNodeDto
  highlight: SpanDto | null
}) {
  if (isLeaf(node)) return <LeafCard node={node} highlight={highlight} />

  if (node.kind === 'LET') {
    return (
      <div className="tsb-grp">
        <div className="tsb-grp__title">
          <Tick node={node} /> “{node.text}”
        </div>
        <Part node={node.children[0]} highlight={highlight} />
      </div>
    )
  }

  if (node.kind === 'NOT') {
    return (
      <div className="tsb-grp">
        <div className="tsb-grp__title">
          <Tick node={node} /> This must <b>not</b> be true:
        </div>
        <div className="tsb-grp__body">
          <Part node={node.children[0]} highlight={highlight} />
        </div>
      </div>
    )
  }

  const and = node.kind === 'AND'
  const ok = node.children.filter((c) => c.passed).length
  const total = node.children.length
  return (
    <div className="tsb-grp">
      <div className="tsb-grp__title">
        <Tick node={node} />
        {and ? (
          <>
            <b>All {total}</b> must be true
          </>
        ) : (
          <>
            <b>Any one</b> of these is enough
          </>
        )}
        <span className="tsb-grp__score">
          {ok} of {total} true
        </span>
      </div>
      <div className="tsb-grp__body">
        {node.children.map((c, i) => (
          <Part key={i} node={c} highlight={highlight} />
        ))}
      </div>
    </div>
  )
}

function LeafCard({
  node,
  highlight,
}: {
  node: ConditionNodeDto
  highlight: SpanDto | null
}) {
  const closest = highlight != null && sameSpan(node.span, highlight)
  const state = node.passed ? 'pass' : node.unknown ? 'unknown' : 'fail'
  return (
    <div className={`tsb-leaf tsb-leaf--${state}${closest ? ' is-closest' : ''}`}>
      <div className="tsb-leaf__head">
        <Tick node={node} />
        <span className="tsb-leaf__say">{leaf(node.text, node.kind, node.op)}</span>
        {closest && (
          <span className="tsb-leaf__flag" title="Changing just this one part would have flipped the decision">
            {node.passed ? 'closest to failing' : 'closest to passing'}
          </span>
        )}
      </div>
      <code className="tsb-leaf__code">{node.text}</code>
      {node.unknown ? (
        <div className="tsb-leaf__wait">
          ⏳ Not enough history yet: this indicator has no value on this candle.
        </div>
      ) : node.kind === 'COMPARE' ? (
        <CompareGauge node={node} />
      ) : (
        <>
          <CrossGauge node={node} />
          {(crossStory(node) ?? node.note) && (
            <div className="tsb-leaf__note">{crossStory(node) ?? node.note}</div>
          )}
        </>
      )}
    </div>
  )
}

function Tick({ node }: { node: ConditionNodeDto }) {
  const state = node.passed ? 'pass' : node.unknown ? 'unknown' : 'fail'
  return (
    <span className={`tsb-tick tsb-tick--${state}`} aria-label={state}>
      {node.passed ? '✓' : node.unknown ? '…' : '✗'}
    </span>
  )
}
