// CYP-868 (OS-A, Epic CYP-867) — the pure model for orchestration message TYPES + reply THREADING. Renders ONLY what
// the server-stamped MessageMeta says (render ≠ authority — the same discipline as server-resolved mention spans,
// CYP-744): the client NEVER derives a kind or a thread membership from body content / timing / heuristics. Absent
// meta ⇒ a plain NOTE-equivalent, NEVER a fabricated TASK/STATUS. A reply link is honoured ONLY from the server's
// `inReplyTo`, and ONLY when its parent is actually present — an inReplyTo to an unknown/absent parent is NOT a
// fabricated thread (it renders top-level).
import type { DeliveredMessage, MessageMeta } from '../types/generated/contract'

/** The stored comm message shape (the contract dedupes it to `Message1`; derive it from the envelope to stay robust
 *  against the codegen name — this is what `DeliveredMessage.message` actually is). */
export type StoredMessage = DeliveredMessage['message']

/** The three orchestration kinds. NOTE is the neutral baseline (also what an absent/null kind resolves to). */
export type MessageKind = 'TASK' | 'STATUS' | 'NOTE'

/**
 * The kind to render, from server-stamped meta ONLY. Absent/null meta or absent/null kind ⇒ NOTE (the plain baseline)
 * — never a fabricated TASK/STATUS. TASK/STATUS appear only when the server actually stamped them.
 */
export function messageKind(meta: MessageMeta | null | undefined): MessageKind {
  return meta?.kind ?? 'NOTE'
}

/** Whether a kind is an orchestration-SIGNIFICANT type (gets a visible badge). NOTE is the quiet default (no badge). */
export function isOrchestrationKind(kind: MessageKind): boolean {
  return kind === 'TASK' || kind === 'STATUS'
}

/**
 * The parent message a reply points at, from the server `inReplyTo` ONLY, and ONLY when that parent is present in the
 * supplied set. Returns null when: no inReplyTo (a top-level message), or an inReplyTo to a parent not in the set (we
 * do NOT fabricate a thread to a message we cannot see). `byId` maps message.id → the delivered envelope.
 */
export function replyParent(message: StoredMessage, byId: ReadonlyMap<string, DeliveredMessage>): StoredMessage | null {
  const parentId = message.meta?.inReplyTo
  if (parentId == null) return null // top-level — not a reply
  const parent = byId.get(parentId)
  return parent ? parent.message : null // unknown/absent parent → NOT a fabricated reply
}

/** Build the id→envelope index the reply helpers key on (from the visible message set). */
export function indexById(messages: readonly DeliveredMessage[]): ReadonlyMap<string, DeliveredMessage> {
  return new Map(messages.map((d) => [d.message.id, d]))
}

/** Max reply depth to indent — a guardrail against a runaway/cyclic inReplyTo chain (server data is untrusted). */
export const MAX_REPLY_DEPTH = 8

/**
 * The thread depth to indent a message by, following the server `inReplyTo` chain upward through the present set. A
 * top-level message (or one whose parent is absent) is depth 0. Cycle- and runaway-safe: a visited-set stops a cycle,
 * and the count is capped at MAX_REPLY_DEPTH. Derived ONLY from server-stamped inReplyTo links (never guessed).
 */
export function replyDepth(message: StoredMessage, byId: ReadonlyMap<string, DeliveredMessage>): number {
  let depth = 0
  const seen = new Set<string>([message.id])
  let current: StoredMessage | null = replyParent(message, byId)
  while (current !== null && depth < MAX_REPLY_DEPTH && !seen.has(current.id)) {
    depth += 1
    seen.add(current.id)
    current = replyParent(current, byId)
  }
  return depth
}
