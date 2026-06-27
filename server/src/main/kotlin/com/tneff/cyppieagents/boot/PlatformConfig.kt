package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Role
import kotlinx.serialization.Serializable
import java.io.File

/**
 * `platform.config.json` (Spec 02 §12): the single file that drives boot — the repo, the hub, and
 * the agents. No secrets live here (tokens / API key come from the host env, Spec §14).
 */
@Serializable
data class PlatformConfig(
    val repo: RepoConfig,
    val hub: HubConfig = HubConfig(),
    val agents: List<AgentConfig>,
) {
    init {
        require(agents.isNotEmpty()) { "platform.config: at least one agent required" }
        require(agents.count { it.role == Role.PO } == 1) { "platform.config: exactly one PO agent required" }
        require(agents.map { it.id }.toSet().size == agents.size) { "platform.config: agent ids must be unique" }
    }

    companion object {
        fun load(file: File): PlatformConfig = CommJson.decodeFromString(file.readText())
    }
}

@Serializable
data class RepoConfig(val url: String, val branch: String = "main")

@Serializable
data class HubConfig(val url: String = "http://localhost:8787")

@Serializable
data class AgentConfig(
    val id: String,
    val name: String,
    val role: Role,
    /** worktree sub-folder name; the agent's session cwd. Defaults to the id. */
    val worktree: String = "",
    /** spawn command in the worktree (Default: claude). */
    val launch: String = "claude",
) {
    /** Effective worktree folder name (falls back to the id when unset). */
    val worktreeName: String get() = worktree.ifBlank { id }
}
