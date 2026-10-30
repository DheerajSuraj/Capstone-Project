import * as Blockly from 'blockly'
import { CATEGORIES, INDICATORS } from './registry'
import { DEFAULT_FEE_PERCENT, ON_TIMEFRAMES, SYMBOLS, TIMEFRAMES } from './options'

// ── Structural blocks (hand-defined) + 28 indicator blocks (generated
// from the registry) + the graphite theme + the category toolbox.
//
// The type system, made physical: Number-shaped outputs cannot plug into
// Boolean sockets. A whole class of the compiler's SEM errors becomes
// impossible to CONSTRUCT; whatever remains is caught by live compilation.

const choices = (values: string[]) => values.map((v) => [v, v])

/** Colour of the named-value blocks (TSL's `let`). */
const NAMED = 300

const defs: object[] = [
  // ── Strategy shell ──
  {
    type: 'tsl_strategy',
    message0: 'strategy %1',
    args0: [{ type: 'field_input', name: 'NAME', text: 'My Strategy' }],
    message1: 'symbol %1 timeframe %2',
    args1: [
      { type: 'field_dropdown', name: 'SYMBOL', options: choices(SYMBOLS) },
      { type: 'field_dropdown', name: 'TIMEFRAME', options: choices(TIMEFRAMES) },
    ],
    message2: 'capital %1 fee %2 %%',
    args2: [
      { type: 'field_number', name: 'CAPITAL', value: 10000, min: 1 },
      // No rounding: a fee of 0.075% must stay 0.075%.
      { type: 'field_number', name: 'FEE', value: DEFAULT_FEE_PERCENT, min: 0 },
    ],
    message3: 'named values %1',
    args3: [{ type: 'input_statement', name: 'VALUES', check: 'Let' }],
    message4: 'rules %1',
    args4: [{ type: 'input_statement', name: 'RULES', check: 'Rule' }],
    colour: 45,
    tooltip: 'The strategy: settings, named values and rules. One per canvas.',
  },
  // ── Named values (TSL's `let`) ──
  {
    type: 'tsl_let',
    message0: 'let %1 = %2',
    args0: [
      { type: 'field_input', name: 'NAME', text: 'value' },
      { type: 'input_value', name: 'VALUE' },
    ],
    previousStatement: 'Let',
    nextStatement: 'Let',
    colour: NAMED,
    inputsInline: true,
    tooltip:
      'A named value: work something out once (a number or a yes/no condition), ' +
      'then use it by name in the rules and in the named values below it.',
  },
  {
    type: 'tsl_ref_num',
    message0: '%1',
    args0: [{ type: 'field_input', name: 'NAME', text: 'value' }],
    output: 'Number',
    colour: NAMED,
    tooltip: 'A named number from "named values". The name must match a let.',
  },
  {
    type: 'tsl_ref_cond',
    message0: '%1',
    args0: [{ type: 'field_input', name: 'NAME', text: 'condition' }],
    output: 'Boolean',
    colour: NAMED,
    tooltip: 'A named yes/no condition from "named values". The name must match a let.',
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
      // No upper limit: a take profit can sit more than 100% above the entry.
      { type: 'field_number', name: 'PCT', value: 5, min: 0.01 },
    ],
    output: 'Action',
    colour: 60,
    tooltip: 'Protective exit, percent from entry (trailing: from the highest price of earlier candles). If a candle opens past the level, it fills at that open.',
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
    type: 'tsl_on',
    message0: '%1 on the %2 chart',
    args0: [
      { type: 'input_value', name: 'TARGET', check: 'Number' },
      // Every timeframe the compiler accepts after ON; the toolbox block
      // starts on 4h.
      { type: 'field_dropdown', name: 'TF', options: choices(ON_TIMEFRAMES) },
    ],
    output: 'Number',
    colour: 230,
    inputsInline: true,
    tooltip:
      'An indicator or price on bigger candles, e.g. the 4h trend on a 1h strategy. ' +
      'Only finished bigger candles count, never the one still forming. ' +
      "Must be bigger than the strategy's own timeframe.",
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
      // Periods are whole numbers; other settings (a Bollinger k of 2.25)
      // keep every decimal.
      args.push({
        type: 'field_number',
        name: a.name,
        value: a.default ?? 14,
        min: a.kind === 'period' ? 1 : 0.01,
        ...(a.kind === 'period' ? { precision: 1 } : {}),
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

// ── The "Named values" category, built each time it opens ──
export const NAMED_VALUES = 'TSL_NAMED_VALUES'

/** A let block, then a block for each named value on the canvas: a
 *  hexagon for a condition, a round one for a number. Register it with
 *  workspace.registerToolboxCategoryCallback(NAMED_VALUES, ...). */
export function namedValuesFlyout(ws: Blockly.WorkspaceSvg): Blockly.utils.toolbox.FlyoutItemInfoArray {
  const items: Blockly.utils.toolbox.FlyoutItemInfoArray = [
    { kind: 'label', text: 'Work something out once:' },
    { kind: 'block', type: 'tsl_let' },
  ]
  const seen = new Set<string>()
  const lets = ws.getAllBlocks(true).filter((b) => b.type === 'tsl_let' && !b.isInFlyout)
  for (const let_ of lets) {
    const name = String(let_.getFieldValue('NAME') ?? '').trim()
    if (!name || seen.has(name)) continue
    seen.add(name)
    if (seen.size === 1) items.push({ kind: 'label', text: '…then use it by name:' })
    const check = let_.getInputTargetBlock('VALUE')?.outputConnection?.getCheck()
    const type = check?.includes('Boolean') ? 'tsl_ref_cond' : 'tsl_ref_num'
    items.push({ kind: 'block', type, fields: { NAME: name } })
  }
  if (seen.size === 0) {
    items.push({ kind: 'label', text: 'Use it by name (match the let):' })
    items.push({ kind: 'block', type: 'tsl_ref_num' })
    items.push({ kind: 'block', type: 'tsl_ref_cond' })
  }
  return items
}

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
        { kind: 'block', type: 'tsl_on', fields: { TF: '4h' } },
        { kind: 'block', type: 'tsl_minmax' },
        { kind: 'block', type: 'tsl_abs' },
      ],
    },
    // Filled when opened (namedValuesFlyout): one block per named value
    // already on the canvas, so using one is a drag, not typing its name.
    { kind: 'category', name: 'Named values', colour: String(NAMED), custom: NAMED_VALUES },
    indicatorCategory('Moving averages'),
    indicatorCategory('Oscillators'),
    indicatorCategory('Trend'),
    indicatorCategory('Channels & stats'),
    indicatorCategory('Bands'),
    indicatorCategory('Volume'),
  ],
}