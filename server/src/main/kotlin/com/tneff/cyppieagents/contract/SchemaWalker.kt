package com.tneff.cyppieagents.contract

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * CYP-234a — the in-house `SerialDescriptor` → OpenAPI-3.1 / JSON-Schema-2020-12 projector. The `:core`
 * `@Serializable` DTOs stay the single source; this walks their kotlinx.serialization descriptors and emits
 * schemas, so a schema can never drift from its type (it IS the type, projected). Chosen over an off-the-shelf
 * generator because it gives explicit control of the two hard shapes the spike must prove (PO-A F1):
 *
 *  - **sealed unions** (`classDiscriminator = "type"`) → `oneOf` of `$ref`s + `discriminator{propertyName:type,
 *    mapping}`, honest because the wire already carries `{"type":…}`. Handles **nested** sealed (a sealed inside
 *    a `List` inside another type, e.g. `AgentMessage.content: List<ContentBlock>`) by pure recursion.
 *  - **opaque / contextual / custom-serializer fields** → **carved** to `{}` (any JSON), NEVER force-projected:
 *    `JsonElement`/`JsonObject`/… (`kotlinx.serialization.json.*`), `SerialKind.CONTEXTUAL`, and any custom
 *    serializer whose descriptor kind isn't a structural/primitive kind we model (e.g. `TolerantToolsSerializer`).
 *
 * Named types (classes / sealed unions) become entries in [components] (keyed by a stable schema name) and are
 * referenced by `$ref`; anonymous shapes (list / map / primitive / carved) are emitted inline. Reuse: the same
 * walker feeds both the spec generation AND the per-DTO conformance tooth.
 */
class SchemaWalker {
    /** Collected named component schemas (schema name → schema), for `components/schemas` in the spec. */
    val components: LinkedHashMap<String, JsonObject> = LinkedHashMap()

    /** Component name → EVERY distinct serialName that claimed it — the collision-guard ledger. Two DIFFERENT
     *  serialNames under one component name (a cross-union wire-discriminator clash, e.g. two unrelated `user`
     *  subtypes) silently drop the second schema; [nameCollisions] surfaces it for the guard tooth. */
    val registeredNames: LinkedHashMap<String, MutableSet<String>> = LinkedHashMap()

    /** Stable component name for a named descriptor: the simple name (works for both an FQN serialName and a
     *  short `@SerialName` discriminator value like "text"). */
    fun schemaName(desc: SerialDescriptor): String = desc.serialName.removeSuffix("?").substringAfterLast('.')

    /** The schema for [desc]: a `$ref` for a named type (registering it in [components]), else an inline schema. */
    fun schemaFor(desc: SerialDescriptor): JsonObject {
        // Carve-outs first (PO-A F1b): opaque JSON, contextual, and anything not a shape we model.
        if (isCarveOut(desc)) return anyJson()

        return when (desc.kind) {
            is PrimitiveKind -> primitive(desc.kind as PrimitiveKind)
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { (0 until desc.elementsCount).forEach { add(desc.getElementName(it)) } }
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaFor(desc.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schemaFor(desc.getElementDescriptor(1)))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> refTo(desc) { objectSchema(desc) }
            PolymorphicKind.SEALED -> refTo(desc) { sealedSchema(desc) }
            else -> anyJson() // OPEN polymorphic / unknown → carve (fail-open to any, never force a wrong shape)
        }
    }

    /** Register [desc] as a named component (once) and return a `$ref` to it. */
    private fun refTo(desc: SerialDescriptor, build: () -> JsonObject): JsonObject {
        val name = schemaName(desc)
        val serial = desc.serialName.removeSuffix("?")
        // Collision ledger: record EVERY serialName that claims this component name (guard tooth = injectivity).
        registeredNames.getOrPut(name) { LinkedHashSet() }.add(serial)
        if (name !in components) {
            components[name] = JsonObject(emptyMap()) // reserve the slot FIRST (breaks recursion cycles)
            components[name] = build()
        }
        return buildJsonObject { put("\$ref", "#/components/schemas/$name") }
    }

    /** Collision-guard result: component names claimed by MORE THAN ONE distinct serialName (should be empty). */
    fun nameCollisions(): Map<String, Set<String>> = registeredNames.filterValues { it.size > 1 }

    private fun objectSchema(desc: SerialDescriptor): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (i in 0 until desc.elementsCount) {
                put(desc.getElementName(i), nullableAware(desc.getElementDescriptor(i)))
            }
        }
        // Required = the elements with no default AND not nullable (kotlinx marks a defaulted element optional).
        val required = (0 until desc.elementsCount)
            .filter { !desc.isElementOptional(it) && !desc.getElementDescriptor(it).isNullable }
            .map { desc.getElementName(it) }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

    /** A sealed union → `oneOf` of the subtype `$ref`s + a `discriminator` on the wire `type` field. */
    private fun sealedSchema(desc: SerialDescriptor): JsonObject {
        // kotlinx sealed descriptor: element[0]="type" (String), element[1]=the polymorphic holder whose
        // elementNames are the @SerialName discriminator values and elementDescriptors are the subtype schemas.
        val holder = desc.getElementDescriptor(1)
        val subNames = (0 until holder.elementsCount).map { holder.getElementName(it) }
        val subDescs = (0 until holder.elementsCount).map { holder.getElementDescriptor(it) }
        return buildJsonObject {
            putJsonArray("oneOf") { subDescs.forEach { add(schemaFor(it)) } } // each $ref registers its component
            putJsonObject("discriminator") {
                put("propertyName", "type")
                putJsonObject("mapping") {
                    subNames.forEachIndexed { i, wire -> put(wire, "#/components/schemas/${schemaName(subDescs[i])}") }
                }
            }
        }
    }

    private fun nullableAware(desc: SerialDescriptor): JsonObject {
        val base = schemaFor(desc)
        // OpenAPI 3.1 / JSON-Schema-2020-12 nullability = a type union with "null". A $ref can't carry it inline,
        // so wrap: {anyOf:[$ref, {type:null}]}. For an inline schema, add "null" to the type set.
        if (!desc.isNullable) return base
        if (base.isEmpty()) return base // a carved any-JSON `{}` already permits null
        return if (base.containsKey("\$ref")) {
            buildJsonObject { putJsonArray("anyOf") { add(base); addJsonObject { put("type", "null") } } }
        } else {
            buildJsonObject {
                base.forEach { (k, v) -> if (k != "type") put(k, v) }
                val t = base["type"]
                putJsonArray("type") { if (t != null) add(t); add("null") }
            }
        }
    }

    private fun primitive(kind: PrimitiveKind): JsonObject = buildJsonObject {
        when (kind) {
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
        }
    }

    /** Carve-out predicate: kotlinx JSON types, contextual, and opaque custom serializers (a class descriptor
     *  with NO elements — e.g. `TolerantToolsSerializer`'s `buildClassSerialDescriptor("TolerantTools")` — carries
     *  no schema, so any-JSON is the honest projection) → any-JSON `{}`. */
    private fun isCarveOut(desc: SerialDescriptor): Boolean =
        desc.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.") ||
            desc.kind == SerialKind.CONTEXTUAL ||
            (desc.kind == StructureKind.CLASS && desc.elementsCount == 0)

    /** `{}` = any JSON (an unconstrained schema) — the honest projection of an opaque/contextual field. */
    private fun anyJson(): JsonObject = JsonObject(emptyMap())
}

/** Convenience for tests/tools: the array of a sealed union's discriminator values (from a `oneOf` schema). */
fun JsonObject.oneOfDiscriminatorMapping(): JsonObject? =
    (this["discriminator"] as? JsonObject)?.get("mapping") as? JsonObject

/** Convenience: the `oneOf` member count. */
fun JsonObject.oneOfCount(): Int = (this["oneOf"] as? JsonArray)?.size ?: 0
