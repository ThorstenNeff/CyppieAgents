package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
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
 * CYP-142 (S4.1) — the bridge relay loop, hermetic (fake wire + fake CC). Proves: on start the bridge
 * declares (`WireHello`) + subscribes (`WireSubscribe`); an inbound `WireDeliver` is injected into the CC
 * session; and the CC turn-result comes back out as a `WireSend` to the agent's spoke (Gate #6 body). The
 * fake CC is **lazy-init** (it emits `system/init` only in reaction to the first turn) — the verified real
 * behaviour — so this exercises the shared CYP-170 path through the bridge. S4.2 hardens it over the REAL
 * spawner; here it's hermetic.
 */
class BridgeRelayTest {

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

    /** Lazy-init fake CC: emits NOTHING until the first stdin turn, then system/init + a success result. */
    private class FakeCcProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        private var first = true
        override suspend fun writeLine(line: String) {
            if (first) {
                first = false
                lines.send("""{"type":"system","subtype":"init","session_id":"bridge-1"}""")
                lines.send("""{"type":"result","subtype":"success","is_error":false,"session_id":"bridge-1","result":"ack"}""")
            }
        }
        override fun destroy() { lines.close() }
    }

    private val remoteCaps = Capabilities(
        structuredUsage = CapabilityStatus.UNAVAILABLE,
        toolGranularity = CapabilityStatus.UNAVAILABLE,
        reliableResult = CapabilityStatus.LIMITED,
        rateLimitSignal = CapabilityStatus.UNAVAILABLE,
        coordination = CapabilityStatus.AVAILABLE, // the honest text-only-wire declaration (S1 verdict)
        kind = ConnectorKind.STREAM_JSON,
    )

    private suspend fun await(cond: () -> Boolean) = withTimeout(5000) { while (!cond()) delay(10) }

    @Test
    fun relaysInboundDeliverToCc_andCcResultToWireSend() = runBlocking {
        val link = FakeWireLink()
        val relay = BridgeRelay("backend", "po-backend", remoteCaps, ProviderInfo.CLAUDE, FakeCcProcess(), link, scope)
        relay.start()

        // On connect: declare + subscribe.
        await { link.sent.any { it is WireHello } && link.sent.any { it is WireSubscribe && it.channels == listOf("po-backend") } }

        // Inbound hub turn → injected into the CC session.
        link.deliver(WireDeliver("please do the thing"))

        // Outbound: the CC turn-result comes back as a WireSend to the agent's spoke (Gate #6 body "ack").
        await { link.sent.any { it is WireSend && it.channel == "po-backend" && it.text == "ack" } }

        relay.close()
    }
}
