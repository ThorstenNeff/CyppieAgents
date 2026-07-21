package com.tneff.cyppieagents.agentview

/**
 * CYP-790 — op-po PO-Output anti-flood. A verbose PO turn coordinates workers via **many** `ToolCall`/`Result`
 * lines (the "interna"), and the one line the operator wanted — the `AssistantText` answer — drowns in them.
 * The lever: fold a contiguous run of tool events into a collapsible summary, leave the prose answer standing.
 *
 * This is a **pure VIEW derivation** over the already-folded [AgentEvent] list — NO new event type, no reducer /
 * protocol / BE seam (spec §2/§10, same line as CYP-742). The three honesty teeth (§3) live here:
 *  - **Z1** a folded run always carries its step COUNT ([TranscriptItem.Run.stepCount]);
 *  - **Z2** a run with any error never silent-folds ([TranscriptItem.Run.hasError] → [defaultCollapsed] = false);
 *  - **Z3** the streaming tail (a trailing RUNNING [AgentEvent.ToolCall]) is never grouped.
 */

/** §14 — the step count at/above which a clean, completed tool-run folds by default. One place, adjustable. */
internal const val RUN_FOLD_THRESHOLD: Int = 4

/**
 * A rendered transcript item: either a single event (rendered exactly as today) or a foldable RUN of contiguous
 * `ToolCall`/`Result` events (a collapsible group). [startIndex] is the 0-based index of this item's FIRST event
 * in the raw list, so a run's child rows keep their existing `agent.<id>.event.<index>…` tags on expand (§7),
 * and the CONTEXT_LOST band / `receded` styling still key on the raw index (§15).
 */
internal sealed interface TranscriptItem {
    val startIndex: Int

    /** Stable LazyColumn key — the run's identity is its FIRST `event.id` (§12), so a growing tail keeps its key
     *  and thus its operator fold-override, and never re-collapses while the PO keeps talking. */
    val key: String

    data class Single(override val startIndex: Int, val event: AgentEvent) : TranscriptItem {
        override val key: String get() = event.id
    }

    data class Run(
        override val startIndex: Int,
        val events: List<AgentEvent>,
    ) : TranscriptItem {
        override val key: String get() = events.first().id

        /** §11 Zähl-Wahrheit: the number of `ToolCall` events — a `Result` is its call's OUTCOME, not its own
         *  step (counting it would double every call: 12 → a false "24"). A run with zero `ToolCall`s (a defined
         *  edge, not expected) counts events instead, so N is never 0. This is the TRUE number, not an estimate. */
        val stepCount: Int
            get() = events.count { it is AgentEvent.ToolCall }.let { if (it == 0) events.size else it }

        /** §3 Zahn 2: ERROR `ToolCall`s + isError `Result`s — a run with any error fails LOUD, never silent-folds. */
        val errorCount: Int
            get() = events.count {
                (it is AgentEvent.ToolCall && it.status == ToolStatus.ERROR) ||
                    (it is AgentEvent.Result && it.isError)
            }

        val hasError: Boolean get() = errorCount > 0

        /** §10 default fold state: a clean run collapses; an error run comes **open** (Zahn 2 fail-loud — its header
         *  still carries the error marker so a later manual collapse stays honest). */
        val defaultCollapsed: Boolean get() = !hasError
    }
}

private fun isToolEvent(e: AgentEvent): Boolean = e is AgentEvent.ToolCall || e is AgentEvent.Result

/**
 * §10 — the render model. When [foldToolRuns] is false (the default, and every non-op-po window) each event is a
 * [TranscriptItem.Single] — **byte-identical to today**. When true, a **maximal contiguous** subsequence of
 * `ToolCall`/`Result` events becomes a [TranscriptItem.Run] iff it (1) reaches [RUN_FOLD_THRESHOLD] steps and
 * (3) is not the streaming tail (a trailing RUNNING `ToolCall`, Zahn 3). Error runs (2) still GROUP but open by
 * default ([TranscriptItem.Run.defaultCollapsed]). The run BREAKS at every non-tool event (each is a statement —
 * PO / operator / platform — never swallowed) **and at [boundary]** (§15: never span the CONTEXT_LOST landmark).
 * Short / streaming runs stay Single rows (byte-identical).
 */
internal fun transcriptItems(
    events: List<AgentEvent>,
    foldToolRuns: Boolean,
    boundary: Int?,
): List<TranscriptItem> {
    if (!foldToolRuns) return events.mapIndexed { i, e -> TranscriptItem.Single(i, e) }

    val out = ArrayList<TranscriptItem>(events.size)
    val n = events.size
    var i = 0
    while (i < n) {
        val e = events[i]
        if (!isToolEvent(e)) {
            out += TranscriptItem.Single(i, e)
            i++
            continue
        }
        // Maximal contiguous tool run [start, j). Break at the first non-tool event OR at the boundary (§15) —
        // but a run may legitimately START at the boundary (all its events are then post-loss), so only break
        // when the boundary falls strictly INSIDE the run (j != start).
        val start = i
        var j = i
        while (j < n && isToolEvent(events[j]) && !(boundary != null && j == boundary && j != start)) {
            j++
        }
        val run = events.subList(start, j).toList()
        val stepCount = run.count { it is AgentEvent.ToolCall }.let { if (it == 0) run.size else it }
        // §3 Zahn 3 (UIUX §-QA fix): fold only a run that is NOT the buffer tail (`j < n`). A run reaching the buffer
        // end may still GROW as the live turn continues — folding it would oscillate (fold on each ToolCall
        // completion, unfold on the next RUNNING one → flicker + auto-follow jump). It folds once something FOLLOWS
        // it (the AssistantText answer / next turn), i.e. the run is definitively closed. This subsumes the earlier
        // "trailing RUNNING ToolCall" check — a running tail is a buffer tail too.
        val isBufferTail = j == n
        if (stepCount >= RUN_FOLD_THRESHOLD && !isBufferTail) {
            out += TranscriptItem.Run(start, run)
        } else {
            for (k in start until j) out += TranscriptItem.Single(k, events[k])
        }
        i = j
    }
    return out
}
