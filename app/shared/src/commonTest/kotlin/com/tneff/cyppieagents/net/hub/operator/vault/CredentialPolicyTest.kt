package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — the capability→policy contract (UIUX `cffbf9ff`) + the ② passphrase floor. Software-only ⇒ a
 * ≥64-bit **passphrase** (no short PIN, no biometric); hardware-backed ⇒ a short **PIN** is allowed + biometric
 * offered. The strength gate rejects weak inputs and clears diceware/random passphrases.
 */
class CredentialPolicyTest {

    @Test
    fun softwareOnly_requiresPassphrase64bit_noShortPin_noBiometric() {
        val p = CredentialPolicy.forCapability(OperatorAuthCapability.SOFTWARE_ONLY, maxAttempts = 5, backoffBaseMs = 30_000, backoffMaxMs = 900_000)
        assertEquals(CredentialKind.PASSPHRASE, p.credentialKind)
        assertEquals(64, p.minEntropyBits, "② no-hardware floor = 64 bit")
        assertNull(p.minPinDigits, "a short PIN is NEVER offered without hardware protecting it (the anchor)")
        assertFalse(p.offersBiometric)
    }

    @Test
    fun hardwareBacked_allowsShortPin_offersBiometric() {
        val p = CredentialPolicy.forCapability(OperatorAuthCapability.HARDWARE_BACKED, maxAttempts = 5, backoffBaseMs = 30_000, backoffMaxMs = 900_000)
        assertEquals(CredentialKind.PIN, p.credentialKind)
        assertEquals(6, p.minPinDigits)
        assertTrue(p.offersBiometric)
    }

    @Test
    fun policy_singleSourcesTheRateLimitNumbers() {
        val p = CredentialPolicy.forCapability(OperatorAuthCapability.SOFTWARE_ONLY, maxAttempts = 7, backoffBaseMs = 1234, backoffMaxMs = 5678)
        assertEquals(7, p.maxAttempts); assertEquals(1234, p.backoffBaseMs); assertEquals(5678, p.backoffMaxMs)
    }

    @Test
    fun strength_rejectsWeak_clearsStrong() {
        val floor = CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS
        // Weak: short / repeated / low-diversity → below the floor.
        assertFalse(PassphraseStrength.meets("hunter2".toCharArray(), floor))
        assertFalse(PassphraseStrength.meets("aaaaaaaaaaaaaaaa".toCharArray(), floor))
        assertFalse(PassphraseStrength.meets("123456789012".toCharArray(), floor))
        // Strong: 6-word-style diceware + a 12-char random mix → clear the floor.
        assertTrue(PassphraseStrength.meets("correct horse battery staple anchor mint".toCharArray(), floor))
        assertTrue(PassphraseStrength.meets("Xk9\$mQ2!vB7z".toCharArray(), floor))
        assertEquals(0, PassphraseStrength.estimateBits(CharArray(0)), "empty ⇒ 0 bits")
    }

    @Test
    fun blocklist_rejectsFamousAndBreached_evenIfStructurallyLong() {
        val floor = CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS
        // #3: "correct horse battery staple" is structurally ~89 bit but famous ⇒ blocklisted ⇒ meets() false.
        assertTrue(PassphraseStrength.estimateBits("correct horse battery staple".toCharArray()) >= floor, "structurally over the floor")
        assertTrue(PassphraseStrength.isBlocklisted("correct horse battery staple".toCharArray()), "but famous ⇒ blocklisted")
        assertFalse(PassphraseStrength.meets("correct horse battery staple".toCharArray(), floor), "⇒ the gate rejects it")
        assertTrue(PassphraseStrength.isBlocklisted("Password123".toCharArray()), "case-insensitive weak root")
        assertFalse(PassphraseStrength.isBlocklisted("Xk9\$mQ2!vB7z anchor mint".toCharArray()), "a genuine random/diceware passphrase passes")
    }

    @Test
    fun verdict_distinguishesTooWeakFromBlocklisted_fromOk() {
        val floor = CredentialPolicy.SOFTWARE_MIN_ENTROPY_BITS
        // AC-3: three distinct honest causes for the enroll UI.
        assertEquals(StrengthVerdict.TOO_WEAK, PassphraseStrength.verdict("hunter2".toCharArray(), floor), "short/low-entropy = tooWeak")
        assertEquals(StrengthVerdict.BLOCKLISTED, PassphraseStrength.verdict("correct horse battery staple".toCharArray(), floor), "famous phrase = blocklisted (not tooWeak, even though structurally long)")
        assertEquals(StrengthVerdict.OK, PassphraseStrength.verdict("Xk9\$mQ2!vB7z anchor mint".toCharArray(), floor))
    }
}
