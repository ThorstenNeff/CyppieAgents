package com.tneff.cyppieagents.compact

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-329 — operator-tunable compact timings (Stagger + Round gap). UI in minutes, wire in ms. The editors mirror
 * the CYP-327 threshold editor: operator-gated, fail-closed, NEVER optimistic, single-sourced bounds.
 *
 * The honesty teeth use a DIVERGENT [ClampingRepo] (server returns a value ≠ the request), NOT the echo stub —
 * an echo can't tell "adopt the server value" from "adopt the drafted request" (the CYP-327 QA lesson).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp329CompactTimingsTest {

    // Server state with NON-default timings so field-preservation assertions are non-vacuous.
    private fun serverStatus() = CompactStatus(
        allowed = true, thresholdTokens = 500_000, armed = true, running = false, lastRun = null,
        staggerMs = 120_000, roundGapMs = 180_000, roundWindowMs = 900_000,
    )

    /** A server that CLAMPS every write to its own timings regardless of the request — the divergence an echo can't
     *  express. Records the exact [CompactConfig] written so field-preservation can be asserted. */
    private class ClampingRepo(
        private val serverStaggerMs: Long = 90_000,
        private val serverRoundGapMs: Long = 240_000,
    ) : CompactRepository {
        var lastConfig: CompactConfig? = null
        override suspend fun getStatus() = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = true, running = false, lastRun = null,
            staggerMs = 120_000, roundGapMs = 180_000, roundWindowMs = 900_000,
        )
        override suspend fun setConfig(config: CompactConfig): CompactStatus {
            lastConfig = config
            return CompactStatus(
                allowed = config.allowed, thresholdTokens = config.thresholdTokens, armed = config.allowed,
                running = false, lastRun = null,
                staggerMs = serverStaggerMs, roundGapMs = serverRoundGapMs, roundWindowMs = config.roundWindowMs,
            )
        }
    }

    private fun vm(repo: CompactRepository, editable: Boolean = true) =
        CompactViewModel(repo, editable, CoroutineScope(Dispatchers.Unconfined)) // Unconfined → settles synchronously

    // --- never-optimistic: adopt the SERVER value, never the drafted request (divergent fixture) ---

    @Test
    fun setStaggerMs_adoptsServerValue_notTheRequest_whenServerAdjusts() {
        val vm = vm(ClampingRepo(serverStaggerMs = 90_000)) // server clamps to 90 s
        vm.setStaggerMs(300_000) // request 5 min (in-range)
        val s = vm.state.value
        assertEquals(90_000, s.status?.staggerMs, "adopts the SERVER stagger, never the requested 300k (never optimistic)")
        assertEquals(90_000, s.staggerSetConfirm, "the transient confirmation shows the SERVER value, not the request")
    }

    @Test
    fun setRoundGapMs_adoptsServerValue_notTheRequest_whenServerAdjusts() {
        val vm = vm(ClampingRepo(serverRoundGapMs = 240_000)) // server clamps to 4 min
        vm.setRoundGapMs(600_000) // request 10 min (in-range)
        val s = vm.state.value
        assertEquals(240_000, s.status?.roundGapMs, "adopts the SERVER round-gap, never the requested 600k")
        assertEquals(240_000, s.roundGapSetConfirm, "the transient confirmation shows the SERVER value")
    }

    // --- a single-field write preserves every other field (incl. the UNSURFACED roundWindowMs) ---

    @Test
    fun setStaggerMs_preservesOtherFields_inTheWrittenConfig() {
        val repo = ClampingRepo()
        vm(repo).setStaggerMs(300_000)
        val sent = repo.lastConfig ?: error("a config was written")
        assertEquals(300_000, sent.staggerMs, "the drafted stagger is written")
        assertEquals(180_000, sent.roundGapMs, "round-gap preserved")
        assertEquals(500_000, sent.thresholdTokens, "threshold preserved")
        assertEquals(900_000, sent.roundWindowMs, "round-window preserved (unsurfaced in the UI, never dropped/reset)")
        assertTrue(sent.allowed, "the allowed gate is preserved")
    }

    // --- fail-closed: out-of-range and non-operator never write ---

    @Test
    fun setStaggerMs_outOfRange_isRejectedBeforeAnyWrite() {
        val repo = ClampingRepo()
        vm(repo).setStaggerMs(5_000) // below CompactConfig.STAGGER_MIN_MS (30 s) → single-source timingBoundsError
        assertNull(repo.lastConfig, "an out-of-range timing is rejected before any write (fail-closed, defence in depth)")
    }

    @Test
    fun setRoundGapMs_nonOperator_isNoOp() {
        val repo = ClampingRepo()
        vm(repo, editable = false).setRoundGapMs(300_000)
        assertNull(repo.lastConfig, "a non-operator write is a no-op (server also 403s)")
    }

    // --- panel: operator edits (inputs), member reads (rows) ---

    @Test
    fun operator_showsTimingInputs_notReadOnlyRows() = runComposeUiTest {
        setContent { MaterialTheme { CompactPanel(vm(StubCompactRepository(serverStatus()), editable = true)) } }
        onNodeWithTag(CompactTags.STAGGER_INPUT).assertExists()
        onNodeWithTag(CompactTags.ROUND_GAP_INPUT).assertExists()
        onNodeWithTag(CompactTags.STAGGER).assertDoesNotExist()   // read-only row replaced by the editor
        onNodeWithTag(CompactTags.ROUND_GAP).assertDoesNotExist()
    }

    @Test
    fun nonOperator_showsReadOnlyTimingRows_noInputs() = runComposeUiTest {
        setContent { MaterialTheme { CompactPanel(vm(StubCompactRepository(serverStatus()), editable = false)) } }
        onNodeWithTag(CompactTags.STAGGER).assertExists()
        onNodeWithTag(CompactTags.ROUND_GAP).assertExists()
        onNodeWithTag(CompactTags.STAGGER_INPUT).assertDoesNotExist()
        onNodeWithTag(CompactTags.ROUND_GAP_INPUT).assertDoesNotExist()
    }

    @Test
    fun operator_editStaggerAndSave_writesServerMirror_minutesToMs() = runComposeUiTest {
        val model = vm(StubCompactRepository(serverStatus()), editable = true) // echo stub → happy path
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.STAGGER_INPUT).performTextClearance()
        onNodeWithTag(CompactTags.STAGGER_INPUT).performTextInput("3") // 3 min → 180_000 ms at the seam
        onNodeWithTag(CompactTags.STAGGER_SET).performClick()
        waitForIdle()
        assertEquals(180_000L, model.state.value.status?.staggerMs, "3 min saved as 180,000 ms (server mirror)")
    }

    @Test
    fun operator_invalidStagger_showsRangeError_disablesSet() = runComposeUiTest {
        setContent { MaterialTheme { CompactPanel(vm(StubCompactRepository(serverStatus()), editable = true)) } }
        onNodeWithTag(CompactTags.STAGGER_INPUT).performTextClearance()
        onNodeWithTag(CompactTags.STAGGER_INPUT).performTextInput("0.1") // 6 s < 30 s floor
        onNodeWithTag(CompactTags.STAGGER_ERROR).assertExists()
        onNodeWithTag(CompactTags.STAGGER_SET).assertIsNotEnabled()
    }

    // --- unit: the minutes↔ms seam and the duration format ---

    @Test
    fun minutesMsSeam_roundTrips_andFormats() {
        assertEquals("2", msToMinutesField(120_000))
        assertEquals("0.5", msToMinutesField(30_000))
        assertEquals("1.5", msToMinutesField(90_000))
        assertEquals(120_000L, minutesFieldToMs("2"))
        assertEquals(30_000L, minutesFieldToMs("0.5"))
        assertEquals(30_000L, minutesFieldToMs("0,5"))   // comma decimal mark accepted
        assertNull(minutesFieldToMs(""))
        assertEquals("2 min", formatCompactDuration(120_000))
        assertEquals("30 s", formatCompactDuration(30_000))
        assertEquals("1 min 30 s", formatCompactDuration(90_000))
        assertEquals("1.23", sanitizeMinutesInput("1a.2.3")) // digits + one dot only
    }
}
