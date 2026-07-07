package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-305 — INDEPENDENT repro of the seed-vs-active mismatch switch-bug (adversarial cross-check vs Backend's fix).
 *
 * The bug: `ProjectRegistry` restores the DURABLE active pointer at boot (`active = loaded.activeProjectId` from
 * projects.json), but `HubState.activeProjectId` inits to `config.projectId`, and `rehydrateActiveProject` pulls
 * ONLY from project-agents.json — never the `config.agents` bootstrap. So when the durable active ("taxidriver",
 * from a prior switch) ≠ config.projectId ("default", where the bootstrap agents seed), a switch-roundtrip to the
 * durable active rehydrates from taxidriver's EMPTY store → 0 agents (the bootstrap roster stranded in "default").
 *
 * Deterministic + emulator-free. Uses the REAL switch roundtrip with a SETTLE-WAIT (not a fast-switch that races
 * past the async rehydrate). Asserts the loss (0 agents) on the buggy tree; MUST flip to "agents survive" on the fix.
 */
class Cyp305SwitchBugReproTest {

    /** config.projectId="default" seeds the bootstrap agents [po, backend]. */
    private fun bootProjects() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private suspend fun E2ePlatform.agentIds(): Set<String> =
        asOperator().use { it.get("$baseUrl/api/agents").body<List<Agent>>() }.map { it.id }.toSet()

    /** The REAL roundtrip: read only AFTER the async rehydrate/onActivated has settled (stable across reads). */
    private suspend fun E2ePlatform.agentIdsSettled(): Set<String> {
        var last = agentIds()
        repeat(100) { delay(20); val now = agentIds(); if (now == last) return now; last = now }
        return last
    }

    @Test
    fun durableActiveNeConfigSeed_switchRoundtrip_strandsBootstrapAgents(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp305-switch").toFile()

        // Boot 1: config seeds "default" [po, backend]. Create "taxidriver" + switch to it → the durable active
        // pointer is persisted as "taxidriver" (≠ config.projectId "default"). taxidriver has NO config agents.
        e2ePlatform(bootProjects(), gitRootOverride = dir, fileBacked = true).use { p1 ->
            p1.asOperator().use { c ->
                c.post("${p1.baseUrl}/api/projects") { contentType(ContentType.Application.Json); setBody(CreateProjectRequest("taxidriver", "Taxidriver")) }
            }
            p1.switchActive("taxidriver")
            println("CYP305 boot1 after switch→taxidriver: agents=${p1.agentIds()}")
        }

        // Boot 2: re-boot() over the SAME files. config STILL seeds "default" [po, backend]; the durable active
        // pointer is "taxidriver". The in-memory rescope stash from boot 1 is GONE (fresh process).
        e2ePlatform(bootProjects(), gitRootOverride = dir, fileBacked = true).use { p2 ->
            println("CYP305 boot2 initial (HubState active=config default): agents=${p2.agentIds()}")
            p2.switchActive("taxidriver")                 // the real switch-roundtrip to the durable active
            val settled = p2.agentIdsSettled()            // settle-wait (not a fast-switch)
            println("CYP305 boot2 after switch→taxidriver (settled): agents=$settled")

            // THE INVARIANT (dual-gate guard): the config bootstrap [po, backend] must SURVIVE the switch-roundtrip
            // to the durable-active project — never stranded in a non-active "default". On the BUGGY tree this REDS
            // ("expected [po, backend] but was []" = the loss made explicit); on Backend's fix it GREENS (survival).
            assertEquals(
                setOf("po", "backend"), settled,
                "CYP-305: bootstrap agents must survive the roundtrip to the durable-active project (0 agents = the loss)",
            )
        }
        dir.deleteRecursively()
    }
}
