package com.tneff.cyppieagents.e2e

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText

/**
 * E2E raw-byte needle-absence (CYP-108, Reviewer standard) — the CYP-44 observability line applied to the
 * whole HTTP/WS surface. Structural DTO checks prove the *parsed* shape is clean; this proves the **raw
 * bytes** of a response body (`bodyAsText()`) or a `/ws` stream contain NEITHER:
 *  (a) a **foreign-projectId** needle — a distinctive other-project id that must not appear in an
 *      active/authorized-scoped response (catches a scope leak in an unexpected/extra wire field), nor
 *  (b) a **secret** needle — a recognizable plaintext credential that must only ever egress masked.
 *
 * It is deliberately a dumb substring grep over the literal bytes, so a leak hiding in a field the DTO
 * doesn't model is still caught. Paired with [NeedleHelperSelfTest] (positive control): a helper that can
 * never fail would make every absence-assertion vacuous, so the self-test feeds each needle in and proves
 * the helper THROWS.
 */
object Needles {
    /**
     * A recognizable plaintext secret. Never a real key; long + no whitespace so it is a valid CYP-104
     * apiKey for seeding, yet its raw appearance on any wire is unambiguously a leak (the masked egress is
     * `***0000`). Seed it as a project's apiKey, then assert it never appears raw in any response.
     */
    const val SECRET: String = "sk-ant-NEEDLE-d34db33f-secret-value-0000"

    /** A distinctive foreign-project id (SAFE_ID-valid) — unmistakable if it leaks into another scope. */
    const val FOREIGN_PROJECT_ID: String = "zneedle-foreign-proj"
}

/** Thrown (an [AssertionError]) when a needle is present — so `assertFailsWith<NeedlePresentError>` works in the self-test. */
class NeedlePresentError(message: String) : AssertionError(message)

/**
 * Assert NONE of the needles appear as raw bytes in [text]. [hop] labels where (which response / which WS
 * stream) for a legible failure. Secrets are not echoed in the failure message (we only say one leaked).
 */
fun assertNoNeedles(
    hop: String,
    text: String,
    foreignProjectIds: Set<String> = emptySet(),
    secrets: Set<String> = setOf(Needles.SECRET),
) {
    for (pid in foreignProjectIds) {
        if (text.contains(pid)) {
            throw NeedlePresentError("[$hop] foreign-projectId needle '$pid' leaked into the wire bytes: ${text.take(400)}")
        }
    }
    for (s in secrets) {
        if (text.contains(s)) {
            throw NeedlePresentError("[$hop] a secret needle leaked into the wire bytes (value masked in this message)")
        }
    }
}

/** Read the FULL response body once and grep it for needles (HTTP hop). Returns the body text for optional reuse. */
suspend fun HttpResponse.assertNoNeedles(
    hop: String,
    foreignProjectIds: Set<String> = emptySet(),
    secrets: Set<String> = setOf(Needles.SECRET),
): String {
    val text = bodyAsText()
    assertNoNeedles(hop, text, foreignProjectIds, secrets)
    return text
}
