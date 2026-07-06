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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Journey J10 (CYP-256 / CYP-247.5 .5a) — **the ratified gate: per-project spawn survives a restart.**
 * Over the REAL embedded platform (real stores file-backed under ONE reused gitRoot): create a NON-boot
 * project, add a PO + a worker to it via real `POST /api/agents` (add-PO-to-fresh-project), start the worker,
 * then **re-boot()** the whole platform over the SAME files — and the project's agents are rehydrated from the
 * durable `ProjectAgentStore` (startable, in its slice), reusing the existing worktree (D3: no re-`git worktree
 * add`). Mutation: skip the `ProjectAgentStore.put` in `add` → after the restart the project is empty → RED.
 */
class J10ProjectAgentPersistenceE2eTest {

    /** FakeGit that ALSO records the worktree paths it was asked to `add` — the D3 no-re-add probe. */
    private class RecordingGit : CommandRunner {
        val worktreeAdds = CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "") // branch absent → -b path
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    target?.let { worktreeAdds.add(it); File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
    }

    private fun bootProjects() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    @Test
    fun perProjectAgents_surviveRestart_rehydratedStartable_worktreeReused(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp256-restart").toFile()
        val run1 = RecordingGit()

        // ---- Boot 1: create a fresh project, add a PO + worker to it (add-PO-to-fresh-project), start the worker.
        e2ePlatform(bootProjects(), gitRootOverride = dir, fileBacked = true, runner = run1).use { p1 ->
            p1.asOperator().use { c ->
                c.post("${p1.baseUrl}/api/projects") { json(); setBody(CreateProjectRequest("beta", "Beta")) }
            }
            p1.switchActive("beta")
            // a fresh project has no PO → adding the first PO is permitted (the per-project guard); then a worker.
            p1.addAgent("po-b", Role.PO)
            p1.addAgent("worker", Role.WORKER)
            assertEquals(HttpStatusCode.OK, p1.startAgent("worker").status, "the worker spawns in beta's runtime")
            assertEquals(setOf("po-b", "worker"), p1.agentIds("beta"), "beta's roster within the session")
            assertTrue(run1.worktreeAdds.any { it.endsWith("projects/beta/worker") }, "boot 1 created beta/worker's worktree once")
        }

        // ---- Boot 2: re-boot() over the SAME files. Only 'default' is config-seeded; beta + its agents must
        // come back from the durable stores (projectRegistry + projectAgents), not from any in-memory carry-over.
        val run2 = RecordingGit()
        e2ePlatform(bootProjects(), gitRootOverride = dir, fileBacked = true, runner = run2).use { p2 ->
            p2.switchActive("beta") // triggers the LAZY rehydration
            assertEquals(setOf("po-b", "worker"), p2.agentIds("beta"), "beta's agents rehydrated from the store after restart")
            assertEquals(HttpStatusCode.OK, p2.startAgent("worker").status, "the rehydrated worker is startable (config intact)")
            // D3: the existing worktree is REUSED — the re-boot's rehydration triggered NO `git worktree add` for it.
            assertTrue(
                run2.worktreeAdds.none { it.endsWith("projects/beta/worker") },
                "rehydration reused the existing worktree — no re-clone / re-`git worktree add` (D3)",
            )
        }
        dir.deleteRecursively()
    }

    // ---- helpers (all through the real path) ----

    private fun io.ktor.client.request.HttpRequestBuilder.json() = contentType(ContentType.Application.Json)

    private suspend fun E2ePlatform.addAgent(id: String, role: Role) {
        asOperator().use { c ->
            val r = c.post("$baseUrl/api/agents") { json(); setBody(NewAgentSpec(id = id, name = id, role = role)) }
            check(r.status == HttpStatusCode.Created) { "add $id → ${r.status}" }
        }
    }

    private suspend fun E2ePlatform.startAgent(id: String) =
        asOperator().use { it.post("$baseUrl/api/agents/$id/start") }

    private suspend fun E2ePlatform.agentIds(activeExpected: String): Set<String> =
        asOperator().use { it.get("$baseUrl/api/agents").body<List<Agent>>() }.map { it.id }.toSet()
}
