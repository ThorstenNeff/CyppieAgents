package com.tneff.cyppieagents.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-417 (S-G) — the [CapacityViewModel] Q5 banner lifetime. The banner needs a **real reject** to appear (a full
 * readout alone does not), is **dismissable**, and **self-clears** when headroom returns. Capacity flows straight
 * from the source (server-authoritative, H5). The VM runs on an [UnconfinedTestDispatcher] so its collectors settle
 * eagerly at the assertion.
 */
class Cyp417CapacityViewModelTest {

    private fun TestScope.newVm(
        capacity: MutableStateFlow<HubCapacity?>,
        rejections: MutableSharedFlow<Unit>,
    ) = CapacityViewModel(
        StubHubCapacitySource(capacityFlow = capacity, rejectionFlow = rejections),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun reject_showsBanner_thenDismiss_hidesIt() = runTest {
        val cap = MutableStateFlow<HubCapacity?>(HubCapacity(4, 4)) // full, but full alone shows NO banner
        val rej = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = newVm(cap, rej)
        assertFalse(vm.overloadVisible.value, "no reject yet → no banner (full readout is not a reject)")
        rej.emit(Unit)
        assertTrue(vm.overloadVisible.value, "a real server reject → banner visible")
        vm.dismissOverload()
        assertFalse(vm.overloadVisible.value, "dismiss hides it (Q5)")
    }

    @Test
    fun headroom_selfClearsTheBanner() = runTest {
        val cap = MutableStateFlow<HubCapacity?>(HubCapacity(4, 4))
        val rej = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = newVm(cap, rej)
        rej.emit(Unit)
        assertTrue(vm.overloadVisible.value)
        cap.value = HubCapacity(3, 4) // headroom returns
        assertFalse(vm.overloadVisible.value, "Q5: the banner self-clears when headroom returns")
    }

    @Test
    fun capacity_flowsThroughFromTheServerSource() = runTest {
        val cap = MutableStateFlow<HubCapacity?>(null)
        val vm = newVm(cap, MutableSharedFlow(extraBufferCapacity = 1))
        assertEquals(null, vm.capacity.value)
        cap.value = HubCapacity(2, 5)
        assertEquals(HubCapacity(2, 5), vm.capacity.value)
    }
}
