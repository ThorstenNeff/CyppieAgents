package com.tneff.cyppieagents.migration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-220 — the Migrations-Screen data contract at the VM/stub seam (the anti-lie surface, §2/§8, at the level a
 * headless test can pin without a Compose render). Renders + Maestro anti-lie teeth are QA's (tags §8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp220MigrationViewModelTest {

    @Test
    fun load_populatesInventoryAndHistory_fromApi() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading, "load completes")
        assertNotNull(s.stores, "the inventory is loaded (honest absence only while loading)")
        scope.cancel()
    }

    @Test
    fun nonMigratableStores_areSurfacedWithReasons_notOmitted() = runTest {
        // §2.2 — the anti-lie: a non-migratable store is SHOWN with a reason, never silently dropped from the list.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val unavailable = vm.state.value.stores!!.unavailable
        assertTrue(unavailable.isNotEmpty(), "non-migratable stores must be surfaced (§2.2 shown-not-omitted)")
        // The §6.4-neutral rows exist (pending_decision), stating neither 'works' nor 'doesn't' — no scope pre-empt.
        assertTrue(
            unavailable.any { it.reason == UnavailableReason.PENDING_DECISION },
            "the form-neutral 'not yet released' row is present (§2.2 / §11.1) — never omitted, never pre-decided",
        )
        scope.cancel()
    }

    @Test
    fun readOnlyStore_carriesProvenanceReason_cyp720() = runTest {
        // CYP-720/BE-8 — a READ_ONLY binding carries WHY (migration window vs legacy freeze); the reason is never
        // dropped in the client model, so the UI can render READ_ONLY honestly (spec §11.4).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val readOnly = vm.state.value.stores!!.migratable.firstOrNull { it.state == StoreBindingState.READ_ONLY }
        assertNotNull(readOnly, "the stub exercises a READ_ONLY store")
        assertNotNull(readOnly!!.readOnlyReason, "CYP-720: READ_ONLY carries its provenance reason, never null-silent")
        scope.cancel()
    }

    @Test
    fun history_stubIsUnavailable_notEmpty_theHonestBe1EmptyState() = runTest {
        // §8 — the two distinct empty states. The stub models "sink not wired" (BE-1 absent) as UNAVAILABLE, NOT an
        // empty RECORDING list — so the UI shows "not recorded", never the silent "nothing happened".
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        assertEquals(HistoryAvailability.UNAVAILABLE, vm.state.value.history!!.availability)
        scope.cancel()
    }
}
