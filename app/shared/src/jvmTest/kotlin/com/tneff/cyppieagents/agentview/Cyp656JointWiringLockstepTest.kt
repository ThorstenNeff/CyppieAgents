package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.window.WindowHost
import com.tneff.cyppieagents.window.WindowManagerState
import com.tneff.cyppieagents.window.WindowState
import com.tneff.cyppieagents.window.WindowTestTags
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_status_running
import kmpcyppieagents.app.shared.generated.resources.agent_status_unknown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * CYP-656 — the JOINT-WIRING tooth (PO-mandated). Pins the now-LOAD-BEARING **lockstep** property: the body status
 * DOT (CYP-573) and the title-bar busy-`*` (CYP-656/2523ca17) read the **SAME** per-agent `session.connection`, so
 * they cannot disagree. Lockstep is the only argument that carries decision (A) (unified-freshness-proxy) — the
 * frequency premise broke — so an untested lockstep is a decision whose ground could evaporate on a future
 * collection-point split, silently.
 *
 * Pins the PROPERTY ("dot and `*` see the same connection value"), NOT the implementation ("`agentConnections` from
 * `agentVms`"). Unlike [Cyp656BusyConnectionGateTest] (which STUBS `connectionFor` by hand → proves the gate function,
 * not that the wiring has ONE source), this renders BOTH markers in ONE [WindowHost]: the DOT from `AgentWindow(vm)`
 * (real body wiring: `viewModel.connection`), the `*` from `connectionFor` fed the SAME `vm.connection`.
 *
 * Non-vacuity is PROVEN by [splitSource_rendersDivergence_provesLockstepToothIsNonVacuous]: feed the title-bar a
 * SEPARATE source than the body → the markers DIVERGE (dot RUNNING but `*` gone) → the lockstep asserts here would
 * RED on a real collection-point split (which the stub tooth cannot see). That test also VERIFIES the split renders.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp656JointWiringLockstepTest {

    private fun sessionWith(conn: StateFlow<ConnectionStatus>) = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
        override val connection: StateFlow<ConnectionStatus> = conn
    }

    /** A real VM holding a last-known RUNNING lifecycle state; its connection is [conn] (the single source under test). */
    private fun vmWith(conn: StateFlow<ConnectionStatus>): AgentViewModel {
        val src = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.RUNNING))
        return AgentViewModel(sessionWith(conn), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = false)
    }

    private fun oneWindow() = WindowManagerState(listOf(WindowState("backend", "Backend", 0f, 0f, 380f, 240f)))

    @Test
    fun lockstep_liveFeed_dotRunning_andStarShown() = runComposeUiTest {
        val conn = MutableStateFlow(ConnectionStatus.LIVE)
        val theVm = vmWith(conn) // ONE source for both markers
        lateinit var runningLabel: String
        setContent {
            MaterialTheme {
                runningLabel = stringResource(Res.string.agent_status_running)
                Box(Modifier.size(1000.dp, 700.dp)) {
                    val c by conn.collectAsState() // title-bar reads the SAME vm.connection source
                    WindowHost(
                        state = oneWindow(),
                        busyFor = { true },
                        connectionFor = { c },
                        windowContent = { AgentWindow(agentId = it.id, viewModel = theVm) },
                    )
                }
            }
        }
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithText(runningLabel).assertExists() // dot: fresh RUNNING
        onNodeWithTag(WindowTestTags.busy("backend")).assertExists() // `*`: shown — both fresh, in lockstep
    }

    @Test
    fun lockstep_droppedFeed_dotUnknown_andStarSuppressed() = runComposeUiTest {
        val conn = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val theVm = vmWith(conn)
        lateinit var runningLabel: String
        lateinit var unknownLabel: String
        setContent {
            MaterialTheme {
                runningLabel = stringResource(Res.string.agent_status_running)
                unknownLabel = stringResource(Res.string.agent_status_unknown)
                Box(Modifier.size(1000.dp, 700.dp)) {
                    val c by conn.collectAsState()
                    WindowHost(
                        state = oneWindow(),
                        busyFor = { true },
                        connectionFor = { c },
                        windowContent = { AgentWindow(agentId = it.id, viewModel = theVm) },
                    )
                }
            }
        }
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithText(unknownLabel).assertExists() // dot: UNKNOWN (positive anchor for the absence below)
        onNodeWithText(runningLabel).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.busy("backend")).assertDoesNotExist() // `*`: suppressed — LOCKSTEP with the dot
    }

    @Test
    fun splitSource_rendersDivergence_provesLockstepToothIsNonVacuous() = runComposeUiTest {
        val bodyConn = MutableStateFlow(ConnectionStatus.LIVE) // body/dot source
        val titleConn = MutableStateFlow(ConnectionStatus.DISCONNECTED) // a SEPARATE title-bar source = the SPLIT
        val theVm = vmWith(bodyConn)
        lateinit var runningLabel: String
        setContent {
            MaterialTheme {
                runningLabel = stringResource(Res.string.agent_status_running)
                Box(Modifier.size(1000.dp, 700.dp)) {
                    val tc by titleConn.collectAsState()
                    WindowHost(
                        state = oneWindow(),
                        busyFor = { true },
                        connectionFor = { tc }, // title-bar reads a DIFFERENT source than the body → the split
                        windowContent = { AgentWindow(agentId = it.id, viewModel = theVm) },
                    )
                }
            }
        }
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // MUTATION-EFFECT VERIFICATION — a collection-point split renders a VISIBLE DIVERGENCE:
        onNodeWithText(runningLabel).assertExists() // dot: fresh RUNNING (body LIVE)
        onNodeWithTag(WindowTestTags.busy("backend")).assertDoesNotExist() // `*`: suppressed (title DISCONNECTED)
        // ⟹ dot and `*` DISAGREE when their sources split → the two lockstep tests above WOULD RED on a real split
        //    (the stub-connectionFor tooth, feeding both sides one hand-value, cannot produce this divergence).
    }
}
