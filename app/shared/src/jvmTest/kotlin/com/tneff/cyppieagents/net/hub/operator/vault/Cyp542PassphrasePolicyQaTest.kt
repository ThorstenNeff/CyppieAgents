package com.tneff.cyppieagents.net.hub.operator.vault

import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-542 / B1 — **Team-2 QA evidence: passphrase INPUT-POLICY enforcement** (the ② strength floor + #3 blocklist),
 * re-verified against the **final** enroll API `c3c03a9d` (`enroll()` is now `suspend`/`Dispatchers.Default`;
 * `EnrollOutcome` is the top-level type on `OperatorEnrollController`; `OperatorEnrollment` implements that interface).
 * QA/DoD-evidence axis, distinct from Dev's `OperatorEnrollmentTest`: each reject is **paired with a positive control**
 * (else vacuous), the floor is proven **load-bearing**, and the enforcement is **non-bypassable at the CORE seal
 * choke-point** (F-#4) — a direct `OperatorSecretVault.enroll` skipping the orchestration is STILL refused.
 *
 * 8-item policy matrix coverage (plan `docs/B1-PASSPHRASE-FLOOR-QA-TESTPLAN.md`):
 *  - M1 Floor-Reject (+ positive, + load-bearing, + M7 validate-before-seal, + M8 reject-not-warn/no-seal) → [m1_floor]
 *  - M2 At-Floor boundary (half-open convention: bits >= floor ⇒ accept) → [m2_atFloorBoundary]
 *  - M3 Blocklist-Reject (+ positive; BLOCKLISTED is an INDEPENDENT gate ≠ TOO_WEAK) → [m3_blocklist]
 *  - M4 Bypass-hardening: case + whitespace normalization → still rejected → [m4_bypassHardening]
 *    (⚠ NFKC is NOT normalized by `PassphraseStrength.normalize` — reported as a low-severity FINDING to the
 *     coordinator, not asserted here; a fullwidth/homoglyph famous phrase can stay over-floor and bypass the blocklist.)
 *  - M5 Core-vs-Client / non-bypassable CORE (F-#4) → [m5_nonBypassableCore]
 *  - M6 Enumeration-hygiene: **N/A for the LOCAL enroll dialog** — the operator sets THEIR OWN secret, so distinct
 *    TOO_WEAK≠BLOCKLISTED copy (AC-3) is correct UX, not an oracle; enumeration hygiene applies to the remote
 *    unlock/verify path (bounded by `OperatorSecretVault.open`'s H-4 rate-limit), not here. Documented, no test.
 *
 * Seam split (Team-1-Tester / CYP-542T owns crypto-store): I assert **policy fires + nothing sealed**; they assert the
 * no-partial-`wrappedPath` artifact + KDF (Argon2id) + migration. Distinct file + own fakes → zero fixture collision.
 */
class Cyp542PassphrasePolicyQaTest {

    // ---- fakes (own copies so Team-1's fixtures are never overwritten) ----
    private class MemStore(var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }
    private class FakeCustody : PlaintextKeyCustody { // no plaintext ⇒ first-enroll path (no migration)
        override fun exists() = false
        override fun load(): KeyPair = error("no plaintext")
        override fun delete() {}
    }
    private val fakeKdf = PassphraseKdf { p, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
    }
    private val policy = CredentialPolicy.forCapability(OperatorAuthCapability.SOFTWARE_ONLY, 5, 30_000, 900_000)
    private fun vault(store: MemStore) = OperatorSecretVault(store, fakeKdf, JceAead(), { 0L })
    private fun enrollment(store: MemStore, pol: CredentialPolicy = policy): OperatorEnrollController =
        OperatorEnrollment(vault(store), FakeCustody(), diceware = null, pol)
    private fun realKey() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val underFloor = "hunter2"
    private val strong = "Zephyr7!mQ anchor-mint Kx9vB"
    private val blocklistedStrong = "correct horse battery staple"
    private val nonBlocklistedStrong = "velvet turbine cactus lantern"

    @Test
    fun m1_floor_underFloorRefusedFailClosed_noSeal_pairedPositive_andLoadBearing() = runBlocking {
        // ★ M1/M8 reject: a sub-floor passphrase → TooWeak, nothing sealed (fail-closed, not a warning).
        val store = MemStore()
        assertIs<EnrollOutcome.TooWeak>(enrollment(store).enroll(underFloor.toCharArray()))
        assertNull(store.blob, "a sub-floor enroll seals nothing (fail-closed, M8)")
        assertEquals(VaultState.Missing, vault(store).state(), "the vault stays Missing after a refused enroll")

        // ★ paired POSITIVE: an over-floor, non-blocklisted passphrase enrolls + seals (else the reject is vacuous).
        val ok = MemStore()
        assertIs<EnrollOutcome.Enrolled>(enrollment(ok).enroll(strong.toCharArray()))
        assertNotNull(ok.blob, "a strong passphrase seals the vault")
        assertEquals(VaultState.Enrolled, vault(ok).state())

        // ★ the floor is LOAD-BEARING (the "lower the floor" mutation encoded at the validate seam, where the injected
        //   policy floor applies): the SAME input is TOO_WEAK at 64 but OK under an injected 0-bit floor. (The CORE seal
        //   re-enforces 64 regardless — that defense-in-depth is m5.)
        assertEquals(StrengthVerdict.TOO_WEAK, enrollment(MemStore()).validate(underFloor.toCharArray()))
        assertEquals(StrengthVerdict.OK, enrollment(MemStore(), policy.copy(minEntropyBits = 0)).validate(underFloor.toCharArray()))
    }

    @Test
    fun m2_atFloorBoundary_halfOpen_bitsGeFloorAccepts() {
        // ★ M2 convention pinned: the gate is half-open — `estimateBits >= floor` ⇒ OK, `< floor` ⇒ TOO_WEAK. Proven
        //   without crafting an exact-entropy string: for a non-blocklisted input of estimate E, verdict at floor=E is
        //   OK (E<E is false) and at floor=E+1 is TOO_WEAK (E<E+1). Locks `<` (not `<=`) so a later estimator swap can't
        //   silently move the line.
        val pp = underFloor.toCharArray()
        val e = PassphraseStrength.estimateBits(pp)
        assertEquals(StrengthVerdict.OK, PassphraseStrength.verdict(pp, e), "at-floor (bits == floor) ACCEPTS (half-open)")
        assertEquals(StrengthVerdict.TOO_WEAK, PassphraseStrength.verdict(pp, e + 1), "just-under-floor (bits < floor) REJECTS")
    }

    @Test
    fun m3_blocklist_structurallyStrongButCommon_refusedFailClosed_noSeal_pairedPositive() = runBlocking {
        // ★ M3 reject: a structurally-strong BUT blocklisted phrase (clears the entropy floor) is refused Blocklisted.
        val store = MemStore()
        assertIs<EnrollOutcome.Blocklisted>(enrollment(store).enroll(blocklistedStrong.toCharArray()))
        assertNull(store.blob, "a blocklisted enroll seals nothing (fail-closed)")
        // BLOCKLISTED, not TOO_WEAK — the blocklist is an INDEPENDENT gate (the phrase IS over-floor).
        assertEquals(StrengthVerdict.BLOCKLISTED, PassphraseStrength.verdict(blocklistedStrong.toCharArray(), policy.minEntropyBits))

        // ★ paired POSITIVE: a structurally-SIMILAR (4-word) but NON-blocklisted phrase enrolls — proving it is the
        //   BLOCKLIST rejecting above, not the length/structure.
        val ok = MemStore()
        assertIs<EnrollOutcome.Enrolled>(enrollment(ok).enroll(nonBlocklistedStrong.toCharArray()))
        assertNotNull(ok.blob, "a non-blocklisted strong phrase of the same shape seals the vault")
        assertEquals(StrengthVerdict.OK, PassphraseStrength.verdict(nonBlocklistedStrong.toCharArray(), policy.minEntropyBits))
    }

    @Test
    fun m4_bypassHardening_caseAndWhitespaceNormalized_stillRejected() {
        // ★ M4: a blocklisted entry in a DIFFERENT CASE or with surrounding/internal WHITESPACE is STILL rejected —
        //   the blocklist compares on a normalized (lowercase + whitespace-stripped) form, not a raw byte match.
        assertEquals(StrengthVerdict.BLOCKLISTED, PassphraseStrength.verdict("Correct Horse Battery Staple".toCharArray(), policy.minEntropyBits),
            "case-variant of a blocklisted phrase is still BLOCKLISTED")
        assertEquals(StrengthVerdict.BLOCKLISTED, PassphraseStrength.verdict("  correct   horse\tbattery  staple  ".toCharArray(), policy.minEntropyBits),
            "whitespace-variant of a blocklisted phrase is still BLOCKLISTED")
        // and the normalization does NOT over-reject a legitimate allowed phrase with different case/spacing:
        assertEquals(StrengthVerdict.OK, PassphraseStrength.verdict("Velvet Turbine Cactus Lantern".toCharArray(), policy.minEntropyBits),
            "the same normalization does not spuriously reject an allowed phrase")
        // ⚠ NFKC is NOT normalized (only lowercase + ASCII-whitespace) — a fullwidth/homoglyph blocklisted phrase can
        //   bypass while staying over-floor. Verified + reported as a low-severity FINDING to the coordinator (not
        //   asserted here as expected behavior). Fix home: NFKC-fold in normalize(), or the CYP-544 zxcvbn follow-up.
    }

    @Test
    fun m5_nonBypassableCore_directVaultEnroll_stillEnforcesFloorAndBlocklist_F4() {
        // ★ M5 / F-#4 (Core-vs-Client, headline): a DIRECT OperatorSecretVault.enroll — bypassing orchestration
        //   validate() entirely (as a test/headless/future-orchestrator could) — is STILL refused fail-closed at the
        //   seal choke-point (require throws, nothing sealed) for sub-floor AND blocklisted.
        val kp = realKey()
        val s1 = MemStore()
        assertFailsWith<IllegalArgumentException> { vault(s1).enroll(underFloor.toCharArray(), kp.private.encoded, kp.public.encoded) }
        assertNull(s1.blob, "core-refused sub-floor: nothing sealed")

        val s2 = MemStore()
        assertFailsWith<IllegalArgumentException> { vault(s2).enroll(blocklistedStrong.toCharArray(), kp.private.encoded, kp.public.encoded) }
        assertNull(s2.blob, "core-refused blocklisted: nothing sealed")

        // ★ paired POSITIVE: a strong passphrase DOES seal through the same direct core path (the require isn't
        //   rejecting everything) — so the two refusals above are the policy, not a broken enroll.
        val s3 = MemStore()
        vault(s3).enroll(strong.toCharArray(), kp.private.encoded, kp.public.encoded)
        assertNotNull(s3.blob, "a strong passphrase seals through the direct core path")
        assertEquals(VaultState.Enrolled, vault(s3).state())
    }
}
