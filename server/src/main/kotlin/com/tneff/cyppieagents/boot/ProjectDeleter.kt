package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.events.EventSink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** What a project cascade-delete tore down — for honest no-orphan reporting (counts only, no content). */
@Serializable
data class ProjectDeleteReceipt(
    val projectId: String,
    val configRemoved: Boolean,
    val eventsRemoved: Int,
    val worktreesRemoved: Int,
)

/**
 * The project cascade-delete (S13 / CYP-91) — the single place that tears down a project's persisted
 * resources, composing the per-partition primitives, **each strictly scoped to the target
 * `projectId`**: the [ProjectConfigStore] override, the [EventSink] event partition, and the
 * [WorktreeManager] worktree root. The registry metadata is dropped LAST so a partial teardown never
 * leaves a project that lists but is half-gone.
 *
 * Two PO-pinned invariants, enforced here:
 * - **fail-closed:** [ProjectRegistry.requireDeletable] runs FIRST (the active project and the last
 *   project are undeletable). A rejection throws and cascades NOTHING.
 * - **no-cross-project:** every primitive keys on the exact `projectId`, so deleting project A leaves
 *   project B's config + events + worktrees untouched. **no-orphan:** all partitions run, in one
 *   serialized unit ([mutex]) so two deletes can't interleave.
 *
 * Live hub channels + sessions are NOT torn down here: delete only ever targets a NON-active project,
 * whose live state doesn't exist (one hub/connector serves the active project today). Tearing down
 * live state on an active-switch is the deferred S13-design part.
 *
 * **Worktree cascade is opt-in (S13-design §4):** config + events are pure project data and are always
 * removed; the worktree dir may hold uncommitted/unpushed work, so it is torn down ONLY when the caller
 * passes `deleteWorktrees = true` (the warned UI path). Agent branches `agent/<…>` are NEVER deleted
 * regardless (commits survive).
 */
class ProjectDeleter(
    private val registry: ProjectRegistry,
    private val projectConfig: ProjectConfigStore,
    private val eventSink: EventSink,
    private val worktrees: WorktreeManager,
    // CYP-198: durable per-agent transcript store — cascade-purged with the project (fail-closed teardown).
    private val agentEventStore: com.tneff.cyppieagents.agentevents.AgentEventStore? = null,
    // CYP-215: the per-project avatar blob dir — cascade-purged with the project too.
    private val avatarBlobs: com.tneff.cyppieagents.avatar.AvatarBlobStore? = null,
    // CYP-215 (F2, closing a pre-existing CYP-210 gap): the durable name/color/persona/launch/avatar overlay —
    // its per-project entries were orphaned on cascade-delete (removeProject was defined but never wired here).
    private val agentOverrides: AgentOverrideStore? = null,
    // CYP-256 (.5a): the durable per-project agent-set — cascade-purged with the project (its runtime-added
    // agents' records must not be orphaned; rehydration would otherwise resurrect a deleted project's agents).
    private val projectAgents: ProjectAgentStore? = null,
    // CYP-325 (defect 2): the durable per-agent token-usage overlay — cascade-purged so a deleted project's
    // last-context-token values don't linger (and can't rehydrate a resurrected id).
    private val tokenUsage: TokenUsageStore? = null,
) {
    private val log = LoggerFactory.getLogger("boot.projectdeleter")
    private val mutex = Mutex()

    suspend fun delete(projectId: String, deleteWorktrees: Boolean = false): ProjectDeleteReceipt = mutex.withLock {
        registry.requireDeletable(projectId) // fail-closed: throws here → no teardown below runs

        val configRemoved = projectConfig.remove(projectId)
        val eventsRemoved = eventSink.deleteByProject(projectId)
        agentEventStore?.deleteByProject(projectId) // CYP-198: purge the agent-window transcript too
        val avatarsRemoved = avatarBlobs?.deleteByProject(projectId) ?: 0 // CYP-215: purge the avatar blobs
        val overridesRemoved = agentOverrides?.removeProject(projectId) ?: 0 // CYP-215 F2: purge the override JSON
        val agentsRemoved = projectAgents?.removeProject(projectId) ?: 0 // CYP-256 (.5a): purge the agent-set store
        tokenUsage?.removeProject(projectId) // CYP-325 (defect 2): purge the persisted token-usage values
        // opt-in: only the warned path removes the worktree (uncommitted work); branches always kept.
        val worktreesRemoved = if (deleteWorktrees) worktrees.deleteProject(projectId) else 0
        registry.drop(projectId) // commit metadata removal last (no half-gone-but-listed project)

        log.info(
            "project '{}' cascade-deleted: config={}, events={}, avatars={}, overrides={}, agents={}, deleteWorktrees={}, worktrees={}",
            projectId, configRemoved, eventsRemoved, avatarsRemoved, overridesRemoved, agentsRemoved, deleteWorktrees, worktreesRemoved,
        )
        ProjectDeleteReceipt(projectId, configRemoved, eventsRemoved, worktreesRemoved)
    }
}
