package com.tneff.cyppieagents.agentview

/**
 * Pure reducer that folds the raw event stream into the rendered transcript list.
 *
 * Kept side-effect-free and coroutine-free so the streaming/merge semantics can be unit-tested
 * directly. [AgentViewModel] applies it as events arrive.
 *
 * Rules:
 *  - [AgentEvent.AssistantText]: deltas sharing an `id` concatenate into one growing item; the
 *    latest `complete` flag wins. (Streaming text is one item, not N rows.)
 *  - [AgentEvent.ToolCall]: same `id` updates in place (e.g. RUNNING → OK/ERROR), keeping its
 *    position; a new `id` appends.
 *  - [AgentEvent.Result] / [AgentEvent.Notice]: append-only, but de-duplicated by `id` so a
 *    reconnect replay of the same terminal event does not double it.
 */
fun foldEvent(current: List<AgentEvent>, event: AgentEvent): List<AgentEvent> {
    val idx = current.indexOfFirst { it.id == event.id }
    return when (event) {
        is AgentEvent.AssistantText -> {
            val existing = current.getOrNull(idx) as? AgentEvent.AssistantText
            if (existing != null) {
                current.toMutableList().also {
                    it[idx] = existing.copy(
                        text = existing.text + event.text,
                        complete = event.complete,
                    )
                }
            } else {
                current + event
            }
        }

        is AgentEvent.ToolCall -> {
            if (idx >= 0 && current[idx] is AgentEvent.ToolCall) {
                current.toMutableList().also { it[idx] = event }
            } else {
                current + event
            }
        }

        is AgentEvent.Result,
        is AgentEvent.Notice,
        // CYP-323: the human echo is append-only and de-duplicated by its stable turn id — idempotent
        // insurance if the same turn were ever re-applied (re-entrant send / a future Hub-echo path).
        is AgentEvent.UserTurn,
        // CYP-326 #1: the injected incoming/system message — append-only, de-duplicated by id (a reconnect
        // replay of the same synthetic event must not double it), rendered in stream order.
        is AgentEvent.IncomingSystem -> {
            if (idx >= 0) current else current + event
        }
    }
}

/** Folds a whole sequence from empty. Convenience for tests and replays. */
fun foldEvents(events: Iterable<AgentEvent>): List<AgentEvent> =
    events.fold(emptyList()) { acc, e -> foldEvent(acc, e) }
