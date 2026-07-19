import * as Blockly from 'blockly'
import { CATEGORIES, INDICATORS } from './registry'

// ── Structural blocks (hand-defined) + 28 indicator blocks (generated
// from the registry) + the graphite theme + the category toolbox.
//
// The type system, made physical: Number-shaped outputs cannot plug into
// Boolean sockets. A whole class of the compiler's SEM errors becomes
// impossible to CONSTRUCT; whatever remains is caught by live compilation.

const defs: object[] = [
  // ── Strategy shell ──
  {
    type: 'tsl_strategy',
    message0: 'strategy %1',
    args0: [{ type: 'field_input', name: 'NAME', text: 'My Strategy' }],
    message1: 'symbol %1 timeframe %2',
    args1: [
      {
        type: 'field_dropdown',
        name: 'SYMBOL',
        options: [
          ['BTCUSDT', 'BTCUSDT'],
          ['ETHUSDT', 'ETHUSDT'],
          ['SOLUSDT', 'SOLUSDT'],
        ],
      },
      {
        type: 'field_dropdown',
        name: 'TIMEFRAME',
        options: [
          ['5m', '5m'],
          ['15m', '15m'],
          ['1h', '1h'],
          ['4h', '4h'],
        ],
      },
    ],
    message2: 'capital %1 fee %2 %%',
    args2: [
      { type: 'field_number', name: 'CAPITAL', value: 10000, min: 1 },
      { type: 'field_number', name: 'FEE', value: 0.1, min: 0, precision: 0.01 },
    ],
    message3: 'rules %1',
    args3: [{ type: 'input_statement', name: 'RULES', check: 'Rule' }],
    colour: 45,
    tooltip: 'The strategy: configuration plus rules. One per canvas.',
  },
  {
    type: 'tsl_rule',
    message0: 'rule %1 %2',
    args0: [
      { type: 'field_input', name: 'NAME', text: 'entry' },
      { type: 'input_statement', name: 'BODY', check: 'Stmt' },
    ],
    previousStatement: 'Rule',
    nextStatement: 'Rule',
    colour: 45,
    tooltip: 'A named rule, evaluated every bar in order.',
  },
  {
    type: 'tsl_if',
    message0: 'IF %1 THEN %2',
    args0: [
      { type: 'input_value', name: 'COND', check: 'Boolean' },
      { type: 'input_value', name: 'THEN', check: 'Action' },
    ],
    message1: 'ELSE %1',
    args1: [{ type: 'input_value', name: 'ELSE', check: 'Action' }],
    previousStatement: 'Stmt',
    nextStatement: 'Stmt',
    colour: 45,
    inputsInline: false,
    tooltip: 'When the condition is true, do THEN; otherwise ELSE (optional).',
  },

  // ── Actions ──
  { type: 'tsl_buy_all', message0: 'BUY with all cash', output: 'Action', colour: 140, tooltip: 'Spend the full available cash.' },
  {
    type: 'tsl_buy_pct',
    message0: 'BUY %1 %% of equity',
    args0: [{ type: 'field_number', name: 'PCT', value: 25, min: 0.01, max: 100 }],
    output: 'Action',
    colour: 140,
  },
  {
    type: 'tsl_buy_amount',
    message0: 'BUY quantity %1',
    args0: [{ type: 'field_number', name: 'AMOUNT', value: 0.01, min: 0 }],
    output: 'Action',
    colour: 140,
  },
  { type: 'tsl_sell_all', message0: 'SELL entire position', output: 'Action', colour: 15, tooltip: 'Close the whole position.' },
  {
    type: 'tsl_sell_pct',
    message0: 'SELL %1 %% of position',
    args0: [{ type: 'field_number', name: 'PCT', value: 50, min: 0.01, max: 100 }],
    output: 'Action',
    colour: 15,
  },
  {
    type: 'tsl_sell_amount',
    message0: 'SELL quantity %1',
    args0: [{ type: 'field_number', name: 'AMOUNT', value: 0.01, min: 0 }],
    output: 'Action',
    colour: 15,
  },
  {
    type: 'tsl_set',
    message0: 'SET %1 = %2 %%',
    args0: [
      {
        type: 'field_dropdown',
        name: 'TARGET',
        options: [
          ['stop loss', 'STOPLOSS'],
          ['take profit', 'TAKEPROFIT'],
          ['trailing stop', 'TRAILING'],
        ],
      },
      { type: 'field_number', name: 'PCT', value: 5, min: 0.01, max: 100 },
    ],
    output: 'Action',
    colour: 60,
    tooltip: 'Protective exit, percent from entry (trailing: from the peak).',
  },

  // ── Logic & comparison ──
  {
    type: 'tsl_compare',
    message0: '%1 %2 %3',
    args0: [
      { type: 'input_value', name: 'A', check: 'Number' },
      {
        type: 'field_dropdown',
        name: 'OP',
        options: [['<', '<'], ['>', '>'], ['<=', '<='], ['>=', '>='], ['==', '=='], ['!=', '!=']],
      },
      { type: 'input_value', name: 'B', check: 'Number' },
    ],
    output: 'Boolean',
    colour: 210,
    inputsInline: true,
  },
  {
    type: 'tsl_logic',
    message0: '%1 %2 %3',
    args0: [
      { type: 'input_value', name: 'A', check: 'Boolean' },
      { type: 'field_dropdown', name: 'OP', options: [['AND', 'AND'], ['OR', 'OR']] },
      { type: 'input_value', name: 'B', check: 'Boolean' },
    ],
    output: 'Boolean',
    colour: 210,
    inputsInline: true,
  },
  {
    type: 'tsl_not',
    message0: 'NOT %1',
    args0: [{ type: 'input_value', name: 'A', check: 'Boolean' }],
    output: 'Boolean',
    colour: 210,
    inputsInline: true,
  },
  {
    type: 'tsl_crossover',
    message0: '%1 crosses ABOVE %2',
    args0: [
      { type: 'input_value', name: 'A', check: 'Number' },
      { type: 'input_value', name: 'B', check: 'Number' },
    ],
    output: 'Boolean',
    colour: 260,
    inputsInline: true,
    tooltip: 'True only on the bar where A moves from below-or-equal to above B.',
  },
  {
    type: 'tsl_crossunder',
    message0: '%1 crosses BELOW %2',
    args0: [
      { type: 'input_value', name: 'A', check: 'Number' },
      { type: 'input_value', name: 'B', check: 'Number' },
    ],
    output: 'Boolean',
    colour: 260,
    inputsInline: true,
  },

  // ── Numbers & prices ──
  {
    type: 'tsl_number',
    message0: '%1',
    args0: [{ type: 'field_number', name: 'NUM', value: 0 }],
    output: 'Number',
    colour: 100,
  },
  {
    type: 'tsl_price',
    message0: '%1',
    args0: [
      {
        type: 'field_dropdown',
        name: 'FIELD',
        options: [
          ['CLOSE', 'CLOSE'],
          ['OPEN', 'OPEN'],
          ['HIGH', 'HIGH'],
          ['LOW', 'LOW'],
          ['VOLUME', 'VOLUME'],
        ],
      },
    ],
    output: 'Number',
    colour: 160,
    tooltip: 'A price series — its value at the current bar.',
  },
  {
    type: 'tsl_arith',
    message0: '%1 %2 %3',
    args0: [
      { type: 'input_value', name: 'A', check: 'Number' },
      { type: 'field_dropdown', name: 'OP', options: [['+', '+'], ['-', '-'], ['x', '*'], ['/', '/']] },
      { type: 'input_value', name: 'B', check: 'Number' },
    ],
    output: 'Number',
    colour: 230,
    inputsInline: true,
  },
  {
    type: 'tsl_lookback',
    message0: '%1 %2 bars ago',
    args0: [
      { type: 'input_value', name: 'TARGET', check: 'Number' },
      { type: 'field_number', name: 'K', value: 1, min: 0, precision: 1 },
    ],
    output: 'Number',
    colour: 230,
    inputsInline: true,
    tooltip: 'The value K bars back. Only backwards — the language has no way to look forward.',
  },
  {
    type: 'tsl_minmax',
    message0: '%1 of %2 and %3',
    args0: [
      { type: 'field_dropdown', name: 'FN', options: [['MAX', 'MAX'], ['MIN', 'MIN']] },
      { type: 'input_value', name: 'A', check: 'Number' },
      { type: 'input_value', name: 'B', check: 'Number' },
    ],
    output: 'Number',
    colour: 230,
    inputsInline: true,
  },
  {
    type: 'tsl_abs',
    message0: 'ABS %1',
    args0: [{ type: 'input_value', name: 'A', check: 'Number' }],
    output: 'Number',
    colour: 230,
    inputsInline: true,
  },
]

// ── The 28 indicator blocks, generated from the registry ──
for (const ind of INDICATORS) {
  const args: object[] = []
  const parts: string[] = [ind.name]
  let slot = 1
  for (const a of ind.args) {
    if (a.kind === 'source') {
      parts.push(`%${slot}`)
      args.push({ type: 'input_value', name: a.name, check: 'Number' })
    } else {
      parts.push(`${a.name.toLowerCase()} %${slot}`)
      args.push({
        type: 'field_number',
        name: a.name,
        value: a.default ?? 14,
        min: a.kind === 'period' ? 1 : 0.01,
        precision: a.kind === 'period' ? 1 : 0.01,
      })
    }
    slot++
  }
  defs.push({
    type: 'tsl_ind_' + ind.name.toLowerCase(),
    message0: parts.join(' '),
    args0: args,
    output: 'Number',
    colour: CATEGORIES[ind.category].colour,
    inputsInline: true,
    tooltip: ind.tooltip,
  })
}

Blockly.defineBlocksWithJsonArray(defs)

// ── Graphite theme, matching the app's design tokens ──
export const tslTheme = Blockly.Theme.defineTheme('tsl-dark', {
  name: 'tsl-dark',
  base: Blockly.Themes.Classic,
  componentStyles: {
    workspaceBackgroundColour: '#14171c',
    toolboxBackgroundColour: '#1c2027',
    toolboxForegroundColour: '#8b909a',
    flyoutBackgroundColour: '#1c2027',
    flyoutForegroundColour: '#8b909a',
    flyoutOpacity: 0.97,
    scrollbarColour: '#2a2f38',
    insertionMarkerColour: '#e8b44c',
    insertionMarkerOpacity: 0.5,
    cursorColour: '#e8b44c',
  },
  fontStyle: { family: "'IBM Plex Mono', monospace", size: 11 },
})

// ── Toolbox: categories in learn-order, indicators grouped by family ──
const indicatorCategory = (name: string) => ({
  kind: 'category',
  name,
  colour: String(CATEGORIES[name].colour),
  contents: INDICATORS.filter((i) => i.category === name).map((i) => ({
    kind: 'block',
    type: 'tsl_ind_' + i.name.toLowerCase(),
    inputs: Object.fromEntries(
      i.args
        .filter((a) => a.kind === 'source')
        .map((a) => [a.name, { shadow: { type: 'tsl_price' } }]),
    ),
  })),
})

export const toolbox = {
  kind: 'categoryToolbox',
  contents: [
    {
      kind: 'category',
      name: 'Strategy',
      colour: '45',
      contents: [
        { kind: 'block', type: 'tsl_strategy' },
        { kind: 'block', type: 'tsl_rule' },
        { kind: 'block', type: 'tsl_if' },
      ],
    },
    {
      kind: 'category',
      name: 'Actions',
      colour: '140',
      contents: [
        { kind: 'block', type: 'tsl_buy_all' },
        { kind: 'block', type: 'tsl_buy_pct' },
        { kind: 'block', type: 'tsl_buy_amount' },
        { kind: 'block', type: 'tsl_sell_all' },
        { kind: 'block', type: 'tsl_sell_pct' },
        { kind: 'block', type: 'tsl_sell_amount' },
        { kind: 'block', type: 'tsl_set' },
      ],
    },
    {
      kind: 'category',
      name: 'Logic',
      colour: '210',
      contents: [
        { kind: 'block', type: 'tsl_compare' },
        { kind: 'block', type: 'tsl_logic' },
        { kind: 'block', type: 'tsl_not' },
        { kind: 'block', type: 'tsl_crossover' },
        { kind: 'block', type: 'tsl_crossunder' },
      ],
    },
    {
      kind: 'category',
      name: 'Values',
      colour: '160',
      contents: [
        { kind: 'block', type: 'tsl_price' },
        { kind: 'block', type: 'tsl_number' },
        { kind: 'block', type: 'tsl_arith' },
        { kind: 'block', type: 'tsl_lookback' },
        { kind: 'block', type: 'tsl_minmax' },
        { kind: 'block', type: 'tsl_abs' },
      ],
    },
    indicatorCategory('Moving averages'),
    indicatorCategory('Oscillators'),
    indicatorCategory('Trend'),
    indicatorCategory('Channels & stats'),
    indicatorCategory('Bands'),
    indicatorCategory('Volume'),
  ],
}