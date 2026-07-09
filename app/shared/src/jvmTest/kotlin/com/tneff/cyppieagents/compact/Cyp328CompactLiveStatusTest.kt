package com.tneff.cyppieagents.compact

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactRunSummary
import com.tneff.cyppieagents.model.CompactStatus
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-328 — a compact sequence that is already running becomes visible WITHOUT any config POST.
 *
 * The bug: [CompactViewModel] loaded status only at init + as the `POST /api/compact/config` response, and the
 * per-sequence list scoped to `status.lastRun.correlationId` — so a sequence that started while the (always-alive)
 * window was open stayed invisible until an unrelated config-change refetched the status. Two UI-only seams fix it,
 * no new contract:
 *   - MUT-A · [CompactViewModel.observeWhileOpen] refetches on open and RE-POLLS while the window is open, so the
 *     server-mirror rows (running / last-run) go live.
 *   - MUT-B · [currentRunId] derives the run from the LIVE event feed (newest by seq), so the list appears the
 *     instant events stream — before/without the status catching up, and without a POST.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp328CompactLiveStatusTest {

    private fun ev(seq: Long, type: EventType, cid: String) = Event(
        id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = "po", projectId = "team-1",
        type = type, severity = Severity.INFO, correlationId = cid, sessionId = null,
    )

    /** Status is idle until [running] is flipped; a config POST is an assertion failure (the "without POST" oracle). */
    private class FlippableRepo : CompactRepository {
        var running = false
        var setConfigCalls = 0
        override suspend fun getStatus() = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = true, running = running,
            lastRun = CompactRunSummary(completed = 0, total = 2, startedTs = 1, finishedTs = null, correlationId = "run-live"),
        )
        override suspend fun setConfig(config: CompactConfig): CompactStatus {
            setConfigCalls++
            throw AssertionError("CYP-328: no config POST expected — the run must surface without a write")
        }
    }

    /** getStatus is idle until [runningFromCall]; onward it reports running. Used to prove the open-refresh. */
    private class CountingRepo(private val runningFromCall: Int) : CompactRepository {
        var getStatusCalls = 0
        var setConfigCalls = 0
        override suspend fun getStatus(): CompactStatus {
            getStatusCalls++
            return CompactStatus(
                allowed = true, thresholdTokens = 500_000, armed = true, running = getStatusCalls >= runningFromCall,
                lastRun = CompactRunSummary(completed = 0, total = 2, startedTs = 1, finishedTs = null, correlationId = "run-live"),
            )
        }
        override suspend fun setConfig(config: CompactConfig): CompactStatus {
            setConfigCalls++
            throw AssertionError("CYP-328: no config POST expected")
        }
    }

    // --- MUT-A: the while-open poll re-reads status (virtual time), flipping idle→running with no config POST ---

    @Test
    fun observeWhileOpen_repolls_flipsIdleToRunning_withoutConfigPost() = runTest {
        val repo = FlippableRepo() // starts idle
        val model = CompactViewModel(repo, editable = true, backgroundScope)
        val job = launch { model.observeWhileOpen() }
        runCurrent()
        assertEquals(false, model.state.value.status?.running, "open-refresh reads the current (idle) status")

        repo.running = true // a sequence starts server-side — crucially, NO config POST
        advanceTimeBy(STATUS_POLL_INTERVAL_MS + 1)
        runCurrent()

        assertEquals(true, model.state.value.status?.running, "the while-open poll picks up the running sequence")
        assertEquals(0, repo.setConfigCalls, "surfaced WITHOUT any config POST — the ‘without POST' oracle")
        job.cancel()
    }

    // --- MUT-B: currentRunId prefers the newest LIVE event's correlationId over a stale status ---

    @Test
    fun currentRunId_prefersNewestLiveEvent_overStaleStatus() {
        // Frozen status still points at an OLD finished run; the live feed carries the running run (higher seq).
        val stale = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = true, running = false,
            lastRun = CompactRunSummary(completed = 3, total = 3, startedTs = 1, finishedTs = 2, correlationId = "old-run"),
        )
        val live = listOf(
            ev(10, EventType.COMPACT_PREPARE_SENT, "run-live"),
            ev(11, EventType.COMPACT_REQUEST_SENT, "run-live"),
            ev(2, EventType.COMPACT_COMPLETED, "old-run"), // decoy: older finished run, lower seq
        )
        assertEquals("run-live", currentRunId(live, stale), "newest-by-seq live event's correlationId wins over the stale status")
    }

    @Test
    fun currentRunId_fallsBackToStatus_whenNoLiveEvents() {
        val status = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = false, running = false,
            lastRun = CompactRunSummary(completed = 1, total = 1, startedTs = 1, finishedTs = 2, correlationId = "run-hist"),
        )
        assertEquals("run-hist", currentRunId(emptyList(), status), "with no live events, fall back to status.lastRun.correlationId")
        assertEquals(null, currentRunId(emptyList(), null), "no events and no status → no run (honest null)")
    }

    // --- MUT-B (panel): the reported symptom — running run's events visible while status is STALE, no POST ---

    @Test
    fun runningSequence_eventsVisibleFromLiveFeed_whenStatusStale_noConfigPost() = runComposeUiTest {
        // status frozen at an OLD finished run (as in the dogfood repro); the live feed carries the running run.
        // The list must show the RUNNING run's events (scoped by the live-derived runId), not the stale old run,
        // and WITHOUT a config POST (StubCompactRepository resolves status once; no write happens).
        val stale = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = true, running = false,
            lastRun = CompactRunSummary(completed = 3, total = 3, startedTs = 1, finishedTs = 2, correlationId = "old-run"),
        )
        val live = listOf(
            ev(10, EventType.COMPACT_PREPARE_SENT, "run-live"),
            ev(11, EventType.COMPACT_REQUEST_SENT, "run-live"),
            ev(2, EventType.COMPACT_COMPLETED, "old-run"), // decoy old run — must NOT leak in
        )
        val model = CompactViewModel(StubCompactRepository(stale), editable = true, CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { CompactPanel(model, compactEvents = live) } }

        onNodeWithTag(CompactTags.EVENTS).assertExists()
        onNodeWithTag(CompactTags.eventRow(0)).assertExists()      // run-live event 1
        onNodeWithTag(CompactTags.eventRow(1)).assertExists()      // run-live event 2
        onNodeWithTag(CompactTags.eventRow(2)).assertDoesNotExist() // only run-live's 2 events (old-run decoy excluded)
        onNodeWithTag(CompactTags.EVENTS_EMPTY).assertDoesNotExist()
    }

    // --- MUT-A (panel): opening the always-alive window REFETCHES status via the panel LaunchedEffect, no POST ---

    @Test
    fun openingWindow_refetchesStatus_flipsIdleRowToRunning_noConfigPost() = runComposeUiTest {
        // The always-alive VM (key=compact-global) was loaded once while idle (getStatus call #1). Opening the
        // window must refetch (call #2 → running) via the panel's LaunchedEffect — so the STATUS row shows running
        // WITHOUT a config POST. Removing the LaunchedEffect leaves the row at "Armed" (idle) → reds. (jvmTest = EN.)
        val repo = CountingRepo(runningFromCall = 2)
        val model = CompactViewModel(repo, editable = true, CoroutineScope(Dispatchers.Unconfined))
        setContent { MaterialTheme { CompactPanel(model) } }
        // Nothing but the panel's LaunchedEffect calls observeWhileOpen, so a flip to running proves the panel drove
        // the open-refetch on the always-alive VM. waitUntil is deterministic against the infinite poll loop; remove
        // the LaunchedEffect and the state never leaves idle → it times out (reds). (The running-row RENDER is
        // pre-existing behaviour, covered elsewhere — CYP-328 only changes WHEN status is refetched.)
        waitUntil(timeoutMillis = 5_000) { model.state.value.status?.running == true }
        assertEquals(0, repo.setConfigCalls, "opened-refresh surfaced running WITHOUT a config POST")
    }
}
