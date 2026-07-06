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
 * by the active project — only the lifecycle bundle needs per-project instancing. `WorktreeManager` is
 * added to the runtime in CYP-247.2 (its disk layout `projects/<projectId>/` is already per-project; only
 * the manager instance is boot-pinned today).
 */
class ProjectRuntime(
    val projectId: String,
    val lifecycle: LifecycleManager,
    val connectorSessions: ConnectorSessions,
    val agentConfigs: AgentConfigRegistry,
    val capabilityRegistry: CapabilityRegistry,
    val providerRegistry: ProviderRegistry,
    val agentManagement: AgentManagement,
)
