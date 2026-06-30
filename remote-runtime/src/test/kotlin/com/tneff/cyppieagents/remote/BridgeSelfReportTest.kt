package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.model.WireFrame
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
import kotlin.test.assertEquals

/**
 * S5 / G4 — the bridge's self-report (the [WireReportingObserver] tap on the S4.0 seam). A masked
 * `rate_limit_event` and an assistant `tool_use` flow through the CC session → are relayed as content-free
 * [WireEvent]s over the [WireLink], and the bridge declares the matching LIMITED caps. This is what makes
 * `rateLimitSignal`/`toolGranularity` = LIMITED honest for a remote agent.
 */
class BridgeSelfReportTest {

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

    /** Emits [scripted] lines (in order) on the first stdin turn (lazy-init like real `claude`). */
    private class ScriptedCc(private val scripted: List<String>) : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        private var first = true
        override suspend fun writeLine(line: String) { if (first) { first = false; scripted.forEach { lines.send(it) } } }
        override fun destroy() { lines.close() }
    }

    @Test
    fun relaysRateLimitAndToolEvents_asContentFreeWireEvents() = runBlocking {
        val link = FakeWireLink()
        val script = listOf(
            """{"type":"system","subtype":"init","session_id":"b1"}""",
            """{"type":"rate_limit_event","rate_limit_info":{"status":"blocked","resetsAt":"2026-01-01"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"secret-cmd"}}]}}""",
        )
        BridgeRelay("backend", "po-backend", BRIDGE_REMOTE_CAPABILITIES, ProviderInfo.CLAUDE, ScriptedCc(script), link, scope).start()
        withTimeout(5000) { while (link.sent.none { it is WireSubscribe }) delay(10) }
        link.deliver(WireDeliver("go")) // triggers lazy-init → the scripted events flow through the tap

        withTimeout(5000) {
            while (link.sent.none { it is WireEvent && it.signal == WireEventType.RATE_LIMIT } ||
                link.sent.none { it is WireEvent && it.signal == WireEventType.TOOL_CALL }) delay(10)
        }
        val events = link.sent.filterIsInstance<WireEvent>()
        val rl = events.first { it.signal == WireEventType.RATE_LIMIT }
        assertEquals("blocked", rl.rateLimit?.get("status"), "rate-limit signal relayed")
        val tc = events.first { it.signal == WireEventType.TOOL_CALL }
        assertEquals("Bash", tc.tool, "tool NAME relayed (never the masked input)")
        // content-free: the tool input ('secret-cmd') must NOT appear anywhere on the wire.
        assertEquals(false, link.sent.any { it is WireEvent && (it.tool?.contains("secret") == true || it.rateLimit?.values?.any { v -> v.contains("secret") } == true) })
    }

    @Test
    fun bridgeCaps_declareRateLimitAndToolGranularity_limited() {
        assertEquals(CapabilityStatus.LIMITED, BRIDGE_REMOTE_CAPABILITIES.rateLimitSignal)
        assertEquals(CapabilityStatus.LIMITED, BRIDGE_REMOTE_CAPABILITIES.toolGranularity)
        assertEquals(CapabilityStatus.UNAVAILABLE, BRIDGE_REMOTE_CAPABILITIES.structuredUsage, "usage stays moot — unverifiable for remote")
        assertEquals(ConnectorKind.STREAM_JSON, BRIDGE_REMOTE_CAPABILITIES.kind)
    }
}
