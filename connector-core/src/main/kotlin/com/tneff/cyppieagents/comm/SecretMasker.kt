package com.tneff.cyppieagents.comm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Redacts token-shaped strings on EVERY egress (Reviewer Gate #3): message bodies, but also
 * `tool_use.input` (command strings) and `tool_result.content` (command output) and log lines —
 * a `git remote -v` or env dump can carry `ANTHROPIC_API_KEY`. Fail-safe: when unsure, redact.
 */
object SecretMasker {

    const val REDACTED = "***REDACTED***"

    // Order matters: assignment patterns first (they capture the value after =/:), then bare tokens.
    private val patterns: List<Regex> = listOf(
        // KEY/TOKEN/SECRET/PASSWORD = value  (env dumps, `export X=...`)
        Regex("""(?i)\b([A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASSWD|PWD))\s*[=:]\s*("?)([^\s"']{6,})\2"""),
        // Authorization: Bearer <token>
        Regex("""(?i)\bBearer\s+([A-Za-z0-9._\-]{8,})"""),
        // Anthropic keys
        Regex("""\bsk-ant-[A-Za-z0-9_\-]{8,}"""),
        // Generic provider keys: sk-..., GitHub tokens, OpenAI-style
        Regex("""\b(?:sk|pk)-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        // AWS access key ids
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    )

    /** Mask a plain string. Idempotent: re-masking already-redacted text is a no-op. */
    fun mask(text: String): String {
        var out = text
        // Keep the human-readable label for assignments (FOO_KEY=***REDACTED***), redact the value.
        out = patterns[0].replace(out) { m -> "${m.groupValues[1]}=$REDACTED" }
        for (i in 1 until patterns.size) {
            out = patterns[i].replace(out) { m ->
                // For Bearer keep the scheme prefix.
                if (m.value.startsWith("Bearer", ignoreCase = true)) "Bearer $REDACTED" else REDACTED
            }
        }
        return out
    }

    /** Recursively mask every string leaf of a JSON value (tool_use.input / tool_result.content). */
    fun maskJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in element) put(k, maskJson(v))
        }
        is JsonArray -> buildJsonArray {
            for (v in element) add(maskJson(v))
        }
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(mask(element.content)) else element
    }
}
