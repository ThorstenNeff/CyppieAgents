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

    /**
     * CYP-255 (.4a) — the switch/activation entry point: return the LIVE runtime for [projectId], or mint +
     * register one via [factory] the first time the project is activated (lazy per-project instancing, so a
     * fresh project gets its own lifecycle/sessions the moment it is switched to — before any spawn). Atomic
     * per key via [ConcurrentHashMap.computeIfAbsent], so two concurrent activations of the same project share
     * ONE runtime. [factory] fully wires the runtime before it becomes visible (fail-closed — [active] never
     * sees a half-built runtime). The LRU cap + eviction of cold runtimes is layered on in CYP-247.4 (.4b).
     */
    fun getOrCreate(projectId: String, factory: ProjectRuntimeFactory): ProjectRuntime =
        runtimes.computeIfAbsent(projectId) { factory.create(it) }

    /** The runtime for [projectId], or null if none is live (not yet activated / LRU-evicted). */
    fun of(projectId: String): ProjectRuntime? = runtimes[projectId]

    /**
     * The active project's runtime. **Fail-closed:** throws if the active project has no live runtime — a
     * caller must never silently fall back to another project's lifecycle (that would be the very
     * cross-project bleed L exists to prevent). CYP-259: the switch mints the target's runtime (getOrCreate)
     * BEFORE it becomes active, so in normal flow this never throws; the throw is the backstop for a not-yet-
     * activated / LRU-evicted project, mapped to a clean 409 at the route boundary (not a 500).
     */
    fun active(): ProjectRuntime = runtimes[activeProjectId()]
        ?: throw com.tneff.cyppieagents.routing.ProjectNotRunnableException(
            "no live runtime for the active project '${activeProjectId()}'",
        )

    /** Count of live runtimes — the accounting the CYP-247.4 LRU cap K reads. */
    fun liveCount(): Int = runtimes.size
}
