package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.ResultEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-142 (S4.0) — the security guards now live in the shared [com.tneff.cyppieagents.connector] core, so
 * the remote bridge REUSES them by construction (it can't be a mask-bypass and it can't re-derive Gate #6).
 *  - Gate #3 ([EventMasking]) masks secrets here, before any egress (the bridge's wire fork included).
 *  - Gate #6 ([MediationGate]) classifies a turn-end identically for the local router AND the bridge.
 */
class ConnectorCoreSecurityTest {

    /** §8 — masking is intact in the shared core: a secret in a result is redacted BEFORE egress. */
    @Test
    fun gate3_eventMasking_redactsSecretsInTheSharedCore() {
        val leaked = ResultEvent(subtype = "success", isError = false, result = "done; sk-ant-deadbeefcafef00d12345", sessionId = "s1")
        val masked = EventMasking.mask(leaked) as ResultEvent
        assertFalse(masked.result!!.contains("sk-ant-deadbeefcafef00d12345"), "the secret must NOT survive masking")
        assertTrue(masked.result!!.contains("***REDACTED***"), "the secret is redacted")
    }

    /** C2 — Gate #6 classification (the failed-turn discipline) is the SAME shared decision. */
    @Test
    fun gate6_mediationGate_classifiesSuccessAndFailure() {
        assertEquals(MediationGate.TurnPost("ack", MessageKind.STATUS), MediationGate.classify(ResultEvent(subtype = "success", isError = false, result = "ack")))
        assertEquals(MediationGate.TurnPost("(no output)", MessageKind.STATUS), MediationGate.classify(ResultEvent(subtype = "success", isError = false, result = "")))
        assertEquals(MediationGate.TurnPost("[turn failed: boom]", MessageKind.STATUS), MediationGate.classify(ResultEvent(subtype = "boom", isError = true)))
    }
}
