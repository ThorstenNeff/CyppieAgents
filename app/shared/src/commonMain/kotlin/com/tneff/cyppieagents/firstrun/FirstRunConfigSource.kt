package com.tneff.cyppieagents.firstrun

import com.tneff.cyppieagents.settings.ConfigRepository
import com.tneff.cyppieagents.settings.RepoConfigState

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

/**
 * CYP-629 live-wiring (B1) — the REAL source over the existing config REST ([ConfigRepository] =
 * `/api/config/apikey` + `/api/config/repo`): `apiKeySet` from `getApiKey().set`, repo-configured from `getRepo()`.
 *
 * **B1: no backend clone-status field** (CYP-684 is the follow-up that adds one), so `cloneStatus` is decoded from
 * repo-configured via [decodeCloneStatus] (`raw = null`) → `NOT_CONFIGURED` / `CONFIGURED_NEVER_CLONED`, **NEVER
 * `CLONED_OK`**. Combined with [firstRunGateMode] gating TRANSPARENT on *configured* (not `CLONED_OK`), this makes the
 * "no fabricated clone success" invariant structural: the client cannot produce `CLONED_OK`, so nothing can render it.
 *
 * A network/decoding failure THROWS (from the underlying `ConfigRepository`), and [FirstRunViewModel.reload] catches
 * it → the status stays `loaded = false` (LOADING, fail-closed — unknown ≠ configured, §1). When CYP-684 lands, pass
 * the real field as `raw` here — no gate change (the same stub→real swap `ConfigRepository` itself used, CYP-96).
 */
class ConfigRepositoryFirstRunConfigSource(private val config: ConfigRepository) : FirstRunConfigSource {
    override suspend fun status(): FirstRunConfigStatus {
        val apiKey = config.getApiKey()
        val repoConfigured = config.getRepo() is RepoConfigState.Configured
        return FirstRunConfigStatus(
            loaded = true,
            apiKeySet = apiKey.set,
            cloneStatus = decodeCloneStatus(raw = null, repoConfigured = repoConfigured),
        )
    }
}
