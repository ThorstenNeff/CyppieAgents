package com.tneff.cyppieagents.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-110 reviewer-M4 fix: the billing-direction key selection ([Rb1RealAgentHarness.resolveRunKey]) is
 * pure, so it is mutation-proven here in a **NON-RUN_RB1-gated** unit test (runs in the default `:e2e`
 * gate). Without this, the selection lived only inside the RUN_RB1-gated boot path → the fail-open
 * mutation (use the local.properties key regardless of mode = a leftover-key billing hijack) would stay
 * GREEN in the default gate. Same lesson as CYP-122's M4: a load-bearing decision behind a gate must be
 * extracted to a pure function and pinned by a non-gated test.
 */
class Rb1AuthModeTest {

    @Test
    fun subscriptionMode_ignoresAPresentLocalPropertiesKey() {
        // THE fail-open guard: subscription (default) must NOT use a present key. The mutation
        // `resolveRunKey = keyFromProps` (drop the mode check) reddens exactly here.
        assertNull(Rb1RealAgentHarness.resolveRunKey(apiKeyMode = false, keyFromProps = "sk-leftover-should-be-ignored"))
    }

    @Test
    fun apiKeyMode_usesThePresentKey() {
        assertEquals("sk-the-one-run-key", Rb1RealAgentHarness.resolveRunKey(apiKeyMode = true, keyFromProps = "sk-the-one-run-key"))
    }

    @Test
    fun apiKeyMode_butNoKey_isNull_oauthFallback() {
        assertNull(Rb1RealAgentHarness.resolveRunKey(apiKeyMode = true, keyFromProps = null))
    }

    @Test
    fun subscriptionMode_noKey_isNull() {
        assertNull(Rb1RealAgentHarness.resolveRunKey(apiKeyMode = false, keyFromProps = null))
    }
}
