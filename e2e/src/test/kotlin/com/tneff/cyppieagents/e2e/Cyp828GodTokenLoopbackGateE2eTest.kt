package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.model.AuthMe
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-828 (god-token / operatorEligible) — the loopback-gate proven at the REAL HTTP/WS request paths (QA
 * behavioral layer, defense-in-depth over Backend's unit/source-scan teeth `Cyp828OperatorEligibleTest`).
 *
 * The invariant (design v3.3): the static god-token grants OPERATOR authority ONLY on a loopback-bound hub; on
 * EVERY off-loopback (remote-reachable) surface it is structurally DENIED at every grant seam — including the
 * `:132` MEMBER-downgrade (off-loopback → 401, not a MEMBER read grant = the CYP-234b/V3-1 metadata leak).
 *
 * The toggle is CONFIG-driven: `loopbackPosture = isLoopbackHost(config.hub.host)`, decoupled from the actual
 * socket bind. So [hubHost] = "127.0.0.1" → posture ON (operator grants), "0.0.0.0" → posture OFF (all seams
 * deny) — hermetic, no non-loopback socket. The god-token is [E2ePlatform.OPERATOR_TOKEN] (Bearer).
 *
 * Non-vacuity is the PAIR per seam: the on-loopback POSITIVE CONTROL proves the seam actually grants (so the
 * off-loopback denial is the GATE biting, not a broken request/fixture), then the off-loopback DENIAL.
 *
 * This file: Phase 1a — the `/api` HTTP seams that route through `resolvePrincipal` (#1 operator route, #5 `/me`,
 * and the `:132` T1d(a) off-loopback→401). #2/#3 (`/ws/terminal`, `/ws/agent`), the tunnel-reject, T1d(b)
 * kill-switch, and #4 (`/api/events` message BODIES, CYP-432) land in the sibling files.
 */
class Cyp828GodTokenLoopbackGateE2eTest {

    private val godToken = E2ePlatform.OPERATOR_TOKEN

    private fun projects() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    /** A hub whose CONFIG host is loopback → `loopbackPosture` ON (the god-token is operator-eligible). */
    private fun onLoopback(block: suspend (E2ePlatform) -> Unit) = runBlocking {
        e2ePlatform(projects(), hubHost = "127.0.0.1").use { block(it) }
    }

    /** A hub whose CONFIG host is NON-loopback → `loopbackPosture` OFF (the god-token is denied at every seam).
     *  No non-loopback socket is bound: the server still binds ephemeral 127.0.0.1; only the posture flips. */
    private fun offLoopback(block: suspend (E2ePlatform) -> Unit) = runBlocking {
        e2ePlatform(projects(), hubHost = "0.0.0.0").use { block(it) }
    }

    // ── Seam #1 — the operator `/api` route (Principal.kt:127 operatorEligible → MachineOperator) ─────────────────

    @Test
    fun seam1_apiOperatorRoute_grantedOnLoopback_deniedOffLoopback() {
        // Positive control: on loopback the god-token IS the operator → the operator-only roster is granted.
        onLoopback { p ->
            val r: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/workspace/members") }
            assertEquals(HttpStatusCode.OK, r.status, "on loopback the god-token grants MachineOperator → operator route 200")
        }
        // ★ The gate: off loopback the same god-token is operator-INELIGIBLE → no principal → 401.
        offLoopback { p ->
            val r: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/workspace/members") }
            assertEquals(HttpStatusCode.Unauthorized, r.status,
                "★ off loopback the god-token is denied at the operator route (401, not MachineOperator)")
        }
    }

    // ── Seam #5 — /api/auth/me (Principal.kt:178 operatorEligible → AuthMe(OPERATOR)) ─────────────────────────────

    @Test
    fun seam5_authMe_reportsOperatorOnLoopback_notOffLoopback_noSplitBrain() {
        onLoopback { p ->
            val me: AuthMe = p.asOperator().use { it.get("${p.baseUrl}/api/auth/me").body() }
            assertEquals(true, me.authenticated, "on loopback /me authenticates the god-token")
            assertEquals(AuthRole_OPERATOR, me.role, "on loopback /me reports role OPERATOR")
        }
        // ★ The gate: off loopback /me must NOT claim OPERATOR (the split-brain the fix closes) → authenticated:false.
        offLoopback { p ->
            val me: AuthMe = p.asOperator().use { it.get("${p.baseUrl}/api/auth/me").body() }
            assertEquals(false, me.authenticated,
                "★ off loopback /me does NOT authenticate the god-token as OPERATOR (no split-brain)")
        }
    }

    // ── T1d(a) — the :132 MEMBER-downgrade is loopback-gated: off-loopback → 401, NOT a MEMBER read grant ─────────

    @Test
    fun t1dA_member132Downgrade_offLoopbackIs401_notAMemberEventLogGrant() {
        // Positive control: on loopback the god-token reads the MEMBER-tier event log (operator ≥ member) → 200.
        onLoopback { p ->
            val r: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/events") }
            assertEquals(HttpStatusCode.OK, r.status, "on loopback the god-token reads the event log")
        }
        // ★ The gate (T1d(a)): off loopback the god-token must NOT fall through to a MEMBER read (the cross-agent
        //   /api/events metadata leak the :132 gate closes) — it is fully denied 401, not 200-as-member.
        offLoopback { p ->
            val r: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/events") }
            assertEquals(HttpStatusCode.Unauthorized, r.status,
                "★ off loopback the god-token is 401 on the MEMBER-tier event log — NOT silently downgraded to a MEMBER read")
        }
    }

    // ── WS helper: connect [path], return the server's close code, or null if the socket STAYS OPEN past [waitMs]
    //    (a granted/authorized connection never closes → `closeReason.await()` blocks → the withTimeout unwinds → null). ─
    private suspend fun wsCloseCodeOrOpen(client: HttpClient, wsBaseUrl: String, path: String, waitMs: Long = 2_000L): Short? {
        var code: Short? = null
        runCatching { withTimeout(waitMs) { client.webSocket("$wsBaseUrl$path") { code = closeReason.await()?.code } } }
        return code // null = stayed open (granted); a code (e.g. 1008 VIOLATED_POLICY) = closed (denied)
    }

    private val revoked = CloseReason.Codes.VIOLATED_POLICY.code

    // ── Seam #2 — /ws/terminal PTY take-over (TerminalAccess.kt:173 operatorEligible → TerminalPrincipal.Operator) ──

    @Test
    fun seam2_wsTerminalTakeover_grantedOnLoopback_deniedOffLoopback() {
        // Positive control: on loopback the god-token is TerminalPrincipal.Operator → the take-over socket STAYS OPEN.
        onLoopback { p ->
            val code = p.client().use { wsCloseCodeOrOpen(it, p.wsBaseUrl, "/ws/terminal?agentId=backend&token=$godToken") }
            assertEquals(null, code, "on loopback the god-token opens the /ws/terminal take-over (Operator) — socket stays open")
        }
        // ★ The gate: off loopback the god-token is not Operator → the PTY take-over socket is CLOSED fail-closed.
        offLoopback { p ->
            val code = p.client().use { wsCloseCodeOrOpen(it, p.wsBaseUrl, "/ws/terminal?agentId=backend&token=$godToken") }
            assertNotEquals(null, code, "★ off loopback the god-token PTY take-over is DENIED — the socket closes fail-closed")
        }
    }

    // ── Seam #3 — /ws/agent observe-any (AgentSocket.kt:65 operatorEligible → true) ───────────────────────────────

    @Test
    fun seam3_wsAgentObserveAny_grantedOnLoopback_1008OffLoopback() {
        // Positive control: on loopback the god-token may observe ANOTHER agent's stream → the socket STAYS OPEN.
        onLoopback { p ->
            val code = p.client().use { wsCloseCodeOrOpen(it, p.wsBaseUrl, "/ws/agent?agentId=backend&token=$godToken") }
            assertEquals(null, code, "on loopback the god-token observes another agent's stream — socket stays open")
        }
        // ★ The gate: off loopback the god-token cannot observe another agent → 1008 (VIOLATED_POLICY), the fail-closed close.
        offLoopback { p ->
            val code = p.client().use { wsCloseCodeOrOpen(it, p.wsBaseUrl, "/ws/agent?agentId=backend&token=$godToken") }
            assertEquals(revoked, code, "★ off loopback the god-token's observe-any is denied → /ws/agent closes 1008")
        }
    }

    // ── Seam #4 — /api/channels/{id}/messages BODY confidentiality (Auth.kt:110 participantFor → OPERATOR_ID;
    //    EventAclFilter/comm ACL: an operator (OPERATOR_ID = member-of-all) reads every body). The SHARPEST seam:
    //    the CYP-432 CONTENT-confidentiality boundary (complementary to T1d(a)'s content-free event-log metadata). ─────
    @Test
    fun seam4_channelMessageBodies_readableByOperatorOnLoopback_deniedOffLoopback() = runBlocking {
        val secret = "cyp828-confidential-message-body-do-not-egress"

        // ★ Positive control ON loopback — EXPLICITLY non-empty (PO's anti-vacuity crux, CYP-432, no shortcut):
        //   the operator (OPERATOR_ID member-of-all) reads the ACTUAL seeded body. If this showed empty for an
        //   unrelated reason, the off-loopback denial below would be vacuous (nothing to filter).
        e2ePlatform(projects(), hubHost = "127.0.0.1").use { p ->
            p.booted.store.append(Message("cyp828-m1", "po-backend", "backend", secret, ts = 1, projectId = "default"))
            val resp: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/channels/po-backend/messages?since=0") }
            assertEquals(HttpStatusCode.OK, resp.status, "positive control: the operator reads the channel (200)")
            // Read as TEXT (the read route returns a wire envelope, not raw Message — CYP-744 DeliveredMessage shape;
            // the point is the CONTENT, so assert the raw body appears — robust to the wire shape, and EXPLICITLY non-empty.
            assertTrue(resp.bodyAsText().contains(secret),
                "★ the operator reads the ACTUAL message-body CONTENT (the sensitive CYP-432 surface is genuinely egressed)")
        }

        // ★ The gate OFF loopback: the SAME body is seeded, but the god-token is operator-INELIGIBLE → not OPERATOR_ID
        //   → not a channel member → the confidential body is NOT egressed (401). Content-confidentiality holds.
        e2ePlatform(projects(), hubHost = "0.0.0.0").use { p ->
            p.booted.store.append(Message("cyp828-m2", "po-backend", "backend", secret, ts = 1, projectId = "default"))
            val r: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/channels/po-backend/messages?since=0") }
            assertEquals(HttpStatusCode.Unauthorized, r.status,
                "★ off loopback the god-token cannot egress the message BODY content (401) — the CYP-432 boundary holds")
        }
    }

    // ── T1d(b) — ON loopback + kill-switch: the god-token is DOWNGRADED to MEMBER (never-lock-out), NOT locked out ──
    //    (`operatorTokenDisabled ∧ hasOperator` at Principal.kt:127 skips the OPERATOR grant → :137 operatorEligible →
    //     MachineAgent(null) = MEMBER read-tier). The loopback-`&&` must NOT be so strict it denies this legit case.
    @Test
    fun t1dB_onLoopbackKillSwitch_godTokenDowngradedToMember_neverLockOutPreserved() = runBlocking {
        // A role store that genuinely HAS an operator (so the kill-switch is "effective") — the never-lock-out guard.
        val roles = InMemoryRoleStore(bootstrapOperatorId = "human-op").also { it.ensureAssigned("human-op", 0L) }
        val killSwitchAuth = AuthDeps(
            // ON loopback (loopbackPosture = true) so the gate is NOT what denies — the kill-switch is.
            tokens = TokenRegistry(emptyMap(), operatorToken = godToken, loopbackPosture = true),
            idp = FakeIdentityProvider(emptyMap()),
            roles = roles,
            nowMs = { 0L },
            operatorTokenDisabled = true, // the deploy kill-switch is SET, and a role-OPERATOR exists → token is INERT
        )
        e2ePlatform(projects(), authDeps = killSwitchAuth).use { p ->
            // Downgraded, not operator: the operator-only route is 403 (MEMBER), NOT 200.
            val op: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/workspace/members") }
            assertEquals(HttpStatusCode.Forbidden, op.status,
                "kill-switch: the god-token is downgraded to MEMBER → operator route 403 (no longer OPERATOR)")
            // ★ Never-lock-out PRESERVED: the same downgraded token STILL reads the MEMBER-tier event log (200, not 401).
            val member: HttpResponse = p.asOperator().use { it.get("${p.baseUrl}/api/events") }
            assertEquals(HttpStatusCode.OK, member.status,
                "★ never-lock-out: the kill-switched god-token is a MEMBER (reads the event log 200), NOT fully locked out")
        }
    }

    private companion object {
        // AuthMe.role carries the enum NAME string; avoid importing the server enum into the e2e test.
        const val AuthRole_OPERATOR = "OPERATOR"
    }
}
