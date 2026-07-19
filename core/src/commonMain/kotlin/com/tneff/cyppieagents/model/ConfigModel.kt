package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * Project-settings wire contract (S15 / CYP-96 — PROJECT-SETTINGS §2). One definition compiled into
 * `:server` (the config endpoints) and `:app:shared` (the Settings UI, CYP-84/85) via `:core`, so the
 * shape can never drift.
 *
 * **Security by structure (Reviewer):** the API key is **write-only** on the wire. [ApiKeyRequest]
 * carries the plaintext key INBOUND (PUT only); [ApiKeyView] is the OUTBOUND view and carries **only**
 * `set` + `masked` (`***<last4>`) — there is no field that could ever transport the plaintext key back
 * to a client. The repo URL is not a secret and round-trips in full.
 */

/**
 * CYP-736 — the guided-first-run repo clone lifecycle on the wire (`GET /api/config/repo`). The values MIRROR
 * the CMP-side CYP-629 `com.tneff.cyppieagents.firstrun.CloneStatus` VERBATIM (that enum is the client DISPLAY
 * axis; a follow-up typealiases it onto THIS `:core` type so the two single-source). This `:core` type is the
 * WIRE AUTHORITY the server stamps and every client decodes.
 *
 * Honesty (CYP-629 §1): a client that cannot yet know the status must NOT read `CLONED_OK` — an absent
 * [RepoConfigView.cloneStatus] (`null`, i.e. not-yet-known / a pre-CYP-736 server) decodes to
 * `CONFIGURED_NEVER_CLONED` (if the repo is configured) else `NOT_CONFIGURED`, NEVER `CLONED_OK`.
 */
@Serializable
enum class CloneStatus { NOT_CONFIGURED, CONFIGURED_NEVER_CLONED, CLONING, CLONE_FAILED, CLONED_OK }

/**
 * CYP-736 — why a [CloneStatus.CLONE_FAILED] (mirrors the CYP-629 CMP `CloneFailReason`). The server GUARANTEES
 * a non-null reason WHENEVER `cloneStatus == CLONE_FAILED` — an unclassifiable failure is [UNKNOWN] (honest),
 * never a guessed `AUTH`/`URL_UNREACHABLE`.
 */
@Serializable
enum class CloneFailReason { URL_UNREACHABLE, AUTH, UNKNOWN }

/** GET/PUT /api/config/repo response: the active project's repo, or `configured=false` when unset. */
@Serializable
data class RepoConfigView(
    val configured: Boolean,
    val url: String? = null,
    val branch: String? = null,
    /**
     * CYP-247 S2 — `true` when a repo change is pending re-provision: the config now points at a new
     * repo/branch, but the live clone still tracks the old one until the project's agents are (re)started
     * (the clone is torn down + re-cloned). EFFECT_DEFERRED (analog CYP-310) — the UI shows a
     * "takes effect on next restart" hint. Additive (default false); the field is set by the config route.
     */
    val reprovisionPending: Boolean = false,
    /**
     * CYP-736 — the guided-first-run clone lifecycle. `null` = UNKNOWN / not-yet-known (repo unconfigured, or
     * a pre-CYP-736 server): the client's `decodeCloneStatus` derives `CONFIGURED_NEVER_CLONED` (if [configured])
     * else `NOT_CONFIGURED` — NEVER a false `CLONED_OK`. **Server INVARIANT (③): non-null ONLY when
     * [configured] == true** (enforced at the config-route wire boundary + tested), so `configured=false`+`CLONED_OK`
     * can never appear on the wire — the client's contradiction-guard backstops a promise, not a primary defense.
     */
    val cloneStatus: CloneStatus? = null,
    /** CYP-736 — the failure reason, non-null WHENEVER [cloneStatus] == [CloneStatus.CLONE_FAILED] (②), else null. */
    val cloneFailReason: CloneFailReason? = null,
)

/** PUT /api/config/repo body. */
@Serializable
data class RepoConfigRequest(
    val url: String,
    val branch: String = "main",
    /**
     * CYP-247 S2 / D5 — the explicit opt-in to DISCARD uncommitted/unpushed `agent/<name>` work when the
     * repo change re-provisions (tears down the old clone). Default `false` → the §2d work-guard BLOCKS the
     * re-provision if any agent has a dirty tree or unpushed commits (no silent loss). `true` = the operator
     * accepts the loss (they have pushed / don't need it) and the re-provision proceeds regardless.
     */
    val discardUnpushed: Boolean = false,
)

/**
 * CYP-466 — one agent whose worktree holds work a repo re-provision would DESTROY: [uncommitted] (a dirty tree)
 * and/or [unpushed] (commits on `agent/<worktree>` not on any remote). [worktree] is the worktree/agent name.
 * Typed (not a display string) so the discard-confirm renders "these agents lose X" from structured flags.
 */
@Serializable
data class AtRiskAgent(
    val worktree: String,
    val uncommitted: Boolean,
    val unpushed: Boolean,
)

/** Human string for logs (`"<name> (uncommitted changes + unpushed commits)"`) — the display the pre-CYP-466
 *  `unpushedWork()` returned, kept as a rendering of the typed [AtRiskAgent] so logs and the API single-source. */
fun AtRiskAgent.display(): String {
    val reasons = buildList {
        if (uncommitted) add("uncommitted changes")
        if (unpushed) add("unpushed commits")
    }
    return "$worktree (${reasons.joinToString(" + ")})"
}

/**
 * CYP-466 — `GET /api/config/repo/reprovision-preview`: the HONEST discard-confirm feed ("confirm the loss you
 * SEE"). [reprovisionPending] mirrors [RepoConfigView.reprovisionPending] (a repo change is staged, awaiting the
 * next (re)start's teardown); [atRisk] is the LIVE per-agent losable work — computed on demand from the SAME
 * `WorktreeManager.unpushedWork()` the re-provision block decision uses, so what the operator confirms is EXACTLY
 * what would (or wouldn't) block. Empty [atRisk] = a re-provision would destroy nothing (safe to discard/restart).
 */
@Serializable
data class ReprovisionPreview(
    val reprovisionPending: Boolean,
    val atRisk: List<AtRiskAgent>,
)

/**
 * GET /api/config/apikey response — the ONLY outbound shape for the key. `set` = a key is stored;
 * `masked` = `***<last4>` (or null when unset). The plaintext key is never present here.
 */
@Serializable
data class ApiKeyView(
    val set: Boolean,
    val masked: String? = null,
)

/** PUT /api/config/apikey body (write-only inbound). */
@Serializable
data class ApiKeyRequest(
    val apiKey: String,
)
