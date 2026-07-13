package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.operator.FinalizedEnrollmentStore
import com.tneff.cyppieagents.auth.operator.InMemoryOperatorDeviceStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.crypto.MasterKeySource
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.SavedAck
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-525 GE5/GE7 — the [Rr3TunnelGate] provisional → SavedAck → combined-atomic-finalize flow: the device anchor + the
 * session's code-hashes commit TOGETHER (H1/H2); no SavedAck → nothing committed → re-mint (no-lockout); concurrent
 * first-connects are serialized (H2); CONNECTED is the post-finalize grant.
 */
class Cyp525EnrollFinalizeGateTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_test"; private val operatorId = "op-1"; private val issuer = "cp-issuer"; private val kid = "kid1"
    private val nowMs = 1_782_517_200_000L
    private val cp = RawKeys.generateEd25519(); private val device = RawKeys.generateEd25519()
    private val minter = CpJwtMinter(cp.privateRaw, kid, issuer)

    private fun config() = Rr3Config(hubId, operatorId, issuer, { k -> if (k == kid) cp.publicRaw else null }, "hub.example")
    private fun finalStore() = FinalizedEnrollmentStore(
        SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset())),
        File(Files.createTempDirectory("cyp525-gate").toFile(), "enrollment.rec"), hubId,
    )
    private fun gate(store: FinalizedEnrollmentStore, ttlMs: Long = 5_000L) = Rr3TunnelGate(
        CpJwtVerifier(), OperatorAssertionVerifier(), InMemoryOperatorDeviceStore(), config(), { nowMs },
        finalizedStore = store, savedAckTimeoutMs = ttlMs,
    )
    private fun cpJwt() = minter.mint(hubId, operatorId, TokenPredicates.expectedChannelBinding(h, hubId), nowMs, ttlMs = 300_000)
    private fun sig(nonce: ByteArray) = RawKeys.ed25519Sign(device.privateRaw, operatorAuthChallenge(h, hubId, nonce))
    private fun reqBytes(nonce: ByteArray, pubkey: ByteArray? = device.publicRaw) =
        CommJson.encodeToString(TunnelAuthRequest(cpJwt(), OperatorPoPWire.Raw(sig(nonce)), nonce, pubkey)).encodeToByteArray()
    private fun ackBytes() = CommJson.encodeToString(SavedAck(ok = true)).encodeToByteArray()

    /** A tunnel that yields [inbound] frames in order (or suspends on [holdAfter]-th receive until [release]). */
    private class GateTunnel(inbound: List<ByteArray>, private val holdAfter: Int = -1, private val release: CompletableDeferred<Unit>? = null) : ServerNoiseTunnel {
        private val q = ArrayDeque(inbound); private var reads = 0
        val sent = CopyOnWriteArrayList<ByteArray>()
        override val handshakeHash = ByteArray(32) { (it + 1).toByte() }
        override suspend fun receive(): ByteArray? { reads++; if (reads == holdAfter) release?.await(); return q.removeFirstOrNull() }
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun close() {}
    }

    private fun grants(t: GateTunnel) = t.sent.mapNotNull { runCatching { CommJson.decodeFromString<TunnelAuthGrant>(it.decodeToString()) }.getOrNull() }
    private fun enrollResponse(t: GateTunnel) = t.sent.firstNotNullOfOrNull { runCatching { CommJson.decodeFromString<EnrollResponse>(it.decodeToString()) }.getOrNull()?.takeIf { r -> r.backupCodes.isNotEmpty() } }

    @Test
    fun provisional_savedAck_finalizes_deliversCodes_andConnects() = runBlocking {
        val store = finalStore()
        val t = GateTunnel(listOf(reqBytes(byteArrayOf(1)), ackBytes()))
        assertTrue(gate(store).authorize(t), "a valid first-enroll with SavedAck finalizes and CONNECTs")
        // combined record committed: anchor + BACKUP_CODE_COUNT code-hashes together
        val fin = store.read()!!
        assertEquals(operatorId, fin.device.deviceId)
        assertEquals(BACKUP_CODE_COUNT, fin.codes.size, "the code-hashes are persisted WITH the anchor (one record)")
        // the EnrollResponse delivered BACKUP_CODE_COUNT plaintexts; the grants were firstEnroll=true then =false
        assertEquals(BACKUP_CODE_COUNT, enrollResponse(t)!!.backupCodes.size)
        val g = grants(t)
        assertEquals(listOf(true, false), listOf(g.first().firstEnroll, g.last().firstEnroll), "provisional grant (firstEnroll=true) then the post-finalize CONNECTED grant (firstEnroll=false)")
    }

    @Test
    fun noSavedAck_discards_nothingCommitted_noLockout() = runBlocking {
        val store = finalStore()
        // request but NO SavedAck (the 2nd receive yields null = the client dropped) → discard.
        val t = GateTunnel(listOf(reqBytes(byteArrayOf(2))))
        assertFalse(gate(store).authorize(t), "no SavedAck → the provisional is discarded (not CONNECTED)")
        assertNull(store.read(), "nothing committed → the next connect re-mints FRESH (no-lockout)")
    }

    @Test
    fun concurrentProvisional_serialized_secondRejected_H2() = runBlocking {
        val store = finalStore()
        val g = gate(store)
        val release = CompletableDeferred<Unit>()
        // Provisional A holds the lock, suspended awaiting its SavedAck (the 2nd receive blocks until `release`).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tA = GateTunnel(listOf(reqBytes(byteArrayOf(3)), ackBytes()), holdAfter = 2, release = release)
        val a = scope.async { g.authorize(tA) }
        withTimeout(3_000) { while (tA.sent.isEmpty()) delay(10) } // A acquired the lock + sent its provisional grant
        // Provisional B (concurrent) — its tryLock must fail → rejected, no second provisional.
        val tB = GateTunnel(listOf(reqBytes(byteArrayOf(4)), ackBytes()))
        assertFalse(g.authorize(tB), "a 2nd concurrent first-connect is rejected (serialized — one provisional in-flight)")
        assertTrue(grants(tB).all { !it.granted }, "B got a reject grant, never a provisional")
        release.complete(Unit) // let A finish
        assertTrue(withTimeout(3_000) { a.await() }, "A finalized")
        assertEquals(operatorId, store.read()!!.device.deviceId)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun tamperedAnchor_failsClosed_cleanReject_notUncaughtThrow() = runBlocking {
        // H1b self-re-read finding: a tampered FINALIZED record must be a CLEAN fail-closed reject (never a silent
        // empty→re-enroll seizure, never an uncaught throw that loops the reconnect + surfaces no diagnostic).
        val file = File(Files.createTempDirectory("cyp525-gate").toFile(), "enrollment.rec")
        val cipher = SecretCipherFactory.single(1, MasterKeySource.Box(SecretCipherFactory.newBoxKeyset()))
        val store = FinalizedEnrollmentStore(cipher, file, hubId)
        val mk = { Rr3TunnelGate(CpJwtVerifier(), OperatorAssertionVerifier(), InMemoryOperatorDeviceStore(), config(), { nowMs }, finalizedStore = store, savedAckTimeoutMs = 5_000L) }
        assertTrue(mk().authorize(GateTunnel(listOf(reqBytes(byteArrayOf(7)), ackBytes()))), "finalize first")
        // byte-flip the at-rest record (an attacker corrupting the anchor)
        val bytes = file.readBytes(); bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte(); file.writeBytes(bytes)
        val t = GateTunnel(listOf(reqBytes(byteArrayOf(8))))
        val ok = mk().authorize(t) // MUST NOT throw (MUT: propagate the read() throw → this line throws → RED)
        assertFalse(ok, "a tampered anchor → clean fail-closed reject, not empty→re-enroll and not an uncaught throw")
        assertTrue(grants(t).any { !it.granted }, "a reject grant was sent (clean diagnostic path)")
    }

    @Test
    fun afterFinalize_steadyState_skipsReveal_verifiesAnchor() = runBlocking {
        val store = finalStore()
        assertTrue(gate(store).authorize(GateTunnel(listOf(reqBytes(byteArrayOf(5)), ackBytes()))), "first-enroll finalizes")
        // a fresh connect, store now finalized → grant{firstEnroll=false} immediately, verify against the anchor, no EnrollResponse
        val t = GateTunnel(listOf(reqBytes(byteArrayOf(6))))
        assertTrue(gate(store).authorize(t), "steady-state connect verifies against the finalized anchor")
        assertNull(enrollResponse(t), "no codes-reveal on a steady-state (already-finalized) connect")
        assertEquals(false, grants(t).single().firstEnroll, "the grant is firstEnroll=false (CONNECTED, no reveal)")
    }
}
