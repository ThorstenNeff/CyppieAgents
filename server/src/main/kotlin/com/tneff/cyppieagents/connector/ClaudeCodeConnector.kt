package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Live Claude-Code connector (Decision D1/D4/D8): spawns one long-lived `claude` process per agent
 * over piped stdio + stream-json, and wires the CYP-9 primitives into an end-to-end path.
 *
 * Security: spawns with [ConnectorDefaults] (pinned flags, tight allowlist, NEVER bypassPermissions
 * — Gate #4); every event is masked before egress (Gate #3); turns are single-flight per session
 * (Gate #5); routing is identity→spoke (Gate #1) and only successful turns post (Gate #6).
 */
class ClaudeCodeConnector(
    private val spawner: ProcessSpawner,
    /**
     * Resolves the parent dir of the agent worktrees **at spawn time** (CYP-247.2), so a spawn lands in the
     * ACTIVE project's `projects/<projectId>/` root rather than a boot-frozen one — boot wires it to
     * `{ runtimeRegistry.active().worktrees.worktreesRoot }`. Lazy like [resolveApiKey], so a CYP-73 restart
     * (and, once runtimes are per-project, a project switch) picks up the current root.
     */
    private val worktreesRoot: () -> File,
    /**
     * Resolves the ANTHROPIC_API_KEY **at spawn time** (S15 / CYP-96), so an operator key change takes
     * effect on the next `open()` (a CYP-73 restart) — there is no boot-frozen value. Backed by the
     * per-project config store (override) → [com.tneff.cyppieagents.boot.Secrets] (env fallback).
     */
    private val resolveApiKey: () -> String?,
    private val registry: SessionRegistry,
    private val router: MediationRouter,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    private val allowedTools: List<String> = ConnectorDefaults.DEFAULT_ALLOWED_TOOLS,
    private val permissionMode: String = ConnectorDefaults.DEFAULT_PERMISSION_MODE,
    private val cliCommand: String = "claude",
    // Observability tap (CYP-37). Null = no tapping (keeps tests/older callers working).
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
    // CYP-198: the durable per-agent transcript feeder. Null = no persistence (older callers/tests).
    private val agentEvents: com.tneff.cyppieagents.agentevents.AgentEventRecorder? = null,
    /**
     * CYP-146 / E1.7 — writes the per-agent `--mcp-config` exposing the in-process Hub MCP server, so the
     * agent has a callable `hub_send` tool (the emission half). Null → no hub tools (dev/tests). The config
     * carries the agent token and is written **out-of-repo, 0600** ([HubMcpConfigWriter]); F1.
     */
    private val mcpConfigWriter: HubMcpConfigWriter? = null,
    /** The agent's bearer token (for the mcp-config auth header). Null → no hub tools for that agent. */
    private val tokenFor: (agentId: String) -> String? = { null },
    /**
     * CYP-163 — a sandbox-ONLY `bypassPermissions` grant. **Null is the production default** → spawns use
     * the sharp [ConnectorDefaults.streamJsonArgs] (Gate #4 fail-closed, never bypass). **Non-null** (the
     * RB1 throwaway-sandbox worker ONLY, human + reviewer signed) → spawns use the **separate, explicit**
     * [ConnectorDefaults.sandboxBypassStreamJsonArgs] override. Must NEVER be set on a product/S8-prod
     * connector — that is the hard, non-leaking invariant the override exists to protect.
     */
    private val sandboxBypassGrant: SandboxBypassGrant? = null,
    /**
     * CYP-167 — durable session-resume. **Null (default) → feature OFF** (no `--resume`, no persistence),
     * so existing callers/tests are unchanged. Non-null (boot) → [open] does read-before-spawn (look up
     * `(projectId, agentId)` → prepend `--resume <id>`) + write-after-init (persist the bound id) + the
     * [ResumingSession] stale-fallback (clear + fresh respawn once if the resumed id is dead).
     */
    private val sessionStore: SessionStore? = null,
    /**
     * Resolves the durable key's projectId at spawn. Boot wires `{ config.projectId }` (boot-frozen,
     * MVP-correct: an agent doesn't change project mid-life — CYP-91 `setActive` is pointer-only). When a
     * live project-switch lands, this would track the agent's owning project instead.
     */
    private val projectIdOf: (agentId: String) -> String = { DEFAULT_PROJECT_ID },
    /** Injectable clock for the entry timestamps (testable). */
    private val clock: () -> Long = System::currentTimeMillis,
) : Connector {

    /**
     * Connector A capability declaration (Doc 10 §4, column A) — **all AVAILABLE**, but NOT blindly:
     * stream-json is the full-fidelity transport and each dimension is coupled to a concrete event/path
     * this connector already produces. Honest declaration is the contract (Doc 10 §3) — if a future
     * change removed a source, the matching dimension would have to drop to LIMITED/UNAVAILABLE here.
     *
     *  - structuredUsage  AVAILABLE ← [ResultEvent.usage] carries per-turn token usage (CYP-36 bander).
     *  - toolGranularity  AVAILABLE ← assistant `tool_use` + user `tool_result` blocks give full
     *                                 `tool.call`/`tool.result` depth (projector CYP-37).
     *  - reliableResult   AVAILABLE ← every turn ends on a [ResultEvent]; this session keys turn-end +
     *                                 mediation handover off it (see [ClaudeCodeSession.start]).
     *  - rateLimitSignal  AVAILABLE ← structured `RateLimitEvent.rate_limit_info` (NOT text-scraped),
     *                                 the source the Warden stall detector reads (S11/Doc 07).
     *  - coordination     AVAILABLE ← mediation: the backend reads the stream + injects turns on stdin
     *                                 ([MediationRouter] + [sendTurn]), so the agent reaches the hub (05 §2).
     */
    override val capabilities: Capabilities = STREAM_JSON_CAPABILITIES

    // E2.1 / CYP-137: Connector A's provider (tool) — Claude (CLI / stream-json realization).
    override val provider: ProviderInfo = ProviderInfo.CLAUDE

    override fun open(agentId: String): ConnectorSession = open(agentId, agentId)

    /** Spawn an agent session whose cwd is [worktreesRoot]/[worktreeName] (Spec §11 isolation). */
    override fun open(agentId: String, worktreeName: String): ConnectorSession {
        val cwd = File(worktreesRoot(), worktreeName)
        // CYP-310: the worktree CLAUDE.md is NEVER auto-written at spawn (the CYP-97 persona-auto-discovery is
        // removed). A new agent starts with an empty CLAUDE.md; it is managed EXCLUSIVELY via the operator-gated
        // `POST /api/agents/{id}/claude-md` (+ external/agent self-edits), read via `GET .../claude-md`.
        val env = buildMap {
            // D3 / CYP-96: resolve the key AT SPAWN (store override → env fallback) → injected into the
            // session ENV, never a CLI arg. A boot-frozen value would ignore an operator key change.
            resolveApiKey()?.let { put("ANTHROPIC_API_KEY", it) }
            put("HUB_AGENT_ID", agentId)
        }
        // CYP-146: expose `hub_send` by handing the agent an --mcp-config for the in-process Hub MCP server
        // (F1: token-bearing config written 0600 OUT-OF-REPO via [HubMcpConfigWriter], absolute path). The
        // hub tool is pre-approved as ONLY `mcp__hub__hub_send` (F3 tight allowlist, no wildcard, never
        // bypassPermissions); built-in tools stay governed by [permissionMode].
        val mcpConfigPath: String? =
            mcpConfigWriter?.let { w -> tokenFor(agentId)?.let { tok -> w.writeFor(agentId, tok).absolutePath } }
        val effectiveAllowedTools =
            if (mcpConfigPath != null) allowedTools + HubMcpConfigWriter.PREFIXED_SEND_TOOL else allowedTools

        // CYP-167: write-after-init — persist the bound id ONCE `system/init` delivers it (the session
        // invokes this at its bind point). Null store (feature off) → null callback → no persistence.
        val projectId = projectIdOf(agentId)
        val onBound: ((String) -> Unit)? = sessionStore?.let { store ->
            { sessionId -> store.upsert(projectId, agentId, sessionId, clock()) }
        }

        // Spawn ONE session with the given resume id (null = fresh, no `--resume`). Both arg paths emit
        // `--resume` identically; Gate #4 stays sharp (only the grant path emits bypass, CYP-163).
        fun spawnSession(resume: String?): ClaudeCodeSession {
            val baseArgs = sandboxBypassGrant
                ?.let { ConnectorDefaults.sandboxBypassStreamJsonArgs(it, effectiveAllowedTools, resume) }
                ?: ConnectorDefaults.streamJsonArgs(effectiveAllowedTools, permissionMode, resume)
            val command = listOf(cliCommand) + baseArgs +
                (mcpConfigPath?.let { listOf("--mcp-config", it) } ?: emptyList())
            val process = spawner.spawn(command, cwd, env)
            // CYP-142 S4.0: the :server factory maps the hub types onto the shared core's seams (the bridge
            // builds its own ClaudeCodeSession with wire-relay/no-op seams). onBound = the CYP-167 store closure.
            return claudeCodeServerSession(agentId, process, registry, router, turnQueue, scope, recorder, projector, onBound, agentEvents, projectId)
        }

        // CYP-167: read-before-spawn. No durable entry (first start, or feature off) ⇒ fresh, no `--resume`,
        // no facade — structurally nothing to resume.
        val resumeId = sessionStore?.find(projectId, agentId)?.sessionId?.takeIf { it.isNotBlank() }
        if (resumeId == null) {
            return spawnSession(null).also { it.start() }
        }
        // An entry exists → attempt `--resume` behind the E4 facade, which self-heals a STALE id: clear the
        // durable entry and respawn fresh exactly once (spike (a)/(b): stale id → is_error while unbound).
        return ResumingSession(
            agentId = agentId,
            scope = scope,
            firstAttempt = spawnSession(resumeId),
            onResumeFailed = { sessionStore.clear(projectId, agentId) },
            respawnFresh = { spawnSession(null) },
        ).also { it.start() }
    }

    companion object {
        /** Connector A (stream-json / API) — full fidelity, all dimensions AVAILABLE (Doc 10 §4). */
        val STREAM_JSON_CAPABILITIES = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.AVAILABLE,
            reliableResult = CapabilityStatus.AVAILABLE,
            rateLimitSignal = CapabilityStatus.AVAILABLE,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.STREAM_JSON,
        )
    }
}

