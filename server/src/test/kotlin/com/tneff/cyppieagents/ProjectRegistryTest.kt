package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The multi-project registry mechanics (S13 / CYP-91): seed-from-boot, operator-gated CRUD through the
 * pure [com.tneff.cyppieagents.model.ProjectGuard], the active pointer, fail-closed delete-safety
 * (requireDeletable mutates nothing), and atomic 0600 persistence that round-trips across a restart.
 */
class ProjectRegistryTest {

    private fun create(id: String, name: String = "X") = CreateProjectRequest(id, name)

    // ---- seed ----

    @Test fun seedsActiveProjectFromBootConfig() {
        val r = ProjectRegistry(file = null, seedProjectId = "default", seedProjectName = "Default")
        val v = r.view()
        assertEquals("default", v.activeProjectId)
        assertEquals(listOf("default"), v.projects.map { it.id })
        assertEquals("Default", v.projects.single().name)
    }

    @Test fun blankSeedFallsBackToDefaultId_failClosed() {
        // A blank seed id would be unscoped; the registry must still have a valid active project.
        val r = ProjectRegistry(file = null, seedProjectId = "")
        assertEquals("default", r.activeProjectId())
    }

    // ---- create ----

    @Test fun create_addsInOrder() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        r.create(create("beta", "Beta"))
        assertEquals(listOf("default", "beta"), r.view().projects.map { it.id })
    }

    @Test fun create_duplicate_conflict() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        assertEquals("project_exists", assertFailsWith<ConflictException> { r.create(create("default")) }.code)
    }

    @Test fun create_badId_badRequest() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        assertEquals("invalid_project_id", assertFailsWith<BadRequestException> { r.create(create("a/b")) }.code)
    }

    // ---- rename ----

    @Test fun rename_ok() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        r.create(create("beta", "Beta"))
        assertEquals("Beta v2", r.rename("beta", "Beta v2").name)
        assertEquals("Beta v2", r.view().projects.first { it.id == "beta" }.name)
    }

    @Test fun rename_unknown_notFound() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        assertEquals("project_not_found", assertFailsWith<NotFoundException> { r.rename("ghost", "X") }.code)
    }

    // ---- active pointer ----

    @Test fun setActive_flipsPointer() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        r.create(create("beta"))
        assertEquals("beta", r.setActive("beta").activeProjectId)
        assertEquals("beta", r.activeProjectId())
    }

    @Test fun setActive_unknown_notFound() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        assertEquals("project_not_found", assertFailsWith<NotFoundException> { r.setActive("ghost") }.code)
    }

    // ---- delete-safety (fail-closed: requireDeletable mutates nothing) ----

    @Test fun requireDeletable_active_protected_andNoMutation() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        r.create(create("beta"))
        assertEquals(
            "active_project_protected",
            assertFailsWith<ConflictException> { r.requireDeletable("default") }.code,
        )
        // nothing dropped
        assertEquals(listOf("default", "beta"), r.view().projects.map { it.id })
    }

    @Test fun requireDeletable_lastProject_protected() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        assertEquals("last_project", assertFailsWith<ConflictException> { r.requireDeletable("default") }.code)
    }

    @Test fun drop_removesNonActive() {
        val r = ProjectRegistry(file = null, seedProjectId = "default")
        r.create(create("beta"))
        assertEquals("beta", r.drop("beta").id)
        assertEquals(listOf("default"), r.view().projects.map { it.id })
    }

    // ---- persistence (atomic 0600, restart round-trip) ----

    @Test fun persists_andReloadsAcrossRestart() {
        val dir = Files.createTempDirectory("proj-reg").toFile()
        try {
            val file = File(dir, "projects.json")
            ProjectRegistry(file, seedProjectId = "default", seedProjectName = "Default").apply {
                create(create("beta", "Beta"))
                setActive("beta")
            }
            // A fresh registry over the same file restores the persisted state.
            val reloaded = ProjectRegistry(file, seedProjectId = "ignored-because-file-exists")
            assertEquals("beta", reloaded.activeProjectId())
            assertEquals(listOf("default", "beta"), reloaded.view().projects.map { it.id })

            val perms = Files.getPosixFilePermissions(file.toPath())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, "registry file is 0600")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun corruptStore_reseedsFromBoot() {
        val dir = Files.createTempDirectory("proj-reg-corrupt").toFile()
        try {
            val file = File(dir, "projects.json").apply { writeText("{ this is not json") }
            val r = ProjectRegistry(file, seedProjectId = "default", seedProjectName = "Default")
            assertEquals("default", r.activeProjectId())
            assertEquals(listOf("default"), r.view().projects.map { it.id })
            assertFalse(r.view().projects.isEmpty(), "a torn store must not brick boot")
            assertTrue(r.exists("default"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
