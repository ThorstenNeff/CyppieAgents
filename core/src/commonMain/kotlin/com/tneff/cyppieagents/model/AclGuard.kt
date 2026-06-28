package com.tneff.cyppieagents.model

/**
 * Server-side guardrail for ACL changes (Slice S7 / CYP-49). The PO is the **hub** of the
 * hub-and-spoke topology (Spec 02 §6.2): if it loses read OR write on a channel it is the hub of,
 * the whole coordination link breaks. The Hub (the source of truth) therefore rejects such a change
 * **fail-closed**; the UI guardrail (CYP-48) is only advisory.
 *
 * Critical design point (Reviewer): the decision is computed over the **recomputed resulting ACL
 * state** ([AclMatrix]), never over the request payload. That is what makes it robust to *all*
 * lockout vectors at once — `canWrite=false`, `canRead=false`, **and** the PO being dropped from a
 * channel's `members` — because [AclMatrix.canRead]/[AclMatrix.canWrite] already fold membership
 * (and deny-on-conflict) into a single fail-closed answer. A payload check that only looked at the
 * entry's `canWrite` flag would be bypassable via the `canRead`/`members` vectors.
 *
 * Pure function over an immutable snapshot — like [AclMatrix] — so the exact logic the server
 * enforces is testable platform-neutrally in `:core` and cannot drift from enforcement.
 */
object AclGuard {
    /**
     * In the resulting [candidate] ACL state, the first channel the PO ([poAgentId]) is the hub of
     * ([poHubChannelIds]) on which it would **lack** read or write — or `null` if the PO retains
     * full read+write on every channel it hubs. A non-null result means the change is a lockout and
     * must be rejected.
     */
    fun lockedOutPoHubChannel(
        candidate: AclMatrix,
        poAgentId: String,
        poHubChannelIds: Set<String>,
    ): String? =
        poHubChannelIds.firstOrNull { ch ->
            !candidate.canRead(ch, poAgentId) || !candidate.canWrite(ch, poAgentId)
        }
}
