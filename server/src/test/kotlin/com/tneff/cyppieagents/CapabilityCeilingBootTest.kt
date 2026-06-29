package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.CapabilityStatus.LIMITED
import com.tneff.cyppieagents.model.CapabilityStatus.UNAVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ConnectorTrust
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E2.4 / CYP-140 (A4 boot application) — the capability ceiling is applied ONCE at the boot loop:
 *  - **LOCAL → clamp is identity** (the connector's caps reach the registry byte-unchanged): E2-S2 guard.
 *  - **REMOTE → self-declared caps are clamped to the §1 ceiling** (a lie degrades, never escalates).
 *
 * **Mutation (M-A4):** skip the clamp (record `capabilitiesFor` verbatim) → the REMOTE liar reaches the
 * registry with all-AVAILABLE → [remoteTrust_selfReportedCapsAreClampedToCeiling] reddens; LOCAL stays green.
 */
class CapabilityCeilingBootTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    /** A connector that declares a chosen [trust] + [capabilities] — to drive the boot clamp. */
    private class TrustConnector(
        override val capabilities: Capabilities,
        override val trust: ConnectorTrust,
    ) : Connector {
        override val provider: ProviderInfo = ProviderInfo.CLAUDE
        override fun open(agentId: String): ConnectorSession = object : ConnectorSession {
            override val agentId: String = agentId
            override val events: Flow<StreamJsonEvent> = emptyFlow()
            override suspend fun sendTurn(turn: UserTurn) {}
            override fun close() {}
        }
    }

    private fun config() = PlatformConfig(
        RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
    )
    private fun secrets() = Secrets(mapOf("tok-po" to "po", "tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
    private fun gitRoot() = Files.createTempDirectory("ceiling-boot").toFile()

    @Test
    fun localTrust_capsByteUnchanged_e2s2() {
        // Honest LOCAL caps with a non-AVAILABLE dim → boot keeps them exactly (clamp = identity).
        val declared = Capabilities(AVAILABLE, LIMITED, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope,
            connectorFactory = { TrustConnector(declared, ConnectorTrust.LOCAL) },
        ).boot()
        assertEquals(declared, booted.capabilityRegistry.get("backend"), "LOCAL clamp is identity")
    }

    @Test
    fun remoteTrust_selfReportedCapsAreClampedToCeiling() {
        // A REMOTE connector LIES all-AVAILABLE → boot clamps to the §1 REMOTE ceiling.
        val lie = Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)
        val booted = BootOrchestrator(
            config(), secrets(), WorktreeManager(FakeGit(), gitRoot()), FakeSpawner(), scope,
            connectorFactory = { TrustConnector(lie, ConnectorTrust.REMOTE) },
        ).boot()
        val eff = booted.capabilityRegistry.get("backend")!!
        assertEquals(UNAVAILABLE, eff.structuredUsage, "billing-critical dim clamped OFF despite the AVAILABLE claim")
        assertEquals(LIMITED, eff.toolGranularity, "claimed AVAILABLE → LIMITED")
        assertEquals(LIMITED, eff.reliableResult)
        assertEquals(LIMITED, eff.rateLimitSignal)
        assertEquals(AVAILABLE, eff.coordination, "the verifiable dim stays AVAILABLE")
    }
}
