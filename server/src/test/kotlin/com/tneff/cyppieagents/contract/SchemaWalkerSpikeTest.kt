package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-234a — **the spike (PO-A F1): does the in-house descriptor walker project the HARD shapes correctly,
 * BEFORE we commit to it as the generator?** Proves, on the two shapes an off-the-shelf generator tends to
 * botch:
 *  1. a **flat** sealed union (`CommWsServerEvent`) → `oneOf` + `discriminator{type}`;
 *  2. a **nested** sealed union — a sealed inside a `List` inside a class (`AgentMessage.content:
 *     List<ContentBlock>`, reached via `StreamJsonEvent → AssistantEvent → AgentMessage`) → recursion;
 *  3. **opaque `JsonElement` + a custom serializer** (`ToolResultBlock.content`, `SystemEvent.tools` via
 *     `TolerantToolsSerializer`) → **carved to `{}` (any JSON)**, never force-projected.
 *
 * Green here ⇒ the in-house walker is the pinned generator (report the outcome; then 234a builds the full
 * generation + the per-DTO conformance tooth on top of THIS walker).
 */
class SchemaWalkerSpikeTest {

    private fun JsonObject.ref(): String? = (this["\$ref"] as? JsonPrimitive)?.content
    private fun JsonObject.props(): JsonObject = this["properties"] as JsonObject
    private fun mappingKeys(schema: JsonObject): Set<String> =
        (schema.oneOfDiscriminatorMapping() ?: JsonObject(emptyMap())).keys

    @Test
    fun flatSealedUnion_projectsToOneOfPlusDiscriminator() {
        val w = SchemaWalker()
        val top = w.schemaFor(serializer<CommWsServerEvent>().descriptor)
        assertEquals("#/components/schemas/CommWsServerEvent", top.ref(), "a named union is referenced")
        val union = w.components["CommWsServerEvent"]!!
        assertTrue(union.oneOfCount() >= 3, "MessageEvent / AclEvent / ChannelsEvent are oneOf members")
        assertEquals("type", ((union["discriminator"] as JsonObject)["propertyName"] as JsonPrimitive).content)
        // the discriminator mapping is honest — the wire @SerialName values
        assertTrue(mappingKeys(union).isNotEmpty(), "discriminator mapping is populated from @SerialName")
    }

    @Test
    fun nestedSealed_insideListInsideClass_projectsByRecursion() {
        val w = SchemaWalker()
        w.schemaFor(serializer<StreamJsonEvent>().descriptor)

        // top union has all 5 subtypes + the honest wire discriminators
        val stream = w.components["StreamJsonEvent"]!!
        assertEquals(5, stream.oneOfCount())
        assertEquals(setOf("system", "rate_limit_event", "assistant", "user", "result"), mappingKeys(stream))

        // AssistantEvent (@SerialName "assistant") → message: $ref AgentMessage (registered by the recursion).
        // NB: components are keyed by the serial NAME — @SerialName overrides the class name, so the union
        // subtypes are keyed by their honest wire discriminator ("assistant"), the un-annotated types by name.
        val assistant = w.components["assistant"]!!
        assertEquals("#/components/schemas/AgentMessage", (assistant.props()["message"] as JsonObject).ref())

        // AgentMessage.content → array whose items are the NESTED sealed ContentBlock $ref
        val content = w.components["AgentMessage"]!!.props()["content"] as JsonObject
        assertEquals("array", (content["type"] as JsonPrimitive).content)
        assertEquals("#/components/schemas/ContentBlock", (content["items"] as JsonObject).ref())

        // ContentBlock is itself a oneOf + discriminator — the nested union projected correctly
        val block = w.components["ContentBlock"]!!
        assertEquals(setOf("text", "thinking", "tool_use", "tool_result"), mappingKeys(block))
    }

    @Test
    fun opaqueJsonElement_andCustomSerializer_areCarvedToAnyJson() {
        val w = SchemaWalker()
        w.schemaFor(serializer<StreamJsonEvent>().descriptor)

        // ToolResultBlock (@SerialName "tool_result").content : JsonElement? → carved to {} (any JSON).
        val toolResult = w.components["tool_result"]!!["properties"] as JsonObject
        assertEquals(JsonObject(emptyMap()), toolResult["content"], "opaque JsonElement is carved to any-JSON {}")

        // SystemEvent (@SerialName "system").tools (TolerantToolsSerializer, opaque descriptor) → carved too.
        val system = w.components["system"]!!["properties"] as JsonObject
        assertEquals(JsonObject(emptyMap()), system["tools"], "a custom-serializer field is carved, never mis-projected")

        // and the carve is DISTINCT from a modelled primitive — a real string field stays typed (non-vacuous)
        val text = w.components["text"]!!["properties"] as JsonObject
        assertEquals("string", ((text["text"] as JsonObject)["type"] as JsonPrimitive).content, "a real field is still typed")
    }

    @Test
    fun requiredVsOptional_isHonest() {
        val w = SchemaWalker()
        w.schemaFor(serializer<StreamJsonEvent>().descriptor)
        // ToolUseBlock (@SerialName "tool_use").id + name are non-default, non-null → required; input has a default → optional.
        val toolUse = w.components["tool_use"]!!
        val required = (toolUse["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: emptySet()
        assertTrue("id" in required && "name" in required, "non-default non-null fields are required")
        assertTrue("input" !in required, "a defaulted field is optional (not required)")
    }
}
