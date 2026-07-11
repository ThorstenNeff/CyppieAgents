package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry

/**
 * CYP-247.1 (L) — the per-project agent **runtime**: the lifecycle / spawn machinery that CYP-246 (M) left
 * as boot-pinned, agentId-keyed singletons. Bundling them here is the seam L de-singletonizes — one
 * [ProjectRuntime] per project, selected by the active pointer ([RuntimeRegistry]), so a spawn / stop /
 * config edit touches only the active project's agents (two projects with an agent id `backend` can no
 * longer collide in a shared agentId-keyed map).
 *
 * **Scaffold (this story): exactly one runtime — the boot project's — holding the SAME instances boot has
 * always built, so behavior is unchanged.** Later stories make the members genuinely per-project
 * (CYP-247.2 `WorktreeManager`, CYP-247.3 the registries) and create / LRU-evict runtimes on switch
 * (CYP-247.4, background-live + cap K, evicted via persist-kill-`--resume`).
 *
 * The shared **comm** layer stays OUT of the runtime: [com.tneff.cyppieagents.comm.HubState] holds the
 * per-project agent slices (M) and the project-stamped channels / ACL matrix (CYP-81/102), already scoped
 * by the active project — only the lifecycle bundle needs per-project instancing.
 *
 * CYP-247.2 added [worktrees]: the per-project [WorktreeManager] (its disk layout `projects/<projectId>/`
 * is already per-project — CYP-82). The spawn path (connector cwd root + the ensure/delete-worktree
 * lambdas) now resolves `runtimeRegistry.active().worktrees`, so a spawn lands in the ACTIVE project's
 * worktree root; with one runtime this is the boot project's manager (unchanged), and per-project spawns
 * become real when CYP-247.4/.5 create a runtime per project.
 */
class ProjectRuntime(
    val projectId: String,
    val lifecycle: LifecycleManager,
    val connectorSessions: ConnectorSessions,
    val agentConfigs: AgentConfigRegistry,
    val capabilityRegistry: CapabilityRegistry,
    val providerRegistry: ProviderRegistry,
    val agentManagement: AgentManagement,
    val worktrees: WorktreeManager,
    /** CYP-316 — this project's per-agent context-window token feed (the `/ws/token-usage` source). */
    val tokenUsage: AgentTokenUsageTracker,
    /** CYP-324 — this project's per-agent busy/idle feed (the `/ws/busy-state` source). */
    val busyState: AgentBusyStateTracker,
    /** CYP-354 (BE-1) — this project's per-agent terminal-control-mode feed (the `/ws/terminal-state` source). */
    val terminalControl: TerminalControlStateTracker,
    /** CYP-326 — this project's per-agent compaction-completed signal (the orchestrator's 2B source). */
    val compactSignal: CompactCompletionSignal,
    /** CYP-355 (BE-2) — this project's hand-off motor (drives `POST /api/agents/{id}/mode`); the single-writer
     *  over its lifecycle + the host PTY, sharing this runtime's transition lock. */
    val handoff: HandoffMotor,
)
