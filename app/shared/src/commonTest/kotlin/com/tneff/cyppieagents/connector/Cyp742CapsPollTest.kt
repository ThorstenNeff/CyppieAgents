package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.agentview.AgentLifecycleEvent
import com.tneff.cyppieagents.agentview.AgentLifecycleSource
import com.tneff.cyppieagents.agentview.AgentLifecycleState
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-742 (Dogfood §4a) — a live-created-then-STARTED agent no longer hangs on "Capabilities not yet reported".
 * Caps are set at connector opt-in (around agent start), LATER than the create event; the once-at-init read
 * misses them. The [ConnectorCapabilityViewModel] now re-polls (bounded) on a RUNNING lifecycle event (≈ opt-in),
 * so caps that land shortly after start are picked up. The bound keeps a never-opting-in agent honestly `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp742CapsPollTest {

    /** A [ConnectorCapabilityRepository] whose caps for [agentId] appear only from the [readyAt]-th read — models
     *  opt-in landing AFTER agent start (init read = #1). */
    private class DelayedCapsRepo(val agentId: String, val readyAt: Int) : ConnectorCapabilityRepository {
        private var reads = 0
        override suspend fun read(): ConnectorReadModel {
            reads++
            return if (reads >= readyAt) ConnectorReadModel(capabilities = mapOf(agentId to CAPS)) else ConnectorReadModel()
        }
    }

    private class NeverCapsRepo : ConnectorCapabilityRepository {
        override suspend fun read(): ConnectorReadModel = ConnectorReadModel()
    }

    private class OneEventLifecycle(private val ev: AgentLifecycleEvent) : AgentLifecycleSource {
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = emptyMap()
        override fun events(): Flow<AgentLifecycleEvent> = flowOf(ev)
    }

    @Test
    fun runningEvent_boundedRepoll_picksUpCapsThatLandAfterStart() = runTest {
        // init read #1 = empty; RUNNING event → re-poll: read #2 empty, read #3 caps → resolved.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = ConnectorCapabilityViewModel(
            DelayedCapsRepo("flutter-dev-2", readyAt = 3),
            lifecycleSource = OneEventLifecycle(AgentLifecycleEvent("flutter-dev-2", AgentLifecycleState.RUNNING)),
            scope = scope,
            capsPollIntervalMs = 1,
        )
        advanceUntilIdle()
        assertNotNull(
            vm.capabilitiesFor("flutter-dev-2"),
            "the bounded re-poll (triggered by the RUNNING event) picks up caps that land after start",
        )
        scope.cancel()
    }

    @Test
    fun runningEvent_capsNeverArrive_boundedPoll_staysNull_andTerminates() = runTest {
        // Honesty + bound: an agent whose connector never reports caps → after the bounded attempts, caps stay
        // null ("not yet reported"), never fabricated, and the poll TERMINATES (advanceUntilIdle returns).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = ConnectorCapabilityViewModel(
            NeverCapsRepo(),
            lifecycleSource = OneEventLifecycle(AgentLifecycleEvent("flutter-dev-2", AgentLifecycleState.RUNNING)),
            scope = scope,
            capsPollAttempts = 3,
            capsPollIntervalMs = 1,
        )
        advanceUntilIdle()
        assertNull(vm.capabilitiesFor("flutter-dev-2"), "caps never fabricated; the bounded poll gives up honestly")
        scope.cancel()
    }

    @Test
    fun nonRunningEvent_doesNotTriggerRepoll() = runTest {
        // STOPPED is not the opt-in signal → no re-poll; caps come only from the once-at-init read (#1, empty).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = ConnectorCapabilityViewModel(
            DelayedCapsRepo("flutter-dev-2", readyAt = 2), // caps WOULD appear on a re-poll (read #2) — but none fires
            lifecycleSource = OneEventLifecycle(AgentLifecycleEvent("flutter-dev-2", AgentLifecycleState.STOPPED)),
            scope = scope,
            capsPollIntervalMs = 1,
        )
        advanceUntilIdle()
        assertNull(
            vm.capabilitiesFor("flutter-dev-2"),
            "a non-RUNNING event must not trigger a re-poll — only RUNNING (≈ opt-in) does",
        )
        scope.cancel()
    }

    private companion object {
        val CAPS = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.AVAILABLE,
            reliableResult = CapabilityStatus.AVAILABLE,
            rateLimitSignal = CapabilityStatus.AVAILABLE,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.MCP,
        )
    }
}
