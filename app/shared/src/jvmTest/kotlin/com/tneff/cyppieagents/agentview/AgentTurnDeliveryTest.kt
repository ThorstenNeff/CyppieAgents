package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F4 (CYP-580, honest delivery — the CYP-353 optimistic-proxy class) — [AgentViewModel.onSend] marks the echoed
 * [AgentEvent.UserTurn]'s `delivered` from the OBSERVED connection state, not from our own action: a turn composed
 * while the per-agent socket is not LIVE is NOT delivered (never rendered as sent); a LIVE send is best-effort
 * delivered. (The server-side silent skip in the restart/respawn window — AgentSocket:141 — is invisible to the
 * client without a server ack/nack; full coverage is a separate server item, not claimed here.)
 *
 * **Mutation (RED):** hardcode `delivered = true` in `onSend` → the offline assertion (`delivered == false`) reddens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnDeliveryTest {

    @BeforeTest fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    private class ConnSession(conn: ConnectionStatus) : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
        override val connection: StateFlow<ConnectionStatus> = MutableStateFlow(conn)
    }

    private fun lastUserTurn(vm: AgentViewModel): AgentEvent.UserTurn =
        vm.transcript.value.filterIsInstance<AgentEvent.UserTurn>().last()

    @Test
    fun onSend_whileNotLive_marksTurnNotDelivered() {
        val vm = AgentViewModel(ConnSession(ConnectionStatus.DISCONNECTED), agentId = "backend")
        vm.onSend("hallo offline")
        assertFalse(
            lastUserTurn(vm).delivered,
            "a turn composed while the socket is not LIVE must be marked NOT delivered (never shown as sent)",
        )
    }

    @Test
    fun onSend_whileLive_marksTurnBestEffortDelivered() {
        // Non-vacuous partner: on a LIVE socket the turn is best-effort delivered (the marker is absent).
        val vm = AgentViewModel(ConnSession(ConnectionStatus.LIVE), agentId = "backend")
        vm.onSend("hallo live")
        assertTrue(lastUserTurn(vm).delivered, "a turn handed off on a LIVE socket is best-effort delivered")
    }
}
