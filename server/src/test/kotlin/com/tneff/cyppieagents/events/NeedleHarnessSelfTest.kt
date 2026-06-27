package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.connector.EventMasking
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the [NeedleHarness] premise BEFORE the CYP-37 §4 wiring exists — so when the projector
 * lands, the plain-needle-absence assertion is known to be meaningful. This test IS the threat model:
 * masking catches the secret needle but NOT the plain one, therefore only a structural metadata-only
 * projection can keep the plain needle out of `Event.detail`.
 */
class NeedleHarnessSelfTest {

    @Test
    fun secretNeedle_isCaughtBySecretMasker() {
        val masked = SecretMasker.mask(NeedleHarness.SECRET_NEEDLE)
        assertFalse(masked.contains(NeedleHarness.SECRET_NEEDLE), "secret-shaped needle must be redacted by the masker")
    }

    @Test
    fun plainNeedle_escapesSecretMasker_soOnlyStructuralExclusionCanCatchIt() {
        val masked = SecretMasker.mask(NeedleHarness.PLAIN_NEEDLE)
        assertTrue(
            masked.contains(NeedleHarness.PLAIN_NEEDLE),
            "plain needle MUST survive masking — that's what makes it a test of *structural* metadata-only, not masking",
        )
    }

    @Test
    fun eventMasking_redactsSecretNeedle_butPlainNeedleSurvivesInContent() {
        // After Gate #3 (EventMasking.mask) the secret needle is gone from content fields, but the
        // plain needle remains in the *content* — exactly why the projector (CYP-37) must NOT lift
        // content fields into Event.detail. Documents the threat model end-to-end at the mask layer.
        val masked: StreamJsonEvent = EventMasking.mask(NeedleHarness.assistantWithNeedles())
        val json = CommJson.encodeToString(StreamJsonEvent.serializer(), masked)
        assertFalse(json.contains(NeedleHarness.SECRET_NEEDLE), "EventMasking must redact the secret needle from content")
        assertTrue(
            json.contains(NeedleHarness.PLAIN_NEEDLE),
            "plain needle survives masking → the projector's structural exclusion is the only thing that keeps it out of detail",
        )
    }

    @Test
    fun needleEvents_carryBothNeedles_inEveryContentField() {
        val json = NeedleHarness.needleEvents().joinToString("\n") {
            CommJson.encodeToString(StreamJsonEvent.serializer(), it)
        }
        assertTrue(json.contains(NeedleHarness.SECRET_NEEDLE), "corpus must carry the secret needle")
        assertTrue(json.contains(NeedleHarness.PLAIN_NEEDLE), "corpus must carry the plain needle")
        // And the absence helper correctly flags a leak (positive control).
        var tripped = false
        try {
            NeedleHarness.assertNoNeedle(json, "self-test positive control")
        } catch (e: AssertionError) {
            tripped = true
        }
        assertTrue(tripped, "assertNoNeedle must FAIL when a needle is present (guards against a no-op assertion)")
    }
}
