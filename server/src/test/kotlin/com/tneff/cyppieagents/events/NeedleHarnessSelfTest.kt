package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.comm.SecretMasker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the [NeedleHarness] premise so the §4 absence assertions are known to be meaningful:
 * masking catches the secret needle but NOT the plain one — therefore only the projector's structural
 * metadata-only exclusion can keep the plain needle out of `Event.detail`. Plus a positive control so
 * the absence helper is not a silent no-op.
 */
class NeedleHarnessSelfTest {

    @Test
    fun secretNeedle_isCaughtBySecretMasker() {
        assertFalse(
            SecretMasker.mask(NeedleHarness.SECRET_NEEDLE).contains(NeedleHarness.SECRET_NEEDLE),
            "secret-shaped needle must be redacted by the masker",
        )
    }

    @Test
    fun plainNeedle_escapesSecretMasker_soOnlyStructuralExclusionCanCatchIt() {
        assertTrue(
            SecretMasker.mask(NeedleHarness.PLAIN_NEEDLE).contains(NeedleHarness.PLAIN_NEEDLE),
            "plain needle MUST survive masking — that's what makes it a test of *structural* metadata-only, not masking",
        )
    }

    @Test
    fun positiveControl_assertNoNeedle_failsWhenNeedlePresent() {
        var tripped = false
        try {
            NeedleHarness.assertNoNeedle("prefix ${NeedleHarness.PLAIN_NEEDLE} suffix", "positive control")
        } catch (e: AssertionError) {
            tripped = true
        }
        assertTrue(tripped, "assertNoNeedle must FAIL when a needle is present (guards against a no-op assertion)")
    }
}
