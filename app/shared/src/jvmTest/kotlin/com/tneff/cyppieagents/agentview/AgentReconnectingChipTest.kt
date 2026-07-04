package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-204 — the window's reconnecting indicator reflects the [AgentSession.connection] state: present while the
 * per-agent WS is not LIVE (auto-reconnecting from the seq cursor), absent on a healthy socket. Text-carried
 * (WCAG 1.4.1) and on its own axis, distinct from the lifecycle status.
 */
@OptIn(ExperimentalTestApi::class)
class AgentReconnectingChipTest {

    private class ConnSession(private val conn: StateFlow<ConnectionStatus>) : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
        override val connection: StateFlow<ConnectionStatus> = conn
    }

    @Test
    fun reconnectingChip_presentWhenNotLive_absentWhenLive() = runComposeUiTest {
        val conn = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(ConnSession(conn), agentId = "backend") }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        // Not LIVE → the reconnecting indicator shows (the window isn't blank; the transcript replays on reconnect).
        onNodeWithTag(AgentViewTags.reconnecting("backend"), useUnmergedTree = true).assertExists()
        // Recovered → LIVE removes the chip (a healthy socket adds no chrome).
        conn.value = ConnectionStatus.LIVE
        waitForIdle()
        onNodeWithTag(AgentViewTags.reconnecting("backend"), useUnmergedTree = true).assertDoesNotExist()
    }
}
