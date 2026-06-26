package com.tneff.cyppieagents.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * Tolerant decoder for `system/init`'s `tools` (CYP-25). Session binding depends on `system/init`
 * decoding successfully, and this field has ALREADY changed shape once (an Int count → an array of
 * names). To stay resilient against further CLI drift, decode an array of string names, and treat
 * ANYTHING else (an int, objects, null, …) as `null` — it must NEVER throw and break the bind.
 */
object TolerantToolsSerializer : KSerializer<List<String>?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.tneff.cyppieagents.model.TolerantTools")

    override fun deserialize(decoder: Decoder): List<String>? {
        val json = decoder as? JsonDecoder ?: return null
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            else -> null // int / object / null / anything else: tolerated, no names
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>?) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("TolerantToolsSerializer supports JSON only")
        json.encodeJsonElement(
            if (value == null) JsonNull else buildJsonArray { value.forEach { add(it) } },
        )
    }
}
