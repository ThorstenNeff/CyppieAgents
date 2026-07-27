package com.tneff.cyppieagents.net.hub.trust

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.tneff.cyppieagents.model.HubTrustState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-808 / CYP-803 (CYP-747 teeth 1/7/8) — **the never-green honesty guard**, the Compose twin of web-ts
 * `hubTrustTrustedTone.honesty.test.ts` Tooth 10. TRUSTED must be **neutral yet distinct**, pinned in BOTH schemes so a
 * palette drift can't hide behind the light/dark default:
 *
 *  - **(a) OVERCLAIM-BACK:** `TRUSTED.color == onSurface` and **NOT `primary`** — trust is issuer-vouched + REVOCABLE,
 *    so no affirming/green accent. Mutation `TRUSTED → scheme.primary` → RED.
 *  - **(b) OVER-NEUTRALISE:** `TRUSTED.color != UNKNOWN.color` (`onSurface` ≠ `onSurfaceVariant`) — full emphasis
 *    (evaluated), never collapsed into the muted absence tone. Mutation `TRUSTED → scheme.onSurfaceVariant` → RED.
 *
 * Pure core ([hubTrustToneFor]) — no render, no headless-Compose hang. `onSurface`/`onSurfaceVariant`/`primary` are the
 * real M3 default tokens, so the pair is meaningfully distinct in both schemes.
 */
class HubTrustToneNeverGreenTest {

    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    @Test
    fun trusted_isOnSurface_notPrimary_bothSchemes() {
        for ((scheme, isDark) in listOf(light to false, dark to true)) {
            val trusted = hubTrustToneFor(HubTrustState.TRUSTED, scheme, isDark).color
            assertEquals(scheme.onSurface, trusted, "TRUSTED = full-emphasis neutral onSurface (dark=$isDark)")
            // (a) overclaim guard: never the affirming/green accent (trust is revocable).
            assertNotEquals(scheme.primary, trusted, "TRUSTED must NOT be primary/green — never-green (dark=$isDark)")
        }
    }

    @Test
    fun trusted_isDistinctFromUnknown_notCollapsedIntoAbsence_bothSchemes() {
        for ((scheme, isDark) in listOf(light to false, dark to true)) {
            val trusted = hubTrustToneFor(HubTrustState.TRUSTED, scheme, isDark).color
            val unknown = hubTrustToneFor(HubTrustState.UNKNOWN, scheme, isDark).color
            // (b) over-neutralise guard: onSurface ≠ onSurfaceVariant → TRUSTED stays distinct from muted absence.
            assertNotEquals(unknown, trusted, "TRUSTED must NOT collapse into UNKNOWN's absence tone (dark=$isDark)")
            assertEquals(scheme.onSurfaceVariant, unknown, "UNKNOWN = muted absence onSurfaceVariant (dark=$isDark)")
            assertEquals(scheme.onSurface, trusted, "TRUSTED = full-emphasis onSurface (dark=$isDark)")
        }
    }
}
