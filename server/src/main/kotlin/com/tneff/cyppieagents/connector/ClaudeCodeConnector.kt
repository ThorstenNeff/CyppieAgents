package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

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
    private val worktreesRoot: File,
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
    /**
     * Resolves the agent's persona at spawn (S14 / CYP-97), written to `CLAUDE.md` in the worktree cwd
     * so Claude-Code auto-discovers it (Doc 05 §5, no `--bare`). Resolved at `open()` so an operator
     * edit takes effect on the next spawn (a CYP-73 restart). Default → no persona / no CLAUDE.md write.
     */
    private val personaOf: (agentId: String) -> String? = { null },
    /**
     * CYP-146 / E1.7 — writes the per-agent `--mcp-config` exposing the in-process Hub MCP server, so the
     * agent has a callable `hub_send` tool (the emission half). Null → no hub tools (dev/tests). The config
     * carries the agent token and is written **out-of-repo, 0600** ([HubMcpConfigWriter]); F1.
     */
    private val mcpConfigWriter: HubMcpConfigWriter? = null,
    /** The agent's bearer token (for the mcp-config auth header). Null → no hub tools for that agent. */
    private val tokenFor: (agentId: String) -> String? = { null },
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
        val cwd = File(worktreesRoot, worktreeName)
        // CYP-97: place the persona as CLAUDE.md before spawn (auto-discovery). Resolved here so the
        // current (possibly edited) persona is used; null/blank → no file written.
        personaOf(agentId)?.takeIf { it.isNotBlank() }?.let {
            cwd.mkdirs()
            File(cwd, "CLAUDE.md").writeText(it)
        }
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
        val command = listOf(cliCommand) +
            ConnectorDefaults.streamJsonArgs(effectiveAllowedTools, permissionMode) +
            (mcpConfigPath?.let { listOf("--mcp-config", it) } ?: emptyList())
        val process = spawner.spawn(command, cwd, env)
        return ClaudeCodeSession(agentId, process, registry, router, turnQueue, scope, recorder, projector)
            .also { it.start() }
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

/** One long-lived stream-json session for a single agent. */
class ClaudeCodeSession(
    override val agentId: String,
    private val process: AgentProcess,
    private val registry: SessionRegistry,
    private val router: MediationRouter,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
) : ConnectorSession {

    private val log = LoggerFactory.getLogger("connector.session")
    private val _events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)
    override val events: Flow<StreamJsonEvent> = _events

    // correlationId for the in-flight work-run: minted at turn.start, carried to result.final (PO c).
    @Volatile private var currentCorrelationId: String? = null

    // Turn-queue key is the STABLE agentId for the whole session lifetime (Gate #5): it must NOT
    // change at system/init, or a turn injected after init would take a different mutex and race a
    // turn still running on the old key. There is one long-lived session per agent, so agentId is
    // the right, stable serialization key. The session_id is used only for routing (registry).
    @Volatile private var boundSessionId: String? = null
    @Volatile private var pendingTurn: CompletableDeferred<Unit>? = null
    private var readerJob: Job? = null

    fun start() {
        readerJob = scope.launch {
            process.stdoutLines.collect { line ->
                val parsed = runCatching { CommJson.decodeFromString<StreamJsonEvent>(line) }.getOrNull()
                if (parsed == null) {
                    // Version drift / partial line: skip rather than crash the reader (CYP-5 note).
                    if (line.isNotBlank()) log.debug("skipping unparsable line for agent={}", agentId)
                    return@collect
                }
                val masked = EventMasking.mask(parsed) // Gate #3: mask BEFORE any egress

                if (masked is SystemEvent && boundSessionId == null && !masked.sessionId.isNullOrBlank()) {
                    boundSessionId = masked.sessionId
                    registry.bind(masked.sessionId!!, agentId) // Gate #1: authoritative session→agent
                }

                // Observability tap (CYP-37): the SINGLE point after masking, before the stream forks
                // to the UI and the hub. Non-blocking (record() is trySend) — no Observer-Effect.
                if (recorder != null && projector != null) {
                    projector.project(agentId, masked.sessionId, currentCorrelationId, masked)
                        .forEach { recorder.record(it) }
                }

                _events.emit(masked) // to /ws/agent (UI), already masked

                // CYP-146 RECONCILE: the stdout `hub_send` extraction (CYP-131 `router.onHubSend`) is
                // RETIRED. The agent now emits via the in-process Hub MCP server (CYP-146), which is the
                // SINGLE router (→ HubMcpTools → postAsAgent). The MCP tool_use surfaces in the stream as
                // the PREFIXED `mcp__hub__hub_send`; routing it here too would DOUBLE-POST, so the collector
                // does not route any tool_use to the hub (tool_use blocks still reach `_events` for the UI).
                // See HubSendExtractionTest.collectorDoesNotRouteMcpHubSend (F2 non-vacuous guard).

                if (masked is ResultEvent) {
                    // Turn end: mediate to the hub spoke (Gate #1/#2/#6 live in the router/hub)…
                    runCatching { router.onResult(masked) }
                        .onFailure { log.warn("mediation failed for agent={}: {}", agentId, it.message) }
                    // …and release the single-flight turn (Gate #5).
                    pendingTurn?.complete(Unit)
                    pendingTurn = null
                }
            }
            // Reached only on NORMAL stdout completion (the process exited on its own); a deliberate
            // close() cancels this job instead → record process.exit here. Exit code isn't exposed by
            // AgentProcess, so it's unknown (null) for MVP.
            if (recorder != null && projector != null) {
                recorder.record(projector.processExit(agentId, boundSessionId, null))
            }
        }
    }

    override suspend fun sendTurn(turn: UserTurn) {
        // Gate #5: serialize on the stable agentId key — hold from injection until this turn's
        // result arrives, so a second injection cannot race a running turn (even across system/init).
        // Single-flight means only the lock holder ever sets pendingTurn, so it can't be overwritten.
        turnQueue.runTurn(agentId) {
            // New work-run: mint a correlationId that the tap carries until this turn's result (PO c).
            val correlationId = UUID.randomUUID().toString()
            currentCorrelationId = correlationId
            if (recorder != null && projector != null) {
                recorder.record(projector.turnStart(agentId, boundSessionId, correlationId))
            }
            val done = CompletableDeferred<Unit>()
            pendingTurn = done
            process.writeLine(turn.toNdjsonLine())
            done.await()
        }
    }

    override fun close() {
        readerJob?.cancel()
        process.destroy()
        boundSessionId?.let { registry.unbind(it) }
        pendingTurn?.cancel()
        if (recorder != null && projector != null) recorder.record(projector.agentStopped(agentId))
    }

    /**
     * Stop the session and **confirm the process is gone** before returning (CYP-73, no zombie). Same
     * teardown as [close] — cancel the reader first so the stdout-completion path doesn't misfire as a
     * crash `process.exit` — then `destroy()` and await actual termination.
     */
    override suspend fun closeAndAwait() {
        readerJob?.cancel()
        process.destroy()
        process.awaitTerminated() // the difference vs close(): we wait until it's really dead
        boundSessionId?.let { registry.unbind(it) }
        pendingTurn?.cancel()
        if (recorder != null && projector != null) recorder.record(projector.agentStopped(agentId))
    }
}
