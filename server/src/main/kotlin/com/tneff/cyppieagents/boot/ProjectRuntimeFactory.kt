package com.tneff.cyppieagents.boot

/**
 * CYP-255 (.4a) — the seam that mints a fresh [ProjectRuntime] for a project on demand. In .4b this is
 * implemented over the boot's shared deps to produce DISTINCT per-runtime instances (own lifecycle /
 * sessions / config / capability / provider registries + a per-project [WorktreeManager] via
 * [WorktreeManager.forProject]), with the mediation spine (deliverer session-listener, projector caps,
 * connector persona) resolving `runtimeRegistry.active().*` so a switch re-targets it. Extracted as a
 * `fun interface` here so [RuntimeRegistry.getOrCreate] (the switch entry point) can lazily create a
 * runtime the first time a project is activated, and so tests can inject a fake minting rule.
 *
 * **Fail-closed contract:** `create` must fully wire the runtime BEFORE it is registered/returned (no
 * half-built runtime is ever visible to [RuntimeRegistry.active]) — the .4b build honors this so the
 * first spawn/worktree op in a just-activated project always finds a live, complete runtime.
 */
fun interface ProjectRuntimeFactory {
    fun create(projectId: String): ProjectRuntime
}
