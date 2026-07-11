package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
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
import org.junit.Rule
import org.junit.rules.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * CYP-142 (S4.2) ⭐ the directive's mandatory teeth: the bridge relays a real long-lived stream-json
 * **stdin-injection** path against a process that emits `system/init` ONLY after the first stdin turn
 * (verified `claude` behaviour), driven through the REAL [ProcessBuilderSpawner] (real pipes) — mirroring
 * the server's `ResumeStdinHangTest`. If the bridge had RE-DERIVED turn injection (gating on a pre-turn
 * bind signal) it would DEADLOCK here. Because it REUSES the shared `:connector-core` `ClaudeCodeSession`
 * (CYP-170: first turn ungated), the inbound `WireDeliver` flows to the CC and the result flows back out.
 */
class BridgeLazyInitE2eTest {

    // CYP-362 — a gate-safe belt. This REAL-process E2E has no overall bound: its `relay.close()` reaches
    // `ClaudeCodeSession.closeAndAwait`, whose (pre-fix) unbounded `awaitTerminated()` = `Process.waitFor()` hung
    // the test indefinitely under load and could block PO1's serial gate forever. JUnit's Timeout rule runs the
    // test on its own thread and ABANDONS it at the deadline (interrupting a parked `waitFor()`) — something a
    // coroutine `withTimeout` cannot do for a non-cancellable blocking read. The root fix (bounded
    // `awaitTerminated`) keeps the happy path far under this; the rule only reddens a FUTURE regression.
    @get:Rule val globalTimeout: Timeout = Timeout.seconds(30)

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

    // A fake `claude`: LAZY init — emits nothing until it reads the first stdin line, then `system/init`
    // + a success result. Stays long-lived (loops).
    private val FAKE_CLAUDE = """
        #!/usr/bin/env bash
        first=1
        while IFS= read -r _line; do
          if [ ${'$'}first -eq 1 ]; then
            printf '%s\n' "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"bridge-1\"}"
            first=0
          fi
          printf '%s\n' "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":\"bridge-1\",\"result\":\"ack\"}"
        done
    """.trimIndent()

    private fun fakeClaude(): File {
        val f = Files.createTempFile("fake-claude-bridge", ".sh").toFile()
        f.writeText(FAKE_CLAUDE); f.setExecutable(true)
        return f
    }

    private val remoteCaps = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED,
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )

    @Test
    fun bridge_relaysOverRealSpawner_lazyInit_noDeadlock() = runBlocking {
        val script = fakeClaude()
        val cwd = Files.createTempDirectory("bridge-e2e").toFile()
        val process = ProcessBuilderSpawner().spawn(listOf(script.absolutePath), cwd, mapOf("HUB_AGENT_ID" to "backend"))
        val link = FakeWireLink()
        val relay = BridgeRelay("backend", "po-backend", remoteCaps, ProviderInfo.CLAUDE, process, link, scope)
        relay.start()
        withTimeout(5000) { while (link.sent.none { it is WireSubscribe }) delay(10) }

        link.deliver(WireDeliver("please do the thing")) // inbound hub turn

        // THE TEETH: a re-derived bridge would hang here; the reused CYP-170 path lets the turn flow → WireSend.
        withTimeout(15000) {
            while (link.sent.none { it is WireSend && it.channel == "po-backend" && it.text == "ack" }) delay(10)
        }
        // CYP-362 — bound the teardown explicitly: `relay.close()` reaches `closeAndAwait` → the (now bounded)
        // process-termination wait. A cancellable regression reddens here fast with a clear message; a
        // non-cancellable one is caught by the class Timeout rule above.
        withTimeout(10_000) { relay.close() }
    }
}
