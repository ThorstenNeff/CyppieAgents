package com.tneff.cyppieagents.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CYP-234a — a **zero-dependency, TEST-ONLY** conformance validator over the exact JSON-Schema vocabulary the
 * [SchemaWalker] emits (`type` / `properties` / `required` / `oneOf`+`discriminator` / `items` /
 * `additionalProperties` / `$ref` / `anyOf` / `enum` / `{}`). Deliberately NOT an external JSON-Schema library:
 * the CYP-226 lesson is that any dependency leaking onto the client/prod classpath is a live-crash risk (the
 * Coil→skiko split); an in-house test util adds **zero** classpath surface — strictly safer than even a
 * test-scoped dep. It validates one narrow thing — does a REAL serialized instance satisfy its GENERATED
 * schema — which is exactly the mis-projection net (a schema that is wrong-but-self-consistent fails here).
 * The correlated-error risk (walker + validator wrong the same way) is retired by a mis-projection mutation
 * test (mutate the walker → conformance reds).
 */
class SchemaConformance(private val components: Map<String, JsonObject>) {

    /** Returns the list of conformance violations (empty = conforms). */
    fun validate(instance: kotlinx.serialization.json.JsonElement, schema: JsonObject, path: String = "$"): List<String> {
        // `{}` (any) accepts everything.
        if (schema.isEmpty()) return emptyList()

        (schema["\$ref"] as? JsonPrimitive)?.content?.let { ref ->
            val name = ref.substringAfterLast('/')
            val target = components[name] ?: return listOf("$path: unresolved \$ref '$ref'")
            return validate(instance, target, path)
        }
        (schema["anyOf"] as? JsonArray)?.let { branches ->
            val ok = branches.any { validate(instance, it as JsonObject, path).isEmpty() }
            return if (ok) emptyList() else listOf("$path: matches no anyOf branch")
        }
        (schema["oneOf"] as? JsonArray)?.let {
            return validateSealed(instance, schema, path)
        }
        (schema["enum"] as? JsonArray)?.let { en ->
            val v = (instance as? JsonPrimitive)?.content
            return if (en.any { (it as JsonPrimitive).content == v }) emptyList() else listOf("$path: '$v' not in enum")
        }

        return when (typeOf(schema)) {
            "object" -> validateObject(instance, schema, path)
            "array" -> validateArray(instance, schema, path)
            "string" -> if (instance is JsonPrimitive && instance.isString) ok() else typeErr(path, "string", instance)
            "integer", "number" -> if (instance is JsonPrimitive && !instance.isString && instance.content.toDoubleOrNull() != null) ok() else typeErr(path, "number", instance)
            "boolean" -> if (instance is JsonPrimitive && (instance.content == "true" || instance.content == "false")) ok() else typeErr(path, "boolean", instance)
            "null" -> if (instance is JsonNull) ok() else typeErr(path, "null", instance)
            null -> ok() // no type constraint → accept (already handled $ref/oneOf/anyOf/enum above)
            else -> ok()
        }
    }

    /** A `oneOf`+`discriminator` union: read the wire `type`, pick the mapped branch, validate against it. */
    private fun validateSealed(instance: kotlinx.serialization.json.JsonElement, schema: JsonObject, path: String): List<String> {
        val obj = instance as? JsonObject ?: return listOf("$path: expected an object for a discriminated union")
        val disc = (schema["discriminator"] as? JsonObject) ?: return listOf("$path: oneOf without a discriminator")
        val prop = (disc["propertyName"] as JsonPrimitive).content
        val wire = (obj[prop] as? JsonPrimitive)?.content ?: return listOf("$path: missing discriminator '$prop'")
        val mapping = disc["mapping"] as? JsonObject
        val ref = (mapping?.get(wire) as? JsonPrimitive)?.content ?: return listOf("$path: discriminator '$wire' not in mapping")
        val name = ref.substringAfterLast('/')
        val target = components[name] ?: return listOf("$path: union branch '$name' unresolved")
        return validate(instance, target, "$path<$wire>")
    }

    private fun validateObject(instance: kotlinx.serialization.json.JsonElement, schema: JsonObject, path: String): List<String> {
        val obj = instance as? JsonObject ?: return typeErr(path, "object", instance)
        val errors = mutableListOf<String>()
        val props = schema["properties"] as? JsonObject
        (schema["required"] as? JsonArray)?.forEach { r ->
            val key = (r as JsonPrimitive).content
            // A required field is satisfied by presence, OR by being an omitted-because-absent field the encoder
            // dropped (explicitNulls=false); the schema marks required only for non-null non-default fields, and
            // the encoder always writes those — so a genuine absence is a real violation.
            if (key !in obj) errors.add("$path.$key: required but absent")
        }
        props?.forEach { (key, sub) ->
            obj[key]?.let { errors += validate(it, sub as JsonObject, "$path.$key") }
        }
        // additionalProperties (map value schema) — validate any extra keys against it if present.
        (schema["additionalProperties"] as? JsonObject)?.let { valSchema ->
            val declared = props?.keys ?: emptySet()
            obj.filterKeys { it !in declared }.forEach { (k, v) -> errors += validate(v, valSchema, "$path.$k") }
        }
        return errors
    }

    private fun validateArray(instance: kotlinx.serialization.json.JsonElement, schema: JsonObject, path: String): List<String> {
        val arr = instance as? JsonArray ?: return typeErr(path, "array", instance)
        val items = schema["items"] as? JsonObject ?: return emptyList()
        return arr.flatMapIndexed { i, el -> validate(el, items, "$path[$i]") }
    }

    private fun typeOf(schema: JsonObject): String? = when (val t = schema["type"]) {
        is JsonPrimitive -> t.content
        is JsonArray -> t.map { (it as JsonPrimitive).content }.firstOrNull { it != "null" } ?: "null" // nullable union
        else -> null
    }

    private fun ok(): List<String> = emptyList()
    private fun typeErr(path: String, expected: String, got: kotlinx.serialization.json.JsonElement) =
        listOf("$path: expected $expected, got ${got::class.simpleName}")
}
