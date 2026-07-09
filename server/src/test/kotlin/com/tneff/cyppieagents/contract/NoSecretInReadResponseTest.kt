package com.tneff.cyppieagents.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-285 — **STANDING GUARD** (CYP-234 exposure-audit LOW-1; the security-analog to the CYP-272 tier-tooth):
 * no sensitive field (a plaintext-credential smell — `token` / `secret` / `password` / `apiKey`) may appear in a
 * RESPONSE schema of the public generated contract, EXCEPT the explicitly-allowlisted one-time-mint credentials,
 * and those ONLY on a CREATE (`POST`) op. Today only `CreatedAgent.token` (POST /api/agents, CYP-171) and
 * `MintedParticipantToken.token` (POST /api/participant-tokens, CYP-234b-3) are sensitive — both create-only
 * mint disclosures, never on a read. This makes that invariant PERMANENT: a future change that plants a
 * `token`/secret field into a read DTO reds HERE, before it silently leaks into the generated OpenAPI / hosted
 * docs (the "public reads leak no secrets" guarantee becomes structural, not manually audited).
 *
 * Scope = REST response schemas (the public read contract). The WS server→client events are a separate surface;
 * their only credential-adjacent field, `StreamJsonEvent.SystemEvent.apiKeySource`, is a non-secret *source
 * label* ("user"/"none"/…), not a key — out of this guard's scope by design.
 */
class NoSecretInReadResponseTest {

    /** A plaintext-credential smell in a field name (lowercased substring). Fail-closed: a legit non-secret
     *  field that happens to contain one of these (e.g. a future pagination `nextPageToken`) must be allowlisted
     *  deliberately — better to flag-and-allowlist than to miss a real secret. */
    private val sensitive = listOf("token", "secret", "password", "passwd", "apikey", "credential", "privatekey")
    private fun isSensitive(field: String) = sensitive.any { field.lowercase().contains(it) }

    /** The ONLY legit sensitive response fields: one-time mint credentials, disclosed exactly once on their
     *  CREATE (POST) op (never re-rendered on a read). `(schemaName, fieldName)` — tied to `POST` below. */
    private val allowlist = setOf("CreatedAgent" to "token", "MintedParticipantToken" to "token")

    /** CYP-326 — deliberately allowlisted NON-credential fields whose name merely contains "token": a token
     *  COUNT / threshold (the compact-orchestration context threshold), safe on reads. The guard's own note
     *  (below) prescribes flag-and-allowlist for such names rather than a rename (which would break the
     *  already-published Dev contract). */
    private val readSafeTokenFields = setOf(
        "CompactStatus" to "thresholdTokens",
        "CompactConfig" to "thresholdTokens",
    )

    private fun restBodyDescriptor(b: RestContract.Body): SerialDescriptor? = when (b) {
        is RestContract.Body.Json -> b.descriptor
        is RestContract.Body.JsonArray -> b.element
        else -> null
    }

    /** Every `(schemaName, fieldName)` reachable from a response body — walked through the SAME generator the
     *  OpenAPI uses, so this sees EXACTLY what the public spec would expose (nested DTOs included, by recursion). */
    private fun responseFields(desc: SerialDescriptor): Set<Pair<String, String>> {
        val w = SchemaWalker()
        w.schemaFor(desc)
        return buildSet {
            for ((name, schema) in w.components) {
                ((schema["properties"] as? JsonObject)?.keys ?: emptySet()).forEach { add(name to it) }
            }
        }
    }

    @Test
    fun noResponseSchema_exposesASensitiveField_exceptAllowlistedMintsOnCreate() {
        val violations = buildList {
            for (op in RestContract.REST_OPS) {
                val desc = restBodyDescriptor(op.response) ?: continue
                for ((schema, field) in responseFields(desc)) {
                    if (!isSensitive(field)) continue
                    // A sensitive response field is legit ONLY as a one-time-mint disclosure on a CREATE (POST) op.
                    val allowed = (op.method == "POST" && (schema to field) in allowlist) ||
                        (schema to field) in readSafeTokenFields // CYP-326: a token COUNT threshold, not a credential
                    if (!allowed) add("${op.method} ${op.path} → $schema.$field")
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "sensitive field(s) exposed in a public READ/response schema (would leak into the generated OpenAPI/docs): $violations",
        )
    }

    /** Non-vacuity (the ticket's required proof): planting a `token` field in a read-DTO would RED. If the
     *  detection ever stopped matching, this fixture check fails — the guard cannot silently go toothless. */
    @Serializable
    private data class LeakyReadDtoFixture(val id: String, val token: String)

    @Test
    fun detection_isNonVacuous_aPlantedTokenFieldIsFlagged() {
        val fields = responseFields(serializer<LeakyReadDtoFixture>().descriptor)
        assertTrue(
            fields.any { it == ("LeakyReadDtoFixture" to "token") } && isSensitive("token"),
            "the guard must detect a planted `token` field in a read DTO — that is the whole point of the standing guard",
        )
    }

    /** Non-vacuity: the allowlist is LIVE — every allowlisted mint field is actually present in the contract, so
     *  the allowlist guards something real (and gets pruned if a mint DTO is ever removed). */
    @Test
    fun allowlist_isLive_everyEntryIsStillInTheContract() {
        val all = RestContract.REST_OPS.mapNotNull { restBodyDescriptor(it.response) }
            .flatMap { responseFields(it) }.toSet()
        for (entry in allowlist) {
            assertTrue(entry in all, "allowlisted mint field $entry is no longer in the contract — prune it from the allowlist (guard hygiene)")
        }
    }
}
