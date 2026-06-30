package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-142 (S4.2) — the bridge reuses Gate #6 ([com.tneff.cyppieagents.connector.MediationGate]), proven at
 * the bridge boundary: a FAILED turn is relayed as a flagged `"[turn failed: …]"` status (NOT raw, NOT
 * dropped, NOT mistaken for success), and an UNBOUND result never produces a `WireSend` (can't double-
 * deliver). Mutation: replace the shared classify with a raw `result.result` send → the failed-turn
 * assertion reds (a failed turn carries no result text).
 */
class BridgeGate6Test {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeWireLink : WireLink {
        val sent = CopyOnWriteArrayList<WireFrame>()
        private val inbound = Channel<WireFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: WireFrame) { sent.add(frame) }
        override val incoming: Flow<WireFrame> = inbound.receiveAsFlow()
        suspend fun deliver(frame: WireFrame) = inbound.send(frame)
        override suspend fun close() { inbound.close() }
    }

    /** Emits [scripted] lines (in order) in reaction to the first stdin turn (lazy). */
    private class ScriptedCc(private val scripted: List<String>) : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        private var first = true
        override suspend fun writeLine(line: String) { if (first) { first = false; scripted.forEach { lines.send(it) } } }
        override fun destroy() { lines.close() }
    }

    private val caps = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED,
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )
    private val init = """{"type":"system","subtype":"init","session_id":"b1"}"""

    private fun relayWith(vararg scripted: String): FakeWireLink {
        val link = FakeWireLink()
        BridgeRelay("backend", "po-backend", caps, ProviderInfo.CLAUDE, ScriptedCc(scripted.toList()), link, scope).start()
        return link
    }

    /** A BOUND session whose turn FAILS → relayed as "[turn failed: <subtype>]" (Gate #6, shared classify). */
    @Test
    fun failedTurn_relayedAsTurnFailedStatus() = runBlocking {
        val link = relayWith(init, """{"type":"result","subtype":"boom","is_error":true,"session_id":"b1"}""")
        withTimeout(5000) { while (link.sent.none { it is WireSubscribe }) delay(10) }
        link.deliver(WireDeliver("do it"))
        withTimeout(5000) {
            while (link.sent.none { it is WireSend && it.channel == "po-backend" && it.text == "[turn failed: boom]" }) delay(10)
        }
    }

    /** An UNBOUND result (error before any system/init) must NOT produce a WireSend (no double-deliver). */
    @Test
    fun unboundResult_producesNoWireSend() = runBlocking {
        // No init line → the result is processed while unbound; the shared session's CYP-170 gate suppresses it.
        val link = relayWith("""{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"stale"}""")
        withTimeout(5000) { while (link.sent.none { it is WireSubscribe }) delay(10) }
        link.deliver(WireDeliver("do it"))
        delay(400) // give any (wrong) WireSend a chance to appear
        assertTrue(link.sent.none { it is WireSend }, "an unbound result must NOT reach the hub")
    }
}
