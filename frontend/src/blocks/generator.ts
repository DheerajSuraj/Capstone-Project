import * as Blockly from 'blockly'
import { INDICATORS } from './registry'
import { num } from './tslText'

// Blocks -> TSL text. The generator is deliberately dumb: it emits source
// and lets the REAL compiler judge it (live /api/compile on every edit).
// One brain, two views — the blocks never re-implement the language rules.
//
// Binary expressions are always parenthesized: correctness by parens
// beats replicating the Pratt precedence table in a second place. (Only
// the one pair around a whole condition or named value is left off; that
// pair never changes the meaning.)

const g = new Blockly.Generator('TSL')
g.INDENT = '    ' // the starters' layout: four spaces per level
const ORDER = 0

// ── Empty sockets ────────────────────────────────────────────────────────
// A socket left empty used to be filled with something harmless-looking:
// an empty AND input became (1 == 1) — always true — so a half-built entry
// rule bought on every candle, and an IF with no action quietly became
// BUY ALL. Now an empty socket is written as the name below, which the
// compiler refuses, and the editor lists in words what is missing. A
// strategy with a hole can't be run or saved by accident.
export const MISSING = 'missing_input'
let missing: string[] = []

function where(b: Blockly.Block): string {
  let p: Blockly.Block | null = b
  while (p && p.type !== 'tsl_rule' && p.type !== 'tsl_let') p = p.getSurroundParent()
  if (p?.type === 'tsl_let') return `named value “${p.getFieldValue('NAME')}”`
  return p ? `rule “${p.getFieldValue('NAME')}”` : 'a block outside any rule'
}

function hole(b: Blockly.Block, what: string): string {
  missing.push(`${what} (in ${where(b)})`)
  return MISSING
}

const value = (gen: Blockly.Generator, b: Blockly.Block, input: string, what: string) =>
  gen.valueToCode(b, input, ORDER) || hole(b, what)

/** One pair of brackets around the whole thing, which never changes its
 *  meaning there: IF (a AND b) is IF a AND b. */
function bare(code: string): string {
  if (!code.startsWith('(') || !code.endsWith(')')) return code
  let depth = 0
  for (let i = 0; i < code.length; i++) {
    if (code[i] === '(') depth++
    else if (code[i] === ')') {
      depth--
      if (depth === 0 && i < code.length - 1) return code // (a) AND (b)
    }
  }
  return code.slice(1, -1)
}

/** Every binary operator in TSL groups from the left (the parser's one
 *  rule for chains), so in a chain of the SAME operator the left side needs
 *  no brackets: (a AND b) AND c is a AND b AND c, and (a - b) - c is
 *  a - b - c. Any other mix keeps all its brackets. */
function leftChain(b: Blockly.Block, left: string): string {
  const l = b.getInputTargetBlock('A')
  return l && l.type === b.type && l.getFieldValue('OP') === b.getFieldValue('OP') ? bare(left) : left
}

const KEYWORDS = new Set([
  'strategy', 'let', 'rule', 'qty', 'IF', 'THEN', 'ELSE', 'BUY', 'SELL', 'SET', 'ALL',
  'OF', 'EQUITY', 'POSITION', 'STOPLOSS', 'TAKEPROFIT', 'TRAILING', 'ON', 'OPEN', 'HIGH',
  'LOW', 'CLOSE', 'VOLUME', 'AND', 'OR', 'NOT',
])

/** A name the language accepts: letters, digits and _, starting with a
 *  letter, not a keyword. A name that already is one is left exactly as
 *  it is (so a rule from the code keeps its name, trailing _ and all). */
function identName(raw: string, fallback: string, prefix: string): string {
  const t = String(raw ?? '').trim()
  if (/^[A-Za-z][A-Za-z0-9_]*$/.test(t) && !KEYWORDS.has(t)) return t
  let n = t.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '')
  if (n === '') return fallback
  if (!/^[A-Za-z]/.test(n)) n = `${prefix}_${n}`
  if (KEYWORDS.has(n)) n = `${n}_${prefix}`
  return n
}

/** A rule name the language accepts. */
export function ruleName(raw: string): string {
  return identName(raw, 'unnamed_rule', 'rule') // ("rule" itself is a keyword)
}

/** A named value's name the language accepts. Empty stays empty: that is a
 *  hole, reported in words, not a made-up name. */
export function letName(raw: string): string {
  return String(raw ?? '').trim() === '' ? '' : identName(raw, '', 'value')
}

/** A strategy name that fits in "quotes" (TSL strings have no escapes). */
export function strategyName(raw: string): string {
  const n = String(raw ?? '').replace(/["\\\r\n]+/g, ' ').replace(/\s+/g, ' ').trim()
  return n || 'My Strategy'
}

g.forBlock['tsl_strategy'] = (block, gen) => {
  const lets = gen.statementToCode(block, 'VALUES').replace(/\s+$/, '')
  const rules = gen.statementToCode(block, 'RULES').replace(/\s+$/, '')
  const lines = [
    `strategy "${strategyName(block.getFieldValue('NAME'))}" {`,
    `    symbol = ${block.getFieldValue('SYMBOL')}`,
    `    timeframe = ${block.getFieldValue('TIMEFRAME')}`,
    `    capital = ${num(block.getFieldValue('CAPITAL'))}`,
    `    fee = ${num(block.getFieldValue('FEE'))}%`,
  ]
  if (lets) lines.push('', lets)
  if (rules) lines.push('', rules)
  lines.push('}')
  return lines.join('\n') + '\n'
}

g.forBlock['tsl_let'] = (block, gen) => {
  const name = letName(block.getFieldValue('NAME')) || hole(block, 'A “let” block has no name')
  const v = value(gen, block, 'VALUE', `“let ${name}” has nothing after =`)
  return `let ${name} = ${bare(v)}\n`
}

const ref = (what: string) => (b: Blockly.Block): [string, number] => [
  letName(b.getFieldValue('NAME')) || hole(b, `A named ${what} block has no name`),
  ORDER,
]
g.forBlock['tsl_ref_num'] = ref('number')
g.forBlock['tsl_ref_cond'] = ref('condition')

g.forBlock['tsl_rule'] = (block, gen) => {
  const body = gen.statementToCode(block, 'BODY')
  // The blank line after each rule keeps rules apart, as in the starters.
  return `rule ${ruleName(block.getFieldValue('NAME'))} {\n${body.replace(/\s+$/, '')}\n}\n\n`
}

g.forBlock['tsl_if'] = (block, gen) => {
  const cond = value(gen, block, 'COND', 'An IF block has no condition')
  const then = value(gen, block, 'THEN', 'An IF block has no action after THEN')
  const els = gen.valueToCode(block, 'ELSE', ORDER)
  return `IF ${bare(cond)} THEN ${then}${els ? ` ELSE ${els}` : ''}\n`
}

// ── Actions (value blocks: [code, order]) ──
g.forBlock['tsl_buy_all'] = () => ['BUY ALL', ORDER]
g.forBlock['tsl_buy_pct'] = (b) => [`BUY qty = ${num(b.getFieldValue('PCT'))}% OF EQUITY`, ORDER]
g.forBlock['tsl_buy_amount'] = (b) => [`BUY qty = ${num(b.getFieldValue('AMOUNT'))}`, ORDER]
g.forBlock['tsl_sell_all'] = () => ['SELL ALL', ORDER]
g.forBlock['tsl_sell_pct'] = (b) => [`SELL qty = ${num(b.getFieldValue('PCT'))}% OF POSITION`, ORDER]
g.forBlock['tsl_sell_amount'] = (b) => [`SELL qty = ${num(b.getFieldValue('AMOUNT'))}`, ORDER]
g.forBlock['tsl_set'] = (b) =>
  [`SET ${b.getFieldValue('TARGET')} = ${num(b.getFieldValue('PCT'))}%`, ORDER]

// ── Logic ──
g.forBlock['tsl_compare'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A comparison is missing its left side')
  const c = value(gen, b, 'B', 'A comparison is missing its right side')
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_logic'] = (b, gen) => {
  const op = b.getFieldValue('OP')
  const a = leftChain(b, value(gen, b, 'A', `An ${op} block is missing its first condition`))
  const c = value(gen, b, 'B', `An ${op} block is missing its second condition`)
  return [`(${a} ${op} ${c})`, ORDER]
}
g.forBlock['tsl_not'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A NOT block has no condition inside')
  return [`(NOT ${a})`, ORDER]
}
g.forBlock['tsl_crossover'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A “crosses above” block is missing its first value')
  const c = value(gen, b, 'B', 'A “crosses above” block is missing its second value')
  return [`CROSSOVER(${a}, ${c})`, ORDER]
}
g.forBlock['tsl_crossunder'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A “crosses below” block is missing its first value')
  const c = value(gen, b, 'B', 'A “crosses below” block is missing its second value')
  return [`CROSSUNDER(${a}, ${c})`, ORDER]
}

// ── Values ──
g.forBlock['tsl_number'] = (b) => [num(b.getFieldValue('NUM')), ORDER]
g.forBlock['tsl_price'] = (b) => [b.getFieldValue('FIELD'), ORDER]
g.forBlock['tsl_arith'] = (b, gen) => {
  const a = leftChain(b, value(gen, b, 'A', 'A maths block is missing its first number'))
  const c = value(gen, b, 'B', 'A maths block is missing its second number')
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_lookback'] = (b, gen) => {
  const target = value(gen, b, 'TARGET', 'A “candles ago” block has nothing to look back at')
  // (CLOSE ON 1d)[1]: the same without brackets, but "CLOSE ON 1d[1]"
  // reads as if the [1] were on the 1d.
  const t = b.getInputTargetBlock('TARGET')?.type === 'tsl_on' ? `(${target})` : target
  return [`${t}[${num(b.getFieldValue('K'))}]`, ORDER]
}
g.forBlock['tsl_on'] = (b, gen) => {
  const target = value(gen, b, 'TARGET', 'A bigger-timeframe block has nothing inside')
  return [`${target} ON ${b.getFieldValue('TF')}`, ORDER]
}
g.forBlock['tsl_minmax'] = (b, gen) => {
  const a = value(gen, b, 'A', `A ${b.getFieldValue('FN')} block is missing its first number`)
  const c = value(gen, b, 'B', `A ${b.getFieldValue('FN')} block is missing its second number`)
  return [`${b.getFieldValue('FN')}(${a}, ${c})`, ORDER]
}
g.forBlock['tsl_abs'] = (b, gen) => {
  const a = value(gen, b, 'A', 'An ABS block has no number inside')
  return [`ABS(${a})`, ORDER]
}

// ── Indicators: one generator per registry row, built in a loop ──
for (const ind of INDICATORS) {
  g.forBlock['tsl_ind_' + ind.name.toLowerCase()] = (b, gen) => {
    const parts = ind.args.map((a) =>
      a.kind === 'source'
        ? gen.valueToCode(b, a.name, ORDER) || 'CLOSE'
        : num(b.getFieldValue(a.name)),
    )
    return [`${ind.name}(${parts.join(', ')})`, ORDER]
  }
}

// ── Comments ─────────────────────────────────────────────────────────────
// A block's comment (right-click > Add Comment, or a // comment from the
// code) is written as // lines above its statement: the strategy, a named
// value, a rule or an IF. A comment on a block inside an IF or a named
// value (an RSI block, say) goes above that IF or named value.

function valueComments(block: Blockly.Block, out: string[]) {
  for (const input of block.inputList) {
    const child = input.connection?.targetBlock()
    if (!child || !child.outputConnection) continue // statements write their own
    const text = child.getCommentText()
    if (text) out.push(text)
    valueComments(child, out)
  }
}

function commentLines(block: Blockly.Block): string {
  const texts: string[] = []
  const own = block.getCommentText()
  if (own) texts.push(own)
  if (block.type === 'tsl_if' || block.type === 'tsl_let') valueComments(block, texts)
  if (texts.length === 0) return ''
  return texts
    .join('\n')
    .split(/\r?\n/)
    .map((l) => (l.trim() === '' ? '//' : `// ${l.replace(/\s+$/, '')}`))
    .join('\n') + '\n'
}

g.scrub_ = (block, code, thisOnly) => {
  // Value blocks (with an output) sit inside an expression: no // there.
  const withComment = block.outputConnection ? code : commentLines(block) + code
  const next = block.nextConnection?.targetBlock()
  return next && !thisOnly ? withComment + g.blockToCode(next) : withComment
}

/** The whole workspace -> TSL source (the first strategy block wins), and
 *  in words every socket left empty. */
export function generateTslChecked(workspace: Blockly.Workspace): {
  source: string
  missing: string[]
} {
  const top = workspace
    .getTopBlocks(true)
    .find((b) => b.type === 'tsl_strategy')
  if (!top) {
    return { source: '', missing: [] }
  }
  missing = []
  g.init(workspace)
  const code = g.blockToCode(top)
  const raw = (Array.isArray(code) ? code[0] : code) as string
  // Indenting puts spaces on the blank lines between rules; drop them.
  const source = raw.replace(/[ \t]+$/gm, '')
  return { source, missing: [...missing] }
}

export function generateTsl(workspace: Blockly.Workspace): string {
  return generateTslChecked(workspace).source
}
