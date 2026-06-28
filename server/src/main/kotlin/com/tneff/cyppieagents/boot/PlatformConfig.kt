package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
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
    val web: WebConfig = WebConfig(),
    /** Event-Log knobs (PRD 06 §8, CYP-43). Default-belegt → existing configs without it still load. */
    val events: EventsConfig = EventsConfig(),
    /**
     * Active tenant for this platform (S12 / CYP-81). MVP = exactly one project; defaulted to
     * [DEFAULT_PROJECT_ID] so pre-S12 configs load unchanged. It is a concrete project id, never an
     * "all/global" wildcard — boot single-sources it into the hub so channels/ACL/messages are
     * scoped to it (fail-closed). The single value that opens "the one" project for later N.
     */
    val projectId: String = DEFAULT_PROJECT_ID,
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

/**
 * Web-client settings (Spec 02 §14). [allowedOrigins] are the exact frontend origins permitted by
 * CORS (e.g. "http://localhost:8080") — NEVER a wildcard in production. Empty = no cross-origin
 * browser access (the safest default; set it to enable the Wasm/JS web client).
 */
@Serializable
data class WebConfig(val allowedOrigins: List<String> = emptyList())

/**
 * Event-Log configuration (PRD 06 §8). Every field is defaulted so a `platform.config.json` written
 * before CYP-43 (no `events` block) loads unchanged (`encodeDefaults`/`explicitNulls=false` in
 * [CommJson]). Paths are resolved against the git root by `bootPlatform`; secrets never live here.
 */
@Serializable
data class EventsConfig(
    /** `context.usage` band width % (CYP-36). */
    val bandPct: Int = 10,
    /** extra `context.usage` threshold = `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (CYP-36). */
    val compactPct: Int = 75,
    /** denominator turning usage token counts into a fill % (PO-signed-off MVP default). */
    val contextWindowTokens: Long = 200_000,
    /** writer-coroutine batch-insert size (CYP-35). */
    val batchSize: Int = 64,
    /** bounded in-memory queue capacity; over it → drop-newest + `log.dropped` (CYP-35 §3.3). */
    val queueCapacity: Int = 4096,
    /** SQLite events DB file (not in the repo); relative paths resolve against the git root. */
    val sinkPath: String = ".cyppie/events.db",
    /** hook spool file the mediator tails (CYP-38); relative paths resolve against the git root. */
    val spoolPath: String = ".cyppie/hooks.spool",
    /**
     * Optional per-type default severity (wire type → `debug`/`info`/`warn`/`error`). Accepted and
     * round-tripped; applying it in the projector is a thin follow-up (MVP: the projector sets severity).
     */
    val severityDefaults: Map<String, String> = emptyMap(),
    /** Retention is a documented switch only in MVP — no auto-rotation (PRD §2). */
    val retention: String = "off",
)

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
