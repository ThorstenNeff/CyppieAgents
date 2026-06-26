package com.tneff.cyppieagents

import kotlinx.serialization.json.Json

/**
 * Single shared Json instance for the whole platform — client, server, and (later) the
 * stream-json mediator all (de)serialize through this exact configuration so the wire
 * contract can never drift between sides.
 *
 *  - [Json.ignoreUnknownKeys] = true: the stream-json protocol from the Claude CLI is
 *    version-sensitive (CYP-5 spike); unmodelled fields must not break decoding.
 *  - classDiscriminator "type": matches the stream-json envelope, where the event/content
 *    kind is carried in a top-level `"type"` field.
 *  - encodeDefaults = true: round-trips stay stable even when a field equals its default.
 */
val CommJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = false
}
