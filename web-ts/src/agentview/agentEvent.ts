// CYP-401 (W3) — the UI-shaped transcript event, a TS port of :app:shared's AgentEvent (agentview/AgentEvent.kt).
// Deliberately a *UI* model, not the wire format: the mapper (streamJsonMapper.ts) translates the generated
// StreamJsonEvent into these. Discriminated on `kind`. Every row carries a stable `id` (keys the list, folds
// streaming text, anchors reconnect de-dup) and `tsMs` (server-stamped for stream rows; the FIRST event's time
// for a row that updates in place — see transcriptFolding.ts).
export type ToolStatus = 'running' | 'ok' | 'error'

/** Assistant turn text; deltas sharing an `id` concatenate (transcriptFolding). `complete` drops the cursor. */
export interface AssistantTextRow {
  kind: 'assistantText'
  id: string
  text: string
  complete: boolean
  tsMs: number
}

/** A tool invocation, one line; re-emitted with the same `id` to update `status` (RUNNING → OK/ERROR). */
export interface ToolCallRow {
  kind: 'toolCall'
  id: string
  tool: string
  summary: string
  status: ToolStatus
  tsMs: number
}

/** A tool/turn result, marked error vs success. */
export interface ResultRow {
  kind: 'result'
  id: string
  label: string
  isError: boolean
  tsMs: number
}

/** System / lifecycle notice (session ready, turn error). */
export interface NoticeRow {
  kind: 'notice'
  id: string
  text: string
  tsMs: number
}

/** A human turn echoed locally on send (CYP-323) — the single source of the user-turn row. */
export interface UserTurnRow {
  kind: 'userTurn'
  id: string
  text: string
  tsMs: number
}

/** A platform-injected incoming message the operator should see (CYP-326 #1). */
export interface IncomingSystemRow {
  kind: 'incomingSystem'
  id: string
  text: string
  tsMs: number
}

export type AgentEvent = AssistantTextRow | ToolCallRow | ResultRow | NoticeRow | UserTurnRow | IncomingSystemRow
