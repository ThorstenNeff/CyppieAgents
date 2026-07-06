package com.tneff.cyppieagents.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-234a-2a — breaks the walker⇄tightness CORRELATION (PO-Assistant rest-note): both the [SchemaWalker]
 * (emitting `required`) and [SchemaTightnessTest] (checking it) share the canonical formula
 * `!isElementOptional && !isNullable`, so a purely CONCEPTUAL formula error would correlate through both and
 * neither would catch it. This pins the formula against a HAND-SPECIFIED expectation (independent of the
 * walker) over a fixture covering every case — a required, a defaulted, a nullable-with-default, and a
 * nullable-without-default field — so a mis-classification (e.g. forgetting that a nullable-no-default field is
 * NOT required) reds here.
 */
class RequiredFormulaTest {

    @Serializable
    private data class Fixture(
        val required: String, // no default, non-null → REQUIRED
        val defaulted: String = "d", // has a default → optional
        val nullableWithDefault: String? = null, // nullable + default → optional
        val nullableNoDefault: String?, // nullable, no default → NOT required (nullable wins over "no default")
    )

    @Test
    fun requiredFormula_isCorrect_acrossDefaultedNullableRequired() {
        val d = serializer<Fixture>().descriptor
        val required = (0 until d.elementsCount)
            .filter { !d.isElementOptional(it) && !d.getElementDescriptor(it).isNullable }
            .map { d.getElementName(it) }.toSet()
        // HAND-specified ground truth (NOT re-derived via the same formula) — the correlation-breaker.
        assertEquals(setOf("required"), required, "only the non-default, non-null field is required")
    }
}
