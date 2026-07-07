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
 * ids, CYP-167). CYP-256 (.5b): when [evictRuntime] is wired, the suspend is followed by a **FULL runtime
 * eviction** — the runtime OBJECT is dropped (memory reclaimed), now safe because `.5a`'s durable
 * ProjectAgentStore survives the drop (re-entry re-mints via `getOrCreate` + rehydrates the agent set +
 * `--resume`s). With [evictRuntime] `null` it is `.4b` session-suspension only (object kept). The client-facing
 * contract is unchanged either way: a SUSPENDED project's processes are off + resumable; re-entry = reconnect
 * + `--resume` (CYP-198/204) — full-eviction is a transparent memory optimization, not a new client state.
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
    /**
     * CYP-256 (.5b) — FULL runtime eviction after a suspend: drop the runtime OBJECT (compare-and-remove) once
     * its sessions are stopped, reclaiming its memory. `null` = `.4b` session-suspension only (object kept).
     * Called under [lock], guarded by "still suspended" (a fast re-activation that removed the project from
     * [suspended] first → the invoke is skipped, so a re-minted runtime is never dropped).
     */
    private val evictRuntime: ((projectId: String) -> Unit)? = null,
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
                // CYP-256 (.5b): and — still under the SAME lock + guard — fully EVICT the runtime object (its
                // sessions are now stopped; its config survives in the durable store). The "still suspended"
                // guard is the race gate: a re-activation that already removed `victim` from `suspended` skips
                // the evict, so its freshly re-minted runtime is never dropped. Belt-and-suspenders: the evict
                // itself is a compare-and-remove ([RuntimeRegistry.evict]) — it only drops the EXACT suspended
                // instance, so even a re-mint that slipped in is spared. Residual (out of MVP scope): a SECOND
                // concurrent switch INTO `victim` between its getOrCreate and its onActivated could still see the
                // instance evicted mid-switch → a transient 409 (ProjectNotRunnableException) that self-heals on
                // retry (getOrCreate re-mints). Unreachable under sequential operator switches; a fuller fix
                // (mark-activating before getOrCreate) is deferred until concurrent multi-switch is in scope.
                synchronized(lock) {
                    if (victim in suspended) {
                        suspended[victim] = stopped
                        evictRuntime?.invoke(victim)
                    }
                }
            }
        }
    }

    /** Test/telemetry: the projects currently live (background or hot), LRU-first. */
    fun liveProjects(): List<String> = synchronized(lock) { live.toList() }

    /** Test/telemetry: the projects currently suspended. */
    fun suspendedProjects(): Set<String> = synchronized(lock) { suspended.keys.toSet() }
}
