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
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.support.RecordingConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-716 (from the CYP-698 adversarial re-read, F3) — the REAL-BOOT regression tooth for comm.sent's tenant
 * bucket. The bug (comm.sent stamped from the SHARED projector's boot constant, not `msg.projectId`) is FIXED
 * (CYP-718), but [com.tneff.cyppieagents.events.Cyp718CommSentProjectIdTest] verifies it by wiring `hub.onSent`
 * ITSELF (a hand-copied replica of `BootOrchestrator.kt:429`) — the SAME copy-drift blindspot CYP-717 just
 * closed for the emit. So the projectId-bucket correctness — which IS cross-project event isolation, not mere
 * projector arithmetic — is proven only at the projector level, never through the real boot wiring.
 *
 * This drives the REAL `BootOrchestrator.boot()`: the shared projector's constant is `config.projectId`
 * ("alpha"); a rescope makes the ACTIVE project "beta" (exactly what a project switch performs —
 * `onActiveSwitch = state::rescope`); a runtime-added PO+worker gives "beta" a postable spoke (the operator is a
 * writable member); a post through the booted chokepoint carries `msg.projectId = "beta"`. The event must bucket
 * into "beta", NOT the projector's boot constant "alpha".
 *
 * **Mutation (the DoD): break the boot wiring's projectId source** — `BootOrchestrator.kt:429`
 * `commSent(msg.from, msg.channelId, msg.meta?.kind, config.projectId)` (the boot constant) instead of
 * `msg.projectId` → **THIS test reddens** (comm.sent buckets into "alpha") while Cyp718 stays GREEN under the
 * same mutation (it hand-wires its own onSent, blind to the boot line) — the blindspot this closes.
 */
class Cyp716BootProjectIdBucketTest {

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

    // config.projectId = "alpha" → the SHARED EventProjector's boot constant (BootOrchestrator: `EventProjector(
    // projectId = config.projectId)`). The config agents seed hub-and-spoke under "alpha".
    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
        projectId = "alpha",
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    @Test
    fun commSentThroughRealBoot_bucketsIntoActiveProject_notTheProjectorsBootConstant() {
        val root = Files.createTempDirectory("cyp716-boot").toFile()
        try {
            val booted = BootOrchestrator(
                config(), secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope,
                connectorFactory = { RecordingConnector() },
            ).boot()

            // Make "beta" a real, runnable project and switch the ACTIVE view to it (create + getOrCreate the
            // runtime, then `state.rescope` — exactly what a project switch performs, onActiveSwitch = state::rescope),
            // while the shared projector's constant stays "alpha". A fresh project has no PO, so add one, then a
            // worker → the spoke `po-w2` has the operator as a writable member; its PO `po2` may post.
            booted.projectRegistry.create(CreateProjectRequest("beta", "Beta"))
            booted.runtimeRegistry.getOrCreate("beta", booted.projectRuntimeFactory)
            booted.state.rescope("beta")
            booted.rehydrateActiveProject()
            val mgmt = booted.runtimeRegistry.active().agentManagement
            mgmt.add(NewAgentSpec("po2", "PO2", Role.PO))
            mgmt.add(NewAgentSpec("w2", "W2", Role.WORKER))

            // Post through the booted chokepoint in beta's spoke → msg.projectId is server-stamped "beta".
            booted.hub.postAsAgent("po2", "po-w2", "cyp716 boot-projectId probe", MessageMeta(kind = MessageKind.NOTE))

            val commSent = runBlocking {
                withTimeout(5_000) {
                    while (
                        booted.eventSink.query(EventFilter(agentId = "po2"), Page(limit = 100))
                            .events.none { it.type == EventType.COMM_SENT }
                    ) {
                        delay(10)
                    }
                    booted.eventSink.query(EventFilter(agentId = "po2"), Page(limit = 100))
                        .events.filter { it.type == EventType.COMM_SENT }
                }
            }

            assertEquals(1, commSent.size, "exactly one comm.sent for the post")
            val e = commSent.single()
            assertEquals("beta", e.projectId, "CYP-716: comm.sent buckets into the ACTIVE project (msg.projectId), through the REAL boot wiring")
            assertNotEquals("alpha", e.projectId, "NOT the shared projector's boot constant (config.projectId)")
        } finally {
            root.deleteRecursively()
        }
    }
}
