package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AgentMgmtGuard
import com.tneff.cyppieagents.routing.TokenRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CYP-171 / E2.6 (S3) — runtime token mint security (SEC1/SEC2/SEC5) + the SEC-OP1 drift guard. */
class TokenRegistryTest {

    private fun registry(seed: Map<String, String> = emptyMap()) = TokenRegistry(seed, operatorToken = "op-secret-token", loopbackPosture = true)

    /** SEC1 — minted tokens are high-entropy, base64url, all distinct, and never collide with the operator
     *  token. (Mutation: a constant/low-entropy token → duplicates → reds.) */
    @Test
    fun mint_isHighEntropy_distinct_andNeverTheOperatorToken() {
        val reg = registry()
        val tokens = (1..1000).map { reg.mint("backend") }
        assertEquals(1000, tokens.toSet().size, "every minted token is unique")
        tokens.forEach { t ->
            assertTrue(t.length >= 43, "256-bit base64url token is ~43 chars: $t")
            assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(t), "base64url charset only: $t")
            assertNotEquals("op-secret-token", t, "a minted token is never the operator token")
        }
    }

    /** SEC2 — `token→agentId` is the SOLE identity: a minted token resolves to its server-assigned agentId;
     *  no other token resolves to it. (Mutation: mint not registering → agentFor null → reds.) */
    @Test
    fun mint_registersTokenToAgent_andNothingElseResolves() {
        val reg = registry()
        val token = reg.mint("backend")
        assertEquals("backend", reg.agentFor(token))
        assertNull(reg.agentFor("some-other-token"))
        assertNull(reg.agentFor(null))
        // the operator token is NOT an agent identity
        assertNull(reg.agentFor("op-secret-token"))
        assertTrue(reg.isOperator("op-secret-token"))
    }

    /** SEC5 — revoke removes the binding: after removal a minted token no longer resolves (→ wire close). */
    @Test
    fun revoke_makesTheTokenStopResolving() {
        val reg = registry()
        val token = reg.mint("backend")
        assertEquals("backend", reg.agentFor(token))
        reg.revoke("backend")
        assertNull(reg.agentFor(token), "a revoked agent's token no longer resolves")
    }

    /** Boot restore: a persisted (token→agentId) binding is loaded via [TokenRegistry.bind]. */
    @Test
    fun bind_loadsAPersistedBinding() {
        val reg = registry()
        reg.bind("restored-token", "backend")
        assertEquals("backend", reg.agentFor("restored-token"))
    }

    /** Seeded boot tokens (env) still resolve alongside minted ones. */
    @Test
    fun seededTokensResolve() {
        val reg = registry(mapOf("tok-po" to "po"))
        assertEquals("po", reg.agentFor("tok-po"))
    }

    /**
     * SEC-OP1 drift guard (single-source): the `:core` reserved set MUST contain the `:server`
     * `HubState.OPERATOR_ID`, so the guard ([AgentMgmtGuard.validateAdd]) can never let an agent claim the
     * operator participant id. If OPERATOR_ID ever changes, this reds until the reserved set follows.
     */
    @Test
    fun operatorId_isInTheReservedAgentIdSet() {
        assertTrue(
            HubState.OPERATOR_ID in AgentMgmtGuard.RESERVED_AGENT_IDS,
            "HubState.OPERATOR_ID ('${HubState.OPERATOR_ID}') must be reserved against agent-id collision",
        )
    }
}
