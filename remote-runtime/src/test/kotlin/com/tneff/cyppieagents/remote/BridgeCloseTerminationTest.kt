package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * CYP-362 — **`BridgeRelay.close()` terminates against a child that is still holding its stdin open.**
 *
 * `BridgeLazyInitE2eTest` did not fail. It **hung**: no assertion fired, no test reported red, the Gradle worker
 * never exited, and `:remote-runtime:test` blocked every full build of both teams. A hang is the worst failure
 * shape there is — it carries no information, and `BUILD SUCCESSFUL` never arrives to contradict it.
 *
 * **The mechanism** (measured with `jstack`, not inferred): the test worker was parked in `runBlocking`, and the
 * only live coroutine sat in `ProcessBuilderSpawner`'s stdout pump at
 * `BufferedReader.readLine() → AgentProcess.kt:68`, while the `bash` child was still alive.
 * [com.tneff.cyppieagents.connector.ClaudeCodeSession.closeAndAwait] cancels **and joins** the reader before it
 * destroys the process. Cancellation does not interrupt a thread parked in a blocking read; the reader returns
 * only at EOF; the child reaches EOF only when `destroy()` closes its stdin — the very call that waits behind the
 * join. Neither side has a timeout.
 *
 * **This test's shape is the point.** The invariant is "close() returns", and the defect's symptom is "close()
 * does not return" — an assertion placed *after* `close()` can never run, so it cannot be the guard. The bound
 * has to sit **around** the call, and it must turn the hang into a red assertion rather than into another hang.
 * That is why `relay.close()` is wrapped here and, from CYP-362 on, in `BridgeLazyInitE2eTest` too: it was the
 * one step of that test outside every `withTimeout`.
 *
 * **What makes it red:** delete `process.destroy()` from [BridgeRelay.close] and this test fails on the
 * `assertNotNull` (verified, not assumed — measured red before the fix, green after). The 20 s bound is not a
 * performance claim: the correct path returns in well under a second; the defect never returns at all.
 */
class BridgeCloseTerminationTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    private class FakeWireLink : WireLink {
        val sent = CopyOnWriteArrayList<WireFrame>()
        private val inbound = Channel<WireFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: WireFrame) { sent.add(frame) }
        override val incoming: Flow<WireFrame> = inbound.receiveAsFlow()
        override suspend fun close() { inbound.close() }
    }

    /**
     * The honest shape of a long-lived `claude`: it blocks on stdin and **never exits on its own**. It emits
     * nothing at all — the outbound path is `BridgeLazyInitE2eTest`'s subject, and a script that exits by itself
     * would let the reader reach EOF for the wrong reason and make this test vacuous.
     */
    private val IDLE_CHILD = """
        #!/usr/bin/env bash
        while IFS= read -r _line; do :; done
    """.trimIndent()

    private fun idleChild(): File {
        val f = Files.createTempFile("cyp362-idle-child", ".sh").toFile()
        f.writeText(IDLE_CHILD); f.setExecutable(true)
        return f
    }

    private val remoteCaps = Capabilities(
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.UNAVAILABLE, CapabilityStatus.LIMITED,
        CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
    )

    @Test
    fun close_returnsPromptly_whileTheChildStillHoldsStdinOpen(): Unit = runBlocking {
        val script = idleChild()
        val cwd = Files.createTempDirectory("cyp362-close").toFile()
        val process = ProcessBuilderSpawner().spawn(listOf(script.absolutePath), cwd, mapOf("HUB_AGENT_ID" to "backend"))
        val link = FakeWireLink()
        val relay = BridgeRelay("backend", "po-backend", remoteCaps, ProviderInfo.CLAUDE, process, link, scope)

        relay.start()
        // The relay is really up — otherwise a close() of a never-started reader would prove nothing.
        withTimeout(5_000) { while (link.sent.none { it is WireSubscribe }) delay(10) }

        // THE TEETH. `close()` awaits the reader, the reader awaits EOF, and EOF awaits `destroy()`. Without the
        // destroy-first ordering this never completes — so the bound goes AROUND the call, and its absence is
        // reported as `null` instead of stalling the build. `withTimeout` alone would do; `withTimeoutOrNull`
        // buys the failure message.
        val returned = withTimeoutOrNull(20_000) { relay.close(); true }
        assertNotNull(
            returned,
            "CYP-362: BridgeRelay.close() never returned. `ClaudeCodeSession.closeAndAwait()` joins the stdout " +
                "reader before destroying the process, and ProcessBuilderSpawner's reader is a BLOCKING " +
                "readLine() that only ends at EOF — which only `destroy()` can produce. Restore the " +
                "`process.destroy()` that precedes `session.closeAndAwait()` in BridgeRelay.close().",
        )
    }
}
