package com.tneff.cyppieagents.auth

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * CYP-186 C.1 — one attributed OPERATOR mutation. **Content-free by construction:** actor + method + path
 * only — NEVER a body/payload (the `PUT /api/config/apikey` body IS the secret; the audit carries the path,
 * never the key). [actor] is `human:<identityId>` or `operator-token` (the machine/bootstrap/break-glass path),
 * never a generic "operator".
 */
@Serializable
data class OperatorAudit(val actor: String, val method: String, val path: String, val tsMs: Long)

/**
 * The **OPERATOR-only** audit log. Deliberately a SEPARATE sink from the MEMBER-readable event-log
 * (structural isolation, not a read-filter): operator identityIds + activity must never enter the MEMBER
 * stream (BE3a guardrail iii). Read only via the OPERATOR-only `GET /api/audit`.
 */
interface AuditSink {
    fun record(audit: OperatorAudit)
    fun recent(limit: Int): List<OperatorAudit>
}

/** No-op default (the token-only [AuthDeps] path + tests that don't assert audit). */
object NoOpAuditSink : AuditSink {
    override fun record(audit: OperatorAudit) {}
    override fun recent(limit: Int): List<OperatorAudit> = emptyList()
}

/**
 * Process-local capped audit buffer (newest-first on read). Bounded so it can't grow unbounded; durable
 * (SQLite) persistence is an optional follow-up — the C mechanism (attribution + structural isolation +
 * kill-switch) is independent of the backing store.
 */
class InMemoryAuditSink(private val cap: Int = 1_000) : AuditSink {
    private val buf = ConcurrentLinkedDeque<OperatorAudit>()

    override fun record(audit: OperatorAudit) {
        buf.addLast(audit)
        while (buf.size > cap) buf.pollFirst()
    }

    override fun recent(limit: Int): List<OperatorAudit> =
        buf.reversed().take(limit.coerceIn(1, cap))
}
