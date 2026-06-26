package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.SecretMasker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reviewer Gate #3: token-shaped strings are redacted on EVERY egress, including tool I/O. */
class SecretMaskerTest {

    @Test
    fun masksAnthropicKey() {
        val masked = SecretMasker.mask("here is sk-ant-api03-ABCdef123456789_secret-token tail")
        assertFalse(masked.contains("sk-ant-api03-ABCdef"))
        assertTrue(masked.contains(SecretMasker.REDACTED))
    }

    @Test
    fun masksEnvAssignmentButKeepsLabel() {
        val masked = SecretMasker.mask("ANTHROPIC_API_KEY=sk-ant-superlongsecretvalue1234")
        assertTrue(masked.startsWith("ANTHROPIC_API_KEY="))
        assertFalse(masked.contains("superlongsecretvalue"))
    }

    @Test
    fun masksBearerHeader() {
        val masked = SecretMasker.mask("Authorization: Bearer abcDEF1234567890token")
        assertTrue(masked.contains("Bearer ${SecretMasker.REDACTED}"))
        assertFalse(masked.contains("abcDEF1234567890token"))
    }

    @Test
    fun masksGithubAndAwsTokens() {
        assertFalse(SecretMasker.mask("ghp_0123456789abcdefABCDEF0123456789abcd").contains("ghp_0123"))
        assertFalse(SecretMasker.mask("AKIAIOSFODNN7EXAMPLE").contains("AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun leavesOrdinaryTextUntouched() {
        val text = "deployed the backend service to staging, all green"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun masksToolInputJsonRecursively() {
        // tool_use.input carrying a command that dumps an env var (Gate #3 egress path).
        val input = buildJsonObject {
            put("command", "echo \$ANTHROPIC_API_KEY=sk-ant-leakedkey1234567890")
            put("description", "harmless")
        }
        val masked = SecretMasker.maskJson(input).jsonObject
        assertFalse(masked["command"].toString().contains("leakedkey"))
        assertEquals("\"harmless\"", masked["description"].toString())
    }
}
