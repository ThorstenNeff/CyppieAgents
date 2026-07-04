package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Role
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-210 — per-agent name/color/persona survive a restart via the `.cyppie/agent-overrides.json` overlay
 * (the OVERRIDE layer over the `platform.config.json` seed). Also proves the config-seed→overlay precedence,
 * blank→preserve, and that `id` is immutable (structurally absent from [AgentEdit]).
 *
 * Mutation (teeth): drop the overlay-apply loop in BootOrchestrator → boot #2 shows the SEED name/color, not
 * the edited ones → [editedNameColorPersona_surviveRestart] reddens.
 */
class AgentOverrideBootTest {

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

    private fun secrets() = Secrets(mapOf("tok-po" to "po", "tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)

    private fun config(backendColor: String? = null) = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("backend", "BE", Role.WORKER, color = backendColor),
        ),
    )

    private fun boot(root: File, ovFile: File, backendColor: String? = null) =
        BootOrchestrator(config(backendColor), secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope, agentOverrideFile = ovFile).boot()

    @Test
    fun editedNameColorPersona_surviveRestart() {
        val root = Files.createTempDirectory("cyp210-restart").toFile()
        val ovFile = File(root, ".cyppie/agent-overrides.json")
        try {
            // Boot #1 — seed from platform.config (name "BE", no color, role-default persona).
            val b1 = boot(root, ovFile)
            assertEquals("BE", b1.state.agent("backend")!!.name)
            assertNull(b1.state.agent("backend")!!.color)

            // Edit name + color + persona → persisted to the overlay file.
            b1.agentManagement.edit("backend", AgentEdit(role = Role.WORKER, name = "Backend Bob", color = "#3B82F6", persona = "# custom persona"))
            assertEquals("Backend Bob", b1.state.agent("backend")!!.name, "name is live after edit")
            assertEquals("#3B82F6", b1.state.agent("backend")!!.color, "color is live after edit")
            assertTrue(ovFile.isFile, "the edit persisted to the overlay file")

            // Boot #2 (RESTART) — same config seed + same overlay file → the overlay WINS per field.
            val b2 = boot(root, ovFile)
            assertEquals("Backend Bob", b2.state.agent("backend")!!.name, "edited name survived restart (overlay over seed)")
            assertEquals("#3B82F6", b2.state.agent("backend")!!.color, "edited color survived restart")
            assertEquals("# custom persona", b2.agentConfigs.personaOf("backend"), "edited persona survived restart")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun configColorSeed_appliesWhenNoOverride() {
        val root = Files.createTempDirectory("cyp210-seed").toFile()
        val ovFile = File(root, ".cyppie/agent-overrides.json")
        try {
            val b = boot(root, ovFile, backendColor = "#EF4444")
            assertEquals("#EF4444", b.state.agent("backend")!!.color, "platform.config color seeds the agent when no overlay")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun blankEdit_preservesStoredValue_andIdIsImmutable() {
        val root = Files.createTempDirectory("cyp210-preserve").toFile()
        val ovFile = File(root, ".cyppie/agent-overrides.json")
        try {
            val b = boot(root, ovFile)
            b.agentManagement.edit("backend", AgentEdit(role = Role.WORKER, name = "Named", color = "#111111"))
            // A follow-up edit that only touches color → name PRESERVED (blank→preserve); id never changes.
            val after = b.agentManagement.edit("backend", AgentEdit(role = Role.WORKER, name = "", color = "#222222"))
            assertEquals("Named", after.name, "blank name preserves the stored value")
            assertEquals("#222222", after.color, "color updated")
            assertEquals("backend", after.id, "id is immutable — AgentEdit has no id field; edit never changes it")
        } finally {
            root.deleteRecursively()
        }
    }
}
