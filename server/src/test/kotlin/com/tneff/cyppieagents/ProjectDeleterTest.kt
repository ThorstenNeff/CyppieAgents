package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentOverrideStore
import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.ProjectDeleter
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.events.draft
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.routing.ConflictException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The project cascade-delete (S13 / CYP-91) — the most destructive op in the platform, so the heaviest
 * proofs the PO pinned:
 *  - **no-orphan:** every partition (config + events + worktrees) of the deleted project is gone.
 *  - **no-cross-project:** the OTHER project's config + events + worktrees are byte-for-byte intact —
 *    deleting A never touches B.
 *  - **fail-closed:** a guard rejection (active / last project) tears down NOTHING.
 *
 * Wired with the real stores (in-memory config + event sink, FakeGit worktree manager) so the cascade
 * is exercised end-to-end, not mocked.
 */
class ProjectDeleterTest {

    private class Fixture {
        val gitRoot: File = Files.createTempDirectory("proj-del-cyp91").toFile()
        val registry = ProjectRegistry(file = null, seedProjectId = "default", seedProjectName = "Default")
        val config = ProjectConfigStore(file = null, RepoConfig("u", "main"), Secrets(mapOf("t" to "po"), "op", apiKey = null))
        val events = InMemoryEventSink(SystemTimeSource())
        val worktrees = WorktreeManager(FakeGit(), gitRoot)
        // CYP-215 F2: a REAL override file so the cascade's removeProject is exercised end-to-end (durable).
        val overrides = AgentOverrideStore(File(gitRoot, "agent-overrides.json"))
        // CYP-910: a REAL share store so the cascade's channel-share purge is exercised end-to-end (durable).
        val shares = ChannelShareStore(File(gitRoot, "channel-shares.json"))
        val deleter = ProjectDeleter(registry, config, events, worktrees, agentOverrides = overrides, channelShares = shares)

        fun seedTwoProjectsWithResources() = runBlocking {
            registry.create(CreateProjectRequest("beta", "Beta")) // default stays active
            config.setApiKey("default", "key-default-0001") // >= MIN_SECRET_LEN (CYP-104)
            config.setApiKey("beta", "key-beta-00002")
            repeat(3) { events.append(draft(team = "default")) }
            repeat(2) { events.append(draft(team = "beta")) }
            File(gitRoot, "projects/default/po").mkdirs()
            File(gitRoot, "projects/beta/po").mkdirs()
            File(gitRoot, "projects/beta/backend").mkdirs()
        }

        suspend fun eventCount(project: String) =
            events.query(EventFilter.ALL, Page(limit = 10_000)).events.count { it.projectId == project }

        fun cleanup() = gitRoot.deleteRecursively()
    }

    @Test
    fun delete_nonActive_withWorktrees_cascadesOnlyThatProject() = runBlocking {
        val f = Fixture()
        try {
            f.seedTwoProjectsWithResources()

            val receipt = f.deleter.delete("beta", deleteWorktrees = true) // opt-in worktree teardown

            // receipt reflects what was torn down
            assertEquals("beta", receipt.projectId)
            assertTrue(receipt.configRemoved, "beta had a config override")
            assertEquals(2, receipt.eventsRemoved, "exactly beta's 2 events")
            assertEquals(2, receipt.worktreesRemoved, "exactly beta's 2 worktrees")

            // no-orphan: beta is gone from every partition
            assertFalse(f.registry.exists("beta"), "beta removed from registry")
            assertEquals(0, f.eventCount("beta"), "beta events gone")
            assertFalse(File(f.gitRoot, "projects/beta").exists(), "beta worktree root gone")

            // no-cross-project: default is completely intact
            assertTrue(f.registry.exists("default"), "default still registered")
            assertTrue(f.config.apiKeyView("default").set, "default config untouched")
            assertEquals(3, f.eventCount("default"), "default events untouched")
            assertTrue(File(f.gitRoot, "projects/default/po").exists(), "default worktree untouched")
        } finally {
            f.cleanup()
        }
    }

    @Test
    fun delete_withoutFlag_keepsWorktrees_butRemovesConfigAndEvents() = runBlocking {
        // S13-design §4: worktree cascade is opt-in (default false). Config + events still go (pure
        // project data); the worktree dir — which may hold uncommitted work — is KEPT.
        val f = Fixture()
        try {
            f.seedTwoProjectsWithResources()

            val receipt = f.deleter.delete("beta") // default deleteWorktrees = false

            assertTrue(receipt.configRemoved, "config removed even when worktrees kept")
            assertEquals(2, receipt.eventsRemoved, "events removed even when worktrees kept")
            assertEquals(0, receipt.worktreesRemoved, "worktrees NOT removed by default")
            assertFalse(f.registry.exists("beta"), "beta removed from registry")
            assertEquals(0, f.eventCount("beta"), "beta events gone")
            assertTrue(File(f.gitRoot, "projects/beta/po").exists(), "beta worktree KEPT (opt-in not set)")
        } finally {
            f.cleanup()
        }
    }

    @Test
    fun delete_nonActive_purgesOverrideJson_notOtherProjects() = runBlocking {
        // CYP-215 F2 (closing a pre-existing CYP-210 gap): the durable agent-override overlay (name/color/
        // persona/launch/avatar) must be cascade-purged with the project — it was orphaned before (removeProject
        // defined but never wired). no-cross-project: the OTHER project's override is byte-for-byte intact.
        val f = Fixture()
        try {
            f.seedTwoProjectsWithResources()
            f.overrides.setAvatar("beta", "po", com.tneff.cyppieagents.model.AgentAvatar.Preset("bottts", "s"))
            f.overrides.put("default", "po", name = "KeepMe", color = "#123456", persona = null, launch = null)
            assertTrue(f.overrides.overrideOf("beta", "po") != null, "seed: beta has an override")

            f.deleter.delete("beta") // non-active cascade

            assertEquals(0, f.overrides.allFor("beta").size, "F2: beta's override JSON (incl. avatar) purged on cascade")
            assertTrue(f.overrides.overrideOf("default", "po") != null, "no-cross-project: default's override intact")
            assertEquals("KeepMe", f.overrides.overrideOf("default", "po")?.name, "default override byte-intact")
        } finally {
            f.cleanup()
        }
    }

    @Test
    fun delete_nonActive_purgesChannelShares_ownerAndGrantee_notOtherProjects() = runBlocking {
        // CYP-910: the cross-project share gate is the last cascade-partition. A surviving share is a LIVE
        // inbound-reach grant a re-created project id would inherit (id-resurrection leak). Deleting beta must
        // drop shares beta OWNS and strip beta from any other record's grantees — while default's own share to a
        // THIRD project (gamma) stays byte-intact (no-cross-project).
        val f = Fixture()
        try {
            f.seedTwoProjectsWithResources()
            f.shares.share("betaChan", ownerProjectId = "beta", sharedWith = setOf("default")) // beta OWNS a share
            f.shares.share("defChan", ownerProjectId = "default", sharedWith = setOf("beta", "gamma")) // beta is a GRANTEE
            assertEquals(setOf("betaChan"), f.shares.sharedInboundChannelIds("default"), "seed: default reaches betaChan")
            assertEquals(setOf("defChan"), f.shares.sharedInboundChannelIds("beta"), "seed: beta reaches defChan")

            f.deleter.delete("beta") // non-active cascade

            assertEquals(null, f.shares.record("betaChan"), "owner-orphan: beta's OWNED share purged")
            assertEquals(emptySet(), f.shares.sharedInboundChannelIds("beta"), "grantee-orphan: a re-created 'beta' inherits NO reach")
            assertEquals(setOf("defChan"), f.shares.sharedInboundChannelIds("gamma"), "no-cross-project: gamma's reach intact")
            assertEquals(setOf("gamma"), f.shares.record("defChan")?.sharedWith, "default's share narrowed to gamma only")
        } finally {
            f.cleanup()
        }
    }

    @Test
    fun delete_activeProject_failsClosed_tearsDownNothing() = runBlocking {
        val f = Fixture()
        try {
            f.seedTwoProjectsWithResources() // default is active

            assertEquals(
                "active_project_protected",
                assertFailsWith<ConflictException> { f.deleter.delete("default") }.code,
            )

            // fail-closed: NOTHING of the active project (or anything) torn down
            assertTrue(f.registry.exists("default") && f.registry.exists("beta"), "registry unchanged")
            assertTrue(f.config.apiKeyView("default").set, "default config intact")
            assertEquals(3, f.eventCount("default"), "default events intact")
            assertEquals(2, f.eventCount("beta"), "beta events intact")
            assertTrue(File(f.gitRoot, "projects/default/po").exists(), "default worktree intact")
            assertTrue(File(f.gitRoot, "projects/beta/po").exists(), "beta worktree intact")
        } finally {
            f.cleanup()
        }
    }

    @Test
    fun delete_lastProject_failsClosed_tearsDownNothing() = runBlocking {
        val f = Fixture()
        try {
            // only the seed project exists → it is both last AND active
            f.config.setApiKey("default", "key-default-0001") // >= MIN_SECRET_LEN (CYP-104)
            repeat(2) { f.events.append(draft(team = "default")) }
            File(f.gitRoot, "projects/default/po").mkdirs()

            assertEquals(
                "last_project",
                assertFailsWith<ConflictException> { f.deleter.delete("default") }.code,
            )

            assertTrue(f.registry.exists("default"), "registry unchanged")
            assertTrue(f.config.apiKeyView("default").set, "config intact")
            assertEquals(2, f.eventCount("default"), "events intact")
            assertTrue(File(f.gitRoot, "projects/default/po").exists(), "worktree intact")
        } finally {
            f.cleanup()
        }
    }
}
