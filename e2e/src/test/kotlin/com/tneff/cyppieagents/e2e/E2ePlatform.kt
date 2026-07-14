package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.PlatformConfig
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
import io.ktor.server.routing.routing
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
    val gitRoot: File,
    // CYP-256 (.5a): a restart-gate reuses ONE gitRoot across two boots, so the first close must NOT delete it.
    private val deleteGitRootOnClose: Boolean = true,
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
        if (deleteGitRootOnClose) gitRoot.deleteRecursively()
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
    // CYP-256 (.5a) — the restart gate: reuse ONE gitRoot across two boots (so files + worktree dirs persist),
    // [fileBacked] the durable stores (projectRegistry + projectAgents + overrides), and inject a [runner] so a
    // test can COUNT `git worktree add` calls (the D3 no-re-add assertion). Defaults preserve every existing test.
    gitRootOverride: File? = null,
    fileBacked: Boolean = false,
    runner: CommandRunner? = null,
    // Post-login E2E (desktop turn→persistence, Team-2): durably back the HUB MESSAGE store so a posted turn
    // survives a re-boot() over the reused gitRoot, provable through the real GET /api/channels/{id}/messages.
    // Null → the production-default InMemoryMessageStore (unchanged for EVERY existing caller). Additive.
    messageStoreFile: File? = null,
    // CYP-247 S3: the LRU session-suspension cap. Default (null) → the production default = 1 (teardown-on-switch).
    // A journey that exercises the cap>1 background-live state machine (J9) passes it explicitly.
    runtimeSuspensionCap: Int? = null,
    // CYP-348: the terminal launch-command seam. Null → prod default (`bash -l` interim worktree-shell,
    // resolved in BootOrchestrator). A full-boot terminal journey injects a fake command here to drive the
    // real boot→PtyManager wiring deterministically (no real `claude`/`bash` dependency).
    terminalLaunchCommand: List<String>? = null,
    // web-e2e (§8 test infra): bind a FIXED port (default 0 = ephemeral, unchanged for every existing journey)
    // so a long-lived boot main can advertise a stable URL to Playwright's `webServer`.
    port: Int = 0,
    // web-e2e: extra routes mounted ALONGSIDE installPlatform on the SAME server — used only by the web-e2e boot
    // main to serve the reference DOM fixture same-origin (no WS cross-origin/CORS). Null → nothing extra (prod
    // path / every existing journey unchanged).
    extraRoutes: (io.ktor.server.routing.Routing.() -> Unit)? = null,
): E2ePlatform {
    require(projects.isNotEmpty()) { "e2ePlatform needs at least one project" }
    val active = projects.first()
    require(active.agents.count { it.role == Role.PO } == 1) { "the first (active) project needs exactly one PO" }

    val gitRoot = gitRootOverride ?: Files.createTempDirectory("e2e-cyp106").toFile()
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
        worktrees = WorktreeManager(runner ?: FakeGit(), gitRoot, active.id),
        spawner = FakeSpawner(),
        scope = scope,
        connectorFactory = connectorFactory,
        // Post-login E2E: durable hub-message store when a file is supplied; else the prod-default InMemory
        // (identical to omitting the arg) — so a posted turn survives a real re-boot() over the reused gitRoot.
        storeFactory = if (messageStoreFile != null) {
            { com.tneff.cyppieagents.comm.JsonFileMessageStore(messageStoreFile) }
        } else {
            { com.tneff.cyppieagents.comm.InMemoryMessageStore() }
        },
        // CYP-256 (.5a): when file-backed, the durable stores live under the (reused) gitRoot so they survive a
        // re-boot() — projectRegistry (which projects exist) + projectAgents (runtime-added agent sets) + overrides.
        projectRegistryFile = if (fileBacked) gitRoot.toPath().resolve(".cyppie/projects.json").toFile() else null,
        projectAgentFile = if (fileBacked) gitRoot.toPath().resolve(".cyppie/project-agents.json").toFile() else null,
        agentOverrideFile = if (fileBacked) gitRoot.toPath().resolve(".cyppie/agent-overrides.json").toFile() else null,
        runtimeSuspensionCap = runtimeSuspensionCap ?: 1, // CYP-247 S3: default = teardown-on-switch (production default)
        terminalLaunchCommand = terminalLaunchCommand, // CYP-348: null → prod bash-l default; a fake command drives the boot→PtyManager wiring
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
    // CYP-255 (.4b): mint each seeded project's runtime via the REAL [ProjectRuntimeFactory] — the SAME path
    // the production switch takes (RuntimeRegistry.getOrCreate on activation) — so every seeded project gets
    // DISTINCT per-project instances (sessions / worktrees / configs / caps / provider / agentManagement), NOT
    // the boot runtime's shared ones. This is what makes the collision-leak money-tooth load-bearing: two
    // projects with an agent id `backend` now resolve to SEPARATE sessions + `projects/<id>/backend` worktrees.
    // Agents are added through the PROJECT'S OWN agentManagement (its runtime), mirroring how a switched-to
    // project's agents land in its runtime in production.
    for (p in projects.drop(1)) {
        booted.projectRegistry.create(CreateProjectRequest(p.id, p.name))
        val rt = booted.runtimeRegistry.getOrCreate(p.id, booted.projectRuntimeFactory)
        booted.state.rescope(p.id) // stamp the new spoke channels with ITS projectId (addAgent below)
        val hubPo = p.agents.firstOrNull { it.role == Role.PO }?.let { Agent(it.id, it.id, it.role, it.id) } ?: bootPo
        if (hubPo != null && booted.state.agents.none { it.role == Role.PO }) booted.state.addAgent(hubPo)
        p.agents.filter { it.role == Role.WORKER }.forEach { a ->
            rt.agentManagement.add(NewAgentSpec(a.id, a.id, a.role)) // the PROJECT's own runtime (distinct)
        }
        p.repo?.let { booted.projectConfig.setRepo(p.id, it, "main") }
        p.apiKey?.let { booted.projectConfig.setApiKey(p.id, it) }
    }
    // CYP-308: restore to the boot-RESOLVED active project (the durable-active view the boot-sync settled on),
    // NOT blindly the first SeedProject. Identical for a fresh boot (durableActive == config.projectId == the
    // first SeedProject); differs only on a restart whose durable active ≠ config.projectId, where production
    // leaves the durable-active as the active view — the harness must mirror that, not force config.projectId.
    booted.state.rescope(booted.activeProjectId)

    val server = embeddedServerNetty(booted, port, extraRoutes)
    server.start(wait = false)
    val resolvedPort = runBlocking { server.engine.resolvedConnectors().first().port }
    return E2ePlatform(booted, server, "http://127.0.0.1:$resolvedPort", now, scope, gitRoot, deleteGitRootOnClose = gitRootOverride == null)
}

private fun embeddedServerNetty(
    booted: BootedPlatform,
    port: Int = 0,
    extraRoutes: (io.ktor.server.routing.Routing.() -> Unit)? = null,
) = io.ktor.server.engine.embeddedServer(Netty, port = port) {
    installPlatform(booted)
    if (extraRoutes != null) routing { extraRoutes() }
}

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
