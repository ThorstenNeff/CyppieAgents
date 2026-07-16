package com.tneff.cyppieagents.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
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

/**
 * CYP-638 S6 — the automatic **cleartext-boundary sentinel gate** for the A2 gateway process. A ListAppender on the
 * ROOT logger (at INFO **and** DEBUG) captures EVERY log line while one real request drives a unique sentinel through
 * every cleartext category (§1 of the acceptance bar): C1 cookie, C2 Bearer, C3 `?token`/`?ticket`, C5 PTY-like WS
 * frame, C7 raw-credential body — and, by pointing at a DEAD hub, exercises the C8 exception path. The property:
 * **no sentinel appears in ANY captured log line** — proven at DEBUG too, because a leak that only shows at DEBUG IS
 * the CYP-190 case. Unlike a manual masking proof, this needs no real creds (only the gateway + a dead hub) so it runs
 * in the gate.
 *
 * Mutation-proven (adversarial re-gate re-runs it): add a naive `gwLog.info(call.request.uri)` at a forward seam → the
 * C3 sentinel appears at INFO **and** DEBUG → red. A tooth that stays green under this pins nothing.
 *
 * ★ VERIFIED, not assumed: removing the `forwardToHub` try/catch does **NOT** red these sentinel teeth — Ktor 3.5's
 * unhandled-exception log is `Unhandled: <method> - <path>` (path only, **no query**), so no `?token` ever reaches the
 * log on that path. The try/catch's provable value is the fail-closed 502 — pinned separately by
 * [deadHub_failsClosedWith502_notUnhandled500] (mutation: re-throw → unhandled 500 → red) — NOT a `?token`-leak fix.
 */
class GatewayS6SentinelTest {

    private val C1 = "SENTcookieC1z9"
    private val C2 = "SENTbearerC2z9"
    private val C3 = "SENTtokenC3z9"
    private val TICKET = "SENTticketz9"
    private val C5 = "SENTptyC5z9"
    private val C7 = "SENTapikeyC7z9"

    /** A port that is guaranteed to have NOTHING listening → any forward throws (ConnectException) → the C8/§3.1
     *  unhandled-exception path is exercised. */
    private fun deadHubPort(): Int = ServerSocket(0).use { it.localPort }

    private fun runSentinelTraffic(level: Level) {
        val root = LoggerFactory.getLogger(Slf4jLogger.ROOT_LOGGER_NAME) as LogbackLogger
        val prevLevel = root.level
        val capture = ListAppender<ILoggingEvent>().apply { start() }
        root.level = level
        root.addAppender(capture)

        val deadPort = deadHubPort()
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:$deadPort") }.start(wait = false)
        val gp = runBlocking { gw.engine.resolvedConnectors().first().port }
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            runBlocking {
                // REST: an ALLOWED PUT carrying a sentinel in EVERY REST cleartext category → forwarded to a DEAD hub →
                // the client throw exercises the C8 unhandled-exception path (which must NOT log the request line).
                runCatching {
                    client.put("http://127.0.0.1:$gp/api/config/apikey?token=$C3&ticket=$TICKET") {
                        header(HttpHeaders.Cookie, "ory_kratos_session=$C1")   // C1
                        header(HttpHeaders.Authorization, "Bearer $C2")         // C2
                        setBody("""{"apiKey":"$C7"}""")                          // C7 (raw credential body)
                    }
                }
                // WS: a PTY-like frame (C5) on an allowed channel, with a ?token in the upgrade URI (C3) → dead hub WS.
                runCatching {
                    withTimeout(3000) {
                        client.webSocket("ws://127.0.0.1:$gp/ws/terminal?token=$C3") { send(Frame.Text(C5)) }
                    }
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

        for ((name, sentinel) in listOf(
            "C1 cookie" to C1, "C2 bearer" to C2, "C3 ?token" to C3, "C3 ?ticket" to TICKET,
            "C5 PTY frame" to C5, "C7 apikey body" to C7,
        )) {
            assertFalse(
                dump.contains(sentinel),
                "cleartext sentinel [$name] leaked into a gateway log line at $level:\n$dump",
            )
        }
    }

    @Test fun noCleartextSentinelInAnyGatewayLog_atInfo() = runSentinelTraffic(Level.INFO)

    @Test fun noCleartextSentinelInAnyGatewayLog_atDebug() = runSentinelTraffic(Level.DEBUG)

    /** The `forwardToHub` try/catch's provable value: an unreachable hub → a bounded **502**, not an unhandled 500.
     *  Mutation: drop the try/catch → the client throw becomes an unhandled 500 → this reds. */
    @Test
    fun deadHub_failsClosedWith502_notUnhandled500() {
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:${deadHubPort()}") }.start(wait = false)
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
