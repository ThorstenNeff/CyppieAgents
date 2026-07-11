package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.HubState

/**
 * CYP-410 (S-A) — the **single source** for the project-switch orchestration. Before this, the exact same
 * sequence was written twice: inline in `PlatformWiring.installPlatform`'s `onActiveSwitch` lambda (the runtime
 * switch) and again in `BootOrchestrator.boot()` (the boot-time view switch). Two copies of a lifecycle sequence
 * are two copies that can drift; this class holds it once so the `SessionManager` seam (and the Phase-2
 * Control-Plane transport behind it) and the boot path both drive the identical steps.
 *
 * **Behavior-preserving by construction (this is a pure refactor):** the common non-suspend body — mint the
 * target runtime ([prepare]) then flip the active view + rehydrate + mark HOT ([activate]) — is shared. The ONLY
 * difference between the two call sites is the OUTGOING-project drain, which the runtime switch does (attribute
 * any in-flight ResultEvent under `active()==outgoing` before the flip, cap==1) and the boot switch does not
 * (nothing meaningful to drain: `rescope` stashes the just-seeded config agents). That single genuine difference
 * is [switch] vs [switchAtBoot] — same calls, same order as before, no reordering.
 */
class ProjectSwitcher(
    private val runtimeRegistry: RuntimeRegistry,
    private val projectRuntimeFactory: ProjectRuntimeFactory,
    private val state: HubState,
    private val suspensionPolicy: RuntimeSuspensionPolicy,
    private val rehydrateActiveProject: () -> Unit,
    /** CYP-247 S3 — drain (stop, awaited) a project's running sessions. Suspends, so only [switch] uses it. */
    private val drainProject: suspend (projectId: String) -> Unit,
) {
    /** Mint the target project's runtime BEFORE it becomes active (so a switched-to project has its lifecycle
     *  before any spawn). Idempotent — an existing runtime is a no-op. */
    private fun prepare(pid: String) {
        runtimeRegistry.getOrCreate(pid, projectRuntimeFactory)
    }

    /** Flip the active view, rehydrate the target's durable agents, mark it HOT (LRU K cap). The common tail. */
    private fun activate(pid: String) {
        state.rescope(pid)
        rehydrateActiveProject() // CYP-256 (.5a)
        suspensionPolicy.onActivated(pid) // CYP-255 (.4b): HOT + resume + enforce K cap
    }

    /**
     * CYP-308 — the **boot-time** view switch (no outgoing drain). At boot the config agents are stashed by
     * `rescope`; there is no live outgoing project whose in-flight events need attribution, so this is exactly
     * the current `BootOrchestrator.boot()` sequence (getOrCreate → rescope → rehydrate → onActivated).
     */
    fun switchAtBoot(pid: String) {
        prepare(pid)
        activate(pid)
    }

    /**
     * CYP-91/247 — the **runtime** switch (the `onActiveSwitch` route body). Synchronously DRAINS + STOPS the
     * OUTGOING project's sessions (awaited) BEFORE the flip when tearing down (cap==1), so any in-flight
     * ResultEvent is attributed under `active()==outgoing` (correct channel + projectId stamp + tokenUsage) and
     * the reader is quiescent (`closeAndAwait` destroys then JOINS the reader, CYP-371). Only in teardown-on-switch
     * mode (cap==1, the S3 default); with cap>1 the outgoing project stays background-live.
     */
    suspend fun switch(pid: String) {
        prepare(pid)
        val outgoing = state.activeProjectId
        if (outgoing != pid && suspensionPolicy.cap == 1) drainProject(outgoing)
        activate(pid)
    }
}
