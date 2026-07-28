package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.CreateChannelRequest

/**
 * CYP-883 (OS-C, Compose mirror of web-ts CYP-875 `channelMgmtModel.ts`) — the pure honesty model for channel
 * management. The mutations are operator-tier and **server-authoritative (render ≠ authority)**: these helpers are
 * client-side HINTS (disable an affordance early), but the SERVER is the boundary — a HUB archive returns 409, a
 * non-operator returns 403 ([CommHttpException]), and the UI surfaces those verbatim, never hiding them behind a
 * pre-guess. Pure — no HTTP here (that is the injected [ChannelMgmtApi] seam).
 */

/** The kinds a user may CREATE. HUB is the protected hub-and-spoke kind — never user-creatable (and not archivable). */
val CREATABLE_KINDS: List<ChannelKind> = listOf(ChannelKind.DIRECT, ChannelKind.GROUP)

/**
 * A HUB channel is protected (hub-and-spoke) → NOT archivable. Client-side HINT to disable the affordance; the server
 * is authoritative (a HUB archive attempt returns 409, surfaced honestly). Fail-closed by construction: only a
 * non-HUB channel is archivable.
 */
fun isArchivable(channel: Channel): Boolean = channel.kind != ChannelKind.HUB

/**
 * Membership IS the ACL (OS-B): a granted member reads AND writes. The create UI grants full read+write per selected
 * agent; finer per-member ACL tuning stays in the AclPanel.
 */
fun memberGrant(agentId: String): ChannelMemberGrant = ChannelMemberGrant(agentId, canRead = true, canWrite = true)

/**
 * Whether a create request is well-formed enough to SUBMIT (fail-closed: the submit affordance stays disabled until
 * valid): non-blank id + name, a CREATABLE kind (DIRECT/GROUP — never HUB), and at least one member grant. The server
 * still validates authoritatively; this only gates the client affordance.
 */
fun isCreateValid(req: CreateChannelRequest): Boolean =
    req.kind in CREATABLE_KINDS && req.id.isNotBlank() && req.name.isNotBlank() && req.members.isNotEmpty()

/**
 * The HONEST, server-authoritative reason for a failed mutation — mapped from the server's status, never hidden or
 * softened (render ≠ authority). A pure enum (not a localized string) so it stays testable + the panel maps it to a
 * `stringResource` (i18n, CYP-386): 409 = a protected HUB channel, 403 = operator-only, anything else = generic.
 */
enum class ChannelMutationReason { PROTECTED_HUB, OPERATOR_ONLY, GENERIC }

/**
 * Map a mutation failure to its honest [ChannelMutationReason] from the server status ([CommHttpException]). 409 ⇒
 * PROTECTED_HUB (hub-and-spoke can't be archived), 403 ⇒ OPERATOR_ONLY, everything else ⇒ GENERIC. The caller SHOWS
 * this (and rolls back its optimistic change) — never a silent success.
 */
fun channelMutationReason(e: Throwable): ChannelMutationReason =
    when ((e as? CommHttpException)?.status) {
        409 -> ChannelMutationReason.PROTECTED_HUB
        403 -> ChannelMutationReason.OPERATOR_ONLY
        else -> ChannelMutationReason.GENERIC
    }
