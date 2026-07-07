package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-308 — the Auftraggeber-confirmed per-project agent-ownership model (supersedes the CYP-305 adopt-heuristic).
 *
 * Model: the config-seeded bootstrap agents belong PERMANENTLY to config.projectId (re-seeded from config each
 * boot); every OTHER project owns its own agents in its store; the durable active pointer is pure VIEW, not
 * ownership; stable across hub-restart — no wandering. The mismatch topology (config.projectId="default" ≠
 * durable active="taxidriver") is the core axis; the restart cycle is the key axis (multi-boot fileBacked).
 *
 * Mutation (BootOrchestrator: seed HubState under durableActive instead of config.projectId, i.e. restore the
 * adopt-behavior) → the config agents wander to taxidriver / vanish from default → these RED.
 */
class Cyp308AgentOwnershipE2eTest {

    private class FakeGit : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult {
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "")
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val t = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    t?.let { File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
    }

    /** config.projectId="default" (FIRST SeedProject) — permanently owns the bootstrap agents [po, a, b]. */
    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("a"), SeedAgent("b"))),
    )
    private val configAgents = setOf("po", "a", "b")

    /** /api/agents returns the ACTIVE project's roster; settle-wait past the async rehydrate/onActivated. */
    private suspend fun E2ePlatform.roster(): Set<String> {
        suspend fun read() = asOperator().use { it.get("$baseUrl/api/agents").body<List<Agent>>() }.map { it.id }.toSet()
        var last = read()
        repeat(100) { delay(20); val now = read(); if (now == last) return now; last = now }
        return last
    }

    private suspend fun E2ePlatform.rosterOf(projectId: String): Set<String> { switchActive(projectId); return roster() }

    private suspend fun E2ePlatform.createProject(id: String) = asOperator().use {
        it.post("$baseUrl/api/projects") { contentType(ContentType.Application.Json); setBody(CreateProjectRequest(id, id)) }
    }

    private suspend fun E2ePlatform.addAgent(id: String, role: Role) = asOperator().use {
        val r = it.post("$baseUrl/api/agents") { contentType(ContentType.Application.Json); setBody(NewAgentSpec(id = id, name = id, role = role)) }
        check(r.status == HttpStatusCode.Created) { "add $id → ${r.status}" }
    }

    // AC1 + AC4: config agents owned by config.projectId, durable-active shows its own (0); identical after
    // restart; no loss over a switch roundtrip.
    @Test
    fun ac1_ac4_ownershipAndViewStableAcrossRestart_noRoundtripLoss(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp308-ac1").toFile()

        // Boot 1: default owns [po,a,b]. Create taxidriver (empty) + switch to it → durable active = taxidriver.
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p1 ->
            p1.createProject("taxidriver")
            assertEquals(emptySet(), p1.rosterOf("taxidriver"), "AC1: taxidriver (active) shows its OWN roster — empty")
            assertEquals(configAgents, p1.rosterOf("default"), "AC1: default owns the config bootstrap agents")
            p1.switchActive("taxidriver") // leave the durable active pointer = taxidriver for the restart
        }

        // Boot 2 (re-boot over SAME files): identical ownership + view; the durable active (taxidriver) is restored.
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p2 ->
            assertEquals(emptySet(), p2.roster(), "AC1 restart: taxidriver (durable active) still shows 0 (its own)")
            assertEquals(configAgents, p2.rosterOf("default"), "AC1 restart: default still owns the config agents")
            // AC4: switch roundtrip — no agent loss on either side.
            assertEquals(emptySet(), p2.rosterOf("taxidriver"), "AC4: taxidriver still 0 after roundtrip")
            assertEquals(configAgents, p2.rosterOf("default"), "AC4: default still owns the config agents — no loss")
        }
        dir.deleteRecursively()
    }

    // AC2 + AC3: an agent added to the durable-active project persists UNDER it (no wander to config.projectId)
    // across restart; each project shows only its own roster (no cross-leak).
    @Test
    fun ac2_ac3_addToDurableActivePersists_noWander_noLeak(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp308-ac2").toFile()

        // Boot 1: create taxidriver, switch to it, add its OWN PO (fresh project → first PO permitted).
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p1 ->
            p1.createProject("taxidriver")
            p1.switchActive("taxidriver")
            p1.addAgent("po-t", Role.PO)
            assertEquals(setOf("po-t"), p1.roster(), "taxidriver owns its own added agent")
        }

        // Boot 2 (restart): no wandering — default = config agents only, taxidriver = its own only.
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p2 ->
            assertEquals(setOf("po-t"), p2.roster(), "AC2: taxidriver's own agent survives restart (rehydrated from ITS store)")
            assertEquals(configAgents, p2.rosterOf("default"), "AC3: default = config agents only — taxidriver's 'po-t' did NOT wander in")
            assertEquals(setOf("po-t"), p2.rosterOf("taxidriver"), "AC3: taxidriver = its own only — config agents did NOT leak in")
        }
        dir.deleteRecursively()
    }
}
