import * as Blockly from 'blockly'
import './defs' // registers the block definitions
import { api, type DiagnosticDto } from '../api'
import { differences, EMPTY_STATE, treeToState } from './fromTree'
import { generateTslChecked } from './generator'

export type CodeAsBlocks =
  /** The blocks are the code: same strategy, same meaning. */
  | { kind: 'exact'; state: object }
  /** Blocks for as much as they can show; `problems` says what they can't. */
  | { kind: 'inexact'; state: object; problems: string[] }
  /** The code has a mistake the compiler can't read past: no blocks yet. */
  | { kind: 'syntax'; diagnostics: DiagnosticDto[] }

/** The code the blocks in `state` write, built off-screen. */
export function blocksToSource(state: object): string {
  const ws = new Blockly.Workspace()
  try {
    Blockly.serialization.workspaces.load(state, ws)
    return generateTslChecked(ws).source
  } finally {
    ws.dispose()
  }
}

/**
 * The code as blocks. The compiler reads the code (one brain: no TSL is
 * parsed here) and sends its tree; the tree becomes blocks. Then the proof:
 * the blocks write their code, the compiler reads that too, and only if both
 * trees mean the same is the answer "exact". Throws when the compiler can't
 * be reached.
 */
export async function codeToBlocks(source: string): Promise<CodeAsBlocks> {
  if (!source.trim()) return { kind: 'exact', state: EMPTY_STATE }
  const res = await api.compileTree(source)
  if (!res.tree) {
    return { kind: 'syntax', diagnostics: res.diagnostics.filter((d) => d.severity === 'ERROR') }
  }
  const { state, problems } = treeToState(res.tree)
  if (problems.length === 0) {
    const back = await api.compileTree(blocksToSource(state))
    if (!back.tree) problems.push('the blocks would not write working code — please report this strategy')
    else problems.push(...differences(res.tree, back.tree))
  }
  return problems.length > 0 ? { kind: 'inexact', state, problems } : { kind: 'exact', state }
}
