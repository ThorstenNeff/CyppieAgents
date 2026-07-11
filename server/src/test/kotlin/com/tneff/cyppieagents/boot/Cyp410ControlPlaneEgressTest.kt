package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.SecretMasker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-410 (S-A) — the **CP-egress BYOA invariant** (F4): every byte the hub sends toward the Control Plane
 * passes through [SecretMasker] first, so the Anthropic credential (and any payload) never crosses the CP
 * connector in the clear. Anchored via [MaskingControlPlaneConnector] — the single masked egress. Mutation-proof:
 * make [MaskingControlPlaneConnector.egress] forward the raw payload (drop the mask) → the raw `sk-ant-…` key
 * reaches the sink → this test reds.
 */
class Cyp410ControlPlaneEgressTest {

    /** A recording sink standing in for the (Phase-2) real outbound tunnel — captures exactly what would leave. */
    private class RecordingCp : ControlPlaneConnector {
        val sent = mutableListOf<String>()
        override suspend fun egress(payload: String) { sent.add(payload) }
    }

    @Test
    fun cpEgress_masksTheAnthropicCredential_beforeItLeavesTheHub() = runBlocking {
        val sink = RecordingCp()
        val egress: ControlPlaneConnector = MaskingControlPlaneConnector(sink)
        val secret = "sk-ant-SECRETVALUE0123456789abcdef"
        // A payload shaped like something the hub might otherwise relay (env dump + auth header both carry the key).
        egress.egress("hub online; ANTHROPIC_API_KEY=$secret ; Authorization: Bearer $secret")

        val leftTheHub = sink.sent.single()
        assertFalse(
            leftTheHub.contains(secret),
            "the Anthropic credential must NOT cross the CP connector in the clear (BYOA / F4)",
        )
        assertTrue(leftTheHub.contains(SecretMasker.REDACTED), "the egress payload is masked before it leaves")
    }

    @Test
    fun noOpStub_isThePhase1Default_andDoesNotSend() = runBlocking {
        // The Phase-1 second-transport stub is a no-op (S-A scope: created, not implemented). It exists + is safe.
        NoOpControlPlaneConnector.start()
        NoOpControlPlaneConnector.egress("anything")
    }
}
