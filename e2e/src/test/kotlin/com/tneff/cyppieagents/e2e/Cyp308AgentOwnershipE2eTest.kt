package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-308 — INDEPENDENT restart-stability verification of the Auftraggeber-confirmed AGENT-OWNERSHIP model
 * (adversarial 2nd leg vs Backend's fix). The model (supersedes the CYP-305 "adopt" heuristic):
 *  - config.agents (platform.config.json) belong PERMANENTLY to config.projectId ("default") — they NEVER wander.
 *  - every other project OWNS its own agents (durable per-project store), stable across a hub RESTART.
 *  - the active pointer is a VIEW only — switching changes which roster you see, never moves/loses agents.
 *
 * The RESTART CYCLE is the core axis ("auch nach Restart"). Deterministic, emulator-free, multi-boot fileBacked.
 * Asserts the DESIRED ownership → REDS on the current CYP-305-adopt tree (config agents WANDER into taxidriver),
 * GREENS on Backend's CYP-308 fix (permanent ownership, no wander).
 */
class Cyp308AgentOwnershipE2eTest {

    private fun boot() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))
    private val CONFIG = setOf("po", "backend")

    private suspend fun E2ePlatform.rosterOf(project: String): Set<String> {
        switchActive(project)
        var last = agentIds(); repeat(100) { delay(20); val n = agentIds(); if (n == last) return n; last = n }; return last
    }
    private suspend fun E2ePlatform.agentIds(): Set<String> =
        asOperator().use { it.get("$baseUrl/api/agents").body<List<Agent>>() }.map { it.id }.toSet()

    private inline fun withBoot(dir: File, block: (E2ePlatform) -> Unit) =
        e2ePlatform(boot(), gitRootOverride = dir, fileBacked = true).use(block)

    @Test
    fun agentOwnership_isPermanentAndStableAcrossRestart_activePointerIsViewOnly(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp308-ownership").toFile()

        // Boot 1: config seeds "default"=[po,backend]. Create "taxidriver" (empty) + point the durable active at it.
        withBoot(dir) { p ->
            p.asOperator().use { c -> c.post("${p.baseUrl}/api/projects") { contentType(ContentType.Application.Json); setBody(CreateProjectRequest("taxidriver", "Taxidriver")) } }
            p.switchActive("taxidriver")
            println("CYP308 boot1: default=${p.rosterOf("default")}  taxidriver=${p.rosterOf("taxidriver")}")
        }

        // ── SCENARIO 1: durable active=taxidriver(empty) → default OWNS config agents, taxidriver 0 — STABLE across restart.
        repeat(2) { n ->
            withBoot(dir) { p ->
                val default = p.rosterOf("default"); val taxi = p.rosterOf("taxidriver")
                println("CYP308 S1 restart#$n: default=$default  taxidriver=$taxi")
                assertEquals(CONFIG, default, "S1#$n: config agents belong PERMANENTLY to default (never wander)")
                assertEquals(emptySet(), taxi, "S1#$n: an empty durable-active owns NOTHING — config agents do NOT move to it")
            }
        }

        // ── SCENARIO 2: add an agent to taxidriver → each project keeps its OWN roster across restart (no leak, no overlay).
        withBoot(dir) { p ->
            p.switchActive("taxidriver")
            p.asOperator().use { c -> c.post("${p.baseUrl}/api/agents") { contentType(ContentType.Application.Json); setBody(NewAgentSpec("driver", "driver", Role.WORKER)) } }
        }
        repeat(2) { n ->
            withBoot(dir) { p ->
                val default = p.rosterOf("default"); val taxi = p.rosterOf("taxidriver")
                println("CYP308 S2 restart#$n: default=$default  taxidriver=$taxi")
                assertEquals(CONFIG, default, "S2#$n: default STILL owns exactly the config agents (taxidriver's 'driver' does NOT leak in)")
                assertEquals(setOf("driver"), taxi, "S2#$n: taxidriver owns exactly its own agent, stable across restart")
            }
        }

        // ── SCENARIO 3: a switch roundtrip loses NOTHING (CYP-305 axis stays closed) + per-project isolation.
        withBoot(dir) { p ->
            val d0 = p.rosterOf("default")
            p.switchActive("taxidriver"); p.switchActive("default")
            val d1 = p.rosterOf("default")
            val t1 = p.rosterOf("taxidriver")
            println("CYP308 S3 roundtrip: default before=$d0 after=$d1  taxidriver=$t1")
            assertEquals(CONFIG, d1, "S3: default's roster survives a switch roundtrip (no agent loss — CYP-305 axis)")
            assertEquals(setOf("driver"), t1, "S3: taxidriver's roster is its own only (no cross-leak of config agents)")
        }
        dir.deleteRecursively()
    }
}
