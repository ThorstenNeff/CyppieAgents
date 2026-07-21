package com.tneff.cyppieagents.db

import com.tneff.cyppieagents.tier.StoreResidencies
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-773 (P7 blocker) — [StoreMigrator] must REFUSE to migrate a store that is not `USER_DB_CAPABLE`. The
 * migrator binds+copies rows into a target DSN; without this guard it would write `hub_secret` / `account` /
 * `roles` INTO a customer's Postgres — a secret/auth leak across the BYODB boundary (§3: the core never pushes
 * anything secret into the customer DB). The read path (`activeDataSource`) already fail-closes routing, but the
 * COPY itself had no residency check.
 *
 * The load-bearing assertion is not "it threw" but "**nothing was written**": a rejected migration must never
 * reach `target.importRows`, so the target DB stays empty and no binding/window is opened.
 */
class Cyp773MigratorResidencyGuardTest {

    /** A target that RECORDS whether the copy ever ran — the tooth's real evidence (importRows never called). */
    private class RecordingTarget : MigrationTarget {
        val importCalls = AtomicInteger(0)
        val stored = ArrayList<ByteArray>()
        override fun exportRows(): List<ByteArray> = stored
        override fun importRows(rows: List<ByteArray>) {
            importCalls.incrementAndGet()
            stored.addAll(rows)
        }
    }

    private class FixedSource(private val rows: List<ByteArray>) : MigrationSource {
        override fun exportRows(): List<ByteArray> = rows
    }

    private fun source() = FixedSource(listOf("row-a".toByteArray(), "row-b".toByteArray()))

    // Sanity: pin the fixtures against the real classification, so the teeth can't rot if a key is reclassified.
    init {
        require(!StoreResidencies.isUserDbCapable("hub_secret")) { "fixture drift: hub_secret must be MUST_STAY_HOME" }
        require(!StoreResidencies.isUserDbCapable("roles")) { "fixture drift: roles must be MUST_STAY_HOME" }
        require(StoreResidencies.isUserDbCapable("project")) { "fixture drift: project must be USER_DB_CAPABLE" }
    }

    /**
     * The money-tooth. Migrating a MUST_STAY_HOME store THROWS [StoreResidencyViolation] AND leaves the target
     * EMPTY — the copy never ran (importRows never called) and no binding/window was opened. Proven for two
     * distinct home stores (a secret store and an auth store), so it is the CLASS, not one key.
     *
     * Mutation (the DoD): delete the residency guard at the top of `migrate()` → the copy proceeds, importRows is
     * called, the target fills → this reddens (while the positive control below stays green).
     */
    @Test
    fun migratingAMustStayHomeStore_throws_andNeverWritesTheTarget() {
        for (homeKey in listOf("hub_secret", "roles")) {
            val target = RecordingTarget()
            val bindings = BindingRegistry(null)

            assertFailsWith<StoreResidencyViolation>("'$homeKey' must be refused") {
                StoreMigrator(bindings).migrate(source(), target, homeKey, "default", "pg1", actor = "op")
            }

            // The load-bearing part: NOTHING was written and NOTHING was bound.
            assertEquals(0, target.importCalls.get(), "'$homeKey': the copy must never run (importRows never called)")
            assertTrue(target.stored.isEmpty(), "'$homeKey': the target DB stays empty — no rows leaked")
            assertNull(bindings.binding(homeKey, "default"), "'$homeKey': no binding/window opened for a refused store")
        }
    }

    /**
     * Anti-vacuity / positive control: a real USER_DB_CAPABLE store is NOT blocked by the guard — the copy runs
     * and the target fills. So the reject tooth above proves "the guard blocks HOME stores", not "the guard
     * blocks everything" (an always-throw would pass the reject tooth but red HERE).
     */
    @Test
    fun migratingAUserDbCapableStore_passesTheGuard_andCopies() {
        val src = source()
        val target = RecordingTarget()
        val bindings = BindingRegistry(null)

        val receipt = StoreMigrator(bindings).migrate(src, target, "project", "default", "pg1", actor = "op")

        assertTrue(receipt.ok, "a user-DB-capable store migrates end-to-end")
        assertEquals(1, target.importCalls.get(), "positive control: the copy DID run for a capable store")
        assertEquals(2, target.stored.size, "both rows copied")
        assertEquals(BindingState.ACTIVE, bindings.binding("project", "default")?.state, "rebound ACTIVE on success")
    }

    /**
     * The guard reads the SAME [StoreResidencies] classification the routing layer uses — so an unknown/typo key
     * is refused fail-closed (unknown ⇒ MUST_STAY_HOME), and the guard will adapt automatically the day CYP-772b
     * opts a store into `userDbCapable` (no change here). Pins the fail-closed default explicitly.
     */
    @Test
    fun unknownStoreKey_isRefusedFailClosed() {
        val target = RecordingTarget()
        assertFailsWith<StoreResidencyViolation> {
            StoreMigrator(BindingRegistry(null)).migrate(source(), target, "some_new_unclassified_store", "default", "pg1", actor = "op")
        }
        assertEquals(0, target.importCalls.get(), "an unknown key is fail-closed refused — nothing written")
    }
}
