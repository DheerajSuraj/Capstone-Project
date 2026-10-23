import * as Blockly from 'blockly'
import { INDICATORS } from './registry'

// Blocks -> TSL text. The generator is deliberately dumb: it emits source
// and lets the REAL compiler judge it (live /api/compile on every edit).
// One brain, two views — the blocks never re-implement the language rules.
//
// Binary expressions are always parenthesized: correctness by parens
// beats replicating the Pratt precedence table in a second place.

const g = new Blockly.Generator('TSL')
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
  while (p && p.type !== 'tsl_rule') p = p.getSurroundParent()
  return p ? `rule “${p.getFieldValue('NAME')}”` : 'a block outside any rule'
}

function hole(b: Blockly.Block, what: string): string {
  missing.push(`${what} (in ${where(b)})`)
  return MISSING
}

const value = (gen: Blockly.Generator, b: Blockly.Block, input: string, what: string) =>
  gen.valueToCode(b, input, ORDER) || hole(b, what)

const KEYWORDS = new Set([
  'strategy', 'let', 'rule', 'qty', 'IF', 'THEN', 'ELSE', 'BUY', 'SELL', 'SET', 'ALL',
  'OF', 'EQUITY', 'POSITION', 'STOPLOSS', 'TAKEPROFIT', 'TRAILING', 'ON', 'OPEN', 'HIGH',
  'LOW', 'CLOSE', 'VOLUME', 'AND', 'OR', 'NOT',
])

/** A rule name the language accepts: letters, digits and _, starting with a letter. */
export function ruleName(raw: string): string {
  let n = String(raw ?? '').trim().replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '')
  if (n === '') return 'unnamed_rule' // ("rule" itself is a keyword)
  if (!/^[A-Za-z]/.test(n)) n = `rule_${n}`
  if (KEYWORDS.has(n)) n = `${n}_rule`
  return n
}

/** A strategy name that fits in "quotes" (TSL strings have no escapes). */
export function strategyName(raw: string): string {
  const n = String(raw ?? '').replace(/["\\\r\n]+/g, ' ').replace(/\s+/g, ' ').trim()
  return n || 'My Strategy'
}

g.forBlock['tsl_strategy'] = (block, gen) => {
  const rules = gen.statementToCode(block, 'RULES')
  return [
    `strategy "${strategyName(block.getFieldValue('NAME'))}" {`,
    `    symbol = ${block.getFieldValue('SYMBOL')}`,
    `    timeframe = ${block.getFieldValue('TIMEFRAME')}`,
    `    capital = ${block.getFieldValue('CAPITAL')}`,
    `    fee = ${block.getFieldValue('FEE')}%`,
    '',
    rules.replace(/\s+$/, ''),
    '}',
  ].join('\n')
}

g.forBlock['tsl_rule'] = (block, gen) => {
  const body = gen.statementToCode(block, 'BODY')
  return `rule ${ruleName(block.getFieldValue('NAME'))} {\n${body.replace(/\s+$/, '')}\n}\n`
}

g.forBlock['tsl_if'] = (block, gen) => {
  const cond = value(gen, block, 'COND', 'An IF block has no condition')
  const then = value(gen, block, 'THEN', 'An IF block has no action after THEN')
  const els = gen.valueToCode(block, 'ELSE', ORDER)
  return `IF ${cond} THEN ${then}${els ? ` ELSE ${els}` : ''}\n`
}

// ── Actions (value blocks: [code, order]) ──
g.forBlock['tsl_buy_all'] = () => ['BUY ALL', ORDER]
g.forBlock['tsl_buy_pct'] = (b) => [`BUY qty = ${b.getFieldValue('PCT')}% OF EQUITY`, ORDER]
g.forBlock['tsl_buy_amount'] = (b) => [`BUY qty = ${b.getFieldValue('AMOUNT')}`, ORDER]
g.forBlock['tsl_sell_all'] = () => ['SELL ALL', ORDER]
g.forBlock['tsl_sell_pct'] = (b) => [`SELL qty = ${b.getFieldValue('PCT')}% OF POSITION`, ORDER]
g.forBlock['tsl_sell_amount'] = (b) => [`SELL qty = ${b.getFieldValue('AMOUNT')}`, ORDER]
g.forBlock['tsl_set'] = (b) =>
  [`SET ${b.getFieldValue('TARGET')} = ${b.getFieldValue('PCT')}%`, ORDER]

// ── Logic ──
g.forBlock['tsl_compare'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A comparison is missing its left side')
  const c = value(gen, b, 'B', 'A comparison is missing its right side')
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_logic'] = (b, gen) => {
  const op = b.getFieldValue('OP')
  const a = value(gen, b, 'A', `An ${op} block is missing its first condition`)
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
g.forBlock['tsl_number'] = (b) => [String(b.getFieldValue('NUM')), ORDER]
g.forBlock['tsl_price'] = (b) => [b.getFieldValue('FIELD'), ORDER]
g.forBlock['tsl_arith'] = (b, gen) => {
  const a = value(gen, b, 'A', 'A maths block is missing its first number')
  const c = value(gen, b, 'B', 'A maths block is missing its second number')
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_lookback'] = (b, gen) => {
  const target = value(gen, b, 'TARGET', 'A “candles ago” block has nothing to look back at')
  return [`${target}[${b.getFieldValue('K')}]`, ORDER]
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
        : String(b.getFieldValue(a.name)),
    )
    return [`${ind.name}(${parts.join(', ')})`, ORDER]
  }
}

g.scrub_ = (block, code, thisOnly) => {
  const next = block.nextConnection?.targetBlock()
  return next && !thisOnly ? code + g.blockToCode(next) : code
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
  const source = (Array.isArray(code) ? code[0] : code) as string
  return { source, missing: [...missing] }
}

export function generateTsl(workspace: Blockly.Workspace): string {
  return generateTslChecked(workspace).source
}

/** Starter workspace: the RSI strategy, as blocks. */
export const STARTER_STATE = {
  blocks: {
    languageVersion: 0,
    blocks: [
      {
        type: 'tsl_strategy',
        x: 24,
        y: 24,
        fields: { NAME: 'My Strategy', SYMBOL: 'BTCUSDT', TIMEFRAME: '1h', CAPITAL: 10000, FEE: 0.1 },
        inputs: {
          RULES: {
            block: {
              type: 'tsl_rule',
              fields: { NAME: 'entry' },
              inputs: {
                BODY: {
                  block: {
                    type: 'tsl_if',
                    inputs: {
                      COND: {
                        block: {
                          type: 'tsl_logic',
                          fields: { OP: 'AND' },
                          inputs: {
                            A: {
                              block: {
                                type: 'tsl_compare',
                                fields: { OP: '<' },
                                inputs: {
                                  A: { block: { type: 'tsl_ind_rsi', fields: { PERIOD: 14 } } },
                                  B: { block: { type: 'tsl_number', fields: { NUM: 30 } } },
                                },
                              },
                            },
                            B: {
                              block: {
                                type: 'tsl_compare',
                                fields: { OP: '>' },
                                inputs: {
                                  A: { block: { type: 'tsl_price', fields: { FIELD: 'CLOSE' } } },
                                  B: {
                                    block: {
                                      type: 'tsl_ind_sma',
                                      fields: { PERIOD: 200 },
                                      inputs: {
                                        SOURCE: { block: { type: 'tsl_price', fields: { FIELD: 'CLOSE' } } },
                                      },
                                    },
                                  },
                                },
                              },
                            },
                          },
                        },
                      },
                      THEN: { block: { type: 'tsl_buy_pct', fields: { PCT: 25 } } },
                    },
                  },
                },
              },
              next: {
                block: {
                  type: 'tsl_rule',
                  fields: { NAME: 'exit' },
                  inputs: {
                    BODY: {
                      block: {
                        type: 'tsl_if',
                        inputs: {
                          COND: {
                            block: {
                              type: 'tsl_compare',
                              fields: { OP: '>' },
                              inputs: {
                                A: { block: { type: 'tsl_ind_rsi', fields: { PERIOD: 14 } } },
                                B: { block: { type: 'tsl_number', fields: { NUM: 70 } } },
                              },
                            },
                          },
                          THEN: { block: { type: 'tsl_sell_all' } },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    ],
  },
}