package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ParticipantTokenStore
import com.tneff.cyppieagents.comm.Clock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.install
import io.ktor.server.response.respondText as respondTextApp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-234b (QA — the missing per-subject rate-limit tooth) — `requireCommReader`/`requireParticipant` apply a
 * per-RESOLVED-SUBJECT token-bucket to a participant token and throw 429 on exhaustion (Auth.kt participantSubject).
 * The [WireRateLimiter] bucket logic itself is proven by HubWireRateLimitTest; this pins the PARTICIPANT wiring:
 *  - 429 once the subject's bucket is empty (the feature works end-to-end through the resolver),
 *  - the bucket key is the RESOLVED SUBJECT, not the raw token — two tokens of ONE subject share a bucket
 *    (RC3-safe / bounded; keying by the raw token would let a holder rotate tokens to bypass the limit),
 *  - an INVALID token resolves to null and NEVER touches the limiter (no 429 for garbage; no unbounded bucket
 *    creation from garbage → a memory-exhaustion guard) — it 401s instead.
 * Frozen [Clock] → no refill → deterministic (no wall-clock race).
 */
class ParticipantRateLimitTest {
    private class FakeClock : Clock { override fun now(): Long = 0L }

    private val store = ParticipantTokenStore { 1_000L }
    private val reg = TokenRegistry(mapOf("tok-agent" to "backend"), "tok-op")
    private fun depsWith(cap: Int) = AuthDeps(
        tokens = reg, idp = FakeIdentityProvider(emptyMap()), roles = InMemoryRoleStore(), nowMs = { 1_000L },
        participantTokens = store, participantRateLimiter = WireRateLimiter(capacity = cap, clock = FakeClock()),
    )

    private fun app(cap: Int, block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        val deps = depsWith(cap)
        application {
            install(StatusPages) { exception<ApiException> { call, cause -> call.respondTextApp(text = "", status = cause.status) } }
            routing { get("/read") { call.respondText(call.requireCommReader(deps, reg)) } }
        }
        block(client)
    }

    @Test
    fun participantRead_429OnExhaustion_perResolvedSubject() = app(cap = 1) { client ->
        val a = store.mint("subj-1"); val b = store.mint("subj-1") // TWO tokens, SAME subject
        assertEquals(HttpStatusCode.OK, client.get("/read") { header("Authorization", "Bearer $a") }.status, "1st read drains subj-1's bucket")
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/read") { header("Authorization", "Bearer $a") }.status, "2nd read on the same token → 429")
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/read") { header("Authorization", "Bearer $b") }.status, "a DIFFERENT token of the SAME subject shares the bucket → 429 (keyed by resolved subject, not token)")
    }

    @Test
    fun invalidToken_neverTouchesTheLimiter() = app(cap = 1) { client ->
        val good = store.mint("subj-2")
        // garbage resolves to null BEFORE the limiter → 401, and creates/drains no bucket…
        assertEquals(HttpStatusCode.Unauthorized, client.get("/read") { header("Authorization", "Bearer garbage-xyz") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/read") { header("Authorization", "Bearer garbage-xyz") }.status)
        // …so the valid token still has its FULL capacity-1 bucket (garbage didn't consume it).
        assertEquals(HttpStatusCode.OK, client.get("/read") { header("Authorization", "Bearer $good") }.status, "a valid token keeps its full bucket — garbage never touched the limiter")
    }
}
