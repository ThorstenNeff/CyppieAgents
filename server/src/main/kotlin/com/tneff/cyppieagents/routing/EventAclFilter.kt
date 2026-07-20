package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * CYP-719 (F2) — the SINGLE ACL-visibility policy for `comm.*` events on the Event-Log READ path, shared by
 * `/api/events` (REST) and `/ws/events` (WS) so the two surfaces cannot drift (mirrors [resolveEventScope],
 * which is the shared *project*-scope resolver). Fixes the leak where any MEMBER reader learned "subject X
 * posted at T in channel Y" without a `canRead(Y)` grant — widened from 1 to 4 paths by CYP-698.
 *
 * Policy for a returned [Event]:
 *  - **Operator** ([isOperator]) → sees everything (member-of-all; no ACL narrowing here — the read is still
 *    project-scoped upstream by [resolveEventScope]).
 *  - Otherwise, keyed on the **property** "the event carries a `channel` in its `detail`" (NOT a positive type
 *    list — a future channel-carrying type is governed automatically): visible iff [acl].canRead(channel,
 *    [subject]) against the CURRENT ACL. [AclMatrix.canRead] is project-AND-acl by construction (channels are
 *    pre-filtered to the active project), so a foreign-project channel is a non-member → denied (never OR'd).
 *    A null [subject] (a credential with no ACL read-subject — the disabled-operator kill-switch downgrade,
 *    [AuthPrincipal.MachineAgent] with a null agentId) can read no channel → **fail-closed** here too.
 *  - A `comm.*` event that carries **no** `channel` (legacy / CYP-712 pre-`channel` data) → **fail-closed**: no
 *    ACL decision is possible, so it is not delivered to a non-operator.
 *  - Any other event (no channel, not `comm.*`; e.g. `tool.call`) → unchanged, delivered.
 *
 * [subject] is the ACL read-subject from the canonical resolver ([commReadSubjectOf] on REST — derived from the
 * guard-stashed principal, whoami-free; [ApplicationCall.wsReaderOrNull] on WS) — Operator→OPERATOR_ID,
 * Human-MEMBER→identityId, Agent→agentId — never re-derived here (CYP-719 §3, single source of truth).
 */
fun aclVisibleEvent(event: Event, subject: String?, isOperator: Boolean, acl: AclMatrix): Boolean {
    if (isOperator) return true
    val channel = (event.detail["channel"] as? JsonPrimitive)?.contentOrNull
    if (channel != null) return subject != null && acl.canRead(channel, subject)
    // channel-less comm.* (legacy) → fail-closed: no channel means no ACL decision is possible.
    if (event.type == EventType.COMM_SENT || event.type == EventType.COMM_RECEIVED) return false
    return true
}

/**
 * CYP-719 §3 — the ACL read-subject for a principal the STRUCTURAL [authenticatedApi] guard ALREADY resolved and
 * stashed under [PrincipalKey]. Deriving the subject from that stash (instead of calling [requireCommReader],
 * which re-runs [resolvePrincipal]) keeps the CYP-240 single-resolve invariant — a human MEMBER on `/api/events`
 * still does exactly ONE whoami (the guard's), guarded by `EventsPrincipalReuseTest`. It is safe because a
 * participant token is 401'd by the guard (CYP-234b-2: an unknown bearer never becomes MEMBER), so the stash is
 * only ever one of the three cases below — there is no participant case to miss.
 *
 * The mapping is byte-identical to the token/session vocab of [wsReaderOrNull] / [requireCommReader]:
 *  - [AuthPrincipal.MachineOperator] (operator token) → [HubState.OPERATOR_ID] (member-of-all).
 *  - [AuthPrincipal.Human] OPERATOR → [HubState.OPERATOR_ID]; MEMBER → its identityId (a first-class ACL
 *    read-subject, fail-closed-empty until an operator grants it per-channel read).
 *  - [AuthPrincipal.MachineAgent] → its agentId, OR **null** for the disabled-operator kill-switch downgrade
 *    ([AuthPrincipal.MachineAgent] with a null agentId, CYP-186 C.2): a degraded credential with NO ACL
 *    read-subject → null → [aclVisibleEvent] fail-closes it out of every channel-scoped comm event.
 */
fun commReadSubjectOf(principal: AuthPrincipal): String? = when (principal) {
    AuthPrincipal.MachineOperator -> HubState.OPERATOR_ID
    is AuthPrincipal.Human -> if (principal.role == AuthRole.OPERATOR) HubState.OPERATOR_ID else principal.identityId
    is AuthPrincipal.MachineAgent -> principal.agentId // null (disabled-operator downgrade) → no ACL subject
}

/**
 * CYP-719 — the ACL-filtered, paging-correct read for `/api/events`. `detail.channel` is not a SQL-queryable
 * column, so the ACL predicate must run in Kotlin AFTER each row is read but BEFORE the `limit` cut — a naive
 * post-hoc filter on a finished [EventPage] would silently shrink the page and corrupt the `nextAfterSeq`
 * cursor (rows the sink already counted toward `limit` would vanish). This over-fetches page-by-page and keeps
 * only ACL-visible events until [page].limit are collected, advancing the scan cursor by the last SCANNED seq
 * (monotonic → always terminates) and returning the cursor as the last KEPT seq.
 *
 * **CYP-719 F-E (bounded scan):** a MEMBER with NO grants can read no `comm.*` row, so a naive over-fetch would
 * scan the WHOLE log (O(N)) to return an empty page — a cheap-to-trigger DoS. The scan is therefore bounded to
 * [MAX_SCAN_BATCHES] sink round-trips per request: if the page can't be filled within that budget, we return
 * what we have with `nextAfterSeq = the last SCANNED seq` (NOT null) so the read is **resumable, never silently
 * truncated** — the client pages on exactly as it would past a full page. A truly-drained sink returns null.
 *
 * Operators bypass the narrowing entirely (a plain [EventSink.query]) — they see all, so no over-fetch is needed.
 */
suspend fun aclQuery(
    sink: EventSink,
    filter: EventFilter,
    page: Page,
    subject: String?,
    isOperator: Boolean,
    acl: AclMatrix,
): EventPage {
    if (isOperator) return sink.query(filter, page)
    val kept = ArrayList<Event>(page.limit)
    var cursor = page.afterSeq
    var drained = false
    var batches = 0
    while (kept.size < page.limit && batches < MAX_SCAN_BATCHES) {
        val batch = sink.query(filter, Page(afterSeq = cursor, limit = page.limit))
        batches++
        if (batch.events.isEmpty()) { drained = true; break }
        for (e in batch.events) if (aclVisibleEvent(e, subject, isOperator = false, acl)) kept.add(e)
        cursor = batch.events.last().seq
        if (batch.nextAfterSeq == null) { drained = true; break } // the sink is drained (a partial page)
    }
    val out = if (kept.size > page.limit) kept.subList(0, page.limit).toList() else kept
    // A full page resumes after the last KEPT seq; a drained sink with a partial page ends (null); a page that
    // hit the scan budget without draining resumes after the last SCANNED seq (the bounded-scan cursor).
    val next = when {
        out.size >= page.limit -> out.last().seq
        drained -> null
        else -> cursor
    }
    return EventPage(events = out, nextAfterSeq = next)
}

/**
 * CYP-719 F-E — the per-request over-fetch budget (sink round-trips) that bounds a grant-less MEMBER's scan to
 * O([MAX_SCAN_BATCHES] · limit) instead of O(whole log). Generous enough that a sparse-but-present page fills in
 * one request in real topologies; small enough that the pathological empty-page scan can't run the table.
 */
private const val MAX_SCAN_BATCHES = 50
