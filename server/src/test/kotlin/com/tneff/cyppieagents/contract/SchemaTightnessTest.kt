package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.model.CommWsClientEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-234a-2a — the **STRUCTURAL tightness check** (PO-A + Tester hardening): the instance-based conformance
 * tooth (234a-1) catches NARROWING / wrong-shape (a schema too STRICT or mis-discriminated fails a real
 * instance), but it CANNOT catch **WIDENING** — a `required→optional` field or a phantom `oneOf` branch — because
 * a real instance always carries its required fields, so a too-PERMISSIVE schema still passes. This closes it by
 * comparing each generated schema BACK against its `SerialDescriptor`'s ground truth (descriptor-derived, not
 * instance-derived):
 *  - `required` == exactly the set of **non-defaulted, non-nullable** descriptor elements (no widening/narrowing);
 *  - `properties` keys == exactly the descriptor's element names;
 *  - a sealed union's `oneOf` branch set == exactly the sealed subclass set (no phantom / missing branch).
 *
 * Together with 234a-1's conformance, "generated ⇒ correct" is now COMPLETE (narrowing AND widening) — the
 * "the rendered docs cannot lie" guarantee actually holds.
 */
class SchemaTightnessTest {

    // WS roots + the REST request/response DTOs (derived from RestContract.REST_OPS so tightness covers EXACTLY
    // the components the OpenAPI emits) + the error envelope. The walker recurses; these are the roots.
    private val roots: List<SerialDescriptor> = buildList {
        add(serializer<StreamJsonEvent>().descriptor)
        add(serializer<CommWsServerEvent>().descriptor)
        add(serializer<CommWsClientEvent>().descriptor)
        add(serializer<EventsWsServerEvent>().descriptor)
        add(serializer<EventsWsClientEvent>().descriptor)
        add(serializer<com.tneff.cyppieagents.model.AgentRunStateEvent>().descriptor)
        add(serializer<com.tneff.cyppieagents.model.UserTurn>().descriptor)
        add(serializer<com.tneff.cyppieagents.model.StoredAgentEvent>().descriptor)
        add(serializer<com.tneff.cyppieagents.model.ApiErrorBody>().descriptor)
        RestContract.REST_OPS.forEach { op ->
            restBodyDescriptor(op.request)?.let { add(it) }
            restBodyDescriptor(op.response)?.let { add(it) }
        }
    }.distinct()

    private fun restBodyDescriptor(b: RestContract.Body): SerialDescriptor? = when (b) {
        is RestContract.Body.Json -> b.descriptor
        is RestContract.Body.JsonArray -> b.element
        else -> null
    }

    private fun JsonObject.reqSet(): Set<String> =
        (this["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: emptySet()

    private fun JsonObject.propKeys(): Set<String> = (this["properties"] as? JsonObject)?.keys ?: emptySet()

    private fun JsonObject.oneOfRefNames(): Set<String> =
        (this["oneOf"] as? JsonArray)?.mapNotNull { ((it as JsonObject)["\$ref"] as? JsonPrimitive)?.content?.substringAfterLast('/') }?.toSet() ?: emptySet()

    @Test
    fun everyGeneratedSchema_isTIGHT_against_itsDescriptor() {
        val w = SchemaWalker()
        roots.forEach { w.schemaFor(it) }

        for ((name, desc) in w.componentDescriptors) {
            val schema = w.components[name]!!
            when (desc.kind) {
                StructureKind.CLASS, StructureKind.OBJECT -> {
                    // ground truth FROM the descriptor
                    val allProps = (0 until desc.elementsCount).map { desc.getElementName(it) }.toSet()
                    val expectedRequired = (0 until desc.elementsCount)
                        .filter { !desc.isElementOptional(it) && !desc.getElementDescriptor(it).isNullable }
                        .map { desc.getElementName(it) }.toSet()
                    assertEquals(allProps, schema.propKeys(), "[$name] properties must be EXACTLY the descriptor's elements")
                    assertEquals(expectedRequired, schema.reqSet(), "[$name] required must be EXACTLY the non-defaulted non-null elements (no widening/narrowing)")
                }
                PolymorphicKind.SEALED -> {
                    val holder = desc.getElementDescriptor(1)
                    val expectedBranches = (0 until holder.elementsCount).map { w.schemaName(holder.getElementDescriptor(it)) }.toSet()
                    assertEquals(expectedBranches, schema.oneOfRefNames(), "[$name] oneOf branches must be EXACTLY the sealed subclass set (no phantom/missing branch)")
                }
                else -> {} // primitives/lists/maps/carved are inline, not named components
            }
        }
    }
}
