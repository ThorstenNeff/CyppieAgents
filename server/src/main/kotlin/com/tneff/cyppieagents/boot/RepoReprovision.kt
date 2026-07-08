package com.tneff.cyppieagents.boot

import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-247 S2 — the in-memory **pending-re-provision** tracker (D4). `setRepo` (the config route) marks a
 * project's clone STALE with the operator's discard opt-in (D5); the next agent (re)start for that project
 * consumes it in the ensure-worktree seam: run the §2d work-guard, then tear the old clone down + re-clone
 * from the new repo — OR, if the guard finds uncommitted/unpushed work and there is no opt-in, leave the
 * mark so the operator can push + retry (no silent loss).
 *
 * In-memory **by design** for this slice (clean-cut S2): a server restart before re-provision is the S4
 * reconciler's job (compare the live clone's remote vs `resolvedRepo`). This is NOT a store — no
 * persistence, no cross-project state; strictly keyed by projectId, fail-closed on a blank id.
 */
class RepoReprovision {
    data class Pending(val discardUnpushed: Boolean)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Mark [projectId]'s clone stale (a repo change was applied). Latest call wins — a re-PUT with
     *  `discardUnpushed=true` updates the opt-in so a blocked re-provision can then proceed. */
    fun markStale(projectId: String, discardUnpushed: Boolean) {
        if (projectId.isNotBlank()) pending[projectId] = Pending(discardUnpushed)
    }

    /** The pending re-provision for [projectId], or null when the clone is current. */
    fun pending(projectId: String): Pending? = pending[projectId]

    /** Clear the mark once the project has been re-provisioned (its clone now matches the new repo). */
    fun clear(projectId: String) { pending.remove(projectId) }
}
