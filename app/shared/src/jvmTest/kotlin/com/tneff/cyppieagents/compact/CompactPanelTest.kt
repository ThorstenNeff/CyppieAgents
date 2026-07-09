package com.tneff.cyppieagents.compact

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactRunSummary
import com.tneff.cyppieagents.model.CompactStatus
import com.tneff.cyppieagents.ui.MaritimeDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-326 — the compact-window render honesty.
 *  - operator → live checkbox (no chip, no gate hint); non-operator → read-only chip + gate hint (no checkbox);
 *  - the INFO disclosure hint is ALWAYS present (§3-1);
 *  - UNKNOWN server state → the threshold/status rows are ABSENT, never a defaulted idle/off (§3-3);
 *  - a timeout last-run renders WARN amber, never neutral (§3-4).
 */
@OptIn(ExperimentalTestApi::class)
class CompactPanelTest {

    private fun vm(repo: CompactRepository, editable: Boolean) =
        CompactViewModel(repo, editable, CoroutineScope(Dispatchers.Unconfined))

    private class ThrowingRepo : CompactRepository {
        override suspend fun getStatus(): CompactStatus = throw RuntimeException("boom")
        override suspend fun setConfig(config: CompactConfig): CompactStatus = throw RuntimeException("boom")
    }

    @Test
    fun operator_showsCheckbox_notChip_withServerMirrorRows() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(false, 500_000, armed = false, running = false)), editable = true)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_TOGGLE).assertExists()
        onNodeWithTag(CompactTags.ALLOW_CHIP).assertDoesNotExist()
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()   // disclosure always
        onNodeWithTag(CompactTags.GATE_HINT).assertDoesNotExist() // operator: no gate hint
        onNodeWithTag(CompactTags.THRESHOLD).assertExists()
        onNodeWithTag(CompactTags.STATUS).assertExists()
    }

    @Test
    fun nonOperator_showsChip_andGateHint_notCheckbox() = runComposeUiTest {
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = true, running = false)), editable = false)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_CHIP).assertExists()
        onNodeWithTag(CompactTags.ALLOW_TOGGLE).assertDoesNotExist() // no fake/disabled switch
        onNodeWithTag(CompactTags.GATE_HINT).assertExists()
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()
    }

    @Test
    fun unknownStatus_hidesServerMirrorRows_keepsDisclosure() = runComposeUiTest {
        val model = vm(ThrowingRepo(), editable = true) // failed load → status null (UNKNOWN)
        setContent { MaterialTheme { CompactPanel(model) } }
        onNodeWithTag(CompactTags.ALLOW_HINT).assertExists()        // disclosure always
        onNodeWithTag(CompactTags.THRESHOLD).assertDoesNotExist()   // §3-3: unknown → absent
        onNodeWithTag(CompactTags.STATUS).assertDoesNotExist()      // never a defaulted idle/off
    }

    @Test
    fun lastRunTimeout_rendersWarnAmber_notNeutral() = runComposeUiTest {
        val timeout = CompactRunSummary(completed = 3, total = 5, pendingAgentIds = listOf("a", "b"), startedTs = 1, finishedTs = 2)
        val model = vm(StubCompactRepository(CompactStatus(true, 500_000, armed = false, running = false, lastRun = timeout)), editable = false)
        setContent { MaterialTheme(colorScheme = MaritimeDark) { CompactPanel(model) } }
        onNodeWithTag(CompactTags.LAST_RUN).assertExists()
        val pm = onNodeWithTag(CompactTags.LAST_RUN, useUnmergedTree = true).captureToImage().toPixelMap()
        // WARN Night amber #FFC857 → red≈1.0, green≈0.78, blue≈0.34 — distinct from neutral onSurfaceVariant
        // #A6BECD (blue≈0.80). A timeout row MUST carry amber glyph pixels.
        var amber = 0
        for (y in 0 until pm.height) {
            for (x in 0 until pm.width) {
                val c = pm[x, y]
                if (c.red > 0.85f && c.green > 0.6f && c.blue < 0.5f) amber++
            }
        }
        assertTrue(amber > 0, "a timeout last-run must render WARN amber (severityColor(WARN)), not neutral onSurfaceVariant")
    }
}
