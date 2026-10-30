import type {
  StrategyTree,
  TreeAction,
  TreeBinaryOp,
  TreeConfig,
  TreeExpr,
  TreeIf,
  TreeLines,
} from '../api'
import { INDICATORS } from './registry'
import { DEFAULT_FEE_PERCENT, ON_TIMEFRAMES, SYMBOLS, TIMEFRAMES } from './options'
import { num } from './tslText'

// Code -> blocks: the parse tree the compiler sends (POST /api/compile with
// withTree) turned into a Blockly workspace, the reverse of generator.ts.
// Nothing here reads TSL text; the compiler already did, so the blocks are
// the code exactly as the compiler understands it. Whatever the blocks
// can't show is listed in words, never quietly dropped or changed.

type Json = { [key: string]: unknown }
type Kind = 'Number' | 'Boolean'
interface Made {
  block: Json
  kind: Kind
}

export interface Converted {
  /** Blockly serialization state, for Blockly.serialization.workspaces.load. */
  state: { blocks: { languageVersion: number; blocks: Json[] } }
  /** Every part of the code the blocks can't show as written, in words.
   *  Empty means the blocks are the code. */
  problems: string[]
}

/** An empty canvas. */
export const EMPTY_STATE: Converted['state'] = { blocks: { languageVersion: 0, blocks: [] } }

// ── Printing (only for messages, and to compare two trees) ──────────────

const OPS: Record<TreeBinaryOp, string> = {
  ADD: '+', SUB: '-', MUL: '*', DIV: '/',
  LT: '<', GT: '>', LE: '<=', GE: '>=', EQ: '==', NEQ: '!=',
  AND: 'AND', OR: 'OR',
}

const value = (v: number | string) => (typeof v === 'number' ? num(v) : String(v))

/** Readable TSL for a message: brackets only around inner calculations. */
export function printExpr(e: TreeExpr): string {
  const inner = (x: TreeExpr) => (x.kind === 'binary' ? `(${printExpr(x)})` : printExpr(x))
  switch (e.kind) {
    case 'number': return value(e.value)
    case 'percent': return `${value(e.value)}%`
    case 'timeframe': return e.value
    case 'string': return `"${e.value}"`
    case 'price': return e.field
    case 'name': return e.name
    case 'call': return `${e.name}(${e.args.map(printExpr).join(', ')})`
    case 'lookback': return `${inner(e.target)}[${e.offset}]`
    case 'on': return `${inner(e.target)} ON ${e.timeframe}`
    case 'unary': return e.op === 'NOT' ? `NOT ${inner(e.operand)}` : `-${inner(e.operand)}`
    case 'binary': return `${inner(e.left)} ${OPS[e.op]} ${inner(e.right)}`
  }
}

export function printAction(a: TreeAction): string {
  if (a.kind === 'set') return `SET ${a.target} = ${printExpr(a.value)}`
  const verb = a.kind === 'buy' ? 'BUY' : 'SELL'
  const s = a.size
  if (s.kind === 'all') return `${verb} ALL`
  if (s.kind === 'quantity') return `${verb} qty = ${printExpr(s.amount)}`
  return `${verb} qty = ${printExpr(s.percent)} OF ${s.base}`
}

/** One text per meaning: every calculation bracketed, -5 the number -5,
 *  and -x written as 0 - x (which is how the blocks show it). */
function canonical(e: TreeExpr): string {
  switch (e.kind) {
    case 'number': return e.value === 0 ? '0' : value(e.value) // -0 is 0
    case 'unary':
      if (e.op === 'NOT') return `(NOT ${canonical(e.operand)})`
      if (e.operand.kind === 'number' && typeof e.operand.value === 'number') {
        return canonical({ kind: 'number', value: -e.operand.value })
      }
      return `(0 - ${canonical(e.operand)})`
    case 'binary': return `(${canonical(e.left)} ${OPS[e.op]} ${canonical(e.right)})`
    case 'call': return `${e.name}(${e.args.map(canonical).join(', ')})`
    case 'lookback': return `${canonical(e.target)}[${e.offset}]`
    case 'on': return `(${canonical(e.target)} ON ${e.timeframe})`
    default: return printExpr(e)
  }
}

function canonicalAction(a: TreeAction | null | undefined): string {
  if (!a) return ''
  if (a.kind === 'set') return `SET ${a.target} = ${canonical(a.value)}`
  const s = a.size
  const size = s.kind === 'all' ? 'ALL'
    : s.kind === 'quantity' ? `qty = ${canonical(s.amount)}`
      : `qty = ${canonical(s.percent)} OF ${s.base}`
  return `${a.kind} ${size}`
}

/**
 * How the strategy the blocks would write differs from the code, in words.
 * Compares meaning, not text: brackets, spacing, blank lines, comments and
 * the order of the settings don't count. Empty means the same strategy.
 */
export function differences(code: StrategyTree, blocks: StrategyTree): string[] {
  const out: string[] = []
  if (code.name !== blocks.name) out.push(`Settings: the name “${code.name}” would become “${blocks.name}”`)

  const settings = (t: StrategyTree) => {
    const m = new Map<string, { text: string; shown: string }>()
    for (const c of t.config) {
      if (!m.has(c.key)) m.set(c.key, { text: canonical(c.value), shown: printExpr(c.value) })
    }
    if (!m.has('fee')) {
      // No fee line means the standard fee: the blocks write it out.
      const fee: TreeExpr = { kind: 'percent', value: DEFAULT_FEE_PERCENT }
      m.set('fee', { text: canonical(fee), shown: printExpr(fee) })
    }
    return m
  }
  const a = settings(code)
  const b = settings(blocks)
  for (const key of new Set([...a.keys(), ...b.keys()])) {
    if (a.get(key)?.text !== b.get(key)?.text) {
      out.push(`Settings: ${key} = ${a.get(key)?.shown ?? '(none)'} would become ${b.get(key)?.shown ?? '(none)'}`)
    }
  }

  const lets = Math.max(code.lets.length, blocks.lets.length)
  for (let i = 0; i < lets; i++) {
    const x = code.lets[i]
    const y = blocks.lets[i]
    if (!x || !y) {
      out.push(x ? `Named value “${x.name}” would be lost` : `A named value “${y.name}” would be added`)
    } else if (x.name !== y.name || canonical(x.value) !== canonical(y.value)) {
      out.push(`Named value “${x.name}”: “let ${x.name} = ${printExpr(x.value)}” would become “let ${y.name} = ${printExpr(y.value)}”`)
    }
  }

  const rules = Math.max(code.rules.length, blocks.rules.length)
  for (let i = 0; i < rules; i++) {
    const x = code.rules[i]
    const y = blocks.rules[i]
    if (!x || !y) {
      out.push(x ? `Rule “${x.name}” would be lost` : `A rule “${y.name}” would be added`)
      continue
    }
    if (x.name !== y.name) out.push(`Rule “${x.name}” would be renamed “${y.name}”`)
    const n = Math.max(x.body.length, y.body.length)
    for (let j = 0; j < n; j++) {
      const s = x.body[j]
      const t = y.body[j]
      const same = s && t
        && canonical(s.condition) === canonical(t.condition)
        && canonicalAction(s.then) === canonicalAction(t.then)
        && canonicalAction(s.else) === canonicalAction(t.else)
      if (!same) {
        out.push(`Rule “${x.name}”: ${s ? `“${printIf(s)}”` : 'nothing'} would become ${t ? `“${printIf(t)}”` : 'nothing'}`)
      }
    }
  }
  return out
}

function printIf(s: TreeIf): string {
  return `IF ${printExpr(s.condition)} THEN ${printAction(s.then)}${s.else ? ` ELSE ${printAction(s.else)}` : ''}`
}

// ── Building blocks ──────────────────────────────────────────────────────

/** Where in the strategy we are (for messages), and what has been found. */
interface Cx {
  where: string
  problems: string[]
  /** What each named value seen so far holds. */
  kinds: Map<string, Kind>
}

const say = (cx: Cx, text: string) => cx.problems.push(`${cx.where}: ${text}`)
const list = (xs: string[]) => xs.slice(0, -1).join(', ') + ' and ' + xs[xs.length - 1]
const EMPTY_SLOT = 'so that slot stays empty'

function make(type: string, fields?: Json, inputs?: Record<string, Json | undefined>): Json {
  const b: Json = { type }
  if (fields && Object.keys(fields).length > 0) b.fields = fields
  if (inputs) {
    const ins: Json = {}
    for (const [name, child] of Object.entries(inputs)) if (child) ins[name] = { block: child }
    if (Object.keys(ins).length > 0) b.inputs = ins
  }
  return b
}

/** A plain number: 14, or -2 (written as minus 2). Not a calculation. */
function literal(e: TreeExpr): number | undefined {
  if (e.kind === 'number') return typeof e.value === 'number' && Number.isFinite(e.value) ? e.value : undefined
  if (e.kind === 'unary' && e.op === 'NEG') {
    const v = literal(e.operand)
    return v === undefined ? undefined : -v
  }
  return undefined
}

function percentLiteral(e: TreeExpr): number | undefined {
  return e.kind === 'percent' && typeof e.value === 'number' && Number.isFinite(e.value) ? e.value : undefined
}

const COMPARE: Partial<Record<TreeBinaryOp, string>> = { LT: '<', GT: '>', LE: '<=', GE: '>=', EQ: '==', NEQ: '!=' }
const ARITH: Partial<Record<TreeBinaryOp, string>> = { ADD: '+', SUB: '-', MUL: '*', DIV: '/' }

/** What a named value holds, from its shape: conditions are comparisons,
 *  AND/OR/NOT, crossings, or other named conditions; the rest are numbers. */
function kindOf(e: TreeExpr, kinds: Map<string, Kind>): Kind {
  switch (e.kind) {
    case 'binary': return COMPARE[e.op] || e.op === 'AND' || e.op === 'OR' ? 'Boolean' : 'Number'
    case 'unary': return e.op === 'NOT' ? 'Boolean' : 'Number'
    case 'call': return e.name === 'CROSSOVER' || e.name === 'CROSSUNDER' ? 'Boolean' : 'Number'
    case 'name': return kinds.get(e.name) ?? 'Number'
    default: return 'Number'
  }
}

const what = (k: Kind) => (k === 'Number' ? 'a number' : 'a yes/no condition')

/** A block for e that fits a socket wanting `want`, or undefined (said why). */
function into(e: TreeExpr, want: Kind | 'Any', cx: Cx): Json | undefined {
  const made = expr(e, want, cx)
  if (!made) return undefined
  if (want !== 'Any' && made.kind !== want) {
    say(cx, `${printExpr(e)} is ${what(made.kind)} where ${what(want)} belongs, ${EMPTY_SLOT}`)
    return undefined
  }
  return made.block
}

function expr(e: TreeExpr, want: Kind | 'Any', cx: Cx): Made | undefined {
  switch (e.kind) {
    case 'number': {
      const v = literal(e)
      if (v === undefined) {
        say(cx, `the number ${printExpr(e)} is too big to use, ${EMPTY_SLOT}`)
        return undefined
      }
      return { block: make('tsl_number', { NUM: v }), kind: 'Number' }
    }
    case 'percent':
      say(cx, `${printExpr(e)}: a percent only works as a size, a stop or the fee, ${EMPTY_SLOT}`)
      return undefined
    case 'timeframe':
    case 'string':
      say(cx, `${printExpr(e)} can't be used in a calculation, ${EMPTY_SLOT}`)
      return undefined
    case 'price':
      return { block: make('tsl_price', { FIELD: e.field }), kind: 'Number' }
    case 'name': {
      // The socket decides: a hexagon for a condition, a round one for a
      // number. A name that holds the wrong kind is the compiler's to report.
      const kind: Kind = want === 'Any' ? (cx.kinds.get(e.name) ?? 'Number') : want
      return { block: make(kind === 'Boolean' ? 'tsl_ref_cond' : 'tsl_ref_num', { NAME: e.name }), kind }
    }
    case 'call':
      return call(e, cx)
    case 'lookback':
      return {
        block: make('tsl_lookback', { K: e.offset }, { TARGET: into(e.target, 'Number', cx) }),
        kind: 'Number',
      }
    case 'on':
      if (!ON_TIMEFRAMES.includes(e.timeframe)) {
        say(cx, `“${printExpr(e)}”: the blocks have ${list(ON_TIMEFRAMES)}, ${EMPTY_SLOT}`)
        return undefined
      }
      return {
        block: make('tsl_on', { TF: e.timeframe }, { TARGET: into(e.target, 'Number', cx) }),
        kind: 'Number',
      }
    case 'unary': {
      if (e.op === 'NOT') {
        return { block: make('tsl_not', undefined, { A: into(e.operand, 'Boolean', cx) }), kind: 'Boolean' }
      }
      const v = literal(e)
      if (v !== undefined) return { block: make('tsl_number', { NUM: v }), kind: 'Number' }
      // -x is 0 - x: the same number, and there is a block for that.
      return {
        block: make('tsl_arith', { OP: '-' }, {
          A: make('tsl_number', { NUM: 0 }),
          B: into(e.operand, 'Number', cx),
        }),
        kind: 'Number',
      }
    }
    case 'binary': {
      const arith = ARITH[e.op]
      if (arith) {
        return {
          block: make('tsl_arith', { OP: arith }, { A: into(e.left, 'Number', cx), B: into(e.right, 'Number', cx) }),
          kind: 'Number',
        }
      }
      const cmp = COMPARE[e.op]
      if (cmp) {
        return {
          block: make('tsl_compare', { OP: cmp }, { A: into(e.left, 'Number', cx), B: into(e.right, 'Number', cx) }),
          kind: 'Boolean',
        }
      }
      return {
        block: make('tsl_logic', { OP: e.op }, { A: into(e.left, 'Boolean', cx), B: into(e.right, 'Boolean', cx) }),
        kind: 'Boolean',
      }
    }
  }
}

function call(e: Extract<TreeExpr, { kind: 'call' }>, cx: Cx): Made | undefined {
  const n = e.args.length
  const arity = (k: number) => {
    say(cx, `${printExpr(e)} takes ${k} value${k === 1 ? '' : 's'}, not ${n}, ${EMPTY_SLOT}`)
    return undefined
  }
  switch (e.name) {
    case 'CROSSOVER':
    case 'CROSSUNDER':
      if (n !== 2) return arity(2)
      return {
        block: make(e.name === 'CROSSOVER' ? 'tsl_crossover' : 'tsl_crossunder', undefined, {
          A: into(e.args[0], 'Number', cx),
          B: into(e.args[1], 'Number', cx),
        }),
        kind: 'Boolean',
      }
    case 'MAX':
    case 'MIN':
      if (n !== 2) return arity(2)
      return {
        block: make('tsl_minmax', { FN: e.name }, { A: into(e.args[0], 'Number', cx), B: into(e.args[1], 'Number', cx) }),
        kind: 'Number',
      }
    case 'ABS':
      if (n !== 1) return arity(1)
      return { block: make('tsl_abs', undefined, { A: into(e.args[0], 'Number', cx) }), kind: 'Number' }
  }
  const ind = INDICATORS.find((i) => i.name === e.name)
  if (!ind) {
    say(cx, `${printExpr(e)}: there is no block called ${e.name} (is it spelled right?), ${EMPTY_SLOT}`)
    return undefined
  }
  if (ind.args.length !== n) return arity(ind.args.length)
  const fields: Json = {}
  const inputs: Record<string, Json | undefined> = {}
  ind.args.forEach((a, i) => {
    if (a.kind === 'source') {
      inputs[a.name] = into(e.args[i], 'Number', cx)
      return
    }
    const v = literal(e.args[i])
    if (v === undefined) {
      say(cx, `in ${printExpr(e)}, ${printExpr(e.args[i])} has to be a plain number to show on the block; the block keeps its usual ${a.default}`)
    } else {
      fields[a.name] = v
    }
  })
  return { block: make('tsl_ind_' + ind.name.toLowerCase(), fields, inputs), kind: 'Number' }
}

function action(a: TreeAction, cx: Cx): Json | undefined {
  if (a.kind === 'set') {
    const p = percentLiteral(a.value)
    if (p === undefined) {
      say(cx, `${printAction(a)}: the stop block needs a percent like 5%, ${EMPTY_SLOT}`)
      return undefined
    }
    return make('tsl_set', { TARGET: a.target, PCT: p })
  }
  const buy = a.kind === 'buy'
  const s = a.size
  if (s.kind === 'all') return make(buy ? 'tsl_buy_all' : 'tsl_sell_all')
  if (s.kind === 'quantity') {
    const v = literal(s.amount)
    if (v === undefined) {
      say(cx, `${printAction(a)}: the quantity has to be a plain number to show on the block, ${EMPTY_SLOT}`)
      return undefined
    }
    return make(buy ? 'tsl_buy_amount' : 'tsl_sell_amount', { AMOUNT: v })
  }
  const p = percentLiteral(s.percent)
  const base = buy ? 'EQUITY' : 'POSITION'
  if (p === undefined || s.base !== base) {
    say(cx, `${printAction(a)}: the blocks ${buy ? 'buy a share of equity' : 'sell a share of the position'} (${buy ? 'BUY' : 'SELL'} qty = 25% OF ${base}), ${EMPTY_SLOT}`)
    return undefined
  }
  return make(buy ? 'tsl_buy_pct' : 'tsl_sell_pct', { PCT: p })
}

function settings(tree: StrategyTree, cx: Cx): Json {
  const fields: Json = {
    NAME: tree.name,
    SYMBOL: SYMBOLS[0],
    TIMEFRAME: '1h',
    CAPITAL: 10000,
    FEE: DEFAULT_FEE_PERCENT,
  }
  const seen = new Set<string>()
  for (const c of tree.config) {
    if (seen.has(c.key)) {
      say(cx, `${c.key} is set twice; the blocks keep the first one`)
      continue
    }
    seen.add(c.key)
    const shown = `${c.key} = ${printExpr(c.value)}`
    switch (c.key) {
      case 'symbol':
        if (c.value.kind === 'name' && SYMBOLS.includes(c.value.name)) fields.SYMBOL = c.value.name
        else say(cx, `${shown}: the blocks have ${list(SYMBOLS)}; they will use ${fields.SYMBOL}`)
        break
      case 'timeframe':
        if (c.value.kind === 'timeframe' && TIMEFRAMES.includes(c.value.value)) fields.TIMEFRAME = c.value.value
        else say(cx, `${shown}: the blocks have ${list(TIMEFRAMES)} (the timeframes with candles); they will use ${fields.TIMEFRAME}`)
        break
      case 'capital': {
        const v = literal(c.value)
        if (v !== undefined) fields.CAPITAL = v
        else say(cx, `${shown}: the capital has to be a plain number to show on the block; it will be ${fields.CAPITAL}`)
        break
      }
      case 'fee': {
        const v = percentLiteral(c.value)
        if (v !== undefined) fields.FEE = v
        else say(cx, `${shown}: the fee has to be a percent like 0.1% to show on the block; it will be ${fields.FEE}%`)
        break
      }
      default:
        say(cx, `“${shown}” is not a setting the blocks have (symbol, timeframe, capital, fee); it will be left out`)
    }
  }
  for (const key of ['symbol', 'timeframe', 'capital'] as const) {
    if (!seen.has(key)) {
      const used = key === 'symbol' ? fields.SYMBOL : key === 'timeframe' ? fields.TIMEFRAME : fields.CAPITAL
      say(cx, `the code has no ${key} line; the blocks always have one and will use ${used}`)
    }
  }
  return fields
}

// ── Comments ─────────────────────────────────────────────────────────────

/** Which block each // comment goes on: the IF or named value it is in or
 *  above, the rule it is in, else the strategy block (the settings live on
 *  it, so their comments do too). The generator writes them back above
 *  those statements. */
function placeComments(tree: StrategyTree): Map<object, string[]> {
  const out = new Map<object, string[]>()
  const put = (node: object, text: string) => {
    const texts = out.get(node)
    if (texts) texts.push(text)
    else out.set(node, [text])
  }
  // A comment on a line of its own can't be on a statement's first line:
  // "inside" then means after it.
  const inside = (n: TreeLines, line: number, ownLine: boolean) =>
    (ownLine ? n.line < line : n.line <= line) && line <= n.endLine
  const ifs = tree.rules.flatMap((r) => r.body)
  const members = [...tree.config, ...tree.lets, ...tree.rules].sort((a, b) => a.line - b.line)

  for (const c of tree.comments ?? []) {
    const text = c.text.startsWith(' ') ? c.text.slice(1) : c.text
    const stmt = ifs.find((s) => inside(s, c.line, c.ownLine)) ?? tree.lets.find((l) => inside(l, c.line, c.ownLine))
    if (stmt) {
      put(stmt, text)
      continue
    }
    if (tree.config.some((k) => inside(k, c.line, c.ownLine))) {
      put(tree, text)
      continue
    }
    const rule = tree.rules.find((r) => inside(r, c.line, c.ownLine))
    if (rule) {
      put(c.ownLine ? (rule.body.find((s) => s.line > c.line) ?? rule) : rule, text)
      continue
    }
    if (c.ownLine && inside(tree, c.line, true)) {
      const next = members.find((m) => m.line > c.line)
      put(next && !tree.config.includes(next as TreeConfig) ? next : tree, text)
      continue
    }
    put(tree, text)
  }
  return out
}

function noted(block: Json, texts: string[] | undefined): Json {
  if (texts && texts.length > 0) {
    const text = texts.join('\n')
    // Big enough to read without scrolling: about 44 characters fit on a
    // line of the 340-wide bubble.
    const lines = text.split('\n').reduce((n, l) => n + Math.max(1, Math.ceil(l.length / 44)), 0)
    block.icons = { comment: { text, pinned: false, width: 340, height: Math.min(260, 28 + 17 * lines) } }
  }
  return block
}

function chain(blocks: Json[]): Json | undefined {
  let next: Json | undefined
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (next) blocks[i].next = { block: next }
    next = blocks[i]
  }
  return next
}

// ── The whole strategy ───────────────────────────────────────────────────

/** The parse tree as one strategy block holding its named values and rules. */
export function treeToState(tree: StrategyTree): Converted {
  const problems: string[] = []
  const kinds = new Map<string, Kind>()
  const notes = placeComments(tree)

  const fields = settings(tree, { where: 'Settings', problems, kinds })

  const letBlocks = tree.lets.map((l) => {
    const cx: Cx = { where: `Named value “${l.name}”`, problems, kinds }
    const block = make('tsl_let', { NAME: l.name }, { VALUE: into(l.value, 'Any', cx) })
    // Later named values and the rules can use it (as the right kind).
    if (!kinds.has(l.name)) kinds.set(l.name, kindOf(l.value, kinds))
    return noted(block, notes.get(l))
  })

  const ruleBlocks = tree.rules.map((r) => {
    const ifBlocks = r.body.map((s, i) => {
      const cx: Cx = { where: r.body.length > 1 ? `Rule “${r.name}”, IF ${i + 1}` : `Rule “${r.name}”`, problems, kinds }
      const block = make('tsl_if', undefined, {
        COND: into(s.condition, 'Boolean', cx),
        THEN: action(s.then, cx),
        ELSE: s.else ? action(s.else, cx) : undefined,
      })
      return noted(block, notes.get(s))
    })
    return noted(make('tsl_rule', { NAME: r.name }, { BODY: chain(ifBlocks) }), notes.get(r))
  })

  const strategy = noted(
    make('tsl_strategy', fields, { VALUES: chain(letBlocks), RULES: chain(ruleBlocks) }),
    notes.get(tree),
  )
  strategy.x = 24
  strategy.y = 24
  return { state: { blocks: { languageVersion: 0, blocks: [strategy] } }, problems }
}
