package com.tneff.cyppieagents.project

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** CYP-249: the client mirror of the server's per-project runtime LRU cap (K = 3, see the CYP-249 switch-runtime
 *  contract). The active project + the 2 most-recently-hot background projects stay warm; anything beyond is
 *  disposed. Kept in one place so a future config-driven K (contract §5) has a single seam. */
const val PROJECT_VM_LRU_CAP: Int = 3

/**
 * CYP-249 — a bounded, per-project [ViewModelStore] holder that mirrors the server's runtime LRU (K = 3).
 *
 * CYP-246 re-keys the project-scoped VMs on `activeProjectId` so a switch hands back fresh VMs (correct isolation),
 * but the previous project's VMs — **including their live `/ws/agent|comm|lifecycle` sockets** — linger in the one
 * shell [ViewModelStore] until the shell leaves composition, accumulating one warm socket-set per visited project.
 * This manager bounds that: each project gets its OWN store, the active + [cap]-1 most-recently-hot stay warm (a
 * cheap switch-back, no reconnect), and the least-recently-used beyond [cap] is **cleared** — which runs each held
 * VM's `onCleared()` → cancels its `viewModelScope` → tears down its sockets. Re-entry to an evicted project mints a
 * fresh store → fresh VMs → a clean reconnect + cursor-resume (CYP-198/204), the normal re-entry path (not an error).
 *
 * Server-authoritative: this only bounds the CLIENT's warm socket set; the server's own runtime LRU (BACKGROUND vs
 * SUSPENDED) is the source of truth. Not thread-safe by design — it is touched only from the Compose main thread.
 */
class ProjectVmStoreManager(private val cap: Int = PROJECT_VM_LRU_CAP) {

    // Access-ordered by hand: the eldest key is the least-recently-hot, the last key is the current/active one.
    private val stores = LinkedHashMap<String, ViewModelStore>()
    private val owners = LinkedHashMap<String, ViewModelStoreOwner>()

    init {
        require(cap >= 1) { "ProjectVmStoreManager cap must be >= 1 (the active project always stays live), was $cap" }
    }

    /**
     * The [ViewModelStoreOwner] for [projectId] — get-or-create, with NO reorder and NO clear, so it is safe to call
     * from composition (idempotent: an existing project returns its cached owner without mutating the LRU order). A
     * first use (or a use after eviction) mints a fresh store → fresh VMs. The MRU-promote + eviction happen in
     * [noteActive], run from a post-commit effect (never clear a store during composition).
     */
    fun ownerFor(projectId: String): ViewModelStoreOwner {
        val store = stores.getOrPut(projectId) { ViewModelStore() } // tail-insert if new; no reorder if present
        return owners.getOrPut(projectId) { SimpleViewModelStoreOwner(store) }
    }

    /**
     * Mark [activeProjectId] most-recently-used and evict (clear + drop) the least-recently-used stores beyond [cap]
     * — never the active one. Clearing a store runs its VMs' `onCleared()` → cancels their scopes → closes their
     * sockets. Call from an effect AFTER the switch commits: the evicted projects are BACKGROUND (never the composed
     * active one), so clearing them is safe. Returns the evicted project ids (for logging/tests).
     */
    fun noteActive(activeProjectId: String): List<String> {
        // Promote to the MRU tail (LinkedHashMap iteration order == least→most recently used).
        stores.remove(activeProjectId)?.let { stores[activeProjectId] = it }
        val evicted = mutableListOf<String>()
        while (stores.size > cap) {
            val lru = stores.keys.firstOrNull() ?: break
            // CYP-266 #3: redundant-by-construction defense-in-depth. The MRU-promote above moves the active
            // project to the tail, and this loop evicts from the FRONT while size > cap — so `lru == active` can
            // never actually occur (active is the tail, unreachable until size <= cap where the loop has stopped).
            // The `activeProjectIsNeverEvicted` tooth guards the invariant; this break is a cheap belt-and-suspenders
            // backstop in case a future refactor breaks the promote. Kept intentionally (not tooth-able on its own).
            if (lru == activeProjectId) break
            stores.remove(lru)?.clear()
            owners.remove(lru)
            evicted += lru
        }
        return evicted
    }

    /** Tear down every held store (each VM's `onCleared()` → socket close). Call when the shell leaves composition. */
    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
        owners.clear()
    }

    /** The currently-warm project ids, least→most recently used. Test/observability hook. */
    fun liveProjectIds(): List<String> = stores.keys.toList()

    /** The configured LRU cap (K). */
    fun cap(): Int = cap
}

/** Minimal [ViewModelStoreOwner] over a single [ViewModelStore] — the per-project owner handed to `viewModel(...)`. */
private class SimpleViewModelStoreOwner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner
