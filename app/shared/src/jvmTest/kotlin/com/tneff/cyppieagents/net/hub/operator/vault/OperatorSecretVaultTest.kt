package com.tneff.cyppieagents.net.hub.operator.vault

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 — [OperatorSecretVault] teeth over the REAL [JceAead] (AES-256-GCM) + an in-memory store + a fast
 * deterministic fake KDF (prod = Argon2id, injected at the composition root). Pins the ratified invariants: the AEAD
 * tag IS the passphrase verifier (wrong passphrase ⇒ fail-closed, never the key); **③ corrupt ≠ missing** (a corrupt
 * vault NEVER re-enrolls — the key-substitution guard); **H-4** online rate-limit resets only on success; **H-2** AAD
 * binds params so a tampered blob can't yield the key.
 */
class OperatorSecretVaultTest {

    private class MemStore(var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }

    // Deterministic fake KDF (prod = Argon2id): kek = SHA-256(passphrase-bytes ‖ salt) — same passphrase+salt ⇒ same KEK.
    private val fakeKdf = PassphraseKdf { passphrase, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply { update(passphrase.concatToString().encodeToByteArray()); update(salt) }.digest()
    }

    private val priv = ByteArray(48) { (it + 1).toByte() }   // opaque "PKCS#8 privkey" (the vault seals arbitrary bytes)
    private val pub = ByteArray(44) { (it + 100).toByte() }  // opaque "X.509 pub"

    private fun vault(store: MemStore, now: () -> Long = { 0L }, maxAttempts: Int = 3) =
        OperatorSecretVault(store = store, kdf = fakeKdf, aead = JceAead(), nowMs = now, maxAttempts = maxAttempts)

    @Test
    fun enroll_thenOpen_roundtrips_returnsThePrivKey() {
        val store = MemStore(); val v = vault(store)
        assertEquals(VaultState.Missing, v.state())
        v.enroll("Zephyr7!mQ anchor-mint Kx9vB".toCharArray(), priv, pub)
        assertEquals(VaultState.Enrolled, v.state())
        assertContentEquals(pub, v.devicePublicKey())
        val open = v.open("Zephyr7!mQ anchor-mint Kx9vB".toCharArray())
        assertIs<VaultOpen.Unlocked>(open)
        assertContentEquals(priv, open.privKeyPkcs8, "the correct passphrase decrypts the exact enrolled key")
    }

    @Test
    fun enroll_refusesWeakOrBlocklistedPassphrase_coreEnforcement_failClosed() {
        // F-#4 (HIGH): the ② floor + #3 blocklist are enforced at the CORE, not only the UI — a below-floor or
        // blocklisted passphrase is REFUSED fail-closed (nothing sealed), so no headless/test/bug path can bypass it.
        val store = MemStore(); val v = vault(store)
        assertFailsWith<IllegalArgumentException> { v.enroll("hunter2".toCharArray(), priv, pub) } // structurally too weak
        assertFailsWith<IllegalArgumentException> { v.enroll("correct horse battery staple".toCharArray(), priv, pub) } // blocklisted (famous)
        assertEquals(VaultState.Missing, v.state(), "a refused enroll seals NOTHING (fail-closed)")
    }

    @Test
    fun wrongPassphrase_failsClosed_notCorrupt_noKey() {
        val store = MemStore(); val v = vault(store)
        v.enroll("right-passphrase-64bit".toCharArray(), priv, pub)
        val open = v.open("wrong-passphrase-xxxxx".toCharArray())
        assertIs<VaultOpen.WrongPassphrase>(open) // the AEAD tag rejects it — never Unlocked, never Corrupt
    }

    @Test
    fun missingVault_isMissing_firstEnrollPath() {
        val v = vault(MemStore())
        assertEquals(VaultState.Missing, v.state())
        assertIs<VaultOpen.Missing>(v.open("anything".toCharArray()))
    }

    @Test
    fun corruptVault_failsClosed_neverReEnroll_keySubstitutionGuard() {
        // ③: a present-but-garbage vault ⇒ Corrupt, NOT Missing — so the flow routes to OOB-recovery, never auto
        // re-enroll (which would let an attacker corrupt the vault to force a new key they control).
        val store = MemStore(blob = "{not valid json".encodeToByteArray()); val v = vault(store)
        assertEquals(VaultState.Corrupt, v.state())
        assertIs<VaultOpen.Corrupt>(v.open("correct".toCharArray()))
        assertTrue(store.blob != null, "the corrupt vault is NOT silently overwritten/re-enrolled")
    }

    @Test
    fun rateLimit_locksOutAfterMaxAttempts_online() {
        var now = 1_000L
        val store = MemStore(); val v = vault(store, now = { now }, maxAttempts = 3)
        v.enroll("real-pass-64bit".toCharArray(), priv, pub)
        repeat(2) { assertIs<VaultOpen.WrongPassphrase>(v.open("nope".toCharArray())) }
        val locked = v.open("nope".toCharArray()) // 3rd failure hits maxAttempts ⇒ lockout
        assertIs<VaultOpen.LockedOut>(locked)
        // Even the CORRECT passphrase is refused while locked (the cooldown is not bypassable through the app).
        assertIs<VaultOpen.LockedOut>(v.open("real-pass-64bit".toCharArray()))
        // After the lockout window, the correct passphrase unlocks again.
        now = locked.untilMs + 1
        assertIs<VaultOpen.Unlocked>(v.open("real-pass-64bit".toCharArray()))
    }

    @Test
    fun lockout_resetsOnlyOnSuccess() {
        val store = MemStore(); val v = vault(store, maxAttempts = 3)
        v.enroll("Basalt5#harbor Qw2nV zephyr".toCharArray(), priv, pub)
        assertIs<VaultOpen.WrongPassphrase>(v.open("bad".toCharArray()))
        assertIs<VaultOpen.WrongPassphrase>(v.open("bad".toCharArray())) // 2 failures (below max)
        assertIs<VaultOpen.Unlocked>(v.open("Basalt5#harbor Qw2nV zephyr".toCharArray()))       // success resets the counter
        // The counter is back to 0 → it takes the full maxAttempts again to lock (not 1 more): 2 wrongs stay
        // WrongPassphrase, only the 3rd re-locks (proving the reset happened).
        assertIs<VaultOpen.WrongPassphrase>(v.open("bad".toCharArray()))
        assertIs<VaultOpen.WrongPassphrase>(v.open("bad".toCharArray()))
        assertIs<VaultOpen.LockedOut>(v.open("bad".toCharArray()))       // the 3rd re-locks
    }

    @Test
    fun aadBinding_tamperedParams_failClosed_noKey() {
        // H-2: the AEAD AAD binds the KDF params. Editing memKiB in the blob changes the AAD (and the fake-KDF params)
        // ⇒ AEAD open fails ⇒ fail-closed, never the key. Proves a params-transplant can't yield a signature.
        val store = MemStore(); val v = vault(store)
        v.enroll("real-pass-64bit".toCharArray(), priv, pub)
        // Tamper memKiB (pipe field index 2: v|salt|memKiB|...) — changes both the AAD and the derived KEK params.
        val fields = store.blob!!.decodeToString().split("|").toMutableList()
        fields[2] = "8"
        store.blob = fields.joinToString("|").encodeToByteArray()
        val open = v.open("real-pass-64bit".toCharArray())
        assertTrue(open !is VaultOpen.Unlocked, "a tampered AAD/params blob never yields the key (was $open)")
    }

    @Test
    fun changePassphrase_reEnroll_oldPassphraseNoLongerOpens() {
        val store = MemStore(); val v = vault(store)
        v.enroll("old-pass-64bit-aaaa".toCharArray(), priv, pub)
        v.enroll("new-pass-64bit-bbbb".toCharArray(), priv, pub) // re-seal (change-passphrase; same key/anchor)
        assertIs<VaultOpen.Unlocked>(v.open("new-pass-64bit-bbbb".toCharArray()))
        assertIs<VaultOpen.WrongPassphrase>(v.open("old-pass-64bit-aaaa".toCharArray()))
    }
}
