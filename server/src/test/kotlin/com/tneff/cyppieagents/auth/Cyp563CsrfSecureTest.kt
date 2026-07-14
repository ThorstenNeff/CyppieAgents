package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-563 — the CSRF cookie's `Secure` attribute is env-gated ([cookiesShouldBeSecure]): ON iff the deployment sets
 * `CYPPIE_COOKIE_SECURE=true` (prod over TLS), OFF by default so localhost dev (plain http) still receives the cookie.
 * Defense-in-depth only (the token is a double-submit value; SameSite=Strict + same-origin is the real barrier), so
 * the default-off keeps dev working while prod opts in.
 *
 * ★ Mutation: hard-wire [cookiesShouldBeSecure] to `false` → [secure_whenEnvTrue] reds.
 */
class Cyp563CsrfSecureTest {

    @Test fun secure_whenEnvTrue() {
        assertTrue(cookiesShouldBeSecure { if (it == "CYPPIE_COOKIE_SECURE") "true" else null }, "CYPPIE_COOKIE_SECURE=true → Secure")
        assertTrue(cookiesShouldBeSecure { if (it == "CYPPIE_COOKIE_SECURE") "TRUE" else null }, "case-insensitive")
        assertTrue(cookiesShouldBeSecure { if (it == "CYPPIE_COOKIE_SECURE") " true " else null }, "trimmed")
    }

    @Test fun notSecure_byDefault_forLocalhostDev() {
        assertFalse(cookiesShouldBeSecure { null }, "unset → default off (localhost dev plain http)")
        assertFalse(cookiesShouldBeSecure { if (it == "CYPPIE_COOKIE_SECURE") "false" else null }, "explicit false → off")
        assertFalse(cookiesShouldBeSecure { if (it == "CYPPIE_COOKIE_SECURE") "1" else null }, "only the literal true opts in")
    }
}
