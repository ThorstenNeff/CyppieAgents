package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * web-e2e (§8 test infra) — a LONG-LIVED boot of the hermetic [e2ePlatform] on a FIXED port, for
 * Playwright's `webServer` to start and drive a REAL browser against. It reuses the CYP-106 journey
 * harness verbatim: `FakeSpawner` + `FakeGit`, so **no real `claude`, no API key, no real repo** (this
 * is why the E2E gate does not need Human-Go — nothing is spawned that costs tokens).
 *
 * What it stands up:
 *  - one project `alpha` (PO `po` + worker [SEED_AGENT]) → the hub-and-spoke channel [SEED_CHANNEL],
 *  - a DETERMINISTIC agent-events transcript for [SEED_AGENT] (seq 1..[SEED_EVENT_COUNT]) appended straight
 *    into `booted.agentEventStore` — the same store `/ws/agent` replays with `?since=<seq>` (`seq>cursor`,
 *    CYP-198). A fixed corpus is what lets the seq-`?since` + reconnect-idempotency teeth assert exact ranges.
 *  - the reference DOM fixture served **same-origin** at `/` (so the browser's WebSocket handshake is
 *    same-origin — no cross-origin/CORS variable in the teeth).
 *
 * NOT a product artifact and NOT on any `check` path. Run via the `:e2e:webE2eServer` Gradle task; it
 * blocks until Playwright kills the process.
 */
object WebE2eSeed {
    const val SEED_AGENT = "backend"
    const val SEED_CHANNEL = "po-backend"
    const val SEED_EVENT_COUNT = 5
    const val DEFAULT_PORT = 8791
    const val PROJECT = "alpha"
    // CYP-422 §A-P2-c XSS-at-detail probe: an HTML/JS payload placed in an Event-Log event's `detail`, so the
    // parity tooth can prove the Event-Log renders `detail` INERT (escaped text, no element injected, no onerror).
    const val XSS_PROBE = "<img src=x onerror=\"window.__xssFired=true\">"
}

fun main() {
    val port = System.getenv("WEB_E2E_PORT")?.toIntOrNull() ?: WebE2eSeed.DEFAULT_PORT
    // The reference client lives in the web-e2e node module (one copy, the executable contract the product
    // SPA must satisfy). The Gradle task runs with workingDir = repo root, so this relative path resolves.
    val fixtureFile = File("web-e2e/fixture/index.html")
    val fixture = if (fixtureFile.exists()) fixtureFile.readText()
    else "<!doctype html><meta charset=utf-8><title>web-e2e fixture missing</title><body>fixture not found at ${fixtureFile.absolutePath}"

    // Assigned after the platform boots (below); the emit-xss route reads it at REQUEST time, by which point it is
    // set (the route handler body never runs during setup).
    var sinkHolder: EventSink? = null
    val platform = e2ePlatform(
        projects = listOf(
            SeedProject(
                WebE2eSeed.PROJECT,
                agents = listOf(SeedAgent("po", Role.PO), SeedAgent(WebE2eSeed.SEED_AGENT)),
            ),
        ),
        port = port,
        // CYP-422 Phase-1 parity: allow the web-ts product SPA's dev/preview origin (Vite :8080) to reach the
        // API/WS cross-origin (the production topology: SPA origin ≠ API origin + CORS). Both host spellings so
        // a browser navigating to either resolves. Overridable via WEB_TS_ORIGINS (comma-separated).
        webAllowedOrigins = (System.getenv("WEB_TS_ORIGINS")
            ?: "http://127.0.0.1:8080,http://localhost:8080").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        extraRoutes = {
            get("/") { call.respondText(fixture, ContentType.Text.Html) }
            // CYP-422 §A-P2-c: emit the XSS-probe Event-Log event LIVE on demand. /ws/events streams live (no
            // history replay), so the parity tooth connects first, then triggers this, then asserts inert render.
            get("/test/emit-xss") {
                sinkHolder?.append(
                    EventDraft(
                        agentId = WebE2eSeed.SEED_AGENT,
                        projectId = WebE2eSeed.PROJECT,
                        type = EventType.TOOL_RESULT,
                        severity = Severity.INFO,
                        sourceTs = 10L,
                        detail = buildJsonObject { put("xssProbe", WebE2eSeed.XSS_PROBE) },
                    ),
                )
                call.respondText("ok")
            }
        },
    )
    sinkHolder = platform.booted.eventSink

    // Deterministic seq corpus for the agent-events feed. Fixed content + increasing ts so the teeth can
    // assert exact seq ranges on `?since`. seq is assigned by the store (gapless, 1-based).
    runBlocking {
        repeat(WebE2eSeed.SEED_EVENT_COUNT) { i ->
            platform.booted.agentEventStore.append(
                agentId = WebE2eSeed.SEED_AGENT,
                projectId = WebE2eSeed.PROJECT,
                tsMs = (i + 1).toLong(),
                event = ResultEvent(subtype = "success", sessionId = "s1"),
            )
        }
        // CYP-452 §A6 Browse corpus — Event-Log events on the REST-history path (GET /api/events). Boot-seeded, so
        // they appear in the Browse panel but NOT the live-only tail (the two-path discriminator). Distinctive
        // detail markers ("BROWSE-SEED-*") + mixed severity let the tooth assert content and a server-side filter.
        platform.booted.eventSink.append(
            EventDraft(WebE2eSeed.SEED_AGENT, WebE2eSeed.PROJECT, EventType.TOOL_CALL, Severity.INFO,
                detail = buildJsonObject { put("browseSeed", "BROWSE-SEED-A") }),
        )
        platform.booted.eventSink.append(
            EventDraft(WebE2eSeed.SEED_AGENT, WebE2eSeed.PROJECT, EventType.TOOL_RESULT, Severity.WARN,
                detail = buildJsonObject { put("browseSeed", "BROWSE-SEED-B") }),
        )
        platform.booted.eventSink.append(
            EventDraft("po", WebE2eSeed.PROJECT, EventType.ERROR_TOOL, Severity.ERROR,
                detail = buildJsonObject { put("browseSeed", "BROWSE-SEED-C") }),
        )
    }

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { platform.close() } })
    // A single machine-readable readiness line for Playwright's `webServer` log (it waits on the /api/health url).
    println("WEB_E2E_READY url=${platform.baseUrl} seq=${WebE2eSeed.SEED_EVENT_COUNT} channel=${WebE2eSeed.SEED_CHANNEL} operatorToken=${E2ePlatform.OPERATOR_TOKEN}")
    System.out.flush()
    Thread.currentThread().join() // block until Playwright tears the process down
}
