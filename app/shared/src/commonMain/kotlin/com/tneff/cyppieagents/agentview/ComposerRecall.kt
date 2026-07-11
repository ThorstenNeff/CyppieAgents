package com.tneff.cyppieagents.agentview

/**
 * CYP-387 §2 — the arrow-up/down **recall cursor** over an immutable, newest-last history (the [InputHistory]
 * snapshot). Pure and deterministic: the composer owns one instance, passes in the current history + live draft,
 * and renders the returned draft. **The history is NEVER mutated here** — a recall-edit is a transient working
 * copy the caller holds in its own draft state; the next ↑/↓ reads the ORIGINAL neighbour (spec §2.2, test 3).
 *
 * Single-line composer (spec §0): ↑/↓ are pure history nav, no cursor-line concern.
 *
 * [older]/[newer] return the draft to SHOW, or `null` when the arrow is **not consumed** (empty history on ↑, or
 * already at the live draft on ↓) — so the field does not swallow a keypress that did nothing.
 */
class ComposerRecall {

    /** null = at the live draft; else an index into the history passed to [older]/[newer]. Exposed for tests. */
    var navIndex: Int? = null
        private set

    // The live draft, captured on ENTRY to history so ↓ past the newest restores it.
    private var stash: String = ""

    /** ↑ (older). Enters history (stashing [draft]) from the live draft, else steps toward older entries. */
    fun older(history: List<String>, draft: String): String? {
        if (history.isEmpty()) return null
        val idx = navIndex?.coerceAtMost(history.lastIndex)
        return when {
            idx == null -> { stash = draft; navIndex = history.lastIndex; history.last() }
            idx > 0 -> { navIndex = idx - 1; history[idx - 1] }
            else -> { navIndex = 0; draft } // at the oldest: no move (draft unchanged) but consumed
        }
    }

    /** ↓ (newer). Steps toward newer entries, or leaves history at the newest — restoring the stashed draft. */
    fun newer(history: List<String>, draft: String): String? {
        val raw = navIndex ?: return null
        if (history.isEmpty()) { navIndex = null; return stash }
        val idx = raw.coerceAtMost(history.lastIndex)
        return if (idx < history.lastIndex) { navIndex = idx + 1; history[idx + 1] } else { navIndex = null; stash }
    }

    /** After a send (the draft was captured as a new entry by the store): leave history, clear the stash. */
    fun reset() { navIndex = null; stash = "" }
}
