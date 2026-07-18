package com.tneff.cyppieagents.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tneff.cyppieagents.contract.ContractGenerator
import com.tneff.cyppieagents.contract.RestContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.slf4j.Logger as Slf4jLogger
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-638 S6 — the automatic **cleartext-boundary sentinel gate** for the A2 gateway process, **enumeration-driven**.
 *
 * A ListAppender on the ROOT logger (at INFO **and** DEBUG) captures EVERY log line while the sentinel drives ONE
 * request per **surface**, carrying a unique sentinel through every cleartext category (§1 of the acceptance bar): C1
 * cookie, C2 Bearer, C3 `?token`, `?ticket`, C5 PTY-like WS frame, C7 raw-credential body. The property proven: **no
 * sentinel appears in ANY captured log line** — at DEBUG too, because a leak that only shows at DEBUG IS the CYP-190
 * case.
 *
 * ★ Why this is enumeration-driven (the fix for the S3 bounce). The driven traffic is derived from the **same surface
 * enumerations the gateway itself is built from** — [RestContract.REST_OPS] (every REST op, control-plane included so
 * the default-deny branch is exercised too), [ContractGenerator.WS_CHANNELS] (the 8 data sockets), and
 * [KRATOS_SELF_SERVICE_FLOWS] (the self-service leg). So the sentinel's SCOPE is the **surface**, not a hand-picked
 * request list: adding a REST op / WS channel / Kratos flow makes it auto-driven here with a sentinel, WITHOUT anyone
 * touching this test. "Add a surface without a sentinel" is therefore **structurally impossible** — the leak-coverage
 * analog to the drift-test that pins S1/S2. (The prior version drove only 2 representative paths through the shared
 * `forwardToUpstream`/`relayFrames` seams; that was blind to a surface with its OWN handler — exactly what the Kratos
 * S3 leg was, which is why the S3 bounce was correct.)
 *
 * The upstreams (hub + Kratos) point at **dead ports on purpose**: no live in-JVM upstream can pollute the ROOT
 * appender with its OWN request logging (false-red), yet EVERY gateway handler still fully runs — it reads
 * method/path/uri and attempts the forward before it throws — so a naive `gwLog.info(call.request.uri)` at ANY handler
 * (the REST catch-all, the Kratos leg, or the WS proxy) fires and reddens this tooth. That handler-level uri-log is the
 * canonical mutation; the dead upstream also exercises the C8 exception path on every surface.
 *
 * Mutation-proven (adversarial re-gate re-runs it): add a naive `gwLog.info(call.request.uri)` at a forward seam → the
 * C3 sentinel appears at INFO **and** DEBUG → red. A tooth that stays green under this pins nothing.
 *
 * ★ VERIFIED, not assumed: removing the `forwardToUpstream` try/catch does **NOT** red these sentinel teeth — Ktor
 * 3.5's unhandled-exception log is `Unhandled: <method> - <path>` (path only, **no query**), so no `?token` ever
 * reaches the log on that path. The try/catch's provable value is the fail-closed 502 — pinned separately by
 * [deadHub_failsClosedWith502_notUnhandled500] (mutation: re-throw → unhandled 500 → red) — NOT a `?token`-leak fix.
 */
class GatewayS6SentinelTest {

    private val C1 = "SENTcookieC1z9"
    private val C2 = "SENTbearerC2z9"
    private val C3 = "SENTtokenC3z9"
    private val TICKET = "SENTticketz9"
    private val C5 = "SENTptyC5z9"
    private val C7 = "SENTapikeyC7z9"

    /** A port guaranteed to have NOTHING listening → any forward throws (ConnectException) → the C8 unhandled path is
     *  exercised, AND (crucially) no live in-JVM upstream can log the driven request and false-red the ROOT capture. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** Fill a `REST_OPS` template's `{param}` placeholders with a benign concrete segment. Deliberately NOT a sentinel:
     *  a path segment carries no credential, and logging the path-only is not the leak class under test (Ktor 3.5's
     *  unhandled log is already path-only) — the sentinels ride the query, headers and body, which are the real
     *  cleartext channels. */
    private fun concretePath(template: String): String =
        template.split('/').joinToString("/") { seg -> if (seg.startsWith("{") && seg.endsWith("}")) "id0" else seg }

    private fun runSentinelTraffic(level: Level) {
        val root = LoggerFactory.getLogger(Slf4jLogger.ROOT_LOGGER_NAME) as LogbackLogger
        val prevLevel = root.level
        val capture = ListAppender<ILoggingEvent>().apply { start() }
        root.level = level
        root.addAppender(capture)

        val deadHub = deadPort()
        val deadKratos = deadPort() // a dead Kratos upstream too → the S3 leg's forward throws (C8 path there as well)
        val gw = embeddedServer(Netty, port = 0) {
            gatewayModule("http://127.0.0.1:$deadHub", kratosBaseUrl = "http://127.0.0.1:$deadKratos")
        }.start(wait = false)
        val gp = runBlocking { gw.engine.resolvedConnectors().first().port }
        val client = HttpClient(CIO) { install(ClientWebSockets) }

        var drivenRest = 0
        var drivenWs = 0
        var drivenKratos = 0
        try {
            runBlocking {
                // (A) REST surface — EVERY RestContract op (control-plane included, so the default-deny branch is driven
                //     too), each carrying a sentinel in every REST cleartext category. A data-plane op is forwarded to a
                //     DEAD hub (fail-closed 502); a `/api/cp/` op is denied at the edge — both handler paths run, so a
                //     naive uri-log at either leaks a sentinel → red.
                for (op in RestContract.REST_OPS) {
                    val url = "http://127.0.0.1:$gp${concretePath(op.path)}?token=$C3&ticket=$TICKET"
                    runCatching {
                        client.request(url) {
                            method = HttpMethod.parse(op.method)
                            header(HttpHeaders.Cookie, "ory_kratos_session=$C1")   // C1
                            header(HttpHeaders.Authorization, "Bearer $C2")         // C2
                            if (!op.method.equals("GET", ignoreCase = true)) setBody("""{"secret":"$C7"}""") // C7
                        }
                    }
                    drivenRest++
                }
                // (B) Kratos self-service surface — the credential-heaviest cleartext leg: EVERY self-service flow driven
                //     as a submit carrying a RAW password (C7) + cookie/bearer/token/ticket through the `/.ory/kratos/
                //     public` prefix. This leg has its OWN handler (not the generic forwardToUpstream catch-all), which
                //     is exactly the surface the sentinel was blind to before the enumeration fix.
                for (flow in KRATOS_SELF_SERVICE_FLOWS) {
                    val url = "http://127.0.0.1:$gp$KRATOS_PUBLIC_PREFIX/self-service/$flow?token=$C3&ticket=$TICKET"
                    runCatching {
                        client.request(url) {
                            method = HttpMethod.Post
                            header(HttpHeaders.Cookie, "ory_kratos_session=$C1")   // C1
                            header(HttpHeaders.Authorization, "Bearer $C2")         // C2
                            setBody("""{"method":"password","password":"$C7"}""")    // C7 (raw credential in a submit body)
                        }
                    }
                    drivenKratos++
                }
                // (C) WS surface — EVERY frontend WS channel, each dialed with a ?token/?ticket in the upgrade URI (C3)
                //     + Cookie/Bearer on the handshake, and a PTY-like frame (C5) sent. The upgrade is accepted, the hub
                //     dial throws (dead), and the browser socket is closed cleanly — the WS handler ran, so a naive
                //     uri-log there leaks C3.
                for (ch in ContractGenerator.WS_CHANNELS) {
                    val url = "ws://127.0.0.1:$gp${ch.path}?token=$C3&ticket=$TICKET"
                    runCatching {
                        withTimeout(3000) {
                            client.webSocket(urlString = url, request = {
                                header(HttpHeaders.Cookie, "ory_kratos_session=$C1")
                                header(HttpHeaders.Authorization, "Bearer $C2")
                            }) { runCatching { send(Frame.Text(C5)) } }
                        }
                    }
                    drivenWs++
                }
            }
        } finally {
            client.close(); gw.stop(0, 0)
        }

        val dump = capture.list.joinToString("\n") { ev ->
            buildString {
                append(ev.level).append(' ').append(ev.loggerName).append(" | ").append(ev.formattedMessage)
                ev.throwableProxy?.let { append(" | ").append(it.className).append(": ").append(it.message) }
            }
        }
        root.detachAppender(capture); root.level = prevLevel

        // Guard the enumeration is non-vacuous — a green from an empty surface set would be a silent hole. `>=` current
        // sizes so a legitimately-added surface never fails this, only a regression-to-empty does.
        assertTrue(drivenRest >= 20 && drivenRest == RestContract.REST_OPS.size, "REST surface must be fully enumerated (drove $drivenRest)")
        assertTrue(drivenWs >= 8 && drivenWs == ContractGenerator.WS_CHANNELS.size, "WS surface must be fully enumerated (drove $drivenWs)")
        assertTrue(drivenKratos >= 6, "Kratos self-service surface must be fully enumerated (drove $drivenKratos)")

        for ((name, sentinel) in listOf(
            "C1 cookie" to C1, "C2 bearer" to C2, "C3 ?token" to C3, "C3 ?ticket" to TICKET,
            "C5 PTY frame" to C5, "C7 apikey body" to C7,
        )) {
            assertFalse(
                dump.contains(sentinel),
                "cleartext sentinel [$name] leaked into a gateway log line at $level " +
                    "(drove $drivenRest REST + $drivenWs WS + $drivenKratos Kratos surfaces):\n$dump",
            )
        }
    }

    @Test fun noCleartextSentinelInAnyGatewayLog_atInfo() = runSentinelTraffic(Level.INFO)

    @Test fun noCleartextSentinelInAnyGatewayLog_atDebug() = runSentinelTraffic(Level.DEBUG)

    /** The `forwardToUpstream` try/catch's provable value: an unreachable hub → a bounded **502**, not an unhandled 500.
     *  Mutation: drop the try/catch → the client throw becomes an unhandled 500 → this reds. */
    @Test
    fun deadHub_failsClosedWith502_notUnhandled500() {
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:${deadPort()}") }.start(wait = false)
        val gp = runBlocking { gw.engine.resolvedConnectors().first().port }
        val client = HttpClient(CIO)
        try {
            runBlocking {
                val resp = client.get("http://127.0.0.1:$gp/api/health") // allowed → forwarded → dead hub
                kotlin.test.assertEquals(HttpStatusCode.BadGateway, resp.status, "dead hub → fail-closed 502 (not an unhandled 500)")
            }
        } finally {
            client.close(); gw.stop(0, 0)
        }
    }
}
