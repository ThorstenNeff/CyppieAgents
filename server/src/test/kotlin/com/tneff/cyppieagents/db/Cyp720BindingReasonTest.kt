package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.boot.MigrationGatedProjectConfigStore
import com.tneff.cyppieagents.boot.PgStoreRouting
import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.ConflictException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * CYP-720 (BE-8) — the write-lock a store holds must say **WHY**, because the two causes demand OPPOSITE
 * operator actions and the platform previously gave them ONE signal:
 *
 *  - a real migration (§4.3) → temporary, "retry after the switch completes" is a real path;
 *  - a CYP-714-coerced LEGACY binding (a row persisted before `state` existed, loaded fail-closed as
 *    READ_ONLY) → nothing is migrating, no switch is coming, and it is NOT temporary. Telling that operator
 *    to wait sent them to wait for an event that never arrives.
 *
 * The client matches on the **typed code**, never the text (`MigrationDialogs.kt` renders the
 * `store_migrating` 409 as tone EFFECT_DEFERRED — "nothing is broken, the action is deferred"), so the fix
 * has to change the CODE, not just the message — otherwise the UI keeps rendering the deferred/retry copy
 * for a lock that will never clear on its own.
 */
class Cyp720BindingReasonTest {

    private fun registryOver(json: String): Pair<BindingRegistry, File> {
        val dir = Files.createTempDirectory("cyp720").toFile()
        val f = File(dir, "bindings.json").apply { writeText(json) }
        return BindingRegistry(f, clock = { 1_000L }) to dir
    }

    /** A binding row as persisted BEFORE `state` existed — the CYP-714 legacy shape (no `state`, no `reason`). */
    private fun legacyRowJson(storeKey: String = "project_config") =
        """{"$storeKey${"\u0000"}default":{"storeKey":"$storeKey","projectId":"default","dsnId":"dsn-1","schema":"public","boundAt":1}}"""

    /** A row that DID persist its state (a real, state-bearing binding). */
    private fun statefulRowJson(state: String, storeKey: String = "project_config") =
        """{"$storeKey${"\u0000"}default":{"storeKey":"$storeKey","projectId":"default","dsnId":"dsn-1","schema":"public","state":"$state","boundAt":1}}"""

    // ---- T1/T2/T6: the read seam records the CAUSE where it is known ----

    /**
     * T1 — the load-bearing tooth. A legacy row is still coerced READ_ONLY (CYP-714 security intact) AND is now
     * stamped LEGACY_UNEVALUATED. Mutation: drop `reason = LEGACY_UNEVALUATED` from the coercion at
     * `StoreBinding.kt` → the reason falls back to the MIGRATION_WINDOW default → RED.
     */
    @Test
    fun legacyRowWithoutState_coercesReadOnly_andRecordsLegacyUnevaluated() {
        val (reg, dir) = registryOver(legacyRowJson())
        try {
            val b = reg.binding("project_config", "default")!!
            assertEquals(BindingState.READ_ONLY, b.state, "CYP-714 fail-closed coercion is INTACT")
            assertEquals(BindingReason.LEGACY_UNEVALUATED, b.reason, "CYP-720: the cause is recorded at the seam that knows it")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T2 — anti-vacuity / no over-coercion: a state-bearing READ_ONLY row (a REAL migration parked read-only) is
     * decoded verbatim and keeps MIGRATION_WINDOW. Without this, "stamp LEGACY_UNEVALUATED everywhere" would
     * pass T1 while destroying the distinction the ticket exists to create.
     */
    @Test
    fun stateBearingReadOnlyRow_keepsMigrationWindowReason() {
        val (reg, dir) = registryOver(statefulRowJson("READ_ONLY"))
        try {
            val b = reg.binding("project_config", "default")!!
            assertEquals(BindingState.READ_ONLY, b.state, "an explicitly persisted READ_ONLY stays READ_ONLY")
            assertEquals(
                BindingReason.MIGRATION_WINDOW, b.reason,
                "a row that DID persist its state is a real migration — never mislabelled legacy",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** T6 — back-compat: a pre-CYP-720 row carrying `state` but NO `reason` decodes without throwing. */
    @Test
    fun rowWithStateButNoReason_decodesBackCompat_active() {
        val (reg, dir) = registryOver(statefulRowJson("ACTIVE"))
        try {
            val b = reg.binding("project_config", "default")!!
            assertEquals(BindingState.ACTIVE, b.state)
            assertEquals(BindingReason.MIGRATION_WINDOW, b.reason, "absent `reason` decodes to the back-compat default")
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T3 — `setState` IS the migration path, so it stamps MIGRATION_WINDOW: re-evaluating a legacy binding CLEARS
     * the legacy reason instead of leaving it stale. Mutation: `cur.copy(state = state)` (drop the reason reset)
     * → the re-evaluated binding keeps LEGACY_UNEVALUATED and keeps emitting the action-required 409 forever → RED.
     */
    @Test
    fun setState_onALegacyBinding_resetsReasonToMigrationWindow() {
        val (reg, dir) = registryOver(legacyRowJson())
        try {
            assertEquals(BindingReason.LEGACY_UNEVALUATED, reg.binding("project_config", "default")!!.reason)
            val next = reg.setState("project_config", "default", BindingState.MIGRATING)!!
            assertEquals(
                BindingReason.MIGRATION_WINDOW, next.reason,
                "a deliberately re-evaluated binding is no longer 'legacy, unevaluated' — the reason must not go stale",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- windowReason: the single source both the lock and its cause are read from ----

    @Test
    fun windowReason_carriesCause_andInMigrationWindowStaysDerivedFromIt() {
        val (legacyReg, d1) = registryOver(legacyRowJson())
        val (migratingReg, d2) = registryOver(statefulRowJson("MIGRATING"))
        val (activeReg, d3) = registryOver(statefulRowJson("ACTIVE"))
        try {
            assertEquals(
                BindingReason.LEGACY_UNEVALUATED,
                PgStoreRouting.windowReason("project_config", "default", legacyReg),
            )
            assertEquals(
                BindingReason.MIGRATION_WINDOW,
                PgStoreRouting.windowReason("project_config", "default", migratingReg),
            )
            assertNull(PgStoreRouting.windowReason("project_config", "default", activeReg), "ACTIVE → not locked")

            // the Boolean predicate is DERIVED from the same call — the two answers cannot drift apart
            listOf(legacyReg to true, migratingReg to true, activeReg to false).forEach { (reg, locked) ->
                assertEquals(
                    locked, PgStoreRouting.inMigrationWindow("project_config", "default", reg),
                    "inMigrationWindow stays single-sourced from windowReason",
                )
            }
        } finally {
            d1.deleteRecursively(); d2.deleteRecursively(); d3.deleteRecursively()
        }
    }

    // ---- T4/T5: the 409 the operator actually receives ----

    private object NoopConfigStore : ProjectConfigStore {
        override fun resolvedRepo(projectId: String) = RepoConfig("git@github.com:o/p.git", "main")
        override fun resolvedApiKey(projectId: String): String? = null
        override fun repoView(projectId: String) = RepoConfigView(configured = true, url = "git@github.com:o/p.git", branch = "main")
        override fun apiKeyView(projectId: String) = ApiKeyView(set = false, masked = null)
        override fun setRepo(projectId: String, url: String, branch: String) = error("unreachable")
        override fun setApiKey(projectId: String, key: String) = error("unreachable")
        override fun remove(projectId: String) = error("unreachable")
    }

    /**
     * T4 — the money-tooth. A LEGACY-locked store rejects writes with a DIFFERENT typed code, so the client
     * cannot render the deferred "retry after the switch" copy for a lock that never clears. Mutation: make
     * `storeMigrating` ignore the reason (one message for both) → RED.
     */
    @Test
    fun legacyLockedStore_rejectsWith_storeBindingUnevaluated_notStoreMigrating() {
        val gate = MigrationGatedProjectConfigStore(NoopConfigStore, BindingReason.LEGACY_UNEVALUATED)
        val ex = assertFailsWith<ConflictException> { gate.setApiKey("default", "sk-whatever-0001") }
        assertEquals("store_binding_unevaluated", ex.code, "a legacy binding is NOT migrating — distinct typed code")
        assertNotEquals("store_migrating", ex.code, "the client must not reach its deferred/retry-after-switch copy here")
        // the message must not promise a switch that is never coming
        assertEquals(false, ex.message!!.contains("retry after the switch"), "no false 'retry after the switch' promise")
    }

    /** T5 — regression guard: a REAL migration still emits the original code+copy, unchanged for the client. */
    @Test
    fun realMigrationLockedStore_stillRejectsWith_storeMigrating() {
        val gate = MigrationGatedProjectConfigStore(NoopConfigStore, BindingReason.MIGRATION_WINDOW)
        val ex = assertFailsWith<ConflictException> { gate.setApiKey("default", "sk-whatever-0002") }
        assertEquals("store_migrating", ex.code, "the migration path is untouched (client contract preserved)")
    }

    /** Reads stay served from source A under BOTH causes — the lock freezes writes only, never reads. */
    @Test
    fun bothCauses_leaveReadsServedFromSourceA() {
        listOf(BindingReason.LEGACY_UNEVALUATED, BindingReason.MIGRATION_WINDOW).forEach { reason ->
            val gate = MigrationGatedProjectConfigStore(NoopConfigStore, reason)
            assertEquals("main", gate.resolvedRepo("default").branch, "reads pass through under $reason")
        }
    }
}
