package com.tneff.cyppieagents.acceptance

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * CYP-687 (M1.1) — the BYOA acceptance **wire-client**: it speaks the `/ws/hub` Hub-Wire-Protocol v1 directly
 * (the [com.tneff.cyppieagents.remote.KtorWireLink] pattern, but SELF-CONTAINED in `:server` — no `:remote-runtime`
 * dep; `:server` already has the Ktor CIO WS client + `:core` DTOs). It is **claude-free**: it does NOT spawn or
 * need a `claude` binary — the M1.1 DoD is hub-side (a remote agent REGISTERS + CONNECTS + exchanges a message BOTH
 * ways over the wire), NOT a real claude turn.
 *
 * Shipped as the `CyppieHubAcceptance` jpackage add-launcher on the `.deb`; `deploy/linux/byoa-m1-acceptance.sh`
 * (run by po2 on the Ubuntu-26 box) drives one probe per invocation and sequences A–E + the 4 fail-closed + the
 * restart-repetition. Each invocation prints `PASS: …` / `FAIL: …` and exits 0/1 (a timeout is a FAIL).
 *
 * Probes (`--cmd`):
 *  - `hello`            (B) connect + WireHello → expect `WireAck("hello")`.
 *  - `send`             (C) connect + hello + `WireSend{channel, nonce}` → expect `WireAck("sent <id>")`; prints
 *                           `MESSAGE_ID=<id>` for the script's REST assertion (server-stamped `from`/`projectId`).
 *  - `await-deliver`    (D) connect + hello + `WireSubscribe(channel)` → expect a `WireDeliver` whose text carries the nonce.
 *  - `event`            (E) connect + hello + `WireEvent(TOOL_CALL)` → fire-and-forget; the server stamps `source=remote`
 *                           into `/api/events` (the ONLY frame that does — A–D never emit one). Script asserts via REST.
 *  - `expect-unauthorized` (fail-closed 1) connect with a bad/operator token → expect the server to close 1008.
 *  - `send-before-hello`   (fail-closed 2) `WireSend` BEFORE any hello → expect `WireError(PROTOCOL)`.
 *  - `elevated-caps`       (fail-closed 3) hello declaring FULL/local caps → still `WireAck("hello")` (server clamps
 *                           to the REMOTE ceiling — the DEGRADED-never-ENABLED assert is done by the script via REST).
 *  - `send-no-write`       (fail-closed 4) `WireSend` to a channel without canWrite → expect uniform `WireError(FORBIDDEN)`.
 */
private const val TIMEOUT_MS = 15_000L

/** The honest REMOTE capability declaration (mirrors the bridge). The server clamps to the REMOTE ceiling regardless. */
private val REMOTE_CAPS = Capabilities(
    structuredUsage = CapabilityStatus.UNAVAILABLE,
    toolGranularity = CapabilityStatus.LIMITED,
    reliableResult = CapabilityStatus.LIMITED,
    rateLimitSignal = CapabilityStatus.LIMITED,
    coordination = CapabilityStatus.AVAILABLE,
    kind = ConnectorKind.STREAM_JSON,
)

/** Fail-closed (3): a client DECLARING full/local caps — the server MUST clamp to REMOTE (DEGRADED, never ENABLED). */
private val ELEVATED_CAPS = Capabilities(
    structuredUsage = CapabilityStatus.AVAILABLE,
    toolGranularity = CapabilityStatus.AVAILABLE,
    reliableResult = CapabilityStatus.AVAILABLE,
    rateLimitSignal = CapabilityStatus.AVAILABLE,
    coordination = CapabilityStatus.AVAILABLE,
    kind = ConnectorKind.STREAM_JSON,
)

private fun pass(msg: String): Nothing { println("PASS: $msg"); exitProcess(0) }
private fun fail(msg: String): Nothing { println("FAIL: $msg"); exitProcess(1) }

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--") && i + 1 < args.size) { m[a.substring(2)] = args[i + 1]; i += 2 } else i += 1
    }
    return m
}

private fun hello(caps: Capabilities = REMOTE_CAPS): WireHello = WireHello(caps, ProviderInfo.CLAUDE)

private suspend fun DefaultClientWebSocketSession.sendFrame(frame: WireFrame) =
    send(Frame.Text(CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, frame))))

/** Await the next decodable [WireFrame] (bounded). Returns null on timeout or a clean close with no frame. */
private suspend fun DefaultClientWebSocketSession.nextFrame(): WireFrame? = withTimeoutOrNull(TIMEOUT_MS) {
    for (f in incoming) {
        if (f is Frame.Text) {
            val env = runCatching { CommJson.decodeFromString(WireEnvelope.serializer(), f.readText()) }.getOrNull()
            if (env != null) return@withTimeoutOrNull env.frame
        }
    }
    null
}

fun main(args: Array<String>): Unit = runBlocking {
    val a = parseArgs(args)
    val cmd = a["cmd"] ?: fail("missing --cmd")
    val url = a["url"] ?: fail("missing --url (e.g. ws://127.0.0.1:8787/ws/hub)")
    val token = a["token"] ?: fail("missing --token")
    val channel = a["channel"]
    val nonce = a["nonce"]

    val client = HttpClient(CIO) { install(WebSockets) }
    try {
        val session = client.webSocketSession(url) { header(HttpHeaders.Authorization, "Bearer $token") }
        with(session) {
            when (cmd) {
                // fail-closed (1): bad/operator token → the server closes 1008 (VIOLATED_POLICY) auth-first, before any frame.
                "expect-unauthorized" -> {
                    val reason = withTimeoutOrNull(TIMEOUT_MS) { closeReason.await() }
                    if (reason?.code == 1008.toShort()) pass("unauthorized token closed 1008 (VIOLATED_POLICY)")
                    fail("expected close 1008, got code=${reason?.code} message=${reason?.message}")
                }
                // fail-closed (2): WireSend BEFORE WireHello → WireError(PROTOCOL).
                "send-before-hello" -> {
                    sendFrame(WireSend(channel ?: fail("missing --channel"), nonce ?: "x"))
                    val f = nextFrame()
                    if (f is WireError && f.code == WireErrorCode.PROTOCOL) pass("send-before-hello → WireError(PROTOCOL)")
                    fail("expected WireError(PROTOCOL), got $f")
                }
                else -> {
                    // All remaining probes handshake first: WireHello → WireAck("hello").
                    val caps = if (cmd == "elevated-caps") ELEVATED_CAPS else REMOTE_CAPS
                    sendFrame(hello(caps))
                    val ack = nextFrame()
                    if (ack !is WireAck || ack.detail != "hello") fail("handshake: expected WireAck(\"hello\"), got $ack")
                    when (cmd) {
                        "hello", "elevated-caps" -> pass("WireHello → WireAck(\"hello\")${if (cmd == "elevated-caps") " (elevated caps accepted; server clamps REMOTE — assert DEGRADED via REST)" else ""}")
                        "send" -> {
                            val ch = channel ?: fail("missing --channel"); val n = nonce ?: fail("missing --nonce")
                            sendFrame(WireSend(ch, n))
                            val f = nextFrame()
                            if (f is WireAck && f.detail?.startsWith("sent ") == true) {
                                val id = f.detail!!.removePrefix("sent ").trim()
                                println("MESSAGE_ID=$id")
                                pass("WireSend → WireAck(\"sent $id\") (hub-assigned id)")
                            }
                            fail("expected WireAck(\"sent <id>\"), got $f")
                        }
                        "await-deliver" -> {
                            val ch = channel ?: fail("missing --channel"); val n = nonce ?: fail("missing --nonce")
                            sendFrame(WireSubscribe(listOf(ch)))
                            // Drain frames until a WireDeliver carries the nonce (bounded).
                            val found = withTimeoutOrNull(TIMEOUT_MS) {
                                for (fr in incoming) {
                                    if (fr is Frame.Text) {
                                        val env = runCatching { CommJson.decodeFromString(WireEnvelope.serializer(), fr.readText()) }.getOrNull()
                                        val d = env?.frame
                                        if (d is WireDeliver && d.text.contains(n)) return@withTimeoutOrNull true
                                    }
                                }
                                false
                            }
                            if (found == true) pass("WireDeliver carrying the nonce received (Hub→Agent)")
                            fail("no WireDeliver with the nonce within ${TIMEOUT_MS}ms")
                        }
                        // fail-closed (4): WireSend to a channel without canWrite → uniform WireError(FORBIDDEN).
                        "send-no-write" -> {
                            sendFrame(WireSend(channel ?: fail("missing --channel"), nonce ?: "x"))
                            val f = nextFrame()
                            if (f is WireError && f.code == WireErrorCode.FORBIDDEN) pass("send-without-canWrite → WireError(FORBIDDEN) (uniform, no topology leak)")
                            fail("expected WireError(FORBIDDEN), got $f")
                        }
                        // (E) connect + hello + WireEvent(TOOL_CALL) — the real bridge self-report path. Fire-and-forget:
                        // the server does NOT ack it (HubWireRoutes:220) but stamps source=remote into /api/events
                        // (WireEventIngest.toDraft). This is the ONLY frame that generates a source=remote event — A–D
                        // (hello/send/deliver) never emit one, which is why E could not fire without this probe. The
                        // script asserts source=remote via REST; E stays the real remote-over-wire discriminator.
                        "event" -> {
                            sendFrame(WireEvent(WireEventType.TOOL_CALL, tool = "cyp687-acceptance"))
                            delay(500) // let the fire-and-forget ingest land server-side before we close the session
                            pass("WireEvent(TOOL_CALL) emitted — server stamps source=remote (asserted via /api/events)")
                        }
                        else -> fail("unknown --cmd '$cmd'")
                    }
                }
            }
        }
    } catch (e: Exception) {
        fail("wire probe '$cmd' threw: ${e.message}")
    } finally {
        client.close()
    }
}
