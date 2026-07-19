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

g.forBlock['tsl_strategy'] = (block, gen) => {
  const rules = gen.statementToCode(block, 'RULES')
  return [
    `strategy "${block.getFieldValue('NAME')}" {`,
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
  return `rule ${block.getFieldValue('NAME')} {\n${body.replace(/\s+$/, '')}\n}\n`
}

g.forBlock['tsl_if'] = (block, gen) => {
  const cond = gen.valueToCode(block, 'COND', ORDER) || 'false /* condition missing */'
  const then = gen.valueToCode(block, 'THEN', ORDER) || 'BUY ALL /* action missing */'
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
  const a = gen.valueToCode(b, 'A', ORDER) || '0'
  const c = gen.valueToCode(b, 'B', ORDER) || '0'
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_logic'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || '(1 == 1)'
  const c = gen.valueToCode(b, 'B', ORDER) || '(1 == 1)'
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_not'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || '(1 == 1)'
  return [`(NOT ${a})`, ORDER]
}
g.forBlock['tsl_crossover'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || 'CLOSE'
  const c = gen.valueToCode(b, 'B', ORDER) || '0'
  return [`CROSSOVER(${a}, ${c})`, ORDER]
}
g.forBlock['tsl_crossunder'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || 'CLOSE'
  const c = gen.valueToCode(b, 'B', ORDER) || '0'
  return [`CROSSUNDER(${a}, ${c})`, ORDER]
}

// ── Values ──
g.forBlock['tsl_number'] = (b) => [String(b.getFieldValue('NUM')), ORDER]
g.forBlock['tsl_price'] = (b) => [b.getFieldValue('FIELD'), ORDER]
g.forBlock['tsl_arith'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || '0'
  const c = gen.valueToCode(b, 'B', ORDER) || '1'
  return [`(${a} ${b.getFieldValue('OP')} ${c})`, ORDER]
}
g.forBlock['tsl_lookback'] = (b, gen) => {
  const target = gen.valueToCode(b, 'TARGET', ORDER) || 'CLOSE'
  return [`${target}[${b.getFieldValue('K')}]`, ORDER]
}
g.forBlock['tsl_minmax'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || '0'
  const c = gen.valueToCode(b, 'B', ORDER) || '0'
  return [`${b.getFieldValue('FN')}(${a}, ${c})`, ORDER]
}
g.forBlock['tsl_abs'] = (b, gen) => {
  const a = gen.valueToCode(b, 'A', ORDER) || '0'
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

/** The whole workspace -> TSL source (the first strategy block wins). */
export function generateTsl(workspace: Blockly.Workspace): string {
  const top = workspace
    .getTopBlocks(true)
    .find((b) => b.type === 'tsl_strategy')
  if (!top) {
    return ''
  }
  g.init(workspace)
  const code = g.blockToCode(top)
  return (Array.isArray(code) ? code[0] : code) as string
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