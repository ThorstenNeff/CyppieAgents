package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
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
    private val apiKey: String?,
    private val registry: SessionRegistry,
    private val router: MediationRouter,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    private val allowedTools: List<String> = ConnectorDefaults.DEFAULT_ALLOWED_TOOLS,
    private val permissionMode: String = ConnectorDefaults.DEFAULT_PERMISSION_MODE,
    private val cliCommand: String = "claude",
) : Connector {

    override fun open(agentId: String): ConnectorSession {
        val cwd = File(worktreesRoot, agentId)
        val env = buildMap {
            apiKey?.let { put("ANTHROPIC_API_KEY", it) } // D3: per-team key injected into the session
            put("HUB_AGENT_ID", agentId)
        }
        val command = listOf(cliCommand) + ConnectorDefaults.streamJsonArgs(allowedTools, permissionMode)
        val process = spawner.spawn(command, cwd, env)
        return ClaudeCodeSession(agentId, process, registry, router, turnQueue, scope).also { it.start() }
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
) : ConnectorSession {

    private val log = LoggerFactory.getLogger("connector.session")
    private val _events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)
    override val events: Flow<StreamJsonEvent> = _events

    // Turn-queue key: agentId until the CLI reports its session_id, then the session_id.
    @Volatile private var turnKey: String = agentId
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
                    turnKey = masked.sessionId!!
                    registry.bind(masked.sessionId!!, agentId) // Gate #1: authoritative session→agent
                }

                _events.emit(masked) // to /ws/agent (UI), already masked

                if (masked is ResultEvent) {
                    // Turn end: mediate to the hub spoke (Gate #1/#2/#6 live in the router/hub)…
                    runCatching { router.onResult(masked) }
                        .onFailure { log.warn("mediation failed for agent={}: {}", agentId, it.message) }
                    // …and release the single-flight turn (Gate #5).
                    pendingTurn?.complete(Unit)
                    pendingTurn = null
                }
            }
        }
    }

    override suspend fun sendTurn(turn: UserTurn) {
        // Gate #5: serialize per session — hold from injection until this turn's result arrives,
        // so a second injection cannot race a running turn.
        turnQueue.runTurn(turnKey) {
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
    }
}
