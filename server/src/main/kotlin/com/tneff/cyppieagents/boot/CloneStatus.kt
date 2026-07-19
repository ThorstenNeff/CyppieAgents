package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.CloneFailReason
import com.tneff.cyppieagents.model.CloneStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-736 — a project's live clone status: the [CloneStatus] plus the [reason] when (and only when) the status
 * is [CloneStatus.CLONE_FAILED]. Read by the `GET /api/config/repo` route, written by [WorktreeManager.ensureClone].
 */
data class CloneState(val status: CloneStatus, val reason: CloneFailReason? = null)

/**
 * CYP-736 — the read+write clone-status seam, keyed by projectId. **In-memory is correct** here: the clone
 * lifecycle is EPHEMERAL / per-boot — a restart RE-clones, so a status must not (and does not) survive it. There
 * is no durable-store variant to build (unlike the CYP-705 read cursor, which IS user state that must persist).
 */
interface CloneStatusStore {
    fun get(projectId: String): CloneState?

    /** Record [projectId]'s clone status. Enforces the ② invariant: [reason] is retained ONLY on
     *  [CloneStatus.CLONE_FAILED] (any other status is stored reason-less, so a stale reason can't cling). */
    fun report(projectId: String, status: CloneStatus, reason: CloneFailReason? = null)
}

class InMemoryCloneStatusStore : CloneStatusStore {
    private val states = ConcurrentHashMap<String, CloneState>()

    override fun get(projectId: String): CloneState? = states[projectId]

    override fun report(projectId: String, status: CloneStatus, reason: CloneFailReason?) {
        // ② invariant: a reason clings ONLY to CLONE_FAILED. The failure path always passes a non-null reason
        // (classifyCloneFailure → …, UNKNOWN if unclassifiable), so CLONE_FAILED is never reason-less; every other
        // status is stored reason-less regardless of what was passed.
        states[projectId] = CloneState(status, if (status == CloneStatus.CLONE_FAILED) reason else null)
    }
}

/**
 * CYP-736 ③-invariant, single-sourced: the clone status the wire emits is non-null ONLY when the repo is
 * [configured]. So `configured=false`+`CLONED_OK` can never appear on the wire — the config route calls THIS at
 * the boundary, and the client's contradiction-guard backstops a promise instead of being the primary defense.
 */
fun cloneStatusForWire(configured: Boolean, state: CloneState?): CloneState? = if (configured) state else null

/**
 * CYP-736 — classify a `git clone` failure from the merged stdout+stderr. The ONE place output→reason
 * (single-sourced so the precedence can't drift). Heuristic + fail-honest: AUTH signatures are matched FIRST
 * (most specific), then the URL/host/reachability signatures, else [CloneFailReason.UNKNOWN] — never a guessed
 * AUTH/URL. Note the ambiguous `Could not read from remote repository` line prints in BOTH failure classes, so
 * it is deliberately NOT a signal on its own; the specific lines decide, and its bare presence falls to UNKNOWN.
 * Pinned by named fixtures (`CloneStatusTest`) so the signature set + AUTH-first order can't silently change.
 */
fun classifyCloneFailure(output: String): CloneFailReason {
    val o = output.lowercase()
    val authSignals = listOf(
        "authentication failed",
        "could not read username",
        "could not read password",
        "permission denied (publickey)",
        "terminal prompts disabled",
        "invalid username or password",
        "support for password authentication was removed",
        "returned error: 403",
        "returned error: 401",
    )
    val urlSignals = listOf(
        "could not resolve host",
        "could not resolve proxy",
        "repository not found",
        "does not appear to be a git repository",
        "connection refused",
        "connection timed out",
        "name or service not known",
        "unable to connect to",
    )
    return when {
        authSignals.any { it in o } -> CloneFailReason.AUTH
        urlSignals.any { it in o } -> CloneFailReason.URL_UNREACHABLE
        else -> CloneFailReason.UNKNOWN
    }
}
