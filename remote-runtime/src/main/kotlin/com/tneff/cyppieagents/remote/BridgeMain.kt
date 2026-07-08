package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

/**
 * CYP-142 (S4.3) — the Remote Bridge **deployable entrypoint** (runs in USER infra). It spawns the user's
 * Claude-Code stream-json session (the user's CC uses the user's OWN credentials — the bridge carries NO
 * `ANTHROPIC_API_KEY`/operator token/repo creds), connects to the hub's `/ws/hub` with the **operator-
 * minted S3 agent token** (Bearer header → server-side `agentFor` identity, no client-supplied agentId),
 * and runs the [BridgeRelay].
 *
 * Config (env, no secrets in any file the bridge ships):
 *  - `HUB_URL`       — e.g. `wss://api.cyppie-agents.com` (the `/ws/hub` path is appended).
 *  - `HUB_AGENT_ID`  — the server-assigned agent id (the spoke is `po-<id>`).
 *  - `HUB_TOKEN`     — the S3-minted per-agent bearer token (the ONLY secret the bridge holds).
 *  - `CLAUDE_CMD`    — the CC launch command (default `claude`); `BRIDGE_CWD` — its working dir.
 */
private val log = LoggerFactory.getLogger("remote.main")

/** The honest capability declaration (S1 / E2.8 verdict); the server clamps REMOTE regardless (E2.4).
 *  S5 (G4): the bridge now self-reports rate-limit + tool events ([WireReportingObserver]) → so
 *  `rateLimitSignal` + `toolGranularity` are honestly **LIMITED** (≤ the REMOTE ceiling). `structuredUsage`
 *  stays UNAVAILABLE (remote token counts are unverifiable — self-report is moot). */
val BRIDGE_REMOTE_CAPABILITIES = Capabilities(
    structuredUsage = CapabilityStatus.UNAVAILABLE,
    toolGranularity = CapabilityStatus.LIMITED,
    reliableResult = CapabilityStatus.LIMITED,
    rateLimitSignal = CapabilityStatus.LIMITED,
    coordination = CapabilityStatus.AVAILABLE,
    kind = ConnectorKind.STREAM_JSON,
)

private fun env(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("missing required env $name")

/**
 * CYP-321 (security scoping) — the bridge spawns the **user's own** Claude Code on the **user's machine**, so it
 * MUST stay bypass-free: it uses the shared [ConnectorDefaults.streamJsonArgs] default (`skipPermissions=false`),
 * NEVER `--dangerously-skip-permissions`. The MVP bypass is authorized for the LOCAL connector's spawns only, not
 * a foreign user machine (CYP-197/BYOA). Extracted so a tooth can pin the absence of the flag.
 */
internal fun bridgeCliCommand(cliCommand: String): List<String> =
    listOf(cliCommand) + ConnectorDefaults.streamJsonArgs()

fun main(): Unit = runBlocking {
    val hubUrl = env("HUB_URL").trimEnd('/')
    val agentId = env("HUB_AGENT_ID")
    val token = env("HUB_TOKEN")
    val spoke = "po-$agentId" // hub-and-spoke convention (Spec §6.2)
    val cliCommand = System.getenv("CLAUDE_CMD")?.takeIf { it.isNotBlank() } ?: "claude"
    val cwd = File(System.getenv("BRIDGE_CWD")?.takeIf { it.isNotBlank() } ?: ".")

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Spawn the user's CC (stream-json) with the verified pinned flags. No API key injected — the user's
    // claude resolves its own auth (OAuth keychain / file via the passthrough HOME/USER); no server secret.
    val command = bridgeCliCommand(cliCommand) // CYP-321: bypass-free (skipPermissions=false) — user's own machine
    val process = ProcessBuilderSpawner().spawn(command, cwd, mapOf("HUB_AGENT_ID" to agentId))

    val link = KtorWireLink("$hubUrl/ws/hub", token, scope)
    link.connect()
    log.info("bridge connected: agent={} spoke={} hub={}", agentId, spoke, hubUrl)
    BridgeRelay(agentId, spoke, BRIDGE_REMOTE_CAPABILITIES, ProviderInfo.CLAUDE, process, link, scope).start()

    awaitCancellation() // run until the process is killed
}
