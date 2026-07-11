package com.tneff.cyppieagents.agentview

/**
 * CYP-387 — a bounded, in-memory ring buffer of the messages an operator has **sent** to one agent, newest
 * last. Session-scoped: it lives with the per-agent [AgentViewModel], so it is already per-agent and holds no
 * persistence in v1. The composer reads [entries] to walk arrow-up/down through prior sends.
 *
 * **This is the STORE only.** The navigation cursor, the arrow-key binding, draft-stash-on-entry and the
 * transient recall-edit copy are the composer's concern (interaction spec §2, layer B) — kept out of here so
 * the store stays a pure, deterministic unit.
 *
 * **Capacity is a live supplier** (`() -> Int`): the size N is ONE global personal preference (spec §3), so a
 * change must take effect on an already-open agent without rebuilding the store (which would wipe the content).
 * Semantics, straight from the spec:
 *  - `N <= 0` → history **off**: [record] is a no-op and [entries] is empty (spec §3.1 `0 = aus`, honest switch).
 *  - at N, the **oldest** entry is evicted (terminal `HISTSIZE` model).
 *  - **no dedup in v1** (spec §1) — consecutive duplicates are both kept; predictable, shell without `HISTCONTROL`.
 *  - blank text is never recorded (the send path rejects it too — defence in depth).
 */
class InputHistory(private val capacity: () -> Int) {

    /** Convenience for a fixed capacity (tests, and any caller with a constant N). */
    constructor(capacity: Int) : this({ capacity })

    // Newest at the end. ArrayDeque gives O(1) oldest-eviction (removeFirst) and append (addLast).
    private val buffer = ArrayDeque<String>()

    /**
     * Newest-last immutable snapshot, bounded to the CURRENT capacity — so a shrink of the global N (or `0`)
     * is reflected on read even before the next send. The composer reads this and never mutates the store.
     */
    val entries: List<String> get() = buffer.toList().takeLast(capacity().coerceAtLeast(0))

    val size: Int get() = entries.size

    /** Record a sent message. No-op when history is off (`N <= 0`) or the text is blank; evicts the oldest at N. */
    fun record(text: String) {
        val cap = capacity()
        if (cap <= 0) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        buffer.addLast(trimmed)
        while (buffer.size > cap) buffer.removeFirst()
    }

    companion object {
        /** Spec §3.1 default; also the range the settings stepper clamps to. */
        const val DEFAULT_CAPACITY = 20
        const val MAX_CAPACITY = 200
    }
}
