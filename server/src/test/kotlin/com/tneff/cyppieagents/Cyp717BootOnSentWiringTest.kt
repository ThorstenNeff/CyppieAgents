package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.support.RecordingConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-717 (from the CYP-698 adversarial re-read, F1) — pins the **production boot wiring** of the wire-provenance
 * chokepoint, closing a copy-drift blindspot (the CYP-549/546 class).
 *
 * [Cyp698ChokepointProvenanceTest] proves the PROPERTY "a post through the chokepoint emits comm.sent", but it
 * sets `hub.onSent` ITSELF — a hand-copied replica of `BootOrchestrator.kt:429`. So the mutation MUT-UNWIRE
 * (delete that boot line) leaves the 698 tooth GREEN: prod would emit NOTHING, yet the guard passes. The
 * delivered property ("PROD emits comm.sent at the chokepoint") hung on one untested boot line.
 *
 * This test drives the REAL boot path ([BootOrchestrator.boot]) and posts through the booted hub, so a dropped
 * boot wiring reddens here.
 *
 * **Mutation (the DoD): delete `hub.onSent = { … }` from BootOrchestrator → this test reddens** (no comm.sent is
 * ever recorded, the poll times out). The 698 tooth stays green under the same mutation — that gap is exactly
 * what this closes.
 */
class Cyp717BootOnSentWiringTest {

    // Unconfined → the deliverer's inline drain runs synchronously; the async EventRecorder is awaited by polling.
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines = kotlinx.coroutines.flow.emptyFlow<String>()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    @Test
    fun bootWiresOnSent_soAPostThroughTheBootedHubEmitsExactlyOneCommSent() {
        val root = Files.createTempDirectory("onsent-boot").toFile()
        try {
            val rec = RecordingConnector()
            val booted = BootOrchestrator(
                config(), secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope,
                connectorFactory = { rec },
            ).boot()

            // A post through the booted chokepoint. If BootOrchestrator forgot to wire `hub.onSent`, provenance
            // is SILENT here — and the per-caller Cyp698 tooth (which wires its OWN onSent) would still pass. Only
            // a real-boot tooth catches a dropped boot wiring line. Body carries a needle to prove no-leak.
            booted.hub.postAsAgent("po", "po-backend", "boot-onSent-probe SECRET-NEEDLE", MessageMeta(kind = MessageKind.NOTE))

            val commSent = runBlocking {
                withTimeout(5_000) {
                    while (
                        booted.eventSink.query(EventFilter(agentId = "po"), Page(limit = 100))
                            .events.none { it.type == EventType.COMM_SENT }
                    ) {
                        delay(10)
                    }
                    booted.eventSink.query(EventFilter.ALL, Page(limit = 100)).events.filter { it.type == EventType.COMM_SENT }
                }
            }

            assertEquals(1, commSent.size, "the boot-wired onSent emits exactly ONE comm.sent for the post")
            val e = commSent.single()
            assertEquals("po", e.agentId, "server-stamped sender identity (from the persisted Message)")
            assertTrue(e.detail.toString().contains("po-backend"), "the channel is stamped into the event")
            assertFalse(e.detail.keys.contains("body"), "metadata only — never a body key")
            assertFalse(e.detail.toString().contains("SECRET-NEEDLE"), "the message body must never leak into the event")
        } finally {
            root.deleteRecursively()
        }
    }
}
