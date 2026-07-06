package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-256 (.5a) — the [ProjectAgentStore] over the CYP-220 seam. Proves: per-project keying + `contains`
 * discrimination (D1), the [com.tneff.cyppieagents.db.MigrationTarget] roundtrip into a **FRESH instance**
 * (the QA rule — a same-instance roundtrip would pass a broken importRows/load vacuously), all-fields
 * preservation, and — the .5a core at the store level — **file persistence survives a fresh instance**
 * (the restart analogue).
 */
class ProjectAgentStoreTest {

    private fun agent(id: String, persona: String? = null, avatar: AgentAvatar? = null) = StoredAgent(
        id = id, name = "N-$id", role = Role.WORKER, worktree = id, launch = "claude",
        persona = persona, connectorKind = ConnectorKind.STREAM_JSON, color = "#123456", avatar = avatar,
    )

    @Test
    fun contains_discriminatesRuntimeAddedAgent() {
        val s = FileProjectAgentStore(null)
        s.put("alpha", agent("backend"))
        assertTrue(s.contains("alpha", "backend"), "a put agent is contained (routes to the store, D1)")
        assertFalse(s.contains("alpha", "frontend"), "an absent agent is not contained (routes to the overlay)")
        assertFalse(s.contains("beta", "backend"), "per-project keyed — alpha's agent is not in beta")
    }

    @Test
    fun migrationRoundtrip_intoFreshInstance_preservesAllFields() {
        val src = FileProjectAgentStore(null)
        src.put("alpha", agent("backend", persona = "coordinator", avatar = AgentAvatar.Preset("bottts", "s1")))
        src.put("alpha", agent("frontend"))
        src.put("beta", agent("worker", persona = "p2"))
        val rows = src.exportRows()

        val dst = FileProjectAgentStore(null) // FRESH instance — a broken importRows/decode is caught here
        dst.importRows(rows)

        assertEquals(src.agentsFor("alpha"), dst.agentsFor("alpha"), "all alpha rows + every field roundtrip")
        assertEquals(src.agentsFor("beta"), dst.agentsFor("beta"), "beta roundtrips independently (per-project)")
        // non-vacuous: the avatar/persona are actually carried (not dropped by the row codec)
        val be = dst.agentsFor("alpha").first { it.id == "backend" }
        assertEquals("coordinator", be.persona)
        assertEquals(AgentAvatar.Preset("bottts", "s1"), be.avatar)
    }

    @Test
    fun filePersistence_survivesFreshInstance_theRestartAnalogue() {
        val f = File(Files.createTempDirectory("cyp256").toFile(), ".cyppie/project-agents.json")
        FileProjectAgentStore(f).put("alpha", agent("backend", persona = "coordinator"))

        // a FRESH instance over the SAME file = the boot-after-restart analogue at the store level.
        val reloaded = FileProjectAgentStore(f)
        val agents = reloaded.agentsFor("alpha")
        assertEquals(1, agents.size, "the runtime-added agent survived the 'restart' (file-durable)")
        assertEquals("coordinator", agents.first().persona, "its config survived too")
        assertTrue(reloaded.contains("alpha", "backend"))
    }

    @Test
    fun removeProject_dropsExactlyThatProject_failClosedOnBlank() {
        val s = FileProjectAgentStore(null)
        s.put("alpha", agent("backend"))
        s.put("beta", agent("worker"))
        assertEquals(0, s.removeProject(""), "fail-closed: a blank projectId never clears anything")
        assertEquals(1, s.removeProject("alpha"), "drops exactly alpha's agents")
        assertTrue(s.agentsFor("alpha").isEmpty())
        assertEquals(1, s.agentsFor("beta").size, "beta is untouched")
    }
}
