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
        // CYP-362: this was the one step outside every `withTimeout`, and it is where the test hung — the whole
        // build with it, for a defect that never once reported red. A test may fail; it may not stall. The
        // termination invariant itself is the subject of `BridgeCloseTerminationTest`; here the bound only
        // guarantees that if it ever breaks again, we learn it from a red test and not from a stuck CI job.
        withTimeout(20_000) { relay.close() }
    }
}
