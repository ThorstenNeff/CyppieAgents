package com.tneff.cyppieagents.model

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-498 — `AuthMe.verified` is a **required** wire field, not optional. `@Required` makes it (a) always
 * serialized — even when `false` and even under a serializer with `encodeDefaults = false` — and (b) non-optional
 * in the descriptor, which is exactly what the OpenAPI generator (`SchemaWalker`) reads to place it in `required`.
 *
 * These teeth lock the liveness edge (Assist CYP-470): an omitted `verified` would fail-closed a legitimate
 * operator at the verify gate. If a future change drops `@Required`, [verified_alwaysOnWire_evenFalse_terseJson]
 * reds (a terse serializer would omit the defaulted field) and [verified_isNonOptional_inDescriptor] reds (the
 * generator would silently move it back out of `required`).
 */
class AuthMeVerifiedRequiredTest {

    @Test
    fun verified_isNonOptional_inDescriptor() {
        val d = AuthMe.serializer().descriptor
        val i = d.getElementIndex("verified")
        assertTrue(i >= 0, "verified element must exist in the descriptor")
        // @Required → isElementOptional == false → SchemaWalker (`!isElementOptional`) marks it contract-required.
        // Single source: this same flag drives both the wire presence and the OpenAPI `required` entry.
        assertFalse(d.isElementOptional(i), "verified must be NON-optional (contract-`required` derives from this)")
    }

    @Test
    fun verified_alwaysOnWire_evenFalse_terseJson() {
        // A serializer that WOULD omit a defaulted field (encodeDefaults = false, the kotlinx default). @Required
        // forces `verified` regardless — this is the invariant, independent of CommJson's global encodeDefaults.
        val terse = Json { encodeDefaults = false }
        val json = terse.encodeToString(AuthMe.serializer(), AuthMe(authenticated = false))
        assertTrue("\"verified\"" in json, "verified must be on the wire even when false / encodeDefaults off: $json")
    }

    @Test
    fun verified_onWire_underCommJson() {
        val json = CommJson.encodeToString(AuthMe.serializer(), AuthMe(authenticated = false))
        assertTrue("\"verified\":false" in json, "CommJson must emit verified:false for an unauthenticated snapshot: $json")
    }
}
