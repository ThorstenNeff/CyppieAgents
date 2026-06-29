package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.routing.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-104 (Low, links CYP-96) — secret-redaction floor + API-key plausibility. No real keys: all values
 * are synthetic. Two axes: (1) [Secrets.mask] never reveals a plaintext tail for short values; (2)
 * [ProjectConfigStore.setApiKey] rejects an implausibly short / whitespace-y key (`invalid_api_key`),
 * prefix-tolerantly (a normal long key is accepted).
 */
class KeyRedactionTest {

    private fun store() = ProjectConfigStore(
        file = null,
        fallbackRepo = RepoConfig("git@github.com:org/repo.git", "main"),
        secrets = Secrets(mapOf("t" to "po"), operatorToken = "op", apiKey = null),
    )

    // ---- mask redaction floor ----

    @Test fun mask_shortValue_noPlaintextTail() {
        // A 4-char value would otherwise render as "***abcd" — the WHOLE secret. Floor → "****".
        assertEquals("****", Secrets.mask("abcd"))
        assertEquals("****", Secrets.mask("a".repeat(Secrets.MIN_SECRET_LEN - 1)), "just below the floor → fully redacted")
    }

    @Test fun mask_longValue_keepsLast4() {
        // >= floor: the last4 tail is a small, acceptable fraction.
        assertEquals("***cd12", Secrets.mask("abXYZqrstucd12")) // len 14
        assertEquals("***WXYZ", Secrets.mask("0123456789WXYZ")) // len 14
    }

    @Test fun mask_empty_unset() {
        assertEquals("unset", Secrets.mask(null))
        assertEquals("unset", Secrets.mask(""))
    }

    // ---- setApiKey plausibility (prefix-tolerant) ----

    @Test fun setApiKey_tooShort_rejected() {
        val e = assertFailsWith<BadRequestException> { store().setApiKey("default", "short") }
        assertEquals("invalid_api_key", e.code)
    }

    @Test fun setApiKey_whitespace_rejected() {
        val e = assertFailsWith<BadRequestException> { store().setApiKey("default", "has space in it here") }
        assertEquals("invalid_api_key", e.code)
    }

    @Test fun setApiKey_blank_rejected() {
        assertEquals("invalid_api_key", assertFailsWith<BadRequestException> { store().setApiKey("default", "   ") }.code)
    }

    @Test fun setApiKey_plausibleLongKey_accepted_andMaskedTail() {
        // prefix-tolerant: NO specific prefix required; a normal-length key is accepted and masked to last4.
        val view = store().setApiKey("default", "synthetic-key-value-0000ABCD")
        assertTrue(view.set)
        assertEquals("***ABCD", view.masked, "accepted key is masked to ***<last4>, never returned in full")
    }
}
