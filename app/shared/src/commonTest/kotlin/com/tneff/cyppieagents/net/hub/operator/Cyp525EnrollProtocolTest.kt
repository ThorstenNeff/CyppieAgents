package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.SavedAck
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-525 §2 — the byte-exact first-enroll finalization protocol in [ClientOperatorAuth]:
 * `Request(+pubkey)` → `Grant{firstEnroll}` → (iff firstEnroll) `EnrollResponse{codes}` → [H3-validate → confirm
 * user-saved] → `SavedAck` → `Grant{granted ∧ !firstEnroll}` = CONNECTED. Steady-state (`!firstEnroll`) IS CONNECTED
 * on the first grant. Every fail-closed path sends NO `SavedAck` (the hub then discards the provisional).
 */
class Cyp525EnrollProtocolTest {

    private val codes = List(BACKUP_CODE_COUNT) { "code-$it" }

    private class ScriptedTunnel(replies: List<ByteArray>) : NoiseTunnel {
        private val queue = ArrayDeque(replies)
        val sent = mutableListOf<ByteArray>()
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray? = if (queue.isEmpty()) null else queue.removeFirst()
        override suspend fun close() {}
    }

    private class FakeStore : OperatorDeviceKeyStore {
        override fun isEnrolled() = true
        override fun devicePublicKey() = ByteArray(32)
        override suspend fun sign(challenge: ByteArray) = PopResult.Signed(DevicePoP.Raw(ByteArray(64)))
    }

    private fun grant(granted: Boolean, firstEnroll: Boolean = false): ByteArray =
        CommJson.encodeToString(TunnelAuthGrant.serializer(), TunnelAuthGrant(granted, null, firstEnroll)).encodeToByteArray()
    private fun enrollResp(c: List<String>): ByteArray =
        CommJson.encodeToString(EnrollResponse.serializer(), EnrollResponse(c)).encodeToByteArray()

    private fun auth(confirmer: EnrollConfirmer) = ClientOperatorAuth(
        OperatorPopBuilder(FakeStore(), NonceGenerator { ByteArray(16) }),
        CpJwtProvider { _, _ -> "jwt" },
        enrollConfirmer = confirmer,
    )

    private fun sentTypes(t: ScriptedTunnel): List<String> = t.sent.map {
        val s = it.decodeToString()
        when {
            "\"backupCodes\"" in s -> "EnrollResponse"
            "\"pop\"" in s || "\"cpJwt\"" in s -> "TunnelAuthRequest"
            else -> "SavedAck"
        }
    }

    @Test
    fun steadyState_firstGrantIsConnected_noEnrollResponse_noSavedAck() = runTest {
        val t = ScriptedTunnel(listOf(grant(granted = true, firstEnroll = false)))
        assertEquals(OperatorAuthOutcome.Granted, auth(EnrollConfirmer { true }).authenticate(t, "hub-1"))
        assertEquals(listOf("TunnelAuthRequest"), sentTypes(t)) // NO SavedAck on the steady-state path
    }

    @Test
    fun firstEnroll_happyPath_reveals_sendsSavedAck_thenFinalGrantConnects() = runTest {
        var shown: List<String>? = null
        val t = ScriptedTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes), grant(true, firstEnroll = false)))
        val outcome = auth(EnrollConfirmer { shown = it; true }).authenticate(t, "hub-1")
        assertEquals(OperatorAuthOutcome.Granted, outcome)
        assertEquals(codes, shown, "the reveal got the hub's minted codes")
        assertEquals(listOf("TunnelAuthRequest", "SavedAck"), sentTypes(t)) // SavedAck sent AFTER the reveal/ack
    }

    @Test
    fun firstEnroll_invalidCodeSet_failsClosed_noConfirm_noSavedAck() = runTest {
        var confirmerCalled = false
        val t = ScriptedTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes.dropLast(1)))) // truncated
        val outcome = auth(EnrollConfirmer { confirmerCalled = true; true }).authenticate(t, "hub-1")
        assertEquals(OperatorAuthOutcome.Rejected, outcome)
        assertFalse(confirmerCalled, "H3: an invalid set is never even shown/confirmed")
        assertTrue("SavedAck" !in sentTypes(t), "no SavedAck on an invalid set (hub discards the provisional)")
    }

    @Test
    fun firstEnroll_userAborts_failsClosed_noSavedAck() = runTest {
        val t = ScriptedTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes)))
        assertEquals(OperatorAuthOutcome.Rejected, auth(EnrollConfirmer { false }).authenticate(t, "hub-1"))
        assertTrue("SavedAck" !in sentTypes(t), "abort ⇒ no SavedAck ⇒ the hub never finalizes")
    }

    @Test
    fun connected_neverInferred_finalGrantMustBeGrantedAndNotFirstEnroll() = runTest {
        // The hub sent SavedAck but the "final" grant still says firstEnroll=true (finalize did not happen) ⇒ NOT CONNECTED.
        val t = ScriptedTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes), grant(true, firstEnroll = true)))
        assertEquals(OperatorAuthOutcome.Rejected, auth(EnrollConfirmer { true }).authenticate(t, "hub-1"))
    }

    @Test
    fun closedTunnelBeforeFinalGrant_afterSavedAck_failsClosed() = runTest {
        val t = ScriptedTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes))) // no final grant
        assertEquals(OperatorAuthOutcome.Rejected, auth(EnrollConfirmer { true }).authenticate(t, "hub-1"))
        assertEquals(listOf("TunnelAuthRequest", "SavedAck"), sentTypes(t)) // SavedAck was sent, then the tunnel closed
    }
}
