package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-220 Phase 3 — [PgProjectRegistry] end-to-end on a REAL (embedded) Postgres: behaviour parity with
 * [FileProjectRegistry] (seed, order, the §guard 4xx codes, active pointer) + a **restart** check (a second
 * instance on the same DB sees the persisted state — the real durability the vertical proves).
 */
class PgProjectRegistryTest {

    private fun withPg(block: (DataSource) -> Unit) = EmbeddedPostgres.start().use { block(it.postgresDatabase) }

    @Test fun seed_thenView_default() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default", "Default")
        assertEquals(listOf("default"), reg.projects().map { it.id })
        assertEquals("default", reg.activeProjectId())
        assertEquals("Default", reg.projects().single().name)
    }

    @Test fun create_preservesInsertionOrder_andExists() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default")
        reg.create(CreateProjectRequest("beta", "Beta"))
        reg.create(CreateProjectRequest("gamma", "Gamma"))
        assertEquals(listOf("default", "beta", "gamma"), reg.projects().map { it.id }, "insertion order preserved (seq)")
        assertTrue(reg.exists("beta")); assertFalse(reg.exists("ghost"))
    }

    @Test fun create_guards_duplicate_and_invalid() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default")
        reg.create(CreateProjectRequest("beta", "Beta"))
        assertEquals("project_exists", assertFailsWith<ConflictException> { reg.create(CreateProjectRequest("beta", "X")) }.code)
        assertEquals("invalid_project_id", assertFailsWith<BadRequestException> { reg.create(CreateProjectRequest(" ", "X")) }.code)
        // fail-closed: nothing extra created
        assertEquals(listOf("default", "beta"), reg.projects().map { it.id })
    }

    @Test fun rename_updates_name() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default")
        reg.create(CreateProjectRequest("beta", "Beta"))
        reg.rename("beta", "Beta Renamed")
        assertEquals("Beta Renamed", reg.projects().first { it.id == "beta" }.name)
    }

    @Test fun setActive_flipsPointer_unknownIs404() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default")
        reg.create(CreateProjectRequest("beta", "Beta"))
        assertEquals("beta", reg.setActive("beta").activeProjectId)
        assertEquals("beta", reg.activeProjectId())
        assertEquals("project_not_found", assertFailsWith<NotFoundException> { reg.setActive("ghost") }.code)
    }

    @Test fun requireDeletable_and_drop_guards() = withPg { ds ->
        val reg = PgProjectRegistry(ds, "default")
        // only project → it is BOTH last and active; ProjectGuard checks last first → `last_project` (parity with File).
        assertEquals("last_project", assertFailsWith<ConflictException> { reg.requireDeletable("default") }.code)
        reg.create(CreateProjectRequest("beta", "Beta"))
        // beta is deletable (non-active, non-last); default (active) is not
        reg.requireDeletable("beta")
        assertEquals("active_project_protected", assertFailsWith<ConflictException> { reg.drop("default") }.code)
        assertEquals("beta", reg.drop("beta").id)
        assertEquals(listOf("default"), reg.projects().map { it.id })
        // now default is last → last_project
        assertEquals("last_project", assertFailsWith<ConflictException> { reg.requireDeletable("default") }.code)
    }

    @Test fun restart_secondInstanceSeesPersistedState() = withPg { ds ->
        PgProjectRegistry(ds, "default").apply {
            create(CreateProjectRequest("beta", "Beta"))
            setActive("beta")
        }
        // a fresh instance on the SAME database (a restart): the data + active pointer survived
        val reopened = PgProjectRegistry(ds, "default", migrate = false)
        assertEquals(listOf("default", "beta"), reopened.projects().map { it.id })
        assertEquals("beta", reopened.activeProjectId(), "active pointer persisted across restart")
    }
}
