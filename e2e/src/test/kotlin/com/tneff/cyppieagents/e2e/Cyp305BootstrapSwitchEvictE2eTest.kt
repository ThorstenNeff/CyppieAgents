package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Role
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-305 — the seed-vs-active mismatch switch-loss (pre-existent .4b bug; reproduced on the .5b-reverted tree).
 *
 * The deploy topology: `platform.config` sets no projectId → `config.projectId="default"`, seeding the bootstrap
 * agents under "default"; but the durable `projects.json` active pointer is "taxidriver" (from a prior switch) —
 * a LEGITIMATE seed-vs-active mismatch the code must carry. Root cause: `HubState.hubAndSpoke` seeds under
 * `config.projectId` while `ProjectRegistry` restores the durable active pointer, and the two are never
 * reconciled → the durable-active project rehydrates from its EMPTY store (config agents never land there) →
 * 0 agents until a restart. Fix (BootOrchestrator): the effective boot project = the durable active pointer when
 * it carries no own store-backed agents (else config.projectId, so a distinct runtime-created project — J10's
 * beta — keeps only its own; no config-agent leak).
 *
 * Deterministic + emulator-free (2-boot fileBacked). The bootstrap agents MUST survive the roundtrip to the
 * durable-active project. Mutation (revert the effective-active seam to config.projectId) → 0 agents → RED.
 */
class Cyp305BootstrapSwitchEvictE2eTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    // config.projectId="default" (FIRST SeedProject) seeds the bootstrap agents [po, a, b].
    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("a"), SeedAgent("b"))),
    )

    private suspend fun E2ePlatform.agentIds(): Set<String> =
        json.parseToJsonElement(asOperator().use { it.get("$baseUrl/api/agents").bodyAsText() })
            .jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

    /** Read only AFTER the async rehydrate/onActivated settles (stable across reads). */
    private suspend fun E2ePlatform.agentIdsSettled(): Set<String> {
        var last = agentIds()
        repeat(100) { delay(20); val now = agentIds(); if (now == last) return now; last = now }
        return last
    }

    @Test
    fun durableActiveNeConfigSeed_bootstrapAgentsSurviveSwitchRoundtrip(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp305").toFile()

        // Boot 1: config seeds "default" [po, a, b]. Create "taxidriver" (empty) + switch to it → the durable
        // active pointer persists as "taxidriver" (≠ config.projectId "default"); taxidriver has NO own agents.
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p1 ->
            assertEquals(setOf("po", "a", "b"), p1.agentIds(), "sanity: config seeds the bootstrap agents under default")
            p1.asOperator().use { c ->
                c.post("${p1.baseUrl}/api/projects") { contentType(ContentType.Application.Json); setBody(CreateProjectRequest("taxidriver", "Taxidriver")) }
            }
            p1.switchActive("taxidriver")
        }

        // Boot 2 (re-boot over the SAME files): config STILL seeds "default"; the durable active is "taxidriver".
        // The in-memory rescope stash from boot 1 is gone. The bootstrap agents must materialise for the durable-
        // active project (its empty store cannot rehydrate them) and survive a real switch roundtrip to it.
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true, runner = FakeGit()).use { p2 ->
            p2.switchActive("taxidriver")
            assertEquals(setOf("po", "a", "b"), p2.agentIdsSettled(), "the durable-active project carries the config bootstrap agents (CYP-305)")
            p2.switchActive("default")
            p2.switchActive("taxidriver")
            assertEquals(setOf("po", "a", "b"), p2.agentIdsSettled(), "and they survive a switch roundtrip (CYP-305)")
        }
        dir.deleteRecursively()
    }
}
