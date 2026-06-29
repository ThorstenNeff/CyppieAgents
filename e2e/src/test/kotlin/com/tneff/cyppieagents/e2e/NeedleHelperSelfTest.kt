package com.tneff.cyppieagents.e2e

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Positive control for [assertNoNeedles] (CYP-108, Reviewer requirement). Needle-absence assertions are
 * only meaningful if the helper actually FAILS when a needle is present — otherwise a no-op grep would
 * make every J1–J4 absence check vacuously "pass". These tests deliberately feed each needle class in and
 * prove the helper throws, and prove a clean text passes. Covers both wire sources symbolically (the
 * helper is source-agnostic: HTTP `bodyAsText()` and `/ws` frame text both flow through the same grep).
 */
class NeedleHelperSelfTest {

    @Test
    fun foreignProjectIdNeedle_present_throws() {
        assertFailsWith<NeedlePresentError> {
            assertNoNeedles(
                "self-control",
                """{"events":[{"agentId":"x","projectId":"${Needles.FOREIGN_PROJECT_ID}"}]}""",
                foreignProjectIds = setOf(Needles.FOREIGN_PROJECT_ID),
            )
        }
    }

    @Test
    fun secretNeedle_present_throws() {
        assertFailsWith<NeedlePresentError> {
            assertNoNeedles(
                "self-control",
                """{"apiKey":"${Needles.SECRET}"}""", // a leak: the raw secret on the wire
                secrets = setOf(Needles.SECRET),
            )
        }
    }

    @Test
    fun secretNeedle_inAnUnexpectedExtraField_stillThrows() {
        // The whole point: a leak in a field the DTO does NOT model is still caught by the raw-byte grep.
        assertFailsWith<NeedlePresentError> {
            assertNoNeedles("self-control", """{"set":true,"masked":"***0000","debugRawKey":"${Needles.SECRET}"}""")
        }
    }

    @Test
    fun cleanText_passes_bothClasses() {
        // No throw: masked-only egress + no foreign id present.
        assertNoNeedles(
            "self-control",
            """{"set":true,"masked":"***0000","projects":[{"id":"alpha"},{"id":"beta"}]}""",
            foreignProjectIds = setOf(Needles.FOREIGN_PROJECT_ID),
            secrets = setOf(Needles.SECRET),
        )
    }
}
