package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-385 — a system notice's tone must distinguish a FAILURE from an INFO.
 *
 * Before this, every [AgentEvent.Notice] rendered in the same neutral `onSurfaceVariant`, so "Verbindung zum
 * Agenten verloren" was tonally identical to "bereit" — a connection loss that reads like "alles ok". The same
 * honesty class as CYP-760 / CYP-643 (meaning lost when the error tone is missing).
 *
 * This pins the DECISION axis purely (no Compose render): the error tone role and the neutral tone role must be
 * distinct. The actual token wiring in `NoticeRow` (ERROR → `colorScheme.error`) is pinned by the source-scan
 * guard [Cyp385NoticeToneWiringGuardTest]; the two error emission sites are pinned by
 * [StreamJsonMapperTest.errorResult_emitsNotice] (turn error), [Cyp383AgentReadyNoticeTest] (ready = NOT error),
 * and [Cyp385ConnLostNoticeIsErrorTest] (connection lost).
 */
class Cyp385NoticeErrorToneTest {

    @Test
    fun errorAndNeutral_areDistinctToneRoles() {
        assertEquals(NoticeToneRole.ERROR, noticeToneRole(isError = true), "a failure notice → the ERROR tone role")
        assertEquals(NoticeToneRole.NEUTRAL, noticeToneRole(isError = false), "a plain notice → the NEUTRAL tone role")
        // The load-bearing invariant: the two roles MUST differ. Mutation: collapse the error branch to NEUTRAL
        // (the pre-CYP-385 lie) ⇒ red. A "connection lost" in the neutral tone is the exact defect this fixes.
        assertNotEquals(
            noticeToneRole(isError = true),
            noticeToneRole(isError = false),
            "an error notice and a neutral notice must render in DISTINCT tones — else 'Verbindung verloren' reads as neutral",
        )
    }
}
