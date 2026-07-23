package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.auth.resolvePrincipal
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.commReadSubjectOf
import com.tneff.cyppieagents.routing.wsReaderOrNull
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-719 §3 — the cross-surface SUBJECT-EQUIVALENCE tooth (PO-required gate tooth). The REST read path derives
 * its ACL subject from the guard-stashed principal via [commReadSubjectOf] (whoami-free, preserving the CYP-240
 * single-resolve invariant); the WS path uses [wsReaderOrNull]. They MUST resolve the SAME credential to the
 * SAME subject, or the two surfaces would ACL-filter comm.* differently. A probe route reports both for one
 * credential; the test asserts they agree AND equal the canonical value (anti-vacuity — not "both null").
 *
 * Mutation: mis-map any principal class in [commReadSubjectOf] (e.g. Human-MEMBER → OPERATOR_ID) → the `rest`
 * half diverges from the `ws` half for that credential → RED.
 */
class Cyp719SubjectEquivalenceTest {

    @Test fun restSubjectEqualsWsSubject_perPrincipalClass() = testApplication {
        val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")
        val idp = FakeIdentityProvider(
            mapOf(
                "sess-op" to ResolvedIdentity("op-1", verified = true, aal2 = true),
                "sess-mem" to ResolvedIdentity("mem-1", verified = true),
            ),
        )
        // op-1 is the pinned bootstrap operator → ensureAssigned("op-1") = OPERATOR; any other identity = MEMBER.
        val deps = AuthDeps(registry, idp, InMemoryRoleStore(bootstrapOperatorId = "op-1"), { 1L }, browserOperatorPostureEnabled = true)
        application {
            routing {
                get("/probe") {
                    val ws = call.wsReaderOrNull(deps, registry) ?: "null"
                    val rest = call.resolvePrincipal(deps)?.let { commReadSubjectOf(it) } ?: "null"
                    call.respondText("$ws|$rest")
                }
            }
        }

        // Each row: (how to auth, the canonical subject both surfaces must produce).
        val operatorToken = client.get("/probe") { bearerAuth("tok-op") }.bodyAsText().split("|")
        assertEquals(listOf("operator", "operator"), operatorToken, "operator token → OPERATOR_ID on both surfaces")

        val agentToken = client.get("/probe") { bearerAuth("tok-be") }.bodyAsText().split("|")
        assertEquals(listOf("backend", "backend"), agentToken, "agent token → agentId on both surfaces")

        val humanOperator = client.get("/probe") { header("X-Session-Token", "sess-op") }.bodyAsText().split("|")
        assertEquals(listOf("operator", "operator"), humanOperator, "human OPERATOR → OPERATOR_ID on both surfaces")

        val humanMember = client.get("/probe") { header("X-Session-Token", "sess-mem") }.bodyAsText().split("|")
        assertEquals(listOf("mem-1", "mem-1"), humanMember, "human MEMBER → identityId on both surfaces")
    }
}
