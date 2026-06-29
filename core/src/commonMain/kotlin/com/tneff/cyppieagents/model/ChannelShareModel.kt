package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * Cross-project channel authorization wire contract (S17 / CYP-93 — CROSS-PROJECT.md §2/§5/§6). The
 * DTOs live in `:core` so the server endpoints and the client cross-project UI compile against ONE
 * definition. A cross-project share is a deliberately punched, **owner-authorized** hole in the
 * fail-closed project boundary ([ProjectScope]); without an authorization the channel stays project-local.
 *
 * **No over-widen:** a share reaches only the channel's EXPLICIT member-agents (read-only by default;
 * write only via explicit ACL), never the whole foreign project — so [ReachedAgent] names concrete
 * agents + their home project, never "project B" wholesale.
 */

/** A new cross-project member's access on a shared channel (read-only default; write per explicit ACL). */
@Serializable
enum class ShareAccess { READ, WRITE }

/** One agent the share reaches: which agent, from which home project, with what access (disclosure §2.4). */
@Serializable
data class ReachedAgent(
    val agentId: String,
    val projectId: String,
    val access: ShareAccess,
)

/**
 * `GET /api/channels/{id}/share` — the cross-project disclosure (§2.4/§6.2). `shared=false` is the
 * explicit fail-closed default ("only in this project"). `reachableScope` is the honest, concrete reach;
 * **`sharedBy` (who) is deferred S18** (single-user = trivially the one operator), so it is absent here.
 */
@Serializable
data class ChannelShareView(
    val shared: Boolean,
    val sharedAt: Long? = null,
    val reachableScope: List<ReachedAgent> = emptyList(),
)

/**
 * `PUT /api/channels/{id}/share` — the directed (owner→grantee) authorization (§2.3). The owner shares
 * ITS channel out to the named grantee project(s); symmetric/bilateral consent is S18. Operator/owner-gated.
 */
@Serializable
data class AuthorizeShareRequest(
    val sharedWith: Set<String>,
)
