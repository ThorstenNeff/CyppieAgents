// CYP-401 (W3) — pure reducer folding the raw event stream into the rendered transcript, ported from
// :app:shared (agentview/TranscriptFolding.kt). Side-effect-free so the merge semantics are unit-tested directly.
//   - assistantText: deltas sharing an id concatenate into one growing row; the latest `complete` wins.
//   - toolCall: same id updates IN PLACE (RUNNING → OK/ERROR), keeping position AND the row's original tsMs
//     (CYP-335: a row is dated by when it happened, not when it was last touched).
//   - result / notice / userTurn / incomingSystem: append-only, de-duplicated by id (reconnect replay safe).
import type { AgentEvent, AssistantTextRow, ToolCallRow } from './agentEvent'

export function foldEvent(current: AgentEvent[], event: AgentEvent): AgentEvent[] {
  const idx = current.findIndex((e) => e.id === event.id)
  switch (event.kind) {
    case 'assistantText': {
      const existing = idx >= 0 && current[idx].kind === 'assistantText' ? (current[idx] as AssistantTextRow) : null
      if (existing !== null) {
        const next = [...current]
        // Spread from `existing`, so tsMs (and id) stay the FIRST delta's by construction.
        next[idx] = { ...existing, text: existing.text + event.text, complete: event.complete }
        return next
      }
      return [...current, event]
    }
    case 'toolCall': {
      const existing = idx >= 0 && current[idx].kind === 'toolCall' ? (current[idx] as ToolCallRow) : null
      if (existing !== null) {
        const next = [...current]
        // Take the incoming payload (RUNNING → OK/ERROR) but keep the row's own start time.
        next[idx] = { ...event, tsMs: existing.tsMs }
        return next
      }
      return [...current, event]
    }
    case 'result':
    case 'notice':
    case 'userTurn':
    case 'incomingSystem':
      return idx >= 0 ? current : [...current, event]
  }
}

/** Folds a whole sequence from empty — convenience for replays and the behavioral corpus. */
export function foldEvents(events: Iterable<AgentEvent>): AgentEvent[] {
  let acc: AgentEvent[] = []
  for (const e of events) acc = foldEvent(acc, e)
  return acc
}
