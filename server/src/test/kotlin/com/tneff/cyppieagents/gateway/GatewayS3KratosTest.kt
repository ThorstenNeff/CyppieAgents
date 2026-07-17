package com.tneff.cyppieagents.gateway

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.Logger as Slf4jLogger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-638 S3 — the full Kratos self-service same-origin proxy. Runs the real [gatewayModule] in front of a fake Kratos
 * PUBLIC API and a fake hub, over a real cookie-jar client, and proves the acceptance bar end-to-end:
 *  1. ALL SIX self-service flows (login/registration/recovery/verification/settings/logout) are reachable through the
 *     gateway under the `/.ory/kratos/public` prefix;
 *  2. a login submit sets the httpOnly `ory_kratos_session` cookie **same-origin** — the gateway relays Kratos's
 *     `Set-Cookie`, so the browser cookie jar (same gateway origin) captures it;
 *  3. that cookie is **auto-forwarded** on the next request → `GET /api/auth/me` (the hub leg) sees it → **200**.
 *
 * Mutation-proven: unmount the Kratos leg (blank `kratosBaseUrl`) → the self-service paths fall to the REST catch-all →
 * 404, no cookie is set, whoami 401 → red.
 */
class GatewayS3KratosTest {

    private val servers = mutableListOf<io.ktor.server.engine.EmbeddedServer<*, *>>()
    @AfterTest fun tearDown() = servers.forEach { it.stop(0, 0) }

    private fun start(block: io.ktor.server.application.Application.() -> Unit): Int {
        val s = embeddedServer(Netty, port = 0, module = block).start(wait = false)
        servers += s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    /** Fake Kratos PUBLIC API: every `/self-service` path responds 200, and a login submit sets the session cookie. */
    private fun fakeKratos(): Int = start {
        routing {
            route("/self-service/{...}") {
                handle {
                    call.response.header(HttpHeaders.SetCookie, "ory_kratos_session=SESSION-abc123; Path=/; HttpOnly")
                    call.respondText("KRATOS:${call.request.local.uri}")
                }
            }
        }
    }

    /** Fake hub: `/api/auth/me` = 200 iff the Kratos session cookie is present, else 401. */
    private fun fakeHub(): Int = start {
        routing {
            get("/api/auth/me") {
                if (call.request.headers[HttpHeaders.Cookie]?.contains("ory_kratos_session=") == true) {
                    call.respondText("""{"role":"OPERATOR"}""")
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
            route("{...}") { handle { call.respondText("HUB") } }
        }
    }

    private fun gateway(hubPort: Int, kratosPort: Int): Int = start {
        gatewayModule("http://127.0.0.1:$hubPort", kratosBaseUrl = "http://127.0.0.1:$kratosPort")
    }

    @Test
    fun allSixSelfServiceFlowsProxied_cookieSetSameOrigin_thenWhoami200() {
        val gp = gateway(fakeHub(), fakeKratos())
        val client = HttpClient(CIO) { install(HttpCookies) } // a browser-like cookie jar
        runBlocking {
            // (1) all six flows reachable through the gateway (single-sourced from KRATOS_SELF_SERVICE_FLOWS)
            for (flow in KRATOS_SELF_SERVICE_FLOWS) {
                val r = client.get("http://127.0.0.1:$gp/.ory/kratos/public/self-service/$flow/browser")
                assertEquals(HttpStatusCode.OK, r.status, "self-service '$flow' must be reachable through the gateway")
            }
            // (2) a login submit → the gateway relays Set-Cookie → the jar captures ory_kratos_session same-origin
            val login = client.post("http://127.0.0.1:$gp/.ory/kratos/public/self-service/login?flow=x")
            assertEquals(HttpStatusCode.OK, login.status, "login submit proxied")
            val jar = client.cookies("http://127.0.0.1:$gp")
            assertTrue(jar.any { it.name == "ory_kratos_session" }, "the ory_kratos_session cookie is set same-origin (got: $jar)")
            // (3) the cookie is auto-forwarded → whoami on the hub leg sees it → 200
            val me = client.get("http://127.0.0.1:$gp/api/auth/me")
            assertEquals(HttpStatusCode.OK, me.status, "login → cookie same-origin → auto-forwarded → whoami 200")
        }
        client.close()
    }

    /**
     * CYP-638 S3 acceptance — the open point from the S6-bar audit §2: the Kratos leg must be proven to leak **no
     * cleartext** into logs, not merely to be reachable + relay the cookie. Complements [GatewayS6SentinelTest] (which
     * drives the Kratos leg against a DEAD upstream): here the leg runs its **success path** end-to-end against a live
     * fake Kratos while a ROOT ListAppender captures at INFO **and** DEBUG, and a login submit carries a raw sentinel
     * password + cookie/bearer/`?token`. The property: no sentinel reaches any log line.
     *
     * Mutation-proven: add `gwLog.info(call.request.uri)` (or the body) in the Kratos leg of `forwardToUpstream` → the
     * `?token`/password sentinel appears → red.
     */
    @Test
    fun kratosLeg_successPath_leaksNoSentinelIntoLogs() {
        for (level in listOf(Level.INFO, Level.DEBUG)) {
            val root = LoggerFactory.getLogger(Slf4jLogger.ROOT_LOGGER_NAME) as LogbackLogger
            val prev = root.level
            val capture = ListAppender<ILoggingEvent>().apply { start() }
            root.level = level
            root.addAppender(capture)

            val gp = gateway(fakeHub(), fakeKratos())
            val client = HttpClient(CIO) { install(HttpCookies) }
            try {
                runBlocking {
                    client.post("http://127.0.0.1:$gp/.ory/kratos/public/self-service/login?token=$SENT_TOKEN") {
                        header(HttpHeaders.Cookie, "ory_kratos_session=$SENT_COOKIE")
                        header(HttpHeaders.Authorization, "Bearer $SENT_BEARER")
                        setBody("""{"method":"password","password":"$SENT_PW"}""")
                    }
                }
            } finally {
                client.close()
            }

            val dump = capture.list.joinToString("\n") { ev ->
                buildString {
                    append(ev.level).append(' ').append(ev.loggerName).append(" | ").append(ev.formattedMessage)
                    ev.throwableProxy?.let { append(" | ").append(it.className).append(": ").append(it.message) }
                }
            }
            root.detachAppender(capture); root.level = prev

            for ((name, sentinel) in listOf(
                "?token" to SENT_TOKEN, "cookie" to SENT_COOKIE, "bearer" to SENT_BEARER, "password body" to SENT_PW,
            )) {
                assertFalse(dump.contains(sentinel), "Kratos-leg sentinel [$name] leaked into a log line at $level:\n$dump")
            }
        }
    }

    private companion object {
        const val SENT_TOKEN = "S3SENTtokenZ9"
        const val SENT_COOKIE = "S3SENTcookieZ9"
        const val SENT_BEARER = "S3SENTbearerZ9"
        const val SENT_PW = "S3SENTpwZ9"
    }
}
