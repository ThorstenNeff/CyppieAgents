package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.db.BindingReason
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView

/**
 * CYP-770 ① — the read-only-window gate for [ProjectRegistry], the one `userDbCapable` store that had a real
 * Postgres implementation ([PgProjectRegistry]) but **no placement point** in [PgStoreRouting] (11 capable keys
 * vs 9 gates). Without it, `project` was migratable — [com.tneff.cyppieagents.db.StoreMigrator] binds any key
 * and opens a MIGRATING window — while **nothing consulted that window**, so writes during the copy `A → B`
 * were neither frozen nor lost-proof: exactly the Finding-B damage the other nine stores are protected from,
 * on the **project registry itself**.
 *
 * Same shape as the other gates (see [MigrationGate]): reads pass through to source A, every mutation throws
 * [storeMigrating] → 409, nothing persisted. [setActive] is included deliberately — the active pointer is
 * registry state that persists, so flipping it mid-window would be lost by the rebind like any other write.
 *
 * (`avatar_blob` was the other ungated capable key and is handled the opposite way — it has **no** Pg impl, so
 * it was de-classified in [com.tneff.cyppieagents.tier.StoreResidencies] rather than given a gate for a path
 * that does not exist.)
 */
class MigrationGatedProjectRegistry(
    private val sourceA: ProjectRegistry,
    private val reason: BindingReason,
) : ProjectRegistry {

    // ---- reads: straight through to source A ----
    override fun view(): ProjectsView = sourceA.view()
    override fun activeProjectId(): String = sourceA.activeProjectId()
    override fun projects(): List<Project> = sourceA.projects()
    override fun exists(id: String): Boolean = sourceA.exists(id)

    // `requireDeletable` is a non-mutating guard (its KDoc: "WITHOUT mutating") — a read, so it passes through.
    // The mutation it guards (`drop`) is frozen below, so nothing cascades during the window either way.
    override fun requireDeletable(id: String) = sourceA.requireDeletable(id)

    // ---- mutations: frozen ----
    override fun create(spec: CreateProjectRequest): Project = throw storeMigrating("project", reason)
    override fun rename(id: String, name: String): Project = throw storeMigrating("project", reason)
    override fun setActive(projectId: String): ProjectsView = throw storeMigrating("project", reason)
    override fun drop(id: String): Project = throw storeMigrating("project", reason)
}
