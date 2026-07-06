package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.ProjectRuntime
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.SwitchActiveRequest
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import com.tneff.cyppieagents.CommJson

/** Role of a seeded agent (the first project must contain exactly one PO — hub-and-spoke needs it). */
data class SeedAgent(val id: String, val role: Role = Role.WORKER)

/**
 * Declarative per-project seed (§7). The FIRST [SeedProject] is the active project booted via
 * [BootOrchestrator]; the rest are created through the REAL endpoints/APIs so all projects' channels
 * coexist in the one [com.tneff.cyppieagents.comm.HubState] (stamped per projectId) and a switch
 * re-scopes between them. `apiKey` must be >= 12 chars (CYP-104 plausibility).
 */
data class SeedProject(
    val id: String,
    val name: String = id,
    val agents: List<SeedAgent> = emptyList(),
    val repo: String? = null,
    val apiKey: String? = null,
)

/**
 * CYP-106 — the hermetic embedded-server E2E harness (§7 of `test/E2E-TEST-PLAN.md`). A REAL
 * `installPlatform` over a real [BootedPlatform] (real ProjectRegistry/HubState/EventSink/stores/
 * TokenRegistry), with the connector/sessions faked ([FakeSpawner]) and git faked ([FakeGit]) so no
 * real `claude` and no real repo are needed. Journeys talk to it through real Ktor clients, so every
 * assertion is proven through the real path. [close] stops the server and cleans up.
 */
class E2ePlatform internal constructor(
    val booted: BootedPlatform,
    private val server: EmbeddedServer<*, *>,
    val baseUrl: String,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val gitRoot: File,
) : AutoCloseable {

    /** WS base (`ws://…`) for real `client.webSocket{}` handshakes — never `client.get` a `/ws/...`. */
    val wsBaseUrl: String get() = baseUrl.replace("http://", "ws://")

    /** A real Ktor client (ContentNegotiation + WebSockets) bound to the test server, optionally bearer'd. */
    fun client(token: String? = null): HttpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(CommJson) }
        install(ClientWebSockets)
        if (token != null) defaultRequest { header("Authorization", "Bearer $token") }
    }

    fun asOperator(): HttpClient = client(OPERATOR_TOKEN)
    fun asAgent(id: String): HttpClient = client(agentToken(id))

    /** Drive the REAL active-project switch (`POST /api/projects/switch`), so journeys assert through it. */
    suspend fun switchActive(projectId: String) {
        asOperator().use { c ->
            c.post("$baseUrl/api/projects/switch") {
                contentType(ContentType.Application.Json); setBody(SwitchActiveRequest(projectId))
            }
        }
    }

    /** Deterministically append a `log.dropped` event for [projectId] (J7 drop seam) — content-free. */
    suspend fun injectDroppedEvent(projectId: String, agentId: String = "system") {
        booted.eventSink.append(
            EventDraft(agentId = agentId, projectId = projectId, type = EventType.LOG_DROPPED, severity = Severity.WARN, sourceTs = now()),
        )
    }

    override fun close() {
        runCatching { server.stop(100, 500) }
        scope.cancel()
        gitRoot.deleteRecursively()
    }

    companion object {
        const val OPERATOR_TOKEN: String = "e2e-operator-token"
        fun agentToken(id: String): String = "e2e-agent-$id"
    }
}

/**
 * Boot a real multi-project platform over [projects] (first = active) and start the embedded server.
 *
 * [connectorFactory] (CYP-124, additive, default null = unchanged prod path → Connector A all-AVAILABLE)
 * is threaded straight into [BootOrchestrator]'s CYP-120 seam, so a journey can boot a [FakeConnector]
 * with reduced capabilities and observe the resulting `capability.degraded` events over the real Event-Log.
 */
fun e2ePlatform(
    projects: List<SeedProject>,
    now: () -> Long = { 0L },
    connectorFactory: ((Connector) -> Connector)? = null,
): E2ePlatform {
    require(projects.isNotEmpty()) { "e2ePlatform needs at least one project" }
    val active = projects.first()
    require(active.agents.count { it.role == Role.PO } == 1) { "the first (active) project needs exactly one PO" }

    val gitRoot = Files.createTempDirectory("e2e-cyp106").toFile()
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Tokens for EVERY agent across ALL projects (so asAgent(id) works after the seed) + the operator.
    val agentTokens = projects.flatMap { it.agents }.distinctBy { it.id }
        .associate { E2ePlatform.agentToken(it.id) to it.id }
    val secrets = Secrets(agentTokens, operatorToken = E2ePlatform.OPERATOR_TOKEN, apiKey = null)

    val config = PlatformConfig(
        repo = RepoConfig(active.repo ?: "git@github.com:org/repo.git", "main"),
        agents = active.agents.map { AgentConfig(it.id, it.id, it.role) },
        projectId = active.id,
    )
    val booted = BootOrchestrator(
        config = config,
        secrets = secrets,
        worktrees = WorktreeManager(FakeGit(), gitRoot, active.id),
        spawner = FakeSpawner(),
        scope = scope,
        connectorFactory = connectorFactory,
    ).boot()

    // Seed the remaining projects through REAL APIs (no production seam): create in the registry, rescope
    // to it so addAgent stamps the new spoke channels with ITS projectId, then restore the active project.
    //
    // CYP-246: agents are now PARTITIONED per project — a freshly-rescoped project's slice is EMPTY. Before
    // the partition, the ambient boot PO leaked across projects and was the hub that let `addAgent` form each
    // worker's `po-<id>` spoke; now it is absent from a seeded project's slice, so the spokes would not form
    // (leaving cross-project isolation journeys with no channels to assert on). Seed each non-active project's
    // slice with a hub PO EXPLICITLY — the SeedProject's own PO if it declares one, else the boot PO carried
    // in as the hub (faithful to the pre-partition seed topology these journeys assert against). This goes
    // through [HubState.addAgent] directly (topology-only: a PO gets no spoke, no lifecycle/worktree), so it
    // does NOT collide with the boot PO in the agentId-keyed lifecycle — that per-project lifecycle is S17/L.
    val bootPo = booted.state.agents.firstOrNull { it.role == Role.PO }
    // CYP-253: the worktree/spawn path now resolves through the fail-closed runtime seam
    // (runtimeRegistry.active().worktrees). A seeded project is rescoped-to below and has agents added to it,
    // so it MUST have a live runtime while active — otherwise active() throws. Register one per seeded project,
    // reusing the boot runtime's still-shared lifecycle machinery (per-runtime instancing is CYP-254/.3) plus a
    // WorktreeManager scoped to the project's id (projects/<p.id>/). This is the harness analogue of CYP-246's
    // per-project seed; production lazily creates the runtime on switch in CYP-255/.4.
    val bootRt = booted.runtimeRegistry.of(active.id)!!
    for (p in projects.drop(1)) {
        booted.projectRegistry.create(CreateProjectRequest(p.id, p.name))
        booted.state.rescope(p.id)
        booted.runtimeRegistry.register(
            ProjectRuntime(
                projectId = p.id,
                lifecycle = bootRt.lifecycle,
                connectorSessions = bootRt.connectorSessions,
                agentConfigs = bootRt.agentConfigs,
                capabilityRegistry = bootRt.capabilityRegistry,
                providerRegistry = bootRt.providerRegistry,
                agentManagement = bootRt.agentManagement,
                worktrees = WorktreeManager(FakeGit(), gitRoot, p.id),
            ),
        )
        val hubPo = p.agents.firstOrNull { it.role == Role.PO }?.let { Agent(it.id, it.id, it.role, it.id) } ?: bootPo
        if (hubPo != null && booted.state.agents.none { it.role == Role.PO }) booted.state.addAgent(hubPo)
        p.agents.filter { it.role == Role.WORKER }.forEach { a ->
            booted.agentManagement.add(NewAgentSpec(a.id, a.id, a.role))
        }
        p.repo?.let { booted.projectConfig.setRepo(p.id, it, "main") }
        p.apiKey?.let { booted.projectConfig.setApiKey(p.id, it) }
    }
    booted.state.rescope(active.id)

    val server = embeddedServerNetty(booted)
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    return E2ePlatform(booted, server, "http://127.0.0.1:$port", now, scope, gitRoot)
}

private fun embeddedServerNetty(booted: BootedPlatform) =
    io.ktor.server.engine.embeddedServer(Netty, port = 0) { installPlatform(booted) }

/** Faked git CommandRunner — exit 0, creates clone/worktree dirs; rev-parse "absent" so add uses `-b`. */
private class FakeGit : CommandRunner {
    override fun run(command: List<String>, cwd: File): CommandResult {
        when {
            command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
            command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "") // branch absent → -b path
            command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                target?.let { File(it).mkdirs() }
            }
        }
        return CommandResult(0, "")
    }
}

/** Faked connector spawner — a no-op AgentProcess, so a session opens with NO real `claude`. */
private class FakeSpawner : ProcessSpawner {
    override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
}

private class FakeProcess : AgentProcess {
    override val stdoutLines: Flow<String> = emptyFlow()
    override suspend fun writeLine(line: String) {}
    override fun destroy() {}
}
