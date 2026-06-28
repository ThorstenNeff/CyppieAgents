package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.Secrets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Per-project API-key resolution (S12 / CYP-82 — Doc 05 D3): the key is resolved by `projectId`, not
 * a single global server constant. Lays the CYP-96 seam; MVP=1 falls back to the team key.
 */
class SecretsKeyResolutionTest {

    @Test
    fun perProjectOverrideWins_andDiffersAcrossProjects() {
        // Mutation: make apiKeyFor ignore projectId (return apiKey always) → these go red.
        val s = Secrets(
            agentTokens = mapOf("t" to "po"),
            operatorToken = "op",
            apiKey = "sk-team-default",
            apiKeysByProject = mapOf("alpha" to "sk-alpha", "beta" to "sk-beta"),
        )
        assertEquals("sk-alpha", s.apiKeyFor("alpha"))
        assertEquals("sk-beta", s.apiKeyFor("beta"))
    }

    @Test
    fun fallsBackToTeamKeyWhenNoOverride() {
        val s = Secrets(mapOf("t" to "po"), "op", apiKey = "sk-team-default")
        assertEquals("sk-team-default", s.apiKeyFor("default"))
        assertEquals("sk-team-default", s.apiKeyFor("anything"))
    }

    @Test
    fun nullTeamKeyAndNoOverrideResolvesNull() {
        val s = Secrets(mapOf("t" to "po"), "op", apiKey = null)
        assertNull(s.apiKeyFor("default"))
    }

    @Test
    fun fromEnvResolvesPerProjectKeyWithFallback() {
        val env = mapOf(
            "HUB_TOKEN_PO" to "tp",
            "OPERATOR_TOKEN" to "op",
            "ANTHROPIC_API_KEY" to "sk-global",
            "ANTHROPIC_API_KEY_ALPHA" to "sk-alpha-env",
        )
        val s = Secrets.fromEnv(listOf("po"), listOf("alpha", "beta")) { env[it] }
        assertEquals("sk-alpha-env", s.apiKeyFor("alpha")) // per-project env override
        assertEquals("sk-global", s.apiKeyFor("beta"))     // no override → team key
        // masked toString never reveals any key value
        assertFalse(s.toString().contains("sk-alpha-env"))
        assertFalse(s.toString().contains("sk-global"))
    }
}
