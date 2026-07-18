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
 * CYP-629 §1/§8 — the gate is TRANSPARENT when the hub is **configured** (`apiKeySet` AND the repo is **set**); while
 * the status is not yet loaded it is LOADING (fail-closed — an unknown status is never treated as done); otherwise
 * ACTIVE. Unknown ≠ configured, so nobody is passed silently into a hub that isn't set up.
 *
 * **B1 (CYP-629 live-wiring): configured, NOT `CLONED_OK`.** There is no backend clone-status source (CYP-684 is the
 * follow-up that would add one), so the client NEVER produces `CLONED_OK` — gating TRANSPARENT on it would hang the
 * wizard forever. "Repo set" ⟺ `cloneStatus != NOT_CONFIGURED` (an unset repo derives to `NOT_CONFIGURED`, a set one
 * to `CONFIGURED_NEVER_CLONED`). The clone is best-effort (CYP-639 boots degraded-tolerant); its lifecycle drives
 * Inc3's display only, and — since the client never fabricates `CLONED_OK`/`CLONE_FAILED` — never claims success.
 */
fun firstRunGateMode(status: FirstRunConfigStatus): FirstRunGateMode = when {
    !status.loaded -> FirstRunGateMode.LOADING
    status.apiKeySet && status.cloneStatus != CloneStatus.NOT_CONFIGURED -> FirstRunGateMode.TRANSPARENT
    else -> FirstRunGateMode.ACTIVE
}

/** CYP-629 — the setup steps (the completion surface is derived, not a step the user acts in first). */
enum class FirstRunStep { API_KEY, REPO, TEAM }

/**
 * CYP-629 §6.3b — the first not-yet-done step, so a resumed/returning operator lands on what's open (not always
 * step 1): API_KEY while no key, else REPO while the repo isn't **set** (`NOT_CONFIGURED`), else TEAM (both core
 * prerequisites met; team is optional). B1: "set", NOT "cloned" — the clone is best-effort and never a prerequisite
 * (there is no `CLONED_OK` source), so a set-but-uncloned repo lands on TEAM, matching [firstRunGateMode]'s TRANSPARENT.
 * Pure so the stepper's initial focus is testable without a render.
 */
fun firstRunOpenStep(status: FirstRunConfigStatus): FirstRunStep = when {
    !status.apiKeySet -> FirstRunStep.API_KEY
    status.cloneStatus == CloneStatus.NOT_CONFIGURED -> FirstRunStep.REPO
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
 * CYP-629 §7.3 — should the poll KEEP polling? Only while a clone is **actively** cloning ([CloneStatus.CLONING]).
 *
 * **B1 (CYP-629 live-wiring): `CLONING` ONLY, not `CONFIGURED_NEVER_CLONED`.** With no backend clone-status source
 * (CYP-684 follow-up), a set-but-uncloned repo derives to `CONFIGURED_NEVER_CLONED` **statically** — nothing ever
 * moves it to a terminal value, so including it here would spin the poll **forever**. `CLONING` is only ever produced
 * by a REAL backend source (B2), where it genuinely transitions to terminal — so this is forward-compatible: dormant
 * under B1 (never `CLONING` → no poll), live under B2 (backend drives `CLONING` → the §7.3 poll runs). It stops the
 * instant the status reaches a TERMINAL value ([isTerminalCloneStatus]) — the ONLY stop-path (no timeout/counter,
 * ux-spec §4.4). `NOT_CONFIGURED`/`CONFIGURED_NEVER_CLONED` are not "in progress", so the poll never spins on them.
 */
fun isCloneInProgress(status: CloneStatus): Boolean =
    status == CloneStatus.CLONING

/**
 * CYP-629 §6.2/§6.3a — the honest degraded-workspace state after a skip, derived from the SAME config status (no
 * separate flag to drift). Visible exactly while the gate would be ACTIVE ([firstRunGateMode] == ACTIVE): unknown
 * (LOADING) shows nothing (we don't assert "unconfigured" when unsure), and configured (TRANSPARENT) shows nothing
 * (the banner clears itself the instant the hub is **configured** — key set + repo set — no lingering nag).
 *
 * SPECIFIC, not generic (§6.3a): it names exactly what is open, via the reused step labels — [missingApiKey] and/or
 * [missingRepo]. **B1: "repo missing" = the repo is not SET (`NOT_CONFIGURED`)**, not "not cloned" — a set repo makes
 * the hub configured (TRANSPARENT), so a visible/ACTIVE banner with a set repo means only the KEY is open. The one
 * edge a generic message would LIE about (CYP-639 confusion): a [cloneFailed] repo is *set*, not missing → it carries
 * the clone-error copy, [missingRepo] false. (Under B1 there is no clone-status source, so `CLONE_FAILED` never
 * occurs and that branch is dormant — kept honest for the CYP-684/B2 backend field.)
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
    // A set-but-failed repo is not "missing" — it carries its own clone-error copy (dormant under B1, no source).
    val cloneFailed = active && status.cloneStatus == CloneStatus.CLONE_FAILED
    return WorkspaceUnconfiguredState(
        visible = active,
        missingApiKey = active && !status.apiKeySet,
        // B1: repo "missing" ⟺ NOT SET (NOT_CONFIGURED). A set-but-uncloned repo is configured, never "missing".
        missingRepo = active && status.cloneStatus == CloneStatus.NOT_CONFIGURED,
        cloneFailed = cloneFailed,
        cloneReason = if (cloneFailed) status.cloneReason else null,
    )
}
