package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.ContentBlock
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.ToolResultBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-756 — test-honesty gap-map ③ (MINOR — fidelity, not security): the opaque-`JsonElement` PASSTHROUGH byte-tooth.
 *
 * `ResultEvent.usage`, `RateLimitEvent.rateLimitInfo`, and `ToolResultBlock.content` are deliberately opaque
 * (`JsonObject?` / `JsonElement?`) — the server relays whatever the stream-json CLI emits without modelling it.
 * The existing masking byte-teeth (`EventLogNeedleAbsenceTest`) prove SECRETS are ABSENT from these fields; they
 * do NOT prove that an arbitrary NON-secret payload survives a round-trip byte-faithful. A regression that
 * concretely typed one of these fields (dropping unknown keys, reordering, or retyping a value) would corrupt
 * the telemetry/tool-fidelity passthrough and pass every existing test. This pins the passthrough: a rich opaque
 * payload — unknown keys, nesting, arrays, mixed value types incl. `null` — must survive decode→encode intact,
 * and its unknown keys must still be present in the re-encoded BYTES.
 */
class Cyp756StreamJsonOpaquePassthroughTest {

    private fun obj(json: String): JsonObject = CommJson.decodeFromString(JsonObject.serializer(), json)
    private fun elem(json: String): JsonElement = CommJson.parseToJsonElement(json)

    /** A deliberately rich, mostly-UNMODELLED opaque object: nested object, array of mixed types, a null, and a
     *  key no DTO field knows about. If passthrough retyped/dropped/reordered-to-loss anything, equality reds. */
    private val richUsage = """{"input_tokens":10,"cache_read":5,"nested":{"a":[1,2,{"k":"v"}],"b":null},"weird_unknown_key":true}"""

    @Test
    fun resultEvent_usage_opaqueSurvivesRoundTrip_byteFaithful() {
        val raw = """{"type":"result","subtype":"success","usage":$richUsage,"session_id":"s1"}"""
        val ev = assertIs<ResultEvent>(CommJson.decodeFromString<StreamJsonEvent>(raw))
        val expected = obj(richUsage)
        assertEquals(expected, ev.usage, "the opaque usage must survive DECODE with every key/value/nesting intact")

        // re-encode → decode again: the opaque object must survive the full round-trip, unchanged.
        val out = CommJson.encodeToString<StreamJsonEvent>(ev)
        val ev2 = assertIs<ResultEvent>(CommJson.decodeFromString<StreamJsonEvent>(out))
        assertEquals(expected, ev2.usage, "the opaque usage must survive the full decode→encode→decode round-trip")

        // byte-level: the UNKNOWN + nested keys must actually reach the re-encoded wire bytes (not silently dropped).
        assertTrue(out.contains("weird_unknown_key"), "an unmodelled opaque key must survive to the output bytes: $out")
        assertTrue(out.contains("nested") && out.contains("cache_read"), "nested/opaque keys must survive: $out")
    }

    @Test
    fun rateLimitEvent_rateLimitInfo_opaqueSurvivesRoundTrip() {
        val info = """{"tier":"x","reset":{"at":123,"unknown":[true,null]},"stray":"keep-me"}"""
        val raw = """{"type":"rate_limit_event","rate_limit_info":$info,"session_id":"s1"}"""
        val ev = assertIs<RateLimitEvent>(CommJson.decodeFromString<StreamJsonEvent>(raw))
        val expected = obj(info)
        assertEquals(expected, ev.rateLimitInfo)
        val out = CommJson.encodeToString<StreamJsonEvent>(ev)
        assertEquals(expected, assertIs<RateLimitEvent>(CommJson.decodeFromString<StreamJsonEvent>(out)).rateLimitInfo)
        assertTrue(out.contains("stray") && out.contains("keep-me"))
    }

    @Test
    fun toolResultBlock_content_opaqueArraySurvivesRoundTrip() {
        // tool output is a string OR an arbitrary array/object — the richest opaque shape (JsonElement, not JsonObject).
        val content = elem("""[{"type":"text","text":"out"},{"extra":123,"deep":{"x":[true,null]}}]""")
        val block: ContentBlock = ToolResultBlock(toolUseId = "t1", content = content)
        val out = CommJson.encodeToString(ContentBlock.serializer(), block)
        val rt = assertIs<ToolResultBlock>(CommJson.decodeFromString(ContentBlock.serializer(), out))
        assertEquals(content, rt.content, "the opaque tool-result content (array + nesting + null) must survive intact")
        assertTrue(out.contains("extra") && out.contains("deep"))
    }

    /**
     * PROVE-RED (non-vacuity): the passthrough assertions above lean on `JsonObject`/`JsonElement` structural
     * equality — prove that equality is KEY-SENSITIVE, so a passthrough that dropped even one opaque key would red.
     * (A vacuous equality — e.g. comparing sizes only — would pass this AND miss a real key loss.)
     */
    @Test
    fun opaqueEquality_isKeySensitive_soASingleDroppedKeyWouldRed() {
        val full = obj(richUsage)
        val lossy = JsonObject(full.filterKeys { it != "weird_unknown_key" })
        assertNotEquals(full, lossy, "structural equality must distinguish a single dropped opaque key (non-vacuity)")
    }
}
