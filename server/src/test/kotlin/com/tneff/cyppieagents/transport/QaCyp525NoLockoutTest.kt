package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.FinalizedEnrollmentStore
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipher
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.SavedAck
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-525 GE5/GE7 — QA independent **no-lockout** teeth for the RR3 provisional→SavedAck→combined-atomic-finalize flow
 * (`docs/design/CYP-525-enroll-completion-semantics.md`). Delta over `Cyp525EnrollFinalizeGateTest` (happy/no-ack-drop/
 * H2/steady): the invariant §3.1 **no reachable (Finalized ∧ no-usable-codes)** and its fixture-fidelity edges —
 * FRESH-codes-per-provisional (old invalidated), the **timeout** liveness path (not just a drop), the **combined-record**
 * invariant, **real-restart** durability (reload from disk, not the same instance), and the **tamper→no-re-enroll**
 * seizure guard. Each drives the REAL gate + REAL `FinalizedEnrollmentStore` (Tink AEAD, atomic-file), never a stub.
 */
class QaCyp525NoLockoutTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_test"; private val operatorId = "op-1"; private val issuer = "cp-issuer"; private val kid = "kid1"
    private val nowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519(); private val device = RawKeys.generateEd25519()
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)

    private fun config() = Rr3Config(hubId, operatorId, issuer, { k -> if (k == kid) cp.publicRaw else null }, "hub.example")
    private fun newCipher(): SecretCipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
    private fun storeAt(cipher: SecretCipher, file: File) = FinalizedEnrollmentStore(cipher, file, hubId)
    private fun tmpFile() = File(Files.createTempDirectory("cyp525-nolock").toFile(), "enrollment.rec")
    private fun gate(store: FinalizedEnrollmentStore, ttlMs: Long = 5_000L) = Rr3TunnelGate(
        CpJwtVerifier(), OperatorAssertionVerifier(), InMemoryOperatorDeviceStore(), config(), { nowMs },
        finalizedStore = store, savedAckTimeoutMs = ttlMs,
    )
    private fun cpJwt() = minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), nowMs, ttlMs = 300_000)
    private fun sig(nonce: ByteArray) = RawKeys.ed25519Sign(device.privateRaw, operatorAuthChallenge(h, hubId, nonce))
    private fun reqBytes(nonce: ByteArray) =
        CommJson.encodeToString(TunnelAuthRequest(cpJwt(), OperatorPoPWire.Raw(sig(nonce)), nonce, device.publicRaw)).encodeToByteArray()
    private fun ackBytes() = CommJson.encodeToString(SavedAck(ok = true)).encodeToByteArray()
    private fun nackBytes() = CommJson.encodeToString(SavedAck(ok = false)).encodeToByteArray()

    /** Scripts inbound frames in order; an optional per-read delay (ms) exercises the bounded-await timeout path. */
    private class ScriptTunnel(inbound: List<ByteArray>, private val delayBeforeRead: Map<Int, Long> = emptyMap()) : ServerNoiseTunnel {
        private val q = ArrayDeque(inbound); private var reads = 0
        val sent = CopyOnWriteArrayList<ByteArray>()
        override val handshakeHash = ByteArray(32) { (it + 1).toByte() }
        override suspend fun receive(): ByteArray? { reads++; delayBeforeRead[reads]?.let { delay(it) }; return q.removeFirstOrNull() }
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun close() {}
    }
    private fun grants(t: ScriptTunnel) = t.sent.mapNotNull { runCatching { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }.getOrNull() }
    private fun codes(t: ScriptTunnel): List<String>? = t.sent.firstNotNullOfOrNull {
        runCatching { CommJson.decodeFromString<EnrollResponse>(it.decodeToString()) }.getOrNull()?.backupCodes?.takeIf { c -> c.isNotEmpty() }
    }

    // ── QA-A4-1 ★ drop→discard→reconnect re-mints FRESH codes (old provisional's codes invalidated) ──
    @Test
    fun dropBeforeAck_thenReconnect_reMintsFreshCodes_oldDiscarded() = runBlocking {
        val cipher = newCipher(); val file = tmpFile(); val store = storeAt(cipher, file)
        // provisional A: request but NO ack (2nd receive → null) → discard.
        val a = ScriptTunnel(listOf(reqBytes(byteArrayOf(1))))
        assertFalse(gate(store).authorize(a), "no SavedAck → provisional discarded (not CONNECTED)")
        assertNull(store.read(), "nothing committed after a discard")
        val codesA = assertNotNull(codes(a), "A revealed provisional codes")
        // provisional B: a fresh connect → firstEnroll=true again → NEW codes.
        val b = ScriptTunnel(listOf(reqBytes(byteArrayOf(2)), ackBytes()))
        assertTrue(gate(store).authorize(b), "B finalizes with SavedAck")
        val codesB = assertNotNull(codes(b), "B revealed fresh codes")
        assertEquals(BACKUP_CODE_COUNT, codesB.size)
        // ★ fresh-per-provisional: A's discarded codes and B's committed codes are DISJOINT (generate()-replace, §3.4).
        assertTrue(codesA.intersect(codesB.toSet()).isEmpty(), "the discarded provisional's codes are NOT reused — B minted fresh")
        assertEquals(BACKUP_CODE_COUNT, store.read()!!.codes.size, "B's code-hashes are the ONLY committed set")
    }

    // ── QA-A4-2 ★ nack (ok=false) → discard, not committed (explicit refusal, not just a drop) ──
    @Test
    fun explicitNack_discards_nothingCommitted() = runBlocking {
        val store = storeAt(newCipher(), tmpFile())
        val t = ScriptTunnel(listOf(reqBytes(byteArrayOf(3)), nackBytes()))
        assertFalse(gate(store).authorize(t), "SavedAck{ok=false} → discard, never CONNECTED")
        assertNull(store.read(), "an explicit nack commits nothing (no-lockout: re-mint next connect)")
    }

    // ── QA-A4-3 ★ SavedAck TIMEOUT (liveness) → discard + the first-enroll lock is released ──
    @Test
    fun savedAckTimeout_discards_andReleasesLock_liveness() = runBlocking {
        val store = storeAt(newCipher(), tmpFile()); val g = gate(store, ttlMs = 150L)
        // provisional A holds past the ack timeout (delay before the 2nd receive >> ttl) → bounded-await fires → discard.
        val a = ScriptTunnel(listOf(reqBytes(byteArrayOf(4)), ackBytes()), delayBeforeRead = mapOf(2 to 1_000L))
        assertFalse(g.authorize(a), "a hung provisional times out → discarded")
        assertNull(store.read(), "timeout commits nothing")
        // ★ liveness: the SAME gate's lock was released → a subsequent first-enroll can finalize (no self-DoS).
        val b = ScriptTunnel(listOf(reqBytes(byteArrayOf(5)), ackBytes()))
        assertTrue(g.authorize(b), "after the timeout the lock is free → a new first-enroll finalizes")
        assertEquals(operatorId, store.read()!!.device.deviceId)
    }

    // ── QA-A4-INV ★ combined-record invariant: a finalize ALWAYS carries device ∧ full code-set (never anchor-only) ──
    @Test
    fun finalize_commitsDeviceAndFullCodeSet_together_noAnchorWithoutCodes() = runBlocking {
        val store = storeAt(newCipher(), tmpFile())
        assertTrue(gate(store).authorize(ScriptTunnel(listOf(reqBytes(byteArrayOf(6)), ackBytes()))))
        val fin = assertNotNull(store.read(), "finalized")
        // §3.1 headline witness: there is no committed state with an anchor but no usable codes.
        assertEquals(operatorId, fin.device.deviceId)
        assertEquals(BACKUP_CODE_COUNT, fin.codes.size, "the anchor and a FULL code-set commit together (never anchor-without-codes)")
        assertTrue(fin.codes.none { it.consumed }, "freshly finalized codes are all unconsumed (usable recovery)")
    }

    // ── QA-A4-5 ★ real-restart durability: reload the store from DISK (new instance) → anchor+codes survive together ──
    @Test
    fun finalized_survivesRealRestart_reloadFromDisk_steadyStateConnects() = runBlocking {
        val cipher = newCipher(); val file = tmpFile()
        assertTrue(gate(storeAt(cipher, file)).authorize(ScriptTunnel(listOf(reqBytes(byteArrayOf(7)), ackBytes()))), "first-enroll finalizes")
        // a REAL restart: a brand-new store instance reads the SAME encrypted file back (same master key).
        val reloaded = assertNotNull(storeAt(cipher, file).read(), "the finalized record survives a store-instance restart")
        assertContentEquals(device.publicRaw, reloaded.device.publicKey, "the enrolled device anchor is durable")
        assertEquals(BACKUP_CODE_COUNT, reloaded.codes.size, "the recovery code-hashes are durable ALONGSIDE the anchor")
        // steady-state connect through a gate over the reloaded store → firstEnroll=false, verifies the anchor, no reveal.
        val t = ScriptTunnel(listOf(reqBytes(byteArrayOf(8))))
        assertTrue(gate(storeAt(cipher, file)).authorize(t), "post-restart steady-state connect verifies against the durable anchor")
        assertNull(codes(t), "no codes-reveal on a durable steady-state connect")
        assertEquals(false, grants(t).single().firstEnroll)
    }

    // ── QA-A4-tamper ★ SECURITY: a tampered finalized record fails CLOSED, never silently empty→re-enroll (seizure) ──
    @Test
    fun tamperedFinalizedRecord_failsClosed_neverSilentReEnroll() = runBlocking {
        val cipher = newCipher(); val file = tmpFile()
        assertTrue(gate(storeAt(cipher, file)).authorize(ScriptTunnel(listOf(reqBytes(byteArrayOf(9)), ackBytes()))), "finalized")
        // byte-flip the ciphertext (past the 4-byte key-version header) → AEAD tamper.
        val raw = file.readBytes(); raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte(); file.writeBytes(raw)
        // ★ the gate reads the anchor → a tampered record THROWS (fail-closed) → it must NOT be treated as empty→re-enroll.
        val t = ScriptTunnel(listOf(reqBytes(byteArrayOf(10)), ackBytes()))
        val threw = runCatching { gate(storeAt(cipher, file)).authorize(t) }.isFailure
        assertTrue(threw || grants(t).all { !it.granted }, "a tampered anchor fails closed (throws or rejects) — no silent seizure via re-enroll")
        assertNull(codes(t), "a tampered anchor NEVER triggers a fresh codes-reveal (the tamper→re-enroll seizure vector is closed)")
    }
}
