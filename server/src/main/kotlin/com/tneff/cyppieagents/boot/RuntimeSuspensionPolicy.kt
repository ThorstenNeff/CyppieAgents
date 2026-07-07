package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-255 (.4b) / CYP-247.4 — the ratified teardown: **background-live with a HARD LRU cap [cap] on the
 * projects whose agent SESSIONS run** (the expensive `claude` processes). Beyond [cap], the least-recently-
 * hot BACKGROUND project is **suspended** — its agents' sessions are killed ([suspendProject]); on re-entry
 * (the project is activated again) it is **resumed** ([resumeProject], `--resume` via the persisted session
 * ids, CYP-167). It is **session-suspension, NOT full runtime eviction**: the cheap runtime object stays in
 * memory (a full drop would lose the non-boot project's in-memory-only agent config — that reclaim is
 * deferred to per-project persistence, CYP-247.5 / CYP-220). The client-facing contract is unchanged: a
 * SUSPENDED project's processes are off + resumable; re-entry = reconnect + `--resume` (CYP-198/204).
 *
 * This class owns ONLY the LRU + cap decision (pure, lock-guarded, deterministically testable with injected
 * actions); the actual stop/spawn is delegated to [suspendProject] / [resumeProject], which run on [scope]
 * (they kill/spawn processes — never inline on the switch response). Cap ≤ 0 disables suspension (unbounded
 * background-live) — the default when no policy is desired.
 */
class RuntimeSuspensionPolicy(
    val cap: Int,
    private val scope: CoroutineScope,
    /** Suspend a project: stop all its RUNNING agents; returns the agent ids stopped (to resume later). */
    private val suspendProject: suspend (projectId: String) -> Set<String>,
    /** Resume a project: (re)start exactly the agents that were suspended (connector `--resume`s them). */
    private val resumeProject: suspend (projectId: String, agentIds: Set<String>) -> Unit,
) {
    private val log = LoggerFactory.getLogger("boot.suspension")
    private val lock = Any()

    // Projects with LIVE sessions, LRU-ordered (least-recently-hot first, most-recent last). The active
    // project is always the last entry after [onActivated]. A LinkedHashSet gives O(1) move-to-end via
    // remove+add and preserves insertion order for the eviction pick.
    private val live = LinkedHashSet<String>()

    // Suspended projects → the agent ids stopped when they were suspended (so re-entry resumes exactly those,
    // not any the operator had already stopped). Presence in this map == RuntimeState.SUSPENDED.
    private val suspended = HashMap<String, Set<String>>()

    /**
     * Report a project's runtime state for `GET /api/projects` (server-derived, never persisted). [activeId]
     * is the live active pointer. A **never-activated** project (never in [live], never [suspended]) reads
     * **HOT** — the no-indicator fail-safe (UIUX/PO ratified): BACKGROUND/SUSPENDED mean "was live", which a
     * fresh project never was, so it must not show a stale background/suspended badge.
     */
    fun stateOf(projectId: String, activeId: String): RuntimeState = synchronized(lock) {
        when {
            projectId == activeId -> RuntimeState.HOT
            projectId in suspended -> RuntimeState.SUSPENDED
            projectId in live -> RuntimeState.BACKGROUND
            else -> RuntimeState.HOT // never-activated → no indicator (fail-safe, not a "was-live" state)
        }
    }

    /**
     * Called AFTER a project becomes active (boot seeds the boot project; the switch calls it for the target).
     * Resumes it if it was suspended, marks it most-recently-hot, then — if the live set now exceeds [cap] —
     * suspends the least-recently-hot OTHER project. The stop/spawn run async on [scope]; the bookkeeping is
     * synchronous + atomic, so [stateOf] is consistent the instant this returns.
     */
    fun onActivated(projectId: String) {
        if (cap <= 0) { // suspension disabled → pure background-live, still track for stateOf
            synchronized(lock) { live.remove(projectId); live.add(projectId); suspended.remove(projectId) }
            return
        }
        val resumeAgents: Set<String>?
        val victim: String?
        synchronized(lock) {
            resumeAgents = suspended.remove(projectId) // was it suspended? resume exactly its stopped agents
            live.remove(projectId); live.add(projectId) // most-recently-hot
            victim = if (live.size > cap) live.firstOrNull { it != projectId } else null
            if (victim != null) {
                live.remove(victim)
                suspended[victim] = emptySet() // provisional → SUSPENDED now; filled with the real set below
            }
        }
        if (resumeAgents != null) {
            log.info("resuming project '{}' ({} agent(s)) on re-entry", projectId, resumeAgents.size)
            scope.launch { runCatching { resumeProject(projectId, resumeAgents) } }
        }
        if (victim != null) {
            log.info("suspending project '{}' (LRU beyond cap K={})", victim, cap)
            scope.launch {
                val stopped = runCatching { suspendProject(victim) }.getOrDefault(emptySet())
                // Only record the real stopped-set if the project is still suspended (a fast re-activation
                // could have resumed it meanwhile — then leave it resumed, don't resurrect the suspended entry).
                synchronized(lock) { if (victim in suspended) suspended[victim] = stopped }
            }
        }
    }

    /** Test/telemetry: the projects currently live (background or hot), LRU-first. */
    fun liveProjects(): List<String> = synchronized(lock) { live.toList() }

    /** Test/telemetry: the projects currently suspended. */
    fun suspendedProjects(): Set<String> = synchronized(lock) { suspended.keys.toSet() }
}
