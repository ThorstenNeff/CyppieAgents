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
) {
    private val log = LoggerFactory.getLogger("boot.projectdeleter")
    private val mutex = Mutex()

    suspend fun delete(projectId: String, deleteWorktrees: Boolean = false): ProjectDeleteReceipt = mutex.withLock {
        registry.requireDeletable(projectId) // fail-closed: throws here → no teardown below runs

        val configRemoved = projectConfig.remove(projectId)
        val eventsRemoved = eventSink.deleteByProject(projectId)
        // opt-in: only the warned path removes the worktree (uncommitted work); branches always kept.
        val worktreesRemoved = if (deleteWorktrees) worktrees.deleteProject(projectId) else 0
        registry.drop(projectId) // commit metadata removal last (no half-gone-but-listed project)

        log.info(
            "project '{}' cascade-deleted: config={}, events={}, deleteWorktrees={}, worktrees={}",
            projectId, configRemoved, eventsRemoved, deleteWorktrees, worktreesRemoved,
        )
        ProjectDeleteReceipt(projectId, configRemoved, eventsRemoved, worktreesRemoved)
    }
}
