package com.tneff.cyppieagents.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-249 — the per-project [ViewModelStore] LRU (K = 3) that mirrors the server's runtime cap. These pin the
 * bookkeeping AND the socket-teardown proxy: a [ViewModel] placed in a project's store has its `onCleared()` run
 * exactly when the manager evicts that project (== `viewModelScope` cancel == its ws sockets close). A warm
 * project (within K) keeps the SAME store/VM (cheap switch-back, no reconnect); an evicted-then-re-entered project
 * gets a FRESH store/VM (a clean reconnect + cursor-resume, the normal re-entry path).
 */
class ProjectVmStoreManagerTest {

    /** A stand-in for a project-scoped VM whose `onCleared()` is the observable proxy for its socket teardown. */
    private class TrackingVm : ViewModel() {
        var cleared = false
            private set
        override fun onCleared() { cleared = true }
    }

    private fun vmIn(owner: ViewModelStoreOwner): TrackingVm =
        ViewModelProvider.create(owner, viewModelFactory { initializer { TrackingVm() } })[TrackingVm::class]

    /** Model a real switch exactly as the shell does: get-or-create the owner (composition), then promote+evict (effect). */
    private fun ProjectVmStoreManager.switchTo(projectId: String): ViewModelStoreOwner {
        val owner = ownerFor(projectId)
        noteActive(projectId)
        return owner
    }

    @Test
    fun warmWithinCap_reusesSameStore_andDoesNotClearItsVm() {
        val mgr = ProjectVmStoreManager(cap = 3)
        val ownerA1 = mgr.switchTo("A")
        val vmA = vmIn(ownerA1)
        mgr.switchTo("B") // A now background, still within K=3

        val ownerA2 = mgr.switchTo("A") // switch back
        assertSame(ownerA1, ownerA2, "a warm project (within K) reuses the SAME owner/store — cheap switch-back")
        assertFalse(vmA.cleared, "a warm project's VM/socket is NOT torn down")
        assertEquals(listOf("B", "A"), mgr.liveProjectIds())
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondCap_clearingItsVm_keepsTheRestWarm() {
        val mgr = ProjectVmStoreManager(cap = 3)
        val vmA = vmIn(mgr.switchTo("A"))
        val vmB = vmIn(mgr.switchTo("B"))
        val vmC = vmIn(mgr.switchTo("C"))
        // Fourth distinct project → the least-recently-hot (A) is evicted → its store cleared → onCleared → sockets close.
        val vmD = vmIn(mgr.switchTo("D"))

        assertEquals(listOf("B", "C", "D"), mgr.liveProjectIds(), "only the active + 2 recent stay warm (K=3)")
        assertTrue(vmA.cleared, "the evicted (LRU) project's VM/socket IS torn down")
        assertFalse(vmB.cleared, "warm projects are untouched")
        assertFalse(vmC.cleared)
        assertFalse(vmD.cleared)
    }

    @Test
    fun reEntryAfterEviction_mintsFreshStore_forACleanReconnect() {
        val mgr = ProjectVmStoreManager(cap = 3)
        val ownerA1 = mgr.switchTo("A")
        val vmA1 = vmIn(ownerA1)
        mgr.switchTo("B"); mgr.switchTo("C"); mgr.switchTo("D") // A evicted here

        assertTrue(vmA1.cleared, "A was evicted (its old socket set torn down)")
        val ownerA2 = mgr.switchTo("A") // re-entry
        assertNotSame(ownerA1, ownerA2, "re-entry after eviction mints a FRESH store → fresh VMs → clean reconnect")
        val vmA2 = vmIn(ownerA2)
        assertNotSame(vmA1, vmA2, "the fresh store yields a new VM, not the torn-down one")
        assertEquals(listOf("C", "D", "A"), mgr.liveProjectIds())
    }

    @Test
    fun activeProjectIsNeverEvicted_evenAtCapOne() {
        val mgr = ProjectVmStoreManager(cap = 1)
        val vmA = vmIn(mgr.switchTo("A"))
        assertFalse(vmA.cleared)
        val vmB = vmIn(mgr.switchTo("B")) // cap=1 → A evicted immediately
        assertTrue(vmA.cleared, "cap=1 keeps only the active project")
        assertFalse(vmB.cleared, "the active project is never evicted")
        assertEquals(listOf("B"), mgr.liveProjectIds())
    }

    @Test
    fun clearAll_tearsDownEveryHeldStore() {
        val mgr = ProjectVmStoreManager(cap = 3)
        val vmA = vmIn(mgr.switchTo("A"))
        val vmB = vmIn(mgr.switchTo("B"))
        mgr.clearAll()
        assertTrue(vmA.cleared && vmB.cleared, "shell disposal tears down all warm projects (no leaked sockets)")
        assertTrue(mgr.liveProjectIds().isEmpty())
    }
}
