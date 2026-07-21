package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.agentview.StubAgentWritableApi
import kotlin.test.Test

/**
 * CYP-788 — the operator→PO write-path, from the CLIENT's point of view (the regression anchor for the
 * hermetic demo surface the Maestro flow drives). CYP-787 seeded the `op-po` inbound channel server-side so
 * `po` now enters `GET /api/agents/writable`'s set; the client change is **nil** — the composer flips
 * READ_ONLY→WRITABLE purely from that set (CYP-738 gate). This pins the rendered flip on the REAL po
 * [AgentWindow]:
 *
 *  - **Before op-po seed** (`po` ∉ writable set): the composer is the proactive READ_ONLY hint
 *    ([AgentViewTags.composerReadonly]) — no editable input. This is the "operator can't yet message the PO"
 *    state whose misleading "no write access" copy CYP-788 makes disappear.
 *  - **After op-po seed** (`po` ∈ set): the hint is gone and the editable input ([AgentViewTags.input]) is
 *    present — the operator can task the PO.
 *
 * The flip is driven by a VM re-key ([key]) because the writability fetch is one-shot-eager (AgentViewModel
 * §4a: no WRITABLE flash before the write-right is known) — exactly the mechanism the demo tab uses. The
 * session is a plain [StubAgentSession] whose `connection` inherits the LIVE default, so the honest-delivery
 * fixture (F4/CYP-580, `delivered = connection==LIVE`) is satisfied — a sent turn is `userTurn`, not
 * `…undelivered`.
 *
 * Mutation proof: invert [com.tneff.cyppieagents.agentview.deriveComposerWritability]'s in-set branch
 * (`in ids → READ_ONLY`) → before-seed shows the input (READ_ONLY hint never appears) → the first `waitUntil`
 * times out → RED; and after-seed shows the hint → the input assertion → RED.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp788OperatorPoComposerTest {

    private val SEED_BTN = "test.opPo.seed"

    @Test
    fun poComposer_flipsReadOnlyToWritable_onOpPoSeed() = runComposeUiTest {
        setContent {
            MaterialTheme {
                var seeded by remember { mutableStateOf(false) }
                // connection inherits the LIVE default (Ask 3 / F4-CYP-580) → a sent turn is delivered.
                val session = remember { StubAgentSession() }
                // po ∉ set before the seed → READ_ONLY; po ∈ set after → WRITABLE. A fresh stub + VM re-key so
                // the one-shot-eager writability fetch re-runs on the flip (no prod refresh path — §4a).
                val vm = key(seeded) {
                    AgentViewModel(
                        session,
                        agentId = "po",
                        agentWritable = StubAgentWritableApi(if (seeded) listOf("po") else emptyList()),
                    )
                }
                Column {
                    Button(onClick = { seeded = true }, modifier = Modifier.testTag(SEED_BTN)) { Text("Seed op-po") }
                    AgentWindow(agentId = "po", viewModel = vm)
                }
            }
        }

        // Before seed: settle onto READ_ONLY (past the UNKNOWN in-flight seed), assert no editable input.
        waitUntil { onAllNodesWithTag(AgentViewTags.composerReadonly("po")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentViewTags.input("po")).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.composerUnknown("po")).assertDoesNotExist() // settled, not stuck UNKNOWN

        // Seed op-po (CYP-787): po enters the writable set → the gate must flip to WRITABLE.
        onNodeWithTag(SEED_BTN).performClick()

        // After seed: the editable input appears and the READ_ONLY hint is gone — operator can task the PO.
        waitUntil { onAllNodesWithTag(AgentViewTags.input("po")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AgentViewTags.composerReadonly("po")).assertDoesNotExist()
    }
}
