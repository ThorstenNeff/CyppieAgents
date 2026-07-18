package com.tneff.cyppieagents.firstrun

/**
 * CYP-629 §7.1 — the repo clone lifecycle, mirrored **client-side** while the Backend seam is pending (built
 * against a stub, real-swapped when `GET /api/config/repo` gains the field). The five states the UX distinguishes
 * (ux-spec §4.2/§8): repo not set · set-but-never-cloned · cloning · clone failed · cloned OK.
 */
enum class CloneStatus { NOT_CONFIGURED, CONFIGURED_NEVER_CLONED, CLONING, CLONE_FAILED, CLONED_OK }

/** CYP-629 §7.1 — why a [CloneStatus.CLONE_FAILED], selecting the actionable copy (ux-spec §4.2). */
enum class CloneFailReason { URL_UNREACHABLE, AUTH, UNKNOWN }

/**
 * CYP-629 — the derived first-run config status the [FirstRunGate] consumes. `apiKeySet` comes from
 * `ConfigRepository.getApiKey().set`; `cloneStatus`/`cloneReason` from the §7 seam (stubbed until Backend lands).
 * `loaded == false` means the status is NOT yet known (load failed / still pending / old server) → the gate must
 * fail closed: it renders the load/retry state and NEVER "done" (unknown ≠ configured, ux-spec §1).
 */
data class FirstRunConfigStatus(
    val loaded: Boolean,
    val apiKeySet: Boolean,
    val cloneStatus: CloneStatus,
    val cloneReason: CloneFailReason? = null,
) {
    companion object {
        /** The fail-closed "nothing known yet" seed — gate LOADING, never TRANSPARENT. */
        val Unknown = FirstRunConfigStatus(loaded = false, apiKeySet = false, cloneStatus = CloneStatus.NOT_CONFIGURED)
    }
}

/** CYP-629 §1 — the gate's top-level mode, derived purely from the config status. */
enum class FirstRunGateMode {
    /** Status not yet known → show load/retry (fail-closed: never pass through on unknown). */
    LOADING,
    /** Unconfigured or partially configured → show the setup steps. */
    ACTIVE,
    /** Both done (key set AND repo CLONED_OK) → gate is transparent, straight to the workspace. */
    TRANSPARENT,
}

/**
 * CYP-629 §1/§8 — the gate is TRANSPARENT only when `apiKeySet` AND repo `CLONED_OK`; while the status is not yet
 * loaded it is LOADING (fail-closed — an unknown status is never treated as done); otherwise ACTIVE. This is the
 * single honesty core: unknown ≠ configured, so nobody is passed silently into a hub that can't start agents.
 */
fun firstRunGateMode(status: FirstRunConfigStatus): FirstRunGateMode = when {
    !status.loaded -> FirstRunGateMode.LOADING
    status.apiKeySet && status.cloneStatus == CloneStatus.CLONED_OK -> FirstRunGateMode.TRANSPARENT
    else -> FirstRunGateMode.ACTIVE
}

/** CYP-629 — the setup steps (the completion surface is derived, not a step the user acts in first). */
enum class FirstRunStep { API_KEY, REPO, TEAM }

/**
 * CYP-629 §6.3b — the first not-yet-done step, so a resumed/returning operator lands on what's open (not always
 * step 1): API_KEY while no key, else REPO while the repo isn't CLONED_OK, else TEAM (both core prerequisites met;
 * team is optional). Pure so the stepper's initial focus is testable without a render.
 */
fun firstRunOpenStep(status: FirstRunConfigStatus): FirstRunStep = when {
    !status.apiKeySet -> FirstRunStep.API_KEY
    status.cloneStatus != CloneStatus.CLONED_OK -> FirstRunStep.REPO
    else -> FirstRunStep.TEAM
}

/**
 * CYP-629 §4.2 — the repo step's clone display state (collapsing the raw [CloneStatus] to the three UX slots +
 * the failure reason). `CONFIGURED_NEVER_CLONED`/`CLONING` and any not-yet-terminal/unknown value render CLONING
 * (fail-closed: never "ok" while unsure); `CLONED_OK` → CLONED_OK; `CLONE_FAILED` → FAILED (carrying the reason
 * for the actionable copy). `NOT_CONFIGURED` has no clone display — the repo field isn't set yet (returns null).
 */
enum class CloneDisplay { CLONING, CLONED_OK, FAILED }

fun cloneDisplay(status: CloneStatus): CloneDisplay? = when (status) {
    CloneStatus.NOT_CONFIGURED -> null
    CloneStatus.CLONED_OK -> CloneDisplay.CLONED_OK
    CloneStatus.CLONE_FAILED -> CloneDisplay.FAILED
    CloneStatus.CONFIGURED_NEVER_CLONED, CloneStatus.CLONING -> CloneDisplay.CLONING
}

/**
 * CYP-629 §7.3 — a poll of `cloneStatus` may stop once it reaches a TERMINAL state: `CLONED_OK` (done) or
 * `CLONE_FAILED` (actionable, correctable via re-save). `CLONING`/`CONFIGURED_NEVER_CLONED` are in-progress; a
 * long clone is NOT a failure (ux-spec §4.4 — the client never invents `CLONE_FAILED` from a timeout).
 */
fun isTerminalCloneStatus(status: CloneStatus): Boolean =
    status == CloneStatus.CLONED_OK || status == CloneStatus.CLONE_FAILED

/**
 * CYP-629 §7.3 — should the poll KEEP polling? Only while a clone is actively in progress
 * ([CloneStatus.CONFIGURED_NEVER_CLONED] just set → about to clone, or [CloneStatus.CLONING]). It stops the instant
 * the status reaches a TERMINAL value ([isTerminalCloneStatus]) — that is the ONLY stop-path (no timeout, no
 * attempt-counter: a long clone is not a failure, ux-spec §4.4). `NOT_CONFIGURED` (no repo yet) is not in progress,
 * so the poll never spins on it.
 */
fun isCloneInProgress(status: CloneStatus): Boolean =
    status == CloneStatus.CONFIGURED_NEVER_CLONED || status == CloneStatus.CLONING

/**
 * CYP-629 §6.2/§6.3a — the honest degraded-workspace state after a skip, derived from the SAME config status (no
 * separate flag to drift). Visible exactly while the gate would be ACTIVE ([firstRunGateMode] == ACTIVE): unknown
 * (LOADING) shows nothing (we don't assert "unconfigured" when unsure), and done (TRANSPARENT) shows nothing (the
 * banner clears itself the instant key + `CLONED_OK` land — no lingering nag).
 *
 * SPECIFIC, not generic (§6.3a): it names exactly what is open, via the reused step labels — [missingApiKey] and/or
 * [missingRepo]. The one edge that a generic message would LIE about (the CYP-639 confusion §7 closes): a repo whose
 * clone FAILED is *set*, not missing — so [cloneFailed] carries the clone-error copy, and [missingRepo] is then
 * false. Precedence: a set-but-failed repo reads "clone failed", never "repository missing".
 */
data class WorkspaceUnconfiguredState(
    val visible: Boolean,
    val missingApiKey: Boolean,
    val missingRepo: Boolean,
    val cloneFailed: Boolean,
    val cloneReason: CloneFailReason?,
)

fun workspaceUnconfigured(status: FirstRunConfigStatus): WorkspaceUnconfiguredState {
    val active = firstRunGateMode(status) == FirstRunGateMode.ACTIVE
    // A set-but-failed repo is not "missing" — it carries its own clone-error copy (never "repository missing").
    val cloneFailed = active && status.cloneStatus == CloneStatus.CLONE_FAILED
    return WorkspaceUnconfiguredState(
        visible = active,
        missingApiKey = active && !status.apiKeySet,
        missingRepo = active && status.cloneStatus != CloneStatus.CLONED_OK && !cloneFailed,
        cloneFailed = cloneFailed,
        cloneReason = if (cloneFailed) status.cloneReason else null,
    )
}
