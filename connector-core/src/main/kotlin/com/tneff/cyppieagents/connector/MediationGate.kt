package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.ResultEvent

/**
 * CYP-142 / E2.6 (S4.0) — **Gate #6** (the failed-turn mediation discipline), extracted from
 * `MediationRouter` into the shared connector core so BOTH the local hub router AND the remote bridge
 * **reuse the same turn-end classification** (not re-derive it — the same reuse-by-construction line as
 * the CYP-170 inversion in [ResumingSession]). The body/kind is decided HERE; the consumer owns only the
 * delivery (`postAsAgent` for the local router; `WireSend` for the bridge) and the identity→spoke routing.
 *
 *  - a successful turn → its result text (or `"(no output)"`), as a STATUS.
 *  - a failed/half turn → a `"[turn failed: …]"` STATUS — downstream can NEVER mistake it for success.
 */
object MediationGate {
    data class TurnPost(val body: String, val kind: MessageKind)

    fun classify(event: ResultEvent): TurnPost =
        if (event.isSuccess) {
            TurnPost(event.result?.takeIf { it.isNotBlank() } ?: "(no output)", MessageKind.STATUS)
        } else {
            TurnPost("[turn failed: ${event.subtype ?: "error"}]", MessageKind.STATUS)
        }
}
