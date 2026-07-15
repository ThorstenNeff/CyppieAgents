package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-595 — the enroll finalization must be **bounded**: a hub that never sends the post-`SavedAck` final grant must
 * NOT hang forever on "Confirming operator" (the live CYP-371-class unbounded-await bug). The client bounds each RR3
 * network receive and surfaces the retryable [OperatorAuthOutcome.EnrollTimedOut] instead. The user-saved confirm is
 * a SEPARATE, unbounded user wait (not exercised here).
 */
class Cyp595EnrollTimeoutTest {

    private val codes = List(BACKUP_CODE_COUNT) { "code-$it" }

    /** Serves [replies] in order, then — instead of a clean `null` close — HANGS on the next receive (the hub stalled). */
    private class StallOnFinalTunnel(replies: List<ByteArray>) : NoiseTunnel {
        private val queue = ArrayDeque(replies)
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = if (queue.isEmpty()) awaitCancellation() else queue.removeFirst()
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

    private fun auth(confirmer: EnrollConfirmer, timeoutMs: Long) = ClientOperatorAuth(
        OperatorPopBuilder(FakeStore(), NonceGenerator { ByteArray(16) }),
        CpJwtProvider { _, _ -> "jwt" },
        enrollConfirmer = confirmer,
        enrollFinalizeTimeoutMs = timeoutMs,
    )

    @Test
    fun finalGrantStalls_timesOut_toEnrollTimedOut_notHang() = runTest {
        // First-enroll grant + codes arrive; the operator confirms saved; SavedAck is sent — then the hub STALLS on the
        // final grant (never sends it). The bounded await must surface EnrollTimedOut, not hang on "Confirming operator".
        val t = StallOnFinalTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes)))
        var outcome: OperatorAuthOutcome? = null
        backgroundScope.launch { outcome = auth(EnrollConfirmer { true }, timeoutMs = 1_000L).authenticate(t, "hub-1") }
        advanceTimeBy(1_001L); runCurrent()
        // Reddening mutation: drop the withTimeout bound in runEnrollProtocol ⇒ readGrant awaits forever ⇒ outcome stays
        // null ⇒ red (the CYP-371 eternal-hang). With the bound: retryable EnrollTimedOut ("window expired — reconnect").
        assertEquals(OperatorAuthOutcome.EnrollTimedOut, outcome, "a stalled final grant ⇒ retryable EnrollTimedOut, not a hang")
    }

    @Test
    fun finalGrantArrivesInTime_stillConnects_boundDoesNotFalseTrip() = runTest {
        // Contrast (non-vacuous): with the SAME small bound, a final grant that arrives promptly still CONNECTS — the
        // timeout guards the hang without breaking the happy path.
        val t = StallOnFinalTunnel(listOf(grant(true, firstEnroll = true), enrollResp(codes), grant(true, firstEnroll = false)))
        var outcome: OperatorAuthOutcome? = null
        backgroundScope.launch { outcome = auth(EnrollConfirmer { true }, timeoutMs = 1_000L).authenticate(t, "hub-1") }
        runCurrent()
        assertEquals(OperatorAuthOutcome.Granted, outcome, "a prompt final grant connects — the bound does not false-trip")
    }
}
