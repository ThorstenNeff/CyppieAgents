package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ConnectorKind
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
    /**
     * End-user auth (CYP-178 / P1). Defaulted null so pre-CYP-178 configs load unchanged and boot stays
     * **fail-closed**: with no `auth` block the human path is deny-all (only the static operator token
     * authenticates). Setting [AuthConfig.kratosPublicUrl] activates the verified-human OPERATOR path.
     */
    val auth: AuthConfig? = null,
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

/**
 * End-user auth config (CYP-178). [kratosPublicUrl] is the Kratos PUBLIC base URL (e.g.
 * `http://127.0.0.1:4433`); boot appends `/sessions/whoami` to validate a caller's session. The
 * platform never talks to the Kratos ADMIN API (RC4) — identities self-serve via Kratos, roles are the
 * platform's own store. [roleDbPath] is the durable authZ role store (resolved against the git root,
 * out-of-repo, gitignored). No secrets live here (the Kratos session secret is Kratos's, at rest on its host).
 */
@Serializable
data class AuthConfig(
    val kratosPublicUrl: String,
    val roleDbPath: String = ".cyppie/roles.db",
    /** whoami request timeout (ms) — bounded so a hung Kratos never hangs the guard (RC1/RC2). */
    val whoamiTimeoutMs: Long = 3_000,
)

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
    /**
     * Persona text (S14 / CYP-97, Doc 09 §9.2): the connector writes it to `CLAUDE.md` in the agent's
     * worktree cwd so Claude-Code auto-discovers it (Doc 05 §5, no `--bare`). Defaulted/nullable so
     * pre-CYP-97 configs load unchanged; null/blank → the connector writes no CLAUDE.md.
     */
    val claudeMd: String? = null,
    /** Connector choice (Doc 10 §1 / CYP-122); default Connector A (stream-json). Defaulted so pre-CYP-122
     *  configs load unchanged. The spawn path selects the connector implementation from this. */
    val connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON,
    /**
     * CYP-169 / E2.6 — **no-spawn / remote (BYOA) flag.** **Orthogonal to [connectorKind]**: location
     * (local vs remote), not execution mode (Doc 12 §1). When true, boot does NOT spawn a local process;
     * the agent is registered into the topology/ACL/lifecycle (STOPPED) and joins over the wire (`/ws/hub`,
     * E2.5) — its `WireHello` binds a [com.tneff.cyppieagents.routing.WireConnectorSession]. Its caps are
     * clamped to the **REMOTE** ceiling from boot (untrusted from birth, E2.4). Defaulted false so existing
     * configs load unchanged; `launch` is ignored for a remote agent (it spawns its own CC in user infra).
     */
    val remote: Boolean = false,
) {
    /** Effective worktree folder name (falls back to the id when unset). */
    val worktreeName: String get() = worktree.ifBlank { id }
}
