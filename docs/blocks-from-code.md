# Blocks from code (24 October 2026)

## The problem

Opening a saved strategy (My strategies → Edit, the Strategy lab, a
ready-made strategy) put its code in the Code view. Pressing **Blocks** then
showed a popup ("The Blocks view shows its own strategy and will replace the
code below…") and, after OK, showed whatever blocks were left from earlier
(or the starter) and replaced the strategy's code with theirs. The blocks
could write code, but nothing turned code into blocks.

## What happens now

- **Blocks shows the strategy in the code.** Its settings, named values
  (`let`), rules, IF/THEN/ELSE, indicators and actions, as blocks. No popup.
- **Opening the blocks changes nothing.** The code stays exactly as written,
  comments and layout included. It is rewritten from the blocks only when a
  block is changed. Moving blocks around is not a change.
- **Comments come along.** A `//` comment shows on its block (the **?**
  icon) and is written back as `//` lines when the blocks rewrite the code.
- Going Code → Blocks again without editing the code reopens the same
  blocks, layout and all.
- A ready-made strategy picked while on Blocks opens as blocks.
- **Code the blocks can't show** (list below): the Builder stays on Code
  with a notice saying exactly what and why, and two buttons: *Keep editing
  the code*, or *Use the blocks anyway* (the code then becomes what the
  blocks show, without those parts).
- **Code with a syntax error:** the notice lists the mistakes to fix first.

## How it works

1. `POST /api/compile` with `"withTree": true` also returns `tree`: the
   parser's own tree as JSON (`ParseTree.java`), sent whenever the code has
   no syntax errors. The lexer now keeps `//` comments and where they are;
   the parser still never sees them. Without `withTree` the response is
   unchanged.
2. `frontend/src/blocks/fromTree.ts` turns the tree into Blockly blocks and
   lists, in words, anything it can't show.
3. `codeToBlocks.ts` proves the result before showing it: it builds the
   blocks off-screen, lets them write their code, has the compiler read that
   code as well, and compares the two trees. Only the same strategy counts
   as "shown exactly".
4. `BlockEditor` no longer writes the code when it opens, only when what the
   blocks say changes.

Why the backend sends the tree instead of the browser parsing TSL: one
compiler (README, key invariant 1). A second parser in TypeScript would
drift from the real one; this way the blocks show the code exactly as the
compiler understands it.

## Changes to the blocks

- The strategy block has a **named values** slot; new blocks `let name = …`,
  a named number and a named condition. The toolbox category **Named
  values** lists the named values on the canvas, ready to drag.
- **"on the … chart"** offers every timeframe the compiler accepts after ON
  (15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 1w); it had 6 of them.
- The fee and the Bollinger k / SuperTrend multiplier keep every decimal
  (a 0.075% fee was rounded to 0.08%). A take profit can be above 100%.
- Block comments are readable: the app's text-box style had made their text
  light grey on Blockly's pale yellow.

The code the blocks write is easier to read: four-space indents, a blank
line between rules, no brackets around a whole condition or around a chain
of the same operator (`a AND b AND c`). Small and huge numbers are written
out: JavaScript printed 0.0000001 as `1e-7`, which TSL can't read.

## What the blocks can't show

- A name with no block, such as a misspelt indicator (`RSII(14)`).
- `SELL qty = 25% OF EQUITY`: the sell block sells a share of the position.
- A coin other than BTCUSDT, ETHUSDT, SOLUSDT, or a strategy timeframe other
  than 5m, 15m, 1h, 4h (the ones TSB has candles for; the compiler also
  accepts 1m and 1d).
- A calculation or a named value where the block has a number box: an
  indicator setting (`RSI(n)`), the capital, a quantity, a stop.
- `ON` with another timeframe (`ON 3h`), a percent inside a calculation, a
  setting the blocks don't have, a missing symbol/timeframe/capital line.
- A number outside a block's range (a stop below 0.01%, buying more than
  100%): found by the check in step 3.

## How it was checked

- **Backend:** the 391 database-free tests pass (9 new: `ParseTreeTest`,
  and the parse tree in `CompilationServiceTest`). `CompileControllerTest`
  has a new test for `withTree`; like the other Spring tests it was not run
  here.
- **Round trip** with the app's own code (headless Blockly) and the real
  compiler, 38 cases: the 8 ready-made strategies, the 8 files in
  `strategies/`, the Builder's default, an RSI-dip strategy on 5m like #7 in
  the bug report (written by hand, and as the old Blocks view saved it), a
  strategy using every block with comments in every place, no fee line, and
  code with compile errors are all shown exactly; their comments are kept,
  and a second round gives the same code.
  The 14 cases the blocks can't show are reported with the right message,
  and the syntax error is reported.
- **Same trades:** each of the 22 exact cases was backtested with TSB's
  engine against its blocks version on 5 random-walk series of 30,000
  candles: identical trades and equity in all 22 (for the code with errors,
  identical errors).
- **In the browser** (Chromium, the real compiler behind a stand-in
  server): Edit → Blocks shows that strategy with no popup; the code stays
  untouched until a block changes; moving blocks changes nothing; editing a
  block rewrites the code and keeps its comments; the notices; no console
  errors.
- **Types:** `tsc` finds no errors. oxlint was not run (only its Windows
  binary is installed).
