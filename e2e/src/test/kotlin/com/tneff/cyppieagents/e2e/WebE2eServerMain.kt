package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Role
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
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

    /**
     * CYP-407 (W9 AC-verify) — OPT-IN second worker, off by default.
     *
     * The default seed is one channel whose every agent is a member, which makes two W9 acceptance criteria
     * structurally UNMEASURABLE rather than passing:
     *  - the non-member cell (marker instead of switches) — there is no non-member pair to render;
     *  - the partial view (§4: an agent sees only its own channels) — with a single channel, "filters to the
     *    caller's channels" is indistinguishable from "returns everything". A green there would be vacuous.
     *
     * A second worker fixes both at once: it adds the spoke `po-<extra>` AND makes each worker a non-member of
     * the other's spoke. Kept behind an env var because this seed is shared by the e2e gate, whose teeth assert
     * exact channel/agent sets — changing the DEFAULT contract to serve one verification would be a poor trade.
     */
    const val EXTRA_AGENT_ENV = "WEB_E2E_EXTRA_AGENT"
}

fun main() {
    val port = System.getenv("WEB_E2E_PORT")?.toIntOrNull() ?: WebE2eSeed.DEFAULT_PORT
    // The reference client lives in the web-e2e node module (one copy, the executable contract the product
    // SPA must satisfy). The Gradle task runs with workingDir = repo root, so this relative path resolves.
    val fixtureFile = File("web-e2e/fixture/index.html")
    val fixture = if (fixtureFile.exists()) fixtureFile.readText()
    else "<!doctype html><meta charset=utf-8><title>web-e2e fixture missing</title><body>fixture not found at ${fixtureFile.absolutePath}"

    // CYP-407: opt-in second worker → a second spoke channel + a non-member pair in both directions. Blank/unset
    // (the default, and what the e2e gate runs) leaves the seed byte-for-byte as before.
    val extraAgent = System.getenv(WebE2eSeed.EXTRA_AGENT_ENV)?.takeIf { it.isNotBlank() }
    val seededAgents = buildList {
        add(SeedAgent("po", Role.PO))
        add(SeedAgent(WebE2eSeed.SEED_AGENT))
        if (extraAgent != null) add(SeedAgent(extraAgent))
    }

    val platform = e2ePlatform(
        projects = listOf(
            SeedProject(
                WebE2eSeed.PROJECT,
                agents = seededAgents,
            ),
        ),
        port = port,
        extraRoutes = {
            get("/") { call.respondText(fixture, ContentType.Text.Html) }
        },
    )

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
    }

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { platform.close() } })
    // A single machine-readable readiness line for Playwright's `webServer` log (it waits on the /api/health url).
    println("WEB_E2E_READY url=${platform.baseUrl} seq=${WebE2eSeed.SEED_EVENT_COUNT} channel=${WebE2eSeed.SEED_CHANNEL} operatorToken=${E2ePlatform.OPERATOR_TOKEN}${if (extraAgent != null) " extraAgent=$extraAgent" else ""}")
    System.out.flush()
    Thread.currentThread().join() // block until Playwright tears the process down
}
