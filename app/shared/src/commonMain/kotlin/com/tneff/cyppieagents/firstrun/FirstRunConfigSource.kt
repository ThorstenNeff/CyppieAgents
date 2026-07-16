package com.tneff.cyppieagents.firstrun

/**
 * CYP-629 §7.3 — the port the [FirstRunGate] observes for its config status. The real implementation composes
 * `ConfigRepository.getApiKey().set` + `getRepo()` + the §7 `cloneStatus` seam (polled to a terminal state); until
 * Backend builds that seam the gate is wired against [StubFirstRunConfigSource] — a mechanical stub→real swap with
 * no UI change (same pattern as `ConfigRepository` itself was stubbed before CYP-96).
 */
interface FirstRunConfigSource {
    /** The current derived status; [FirstRunConfigStatus.loaded] == false while unknown (fail-closed). */
    suspend fun status(): FirstRunConfigStatus
}

/**
 * CYP-629 §7.1 fail-closed decode: an absent/null `cloneStatus` (old server, or the field not yet built) decodes
 * to [CloneStatus.CONFIGURED_NEVER_CLONED] when the repo IS configured, else [CloneStatus.NOT_CONFIGURED] — NEVER
 * [CloneStatus.CLONED_OK]. Unknown ≠ ok: the gate then renders "cloning/unknown", never "done".
 */
fun decodeCloneStatus(raw: CloneStatus?, repoConfigured: Boolean): CloneStatus =
    raw ?: if (repoConfigured) CloneStatus.CONFIGURED_NEVER_CLONED else CloneStatus.NOT_CONFIGURED

/** Deterministic stub for building/testing the gate shell before the §7 seam + poll wiring exist. */
class StubFirstRunConfigSource(private val fixed: FirstRunConfigStatus) : FirstRunConfigSource {
    override suspend fun status(): FirstRunConfigStatus = fixed
}
