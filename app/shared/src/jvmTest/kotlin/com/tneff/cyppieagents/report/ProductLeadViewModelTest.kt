package com.tneff.cyppieagents.report

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-90: the Product-Lead VM logic over [StubReportRepository] on an Unconfined scope. Covers the
 * reviewer gates — each mutation-provable:
 * - **fail-closed:** without operator access the VM neither lists nor generates nor selects — no report
 *   at all (drop the `accessible` guards → RED).
 * - **immutable history:** each generate appends a NEW snapshot; the list is newest-first, never merged.
 * - **honest errors:** a gate denial maps to `report_access_denied`.
 */
class ProductLeadViewModelTest {

    private fun vm(repo: ReportRepository, accessible: Boolean = true) =
        ProductLeadViewModel(repo, accessible = accessible, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun noAccess_failClosed_neverLoadsOrGenerates() {
        val vm = vm(StubReportRepository(), accessible = false)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.metas.isEmpty())
        vm.generate(ReportType.STATUS)
        assertFalse(vm.state.value.generating)
        assertTrue(vm.state.value.metas.isEmpty()) // no-op → still no report
        assertNull(vm.state.value.selected)
    }

    @Test
    fun generate_createsAndSelectsNewSnapshot() {
        val vm = vm(StubReportRepository())
        vm.generate(ReportType.DEFECTS)
        val s = vm.state.value
        assertEquals(1, s.metas.size)
        assertNotNull(s.selected)
        assertEquals(ReportType.DEFECTS, s.selected?.type)
        assertFalse(s.generating)
    }

    @Test
    fun generate_immutableHistory_newestFirst() {
        val vm = vm(StubReportRepository())
        vm.generate(ReportType.USAGE)
        vm.generate(ReportType.STATUS)
        val s = vm.state.value
        assertEquals(2, s.metas.size) // never merged — a history of snapshots
        assertEquals(ReportType.STATUS, s.metas.first().type) // newest first
        assertEquals(ReportType.STATUS, s.selected?.type) // the fresh run is selected
        // The two snapshots are distinct immutable runs (strictly increasing as-of time).
        assertTrue(s.metas[0].generatedAt > s.metas[1].generatedAt)
    }

    @Test
    fun generate_gateDenied_mapsAccessError() {
        val vm = vm(StubReportRepository(denyWrites = "operator_required"))
        vm.generate(ReportType.STATUS)
        assertEquals("report_access_denied", vm.state.value.error)
        assertFalse(vm.state.value.generating)
    }

    @Test
    fun select_loadsFullSnapshot() {
        val repo = StubReportRepository()
        val vm = vm(repo)
        vm.generate(ReportType.USAGE)
        val id = vm.state.value.metas.first().id
        vm.select(id)
        assertEquals(id, vm.state.value.selected?.id)
    }

    @Test
    fun defectsSnapshot_carriesSeverityItems_contentFree() {
        val vm = vm(StubReportRepository())
        vm.generate(ReportType.DEFECTS)
        val items = vm.state.value.selected!!.sections.flatMap { it.items }
        // At least one severity-bearing (defect) item; refs are non-sensitive labels, never bodies.
        assertTrue(items.any { it.severity != null })
        assertTrue(items.all { it.refLabel == null || !it.refLabel.contains("\n") })
    }
}
