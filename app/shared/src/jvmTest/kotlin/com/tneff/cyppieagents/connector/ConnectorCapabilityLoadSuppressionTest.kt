package com.tneff.cyppieagents.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.agentview.AgentLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-280 — during the caps load window (e.g. a project switch, when `capabilities` is still `emptyMap()`) the
 * fidelity badge must be SUPPRESSED, not rendered as `○ not-reported`: a transient `○` on a full-fidelity agent
 * is actively wrong (worse than absence). "Not loaded yet" ≠ "loaded but genuinely absent".
 *
 * Mutation proof: drop `if (loading) return` in [ConnectorCapabilityBadge] → [badge_suppressedWhileLoading…]
 * REDs (the badge renders while loading); drop `loading = false` in ConnectorCapabilityViewModel.reload →
 * [vm_loadingTrue_untilFirstReadSettles]'s second assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectorCapabilityLoadSuppressionTest {

    @Test
    fun badge_suppressedWhileLoading_evenWhenCapsNull() = runComposeUiTest {
        setContent { MaterialTheme { ConnectorCapabilityBadge(caps = null, agentId = "a", lifecycle = AgentLifecycleState.RUNNING, loading = true) } }
        onNodeWithTag(ConnectorTags.fidelityBadge("a")).assertDoesNotExist()
    }

    @Test
    fun badge_showsNotReported_onceSettledAndCapsNull() = runComposeUiTest {
        // Non-vacuous: a SETTLED null (genuinely unreported) DOES show the ○ badge — only the load window suppresses it.
        setContent { MaterialTheme { ConnectorCapabilityBadge(caps = null, agentId = "a", lifecycle = AgentLifecycleState.RUNNING, loading = false) } }
        onNodeWithTag(ConnectorTags.fidelityBadge("a")).assertExists()
    }

    /** `read()` suspends on [gate] → the VM stays `loading = true` until the test releases it. */
    private class GatedRepo(val gate: CompletableDeferred<Unit>) : ConnectorCapabilityRepository {
        override suspend fun read(): ConnectorReadModel {
            gate.await()
            return ConnectorReadModel(emptyMap(), emptyMap())
        }
    }

    @Test
    fun vm_loadingTrue_untilFirstReadSettles() {
        val gate = CompletableDeferred<Unit>()
        val vm = ConnectorCapabilityViewModel(GatedRepo(gate), scope = CoroutineScope(Dispatchers.Unconfined))
        assertEquals(true, vm.state.value.loading, "loading while the read is in flight")
        gate.complete(Unit)
        assertEquals(false, vm.state.value.loading, "settled (badge no longer suppressed) once the read completes")
    }
}
