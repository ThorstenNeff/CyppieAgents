package com.tneff.cyppieagents.boot

import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-247.1 (L) — the per-project [ProjectRuntime] holder + active-runtime resolver: the ONE place L makes
 * the agent lifecycle per-project. [active] returns the runtime for the live active project (the same
 * pointer `HubState.rescope` / `ProjectRegistry.setActive` flip), so a switch re-targets the lifecycle
 * machinery the way CYP-246's `rescope` already re-targets the agent set.
 *
 * **Scaffold (this story): exactly one runtime is registered — the boot project's — so [active] returns it
 * and behavior is identical.** Later stories register a runtime per project (lazily on first activation,
 * CYP-247.4) and evict beyond the LRU cap K (background-live default; evicted runtimes are persisted +
 * killed and resumed via `--resume` on re-entry).
 *
 * [activeProjectId] is the **live** resolver (e.g. `{ state.activeProjectId }`), never a frozen value, so
 * the registry follows the switch without re-wiring — the same pattern CYP-246 used to un-freeze
 * `AgentManagement`'s project scope.
 */
class RuntimeRegistry(private val activeProjectId: () -> String) {
    private val runtimes = ConcurrentHashMap<String, ProjectRuntime>()

    /** Register (or replace) a project's runtime. Boot registers the boot project's; L registers per project. */
    fun register(runtime: ProjectRuntime): ProjectRuntime {
        runtimes[runtime.projectId] = runtime
        return runtime
    }

    /** The runtime for [projectId], or null if none is live (not yet activated / LRU-evicted). */
    fun of(projectId: String): ProjectRuntime? = runtimes[projectId]

    /**
     * The active project's runtime. **Fail-closed:** throws if the active project has no live runtime — a
     * caller must never silently fall back to another project's lifecycle (that would be the very
     * cross-project bleed L exists to prevent). Today the boot runtime is always registered for the active
     * project, so this never throws; when L lazily instances runtimes, activation registers before first use.
     */
    fun active(): ProjectRuntime = runtimes[activeProjectId()]
        ?: error("no live ProjectRuntime for active project '${activeProjectId()}'")

    /** Count of live runtimes — the accounting the CYP-247.4 LRU cap K reads. */
    fun liveCount(): Int = runtimes.size
}
