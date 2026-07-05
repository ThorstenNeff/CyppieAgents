package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.ChannelShareRecord
import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.routing.ConflictException

/**
 * CYP-220 Phase 6 S3 — Finding B: **read-only-window ENFORCEMENT** during a store migration.
 *
 * The migration window is the interval between the copy `A → B` and the atomic rebind to
 * [com.tneff.cyppieagents.db.BindingState.ACTIVE] ([com.tneff.cyppieagents.db.StoreMigrator], Design §4.3). While
 * a store's binding is in that window (MIGRATING / READ_ONLY), the store must stay **readable from source A** but
 * **reject every write** — a write applied to A after B was copied would be **silently lost** the moment the
 * rebind flips reads/writes onto B (or, worse, applied to both → a duplicate). The migrator's own KDoc defers
 * exactly this: *"writes gated + reads from source A — enforced at the store-access layer … Finding B."*
 *
 * Enforcement lives at the **single store-access placement point** — the [PgStoreRouting] accessors, the only way
 * a caller obtains a store instance (same seam, no-bypass, as the residency guard). During the window the
 * accessor hands out one of the gates below instead of a live A/B store: reads pass straight through to source A,
 * every mutation throws [storeMigrating] → HTTP 409 `store_migrating` (typed, fail-closed, nothing persisted).
 * The client's contract is "retry after the switch"; the 409 IS the signal, so the write happens exactly once,
 * against B, after ACTIVE.
 */
internal fun storeMigrating(storeKey: String): ConflictException =
    ConflictException(
        "store '$storeKey' is migrating; writes are temporarily rejected — retry after the switch completes",
        code = "store_migrating",
    )

/**
 * Read-only window gate for [RemoteTokenStore]: [all] reads through to source A ([sourceA]); [put]/[remove] are
 * rejected ([storeMigrating] → 409) so a token minted/revoked mid-migration can never be lost in A or duplicated
 * into B. The [RemoteTokenIssuer] revoke/issue path surfaces the 409 to the operator, who retries after ACTIVE.
 */
class MigrationGatedRemoteTokenStore(private val sourceA: RemoteTokenStore) : RemoteTokenStore {
    override fun all(): Map<String, String> = sourceA.all()
    override fun put(agentId: String, token: String): Unit = throw storeMigrating("remote_token")
    override fun remove(agentId: String): Unit = throw storeMigrating("remote_token")
}

/**
 * Read-only window gate for [ProjectConfigStore]: every resolution/view reads through to source A ([sourceA]);
 * every mutation ([setRepo]/[setApiKey]/[remove]) is rejected ([storeMigrating] → 409). Spawn/boot resolution
 * (repo + API key) therefore stays correct and consistent from A throughout the window; operator config PUTs and
 * the project-delete config-cascade get a typed 409 and retry after the switch.
 */
class MigrationGatedProjectConfigStore(private val sourceA: ProjectConfigStore) : ProjectConfigStore {
    override fun resolvedRepo(projectId: String): RepoConfig = sourceA.resolvedRepo(projectId)
    override fun resolvedApiKey(projectId: String): String? = sourceA.resolvedApiKey(projectId)
    override fun repoView(projectId: String): RepoConfigView = sourceA.repoView(projectId)
    override fun apiKeyView(projectId: String): ApiKeyView = sourceA.apiKeyView(projectId)
    override fun setRepo(projectId: String, url: String, branch: String): RepoConfigView = throw storeMigrating("project_config")
    override fun setApiKey(projectId: String, key: String): ApiKeyView = throw storeMigrating("project_config")
    override fun remove(projectId: String): Boolean = throw storeMigrating("project_config")
}

/**
 * Read-only window gate for [AgentOverrideStore] (S4): reads ([overrideOf]/[allFor]) pass through to source A;
 * every mutation ([put]/[setAvatar]/[removeAgent]/[removeProject]) is rejected ([storeMigrating] → 409), so a
 * name/colour/persona/avatar edit or a cascade during the window can never be lost in A or duplicated into B.
 */
class MigrationGatedAgentOverrideStore(private val sourceA: AgentOverrideStore) : AgentOverrideStore {
    override fun overrideOf(projectId: String, agentId: String): AgentOverride? = sourceA.overrideOf(projectId, agentId)
    override fun allFor(projectId: String): Map<String, AgentOverride> = sourceA.allFor(projectId)
    override fun put(projectId: String, agentId: String, name: String?, color: String?, persona: String?, launch: String?): AgentOverride = throw storeMigrating("agent_override")
    override fun setAvatar(projectId: String, agentId: String, avatar: AgentAvatar?): AgentOverride = throw storeMigrating("agent_override")
    override fun removeAgent(projectId: String, agentId: String): Boolean = throw storeMigrating("agent_override")
    override fun removeProject(projectId: String): Int = throw storeMigrating("agent_override")
}

/**
 * Read-only window gate for [ChannelShareStore] (S4): reads ([record]/[sharedInboundChannelIds]) pass through
 * to source A; every mutation ([share]/[revoke]) is rejected ([storeMigrating] → 409), so a cross-project share
 * grant/revoke during the window can never be lost in A or duplicated into B. The gate is the AclMatrix permit
 * input, so freezing writes keeps the authorization gate consistent from A throughout the window.
 */
class MigrationGatedChannelShareStore(private val sourceA: ChannelShareStore) : ChannelShareStore {
    override fun record(channelId: String): ChannelShareRecord? = sourceA.record(channelId)
    override fun sharedInboundChannelIds(activeProjectId: String): Set<String> = sourceA.sharedInboundChannelIds(activeProjectId)
    override fun share(channelId: String, ownerProjectId: String, sharedWith: Set<String>): ChannelShareRecord = throw storeMigrating("channel_share")
    override fun revoke(channelId: String): Boolean = throw storeMigrating("channel_share")
}
