package com.tneff.cyppieagents.net.hub.operator.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * B1 E2E-HUNT (Team-2, adversarial) — **race on the H-4 online rate-limit / lockout counter.**
 *
 * `OperatorSecretVault.open()` is a lock-free read-modify-write: it reads the blob (attempts=N), derives the
 * KEK, opens the AEAD, and on a wrong passphrase writes `attempts = N+1` (`OperatorSecretVault.kt` ~L108-111).
 * Nothing serializes two concurrent `open()` calls, so two parallel wrong guesses can both read `attempts=N`
 * and both write `N+1` — one increment lost. Repeated, this lets an attacker who *parallelizes* online guesses
 * exceed `maxAttempts` and never trip the lockout the class advertises as bounding online guessing (H-4).
 *
 * The window is held open DETERMINISTICALLY (not `delay`-guessed): the injected KDF blocks on a
 * `CyclicBarrier(2)` for exactly the two racing opens, so both are guaranteed to have read `attempts=N` (the
 * read precedes `deriveKek`) before either writes. The [control] proves the lockout DOES engage when the two
 * wrong attempts are sequential — so a bypass in [hunt] is the race, not a broken lockout.
 *
 * Severity note (honest): the vault is client-side/single-user and the desktop unlock dialog likely serializes
 * attempts, so current reachability is LOW — but the class makes no serialization guarantee, is a reusable
 * security primitive, and the H-4 "bounds online guessing" claim is literally violated under concurrency. If
 * [hunt] reddens, it is a real fail-closed gap (rate-limit bypass) to report; grade + reach as above.
 */
class VaultLockoutRaceHuntTest {

    private class MemStore(@Volatile var blob: ByteArray? = null) : VaultStore {
        override fun exists() = blob != null
        override fun read() = blob
        @Synchronized override fun write(bytes: ByteArray) { blob = bytes }
        override fun delete() { blob = null }
    }

    private val strong = "Zephyr7!mQ anchor-mint Kx9vB"
    private val wrong = "Zephyr7!mQ anchor-mint WRONGxx"

    private fun sha256Kdf() = PassphraseKdf { p, salt, _ ->
        MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
    }
    private fun newKey(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    @Test
    fun sequentialWrongOpens_lockOutAfterMaxAttempts_control() {
        // Non-vacuity control: un-raced, the lockout engages exactly at maxAttempts.
        val store = MemStore()
        val key = newKey()
        val vault = OperatorSecretVault(store, sha256Kdf(), JceAead(), { 0L }, maxAttempts = 2)
        vault.enroll(strong.toCharArray(), key.private.encoded, key.public.encoded)

        assertIs<VaultOpen.WrongPassphrase>(vault.open(wrong.toCharArray()), "1st wrong: not yet locked")
        assertIs<VaultOpen.LockedOut>(vault.open(wrong.toCharArray()), "2nd wrong reaches maxAttempts=2 → locked")
        assertIs<VaultOpen.LockedOut>(vault.open(strong.toCharArray()), "even a correct passphrase is now locked out")
    }

    @Test
    fun concurrentWrongOpens_mustStillLockOut_afterMaxAttempts_H4() {
        val store = MemStore()
        val key = newKey()
        // Enroll via a non-gating vault over the SAME store (same SHA-256 KDF ⇒ identical KEK for (pp,salt)).
        OperatorSecretVault(store, sha256Kdf(), JceAead(), { 0L }, maxAttempts = 2)
            .enroll(strong.toCharArray(), key.private.encoded, key.public.encoded)

        // Gate ONLY the two racing opens between their read (L89) and write (L111): both block in deriveKek
        // AFTER having read attempts=0, then release together → both compute+write attempts=1 (one lost).
        val gate = AtomicInteger(0)
        val barrier = CyclicBarrier(2)
        val gatingKdf = PassphraseKdf { p, salt, _ ->
            if (gate.getAndIncrement() < 2) barrier.await(5, TimeUnit.SECONDS)
            MessageDigest.getInstance("SHA-256").apply { update(p.concatToString().encodeToByteArray()); update(salt) }.digest()
        }
        val raceVault = OperatorSecretVault(store, gatingKdf, JceAead(), { 0L }, maxAttempts = 2)

        runBlocking {
            (1..2).map { async(Dispatchers.IO) { raceVault.open(wrong.toCharArray()) } }.awaitAll()
        }

        // INVARIANT (H-4): two wrong attempts — even concurrent — MUST leave the vault locked out. A lost-update
        // leaves attempts=1 (< maxAttempts) → not locked → a correct passphrase unlocks = the rate-limit bypassed.
        val after = raceVault.open(strong.toCharArray())
        assertIs<VaultOpen.LockedOut>(
            after,
            "after 2 CONCURRENT wrong opens the vault must be LOCKED OUT; got $after — the attempt-counter " +
                "read-modify-write in OperatorSecretVault.open() lost an increment, bypassing the H-4 online rate-limit",
        )
    }
}
