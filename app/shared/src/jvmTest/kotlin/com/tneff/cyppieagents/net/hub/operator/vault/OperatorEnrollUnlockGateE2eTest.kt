package com.tneff.cyppieagents.net.hub.operator.vault

import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B1 Desktop-path **enroll → unlock gate** round-trip (Team-2, Post-Login-Lane) — the *consequence* of the
 * passphrase policy, not just the policy.
 *
 * My CYP-542 evidence proved the floor + blocklist are enforced fail-closed and **nothing is sealed** on a
 * refusal. This lifts that to the **user-facing outcome the desktop actually gates on: can the operator
 * unlock?** The chain is enroll (real [OperatorEnrollment] orchestration) → **relaunch** (a fresh
 * [OperatorSecretVault] over the same at-rest blob) → [OperatorSecretVault.open]. So:
 *  - a STRONG passphrase enrolls **and its unlock actually yields the enrolled device key** (sign-capable);
 *  - a WRONG passphrase fails closed with **no key material** (the passphrase is the cryptographic gate, not
 *    a UI gesture over a plaintext key);
 *  - a sub-floor / blocklisted passphrase **never enrolls, therefore is never unlockable** — the gate is
 *    non-bypassable all the way to the unlock consequence.
 *
 * Non-vacuity (hard rule): every refusal tooth is paired with a positive control that DOES enroll+unlock, and
 * the happy-path tooth asserts the exact enrolled key comes back out. Keystone mutation (run, then reverted —
 * prod pristine): make `PassphraseStrength.verdict` return `OK` unconditionally → the weak/blocklisted teeth
 * go RED (they now enroll + unlock) while the strong/wrong teeth stay GREEN. That single mutation reddens BOTH
 * the `OperatorEnrollment.validate` gate AND the `OperatorSecretVault.enroll` F-#4 core at once.
 *
 * Lane: real orchestration + real [JceAead] seal/open; fakes only at the store/custody/KDF seams (my own
 * copies — zero collision with CYP-542T's crypto-store unit teeth). The hub-side device registration / PoP
 * (CYP-525) is a SEPARATE seam, not asserted here. Corrupt-vault / rate-limit / AEAD-internal teeth are
 * deliberately out of scope (CYP-542T's crypto-store lane).
 */
class OperatorEnrollUnlockGateE2eTest {

    // ---- fakes (own copies so no CYP-542T fixture is touched) ----
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

    private fun vaultOver(store: MemStore) = OperatorSecretVault(store, fakeKdf, JceAead(), { 0L })
    private fun controllerOver(store: MemStore, key: KeyPair): OperatorEnrollController =
        OperatorEnrollment(vaultOver(store), FakeCustody(), diceware = null, policy, newKey = { key })
    private fun newEd25519() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val underFloor = "hunter2"
    private val strong = "Zephyr7!mQ anchor-mint Kx9vB"
    private val blocklistedStrong = "correct horse battery staple"
    private val nonBlocklistedStrong = "velvet turbine cactus lantern"

    @Test
    fun strongEnroll_thenRelaunch_unlocksTheEnrolledDeviceKey(): Unit = runBlocking {
        val store = MemStore()
        val key = newEd25519()

        // Enroll through the REAL orchestration; precondition established positively.
        assertIs<EnrollOutcome.Enrolled>(controllerOver(store, key).enroll(strong.toCharArray()))
        assertEquals(VaultState.Enrolled, vaultOver(store).state(), "the vault is sealed after a strong enroll")

        // ★ RELAUNCH: a brand-new vault over the SAME at-rest blob (the app restarted). Unlock must return the
        //   EXACT enrolled device key — proving the passphrase cryptographically yields a sign-capable key, not
        //   merely a boolean "ok".
        val relaunched = vaultOver(store)
        val opened = relaunched.open(strong.toCharArray())
        assertIs<VaultOpen.Unlocked>(opened, "the correct passphrase unlocks the relaunched vault")
        assertTrue(opened.privKeyPkcs8.contentEquals(key.private.encoded), "unlock yields the exact enrolled private key")
        assertTrue(relaunched.devicePublicKey().contentEquals(key.public.encoded), "the pinned device pubkey survives relaunch")
    }

    @Test
    fun wrongPassphrase_afterEnroll_failsClosed_yieldsNoKey(): Unit = runBlocking {
        val store = MemStore()
        val key = newEd25519()
        assertIs<EnrollOutcome.Enrolled>(controllerOver(store, key).enroll(strong.toCharArray()))

        // Wrong (but also structurally-strong) passphrase → no key material. The AEAD tag IS the verifier.
        val opened = vaultOver(store).open("Zephyr7!mQ anchor-mint WRONGxx".toCharArray())
        assertIs<VaultOpen.WrongPassphrase>(opened, "a wrong passphrase fails closed — the vault is the gate, not the UI")

        // ★ paired positive: the RIGHT passphrase on the same vault still unlocks (the vault isn't just refusing all).
        assertIs<VaultOpen.Unlocked>(vaultOver(store).open(strong.toCharArray()), "the correct passphrase still unlocks")
    }

    @Test
    fun subFloorPassphrase_neverEnrolls_soIsNeverUnlockable(): Unit = runBlocking {
        // ★ the non-bypassable gate carried to the unlock consequence: a sub-floor passphrase is refused typed
        //   (TooWeak), nothing is sealed, so the vault stays Missing and open() can only ever be Missing.
        val store = MemStore()
        assertIs<EnrollOutcome.TooWeak>(controllerOver(store, newEd25519()).enroll(underFloor.toCharArray()))
        assertEquals(VaultState.Missing, vaultOver(store).state(), "a sub-floor enroll seals nothing")
        assertIs<VaultOpen.Missing>(vaultOver(store).open(underFloor.toCharArray()), "a never-enrolled sub-floor passphrase is unlockable to NOTHING")

        // ★ paired positive: a strong passphrase on a fresh store enrolls AND unlocks (else the refusal is vacuous).
        val ok = MemStore()
        val key = newEd25519()
        assertIs<EnrollOutcome.Enrolled>(controllerOver(ok, key).enroll(strong.toCharArray()))
        assertIs<VaultOpen.Unlocked>(vaultOver(ok).open(strong.toCharArray()), "a strong passphrase enrolls and unlocks")
    }

    @Test
    fun blocklistedStrongPassphrase_neverEnrolls_soNoUnlock(): Unit = runBlocking {
        // ★ a structurally-STRONG but blocklisted phrase (clears the entropy floor) is refused Blocklisted — an
        //   INDEPENDENT gate — so it likewise never becomes an unlockable vault.
        val store = MemStore()
        assertIs<EnrollOutcome.Blocklisted>(controllerOver(store, newEd25519()).enroll(blocklistedStrong.toCharArray()))
        assertEquals(VaultState.Missing, vaultOver(store).state(), "a blocklisted enroll seals nothing")
        assertIs<VaultOpen.Missing>(vaultOver(store).open(blocklistedStrong.toCharArray()), "a blocklisted phrase unlocks to NOTHING")

        // ★ paired positive: a NON-blocklisted phrase of the same 4-word shape enrolls AND unlocks — proving it is
        //   the BLOCKLIST rejecting above, not the length/structure.
        val ok = MemStore()
        val key = newEd25519()
        assertIs<EnrollOutcome.Enrolled>(controllerOver(ok, key).enroll(nonBlocklistedStrong.toCharArray()))
        assertIs<VaultOpen.Unlocked>(vaultOver(ok).open(nonBlocklistedStrong.toCharArray()), "a non-blocklisted phrase of the same shape enrolls and unlocks")
    }
}
