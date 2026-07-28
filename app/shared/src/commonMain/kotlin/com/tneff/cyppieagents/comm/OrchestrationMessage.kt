package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta

/**
 * CYP-879 (OS-A, Compose mirror of web-ts CYP-868 `orchestrationMessage.ts`) — the pure model for orchestration
 * message TYPES + reply THREADING. Renders ONLY what the server-stamped [MessageMeta] says (**render ≠ authority** —
 * the same discipline as server-resolved mention spans): the client NEVER derives a kind or a thread membership from
 * body content / timing / heuristics.
 *
 *  - Absent/null meta (or absent/null kind) ⇒ a plain [MessageKind.NOTE] baseline — NEVER a fabricated TASK/STATUS.
 *  - A reply link is honoured ONLY from the server's [MessageMeta.inReplyTo], and ONLY when its parent is actually
 *    present in the visible set — an inReplyTo to an unknown/absent parent is NOT a fabricated thread (top-level).
 *  - The indent depth follows the server `inReplyTo` chain upward and is cycle-/runaway-safe (visited-set + cap).
 *
 * Pure (no Compose, no transport) — the honesty is unit-provable and the render layer just displays these results.
 */

/**
 * The kind to render, from server-stamped meta ONLY. Absent/null meta or absent/null kind ⇒ [MessageKind.NOTE] (the
 * plain baseline) — never a fabricated TASK/STATUS. TASK/STATUS appear only when the server actually stamped them.
 */
fun messageKind(meta: MessageMeta?): MessageKind = meta?.kind ?: MessageKind.NOTE

/**
 * Whether a kind is orchestration-SIGNIFICANT (gets a visible badge). NOTE is the quiet default (no badge) — badging
 * NOTE would fabricate significance the server did not stamp.
 */
fun isOrchestrationKind(kind: MessageKind): Boolean = kind == MessageKind.TASK || kind == MessageKind.STATUS

/** Build the id→message index the reply helpers key on (from the visible message set). */
fun indexById(messages: List<Message>): Map<String, Message> = messages.associateBy { it.id }

/**
 * The parent a reply points at, from the server [MessageMeta.inReplyTo] ONLY, and ONLY when that parent is present in
 * [byId]. Returns null when: no inReplyTo (a top-level message), or an inReplyTo to a parent not in the set (we do NOT
 * fabricate a thread to a message we cannot see).
 */
fun replyParent(message: Message, byId: Map<String, Message>): Message? {
    val parentId = message.meta?.inReplyTo ?: return null // top-level — not a reply
    return byId[parentId] // unknown/absent parent → null → NOT a fabricated reply
}

/** Max reply depth to indent — a guardrail against a runaway/cyclic inReplyTo chain (server data is untrusted). */
const val MAX_REPLY_DEPTH: Int = 8

/**
 * The thread depth to indent [message] by, following the server `inReplyTo` chain upward through the present set. A
 * top-level message (or one whose parent is absent) is depth 0. Cycle- and runaway-safe: a visited-set stops a cycle,
 * and the count is capped at [MAX_REPLY_DEPTH]. Derived ONLY from server-stamped inReplyTo links (never guessed).
 */
fun replyDepth(message: Message, byId: Map<String, Message>): Int {
    var depth = 0
    val seen = mutableSetOf(message.id)
    var current: Message? = replyParent(message, byId)
    while (current != null && depth < MAX_REPLY_DEPTH && current.id !in seen) {
        depth += 1
        seen.add(current.id)
        current = replyParent(current, byId)
    }
    return depth
}
