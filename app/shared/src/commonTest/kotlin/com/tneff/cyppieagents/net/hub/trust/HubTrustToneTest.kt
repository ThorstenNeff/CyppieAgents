package com.tneff.cyppieagents.net.hub.trust

import androidx.compose.material3.lightColorScheme
import com.tneff.cyppieagents.eventlog.severityColorFor
import com.tneff.cyppieagents.model.HubDescriptorValidity
import com.tneff.cyppieagents.model.HubTrustState
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-808 — the 5-state tone MAP + the two-signal fail-closed derivation. Contract-parity with web-ts `hubTrustView.ts`:
 * glyph FORMS ◯◔●⊘◑, lowercase state tokens, and the tone roles (unknown/pending muted, TRUSTED neutral-full,
 * REJECTED/STALE amber). Pure — no render.
 */
class HubTrustToneTest {

    private val scheme = lightColorScheme()
    private fun tone(s: HubTrustState) = hubTrustToneFor(s, scheme, dark = false)

    @Test
    fun glyphs_areTheDistinctFormsPerState_parityWithWebTs() {
        assertEquals("◯", tone(HubTrustState.UNKNOWN).glyph)
        assertEquals("◔", tone(HubTrustState.PENDING).glyph)
        assertEquals("●", tone(HubTrustState.TRUSTED).glyph)
        assertEquals("⊘", tone(HubTrustState.REJECTED).glyph)
        assertEquals("◑", tone(HubTrustState.STALE).glyph)
        // N4 / WCAG 1.4.1: all five forms distinct, and REJECTED ≠ STALE despite the shared amber hue.
        val glyphs = HubTrustState.entries.map { tone(it).glyph }
        assertEquals(glyphs.size, glyphs.toSet().size, "every state must carry a distinct glyph FORM")
    }

    @Test
    fun stateToken_isPlainLowercaseName() {
        for (s in HubTrustState.entries) {
            assertEquals(s.name.lowercase(), tone(s).stateToken, "the testTag token is the plain lowercase state name")
        }
    }

    @Test
    fun colorRoles_matchTheContract() {
        // Muted absence tone for the two neutral non-affirmative states.
        assertEquals(scheme.onSurfaceVariant, tone(HubTrustState.UNKNOWN).color)
        assertEquals(scheme.onSurfaceVariant, tone(HubTrustState.PENDING).color)
        // TRUSTED = full-emphasis neutral (never-green) — pinned harder in HubTrustToneNeverGreenTest.
        assertEquals(scheme.onSurface, tone(HubTrustState.TRUSTED).color)
        // REJECTED + STALE = the ONE WARN-amber source (night-safe; NOT tertiary, NOT error-red).
        val amber = severityColorFor(Severity.WARN, scheme, dark = false)
        assertEquals(amber, tone(HubTrustState.REJECTED).color)
        assertEquals(amber, tone(HubTrustState.STALE).color)
        assertEquals(tone(HubTrustState.REJECTED).color, tone(HubTrustState.STALE).color, "same amber hue (distinct via glyph+copy)")
    }

    @Test
    fun badgeState_isFailClosed_malformedAndNullBothCollapseToUnknown() {
        // MALFORMED collapses even an explicit TRUSTED to UNKNOWN (trust couldn't be evaluated — never trusted).
        assertEquals(HubTrustState.UNKNOWN, hubTrustBadgeState(HubTrustState.TRUSTED, HubDescriptorValidity.MALFORMED))
        // An absent/null signal defaults to UNKNOWN (never TRUSTED, never absence).
        assertEquals(HubTrustState.UNKNOWN, hubTrustBadgeState(null, HubDescriptorValidity.VALID))
        // A real state with a VALID descriptor passes through unchanged.
        assertEquals(HubTrustState.STALE, hubTrustBadgeState(HubTrustState.STALE, HubDescriptorValidity.VALID))
    }

    @Test
    fun upstreamError_isPresentIffMalformed() {
        assertTrue(descriptorUpstreamError(HubDescriptorValidity.MALFORMED))
        assertFalse(descriptorUpstreamError(HubDescriptorValidity.VALID))
    }
}
