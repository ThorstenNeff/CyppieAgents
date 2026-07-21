package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-790 — pure derivation of the op-po tool-run fold ([transcriptItems]). Headless; the honesty teeth live here.
 * Builders keep the events minimal — only the fields the predicate reads (kind, status, isError) matter; tsMs is
 * irrelevant to the pure derivation (the render passes the CONTEXT_LOST `boundary` as an index directly).
 *
 * A run folds ONLY when it is not the buffer tail (§3 Zahn 3, UIUX §-QA): most fold cases therefore end with a
 * trailing [answer] (the AssistantText that closes a real PO turn) so the run is definitively completed.
 */
class Cyp790ToolRunFoldingTest {

    private fun tc(i: Int, status: ToolStatus = ToolStatus.OK) =
        AgentEvent.ToolCall("tc$i", "hub", "send $i", status, tsMs = i.toLong())
    private fun res(i: Int, isError: Boolean = false) =
        AgentEvent.Result("r$i", "ok $i", isError, tsMs = i.toLong())
    private fun txt(i: Int, complete: Boolean = true) =
        AgentEvent.AssistantText("a$i", "prosa $i", complete, tsMs = i.toLong())
    private fun user(i: Int) = AgentEvent.UserTurn("u$i", "hi $i", tsMs = i.toLong())
    private fun sys(i: Int) = AgentEvent.IncomingSystem("s$i", "/compact", tsMs = i.toLong())
    private fun notice(i: Int) = AgentEvent.Notice("n$i", "note $i", tsMs = i.toLong())
    /** The trailing AssistantText answer that closes a PO turn — makes a preceding run no longer the buffer tail. */
    private fun answer() = txt(999)

    private fun items(events: List<AgentEvent>, fold: Boolean = true, boundary: Int? = null) =
        transcriptItems(events, foldToolRuns = fold, boundary = boundary)

    private fun runs(events: List<AgentEvent>, fold: Boolean = true, boundary: Int? = null) =
        items(events, fold, boundary).filterIsInstance<TranscriptItem.Run>()

    // --- The op-po Boolean gate (the PO's added tooth) -------------------------------------------------

    @Test
    fun foldDisabled_everyEventIsSingle_byteIdentical() {
        // A closed 5-ToolCall run that WOULD fold — but foldToolRuns=false → all Single, no Run.
        // Mutation: drop the `if (!foldToolRuns) return …` guard (fold globally) → this REDs (a Run appears).
        val events = (0 until 5).map { tc(it) } + answer()
        val out = items(events, fold = false)
        assertTrue(out.all { it is TranscriptItem.Single }, "foldToolRuns=false must never group")
        assertEquals(6, out.size)
    }

    @Test
    fun foldEnabled_sameRun_groups() {
        // The contrast: the SAME closed run under foldToolRuns=true DOES group — proves the gate is the discriminator.
        assertEquals(1, runs((0 until 5).map { tc(it) } + answer()).size)
    }

    // --- §3 Zahn 3 (UIUX §-QA fix): a run folds only when it is NOT the buffer tail --------------------

    @Test
    fun bufferTailRun_doesNotFold_butFoldsOnceFollowed() {
        // A long clean run at the buffer end must NOT fold (it may still grow → oscillation). It folds once an
        // event follows it (the turn's answer). Mutation: fold condition drops `&& !isBufferTail` → the buffer-tail
        // run folds → the first assertion REDs.
        val tail = (0 until 5).map { tc(it) }
        assertEquals(0, runs(tail).size, "a run at the buffer tail must not fold")
        assertEquals(1, runs(tail + answer()).size, "…but folds once something follows it")
    }

    @Test
    fun runningToolCall_atTail_staysSingle() {
        // A RUNNING trailing ToolCall is a buffer tail too → not folded (subsumed by the buffer-tail rule).
        assertEquals(0, runs(listOf(tc(0), tc(1), tc(2), tc(3, ToolStatus.RUNNING))).size)
    }

    // --- §11 count truth (Zahn 1) ---------------------------------------------------------------------

    @Test
    fun stepCount_isToolCallCount_notDoubledByResults() {
        // 4 ToolCalls each with a Result (8 events) + answer → ONE run whose stepCount is 4, NOT 8.
        // Mutation: count all events instead of ToolCalls → stepCount 8 → REDs.
        val events = (0 until 4).flatMap { listOf(tc(it), res(it)) } + answer()
        val run = runs(events).single()
        assertEquals(4, run.stepCount, "a Result is its call's outcome, not its own step")
    }

    @Test
    fun zeroToolCallRun_countsEvents_soNeverZero() {
        // Defined edge (§11): a run of only Results → stepCount falls back to the event count, never 0.
        assertEquals(4, runs((0 until 4).map { res(it) } + answer()).single().stepCount)
    }

    // --- §10 threshold ---------------------------------------------------------------------------------

    @Test
    fun belowThreshold_staysSingle() {
        // THRESHOLD-1 steps do NOT fold even when closed by a following event — "ab", not "nahe" (§15). 3 < 4.
        val events = (0 until 3).map { tc(it) } + answer()
        assertEquals(0, runs(events).size)
    }

    // --- §10 predicate: breaks at every non-tool type + the boundary ----------------------------------

    @Test
    fun runBreaksAtAssistantText() {
        // 4 tools · prose · 4 tools · answer → TWO runs, split by the prose (a Single) which is never swallowed.
        val events = (0 until 4).map { tc(it) } + txt(100) + (5 until 9).map { tc(it) } + answer()
        val out = items(events)
        assertEquals(2, out.filterIsInstance<TranscriptItem.Run>().size)
        assertTrue(out.any { it is TranscriptItem.Single && it.event is AgentEvent.AssistantText })
    }

    @Test
    fun runBreaksAtEveryNonToolType() {
        for (breaker in listOf(user(100), sys(100), notice(100))) {
            val events = (0 until 4).map { tc(it) } + breaker + (5 until 9).map { tc(it) } + answer()
            assertEquals(2, runs(events).size, "a ${breaker::class.simpleName} must break the run")
        }
    }

    @Test
    fun runBreaksAtBoundary_neverSpansContextLostLandmark() {
        // 8 contiguous ToolCalls + answer, loss boundary at index 4 → TWO runs [0,4) and [4,8); neither spans the
        // landmark. Mutation: drop the boundary break → ONE 8-step run spanning the landmark → REDs.
        val events = (0 until 8).map { tc(it) } + answer()
        val rs = runs(events, boundary = 4)
        assertEquals(2, rs.size)
        assertEquals(listOf(0, 4), rs.map { it.startIndex })
    }

    // --- §3 Zahn 2: error runs fail LOUD (group, but come open, with the count) ------------------------

    @Test
    fun errorRun_groupsButComesOpen_withErrorCount() {
        // A 4-step closed run with one ERROR ToolCall and one isError Result → grouped, hasError, comes OPEN (Zahn 2).
        // Mutation: `defaultCollapsed = !hasError` → `= true` → comesOpen REDs.
        val events = listOf(tc(0), tc(1, ToolStatus.ERROR), res(1, isError = true), tc(2), tc(3)) + answer()
        val run = runs(events).single()
        assertTrue(run.hasError)
        assertEquals(2, run.errorCount, "one ERROR ToolCall + one isError Result")
        assertFalse(run.defaultCollapsed, "an error run must NOT silent-fold — it comes open")
    }

    @Test
    fun cleanRun_collapsesByDefault() {
        assertTrue(runs((0 until 4).map { tc(it) } + answer()).single().defaultCollapsed)
    }
}
