package com.tneff.cyppieagents.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-220 / CYP-727 — the Migrations-Screen data contract at the VM/stub seam (the anti-lie surface, §2/§8, at the
 * level a headless test can pin without a Compose render). Renders + Maestro anti-lie teeth are QA's (tags §8).
 *
 * **CYP-727:** the inventory is an explicit tri-state — a failed stores query is [InventoryState.Unavailable],
 * NEVER a silent empty. These tests pin `Unavailable ≠ Known(empty) ≠ Checking` so the safe-but-silent §4a
 * collapse can't come back (the old `runCatching{}.getOrNull()` mapped a failure to `null`, byte-identical to a
 * genuinely-empty inventory — a "nothing to migrate" lie).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp220MigrationViewModelTest {

    /** A [MigrationApi] whose inventory query FAILS — everything else is the honest stub. */
    private class FailingStoresApi(private val delegate: MigrationApi = StubMigrationApi()) : MigrationApi by delegate {
        override suspend fun stores(projectId: String): MigrationStores =
            throw RuntimeException("inventory query failed")
    }

    /** A [MigrationApi] whose inventory query blocks on [gate] — lets a test observe the CHECKING state. */
    private class BlockingStoresApi(
        private val gate: CompletableDeferred<MigrationStores>,
        private val delegate: MigrationApi = StubMigrationApi(),
    ) : MigrationApi by delegate {
        override suspend fun stores(projectId: String): MigrationStores = gate.await()
    }

    @Test
    fun load_populatesInventory_asKnown_fromApi() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val inv = assertIs<InventoryState.Known>(
            vm.state.value.inventory,
            "a successful load is Known (honest inventory), not Checking/Unavailable",
        )
        assertTrue(inv.stores.migratable.isNotEmpty(), "the stub inventory is populated")
        scope.cancel()
    }

    @Test
    fun nonMigratableStores_areSurfacedWithReasons_notOmitted() = runTest {
        // §2.2 — the anti-lie: a non-migratable store is SHOWN with a reason, never silently dropped from the list.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(StubMigrationApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val known = assertIs<InventoryState.Known>(vm.state.value.inventory)
        val unavailable = known.stores.unavailable
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
        val known = assertIs<InventoryState.Known>(vm.state.value.inventory)
        val readOnly = known.stores.migratable.firstOrNull { it.state == StoreBindingState.READ_ONLY }
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

    // ---- CYP-727: the safe-but-silent §4a fix — failed ≠ empty ≠ loading -------------------------------------

    @Test
    fun failedInventoryQuery_isUnavailable_notEmpty_cyp727() = runTest {
        // The core tooth: a stores() failure must land on Unavailable (→ the retry surface), NEVER a fabricated
        // empty. Mutation (load() folds onFailure → Known(empty)) reddens exactly here.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = MigrationViewModel(FailingStoresApi(), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val inv = vm.state.value.inventory
        assertEquals(InventoryState.Unavailable, inv, "a FAILED inventory query is Unavailable, not a silent empty")
        assertTrue(inv !is InventoryState.Known, "failure must never be rendered as a (possibly empty) Known inventory")
        scope.cancel()
    }

    @Test
    fun emptyInventory_isKnownEmpty_notUnavailable_cyp727() = runTest {
        // The mirror: a genuinely-empty load is Known-with-empty-groups, distinct from a failed one. The section
        // shows its honest "nothing to migrate" line here — a truth ONLY when the query actually succeeded empty.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val emptyApi = StubMigrationApi(stores = MigrationStores(migratable = emptyList(), unavailable = emptyList()))
        val vm = MigrationViewModel(emptyApi, projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        val known = assertIs<InventoryState.Known>(
            vm.state.value.inventory,
            "a successful empty load is Known(empty), NOT Unavailable — empty ≠ error",
        )
        assertTrue(
            known.stores.migratable.isEmpty() && known.stores.unavailable.isEmpty(),
            "the genuinely-empty inventory carries empty groups (the honest 'nothing to migrate')",
        )
        scope.cancel()
    }

    @Test
    fun inventoryQueryInFlight_isChecking_notEmpty_cyp727() = runTest {
        // The third distinct state: while the query is in flight the inventory is Checking (spinner), never a
        // premature empty. Held open on a gate, then released → Known.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<MigrationStores>()
        val vm = MigrationViewModel(BlockingStoresApi(gate), projectId = "proj-1", scope = scope)
        advanceUntilIdle()
        assertEquals(InventoryState.Checking, vm.state.value.inventory, "an in-flight query is Checking, not empty")
        gate.complete(StubMigrationApi.DEFAULT_STORES)
        advanceUntilIdle()
        assertIs<InventoryState.Known>(vm.state.value.inventory, "the completed query resolves to Known")
        scope.cancel()
    }
}
