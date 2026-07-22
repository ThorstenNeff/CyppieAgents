package com.tneff.cyppieagents.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-798 — honesty tooth for the STANDALONE hub-trust enum export (part ②). The 3 axis-a `:core` enums are a
 * language-neutral wire vocabulary that the TS/browser team (Team-2) consumes from `openapi.json` — but they are
 * route-UNreferenced (§5-a client-DERIVED, don't cross the wire), so they only surface as named `components/schemas`
 * via [ContractGenerator.STANDALONE_ENUM_EXPORTS] + [SchemaWalker.registerNamedEnum]. This pins their PRESENCE and
 * EXACT members + plain `{type:string,enum:[…]}` shape, so:
 *   - dropping a member from STANDALONE_ENUM_EXPORTS (the export knob) REDs here (the browser team silently loses a
 *     type — the whole merge-purpose of CYP-798),
 *   - a member drift in `:core/HubTrust.kt` (e.g. adding an un-ratified TrustRejectReason) REDs here too.
 * Mutation-verified: removing HubTrustState from the export list → this test RED.
 */
class Cyp798StandaloneEnumExportTest {

    private fun schemas(): JsonObject =
        (ContractGenerator.openApi()["components"] as JsonObject)["schemas"] as JsonObject

    private fun enumMembers(name: String): List<String> {
        val schema = schemas()[name] as? JsonObject
            ?: error("standalone enum '$name' is MISSING from openapi components/schemas — the browser team can't consume it")
        assertEquals(
            "string", (schema["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            "'$name' must be a plain string enum (the form po2/Dev5 measured against contract:gen — no wrapper/discriminant)",
        )
        val arr = schema["enum"] as? JsonArray ?: error("'$name' has no 'enum' array")
        return arr.map { it.jsonPrimitive.content }
    }

    @Test
    fun hubTrustState_isExported_withTheFiveRatifiedStates_inDeclarationOrder() {
        assertEquals(
            listOf("UNKNOWN", "PENDING", "TRUSTED", "REJECTED", "STALE"),
            enumMembers("HubTrustState"),
        )
    }

    @Test
    fun trustRejectReason_isExported_asTheClosedTwoMemberSet() {
        assertEquals(
            listOf("KEY_CHANGED", "OOB_REJECTED"),
            enumMembers("TrustRejectReason"),
        )
    }

    @Test
    fun hubDescriptorValidity_isExported_asTheSeparateMalformedAxis() {
        assertEquals(
            listOf("VALID", "MALFORMED"),
            enumMembers("HubDescriptorValidity"),
        )
    }

    @Test
    fun standaloneExport_introducesNoNameCollision() {
        // registerNamedEnum shares the collision ledger with refTo; a standalone name clashing with a walked DTO
        // component would corrupt the browser types. There must be exactly 3 export descriptors, none colliding.
        assertEquals(3, ContractGenerator.STANDALONE_ENUM_EXPORTS.size)
        val names = ContractGenerator.STANDALONE_ENUM_EXPORTS.map { it.serialName.substringAfterLast('.') }
        assertTrue(names.toSet() == setOf("HubTrustState", "TrustRejectReason", "HubDescriptorValidity"), "unexpected export set: $names")
    }
}
