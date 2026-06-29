package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.model.ReachedAgent
import com.tneff.cyppieagents.model.ShareAccess
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Cross-project channel-share endpoints (S17 / CYP-93 — CROSS-PROJECT.md §2/§5/§6). The share is the
 * **owner authorization gate** for a channel to span the fail-closed project boundary; the permit lives
 * in [com.tneff.cyppieagents.model.AclMatrix] (consulting [HubState.sharedInboundChannelIds]).
 *
 * Routes (granularity = per channel, §6.1):
 *  - `GET    /api/channels/{id}/share` → [ChannelShareView] (disclosure: shared? + sharedAt + reachable
 *    agents). **Participant** — the badge/status is read-only disclosure, not a secret.
 *  - `PUT    /api/channels/{id}/share` `AuthorizeShareRequest{sharedWith}` → set the directed share.
 *  - `DELETE /api/channels/{id}/share` → revoke (the gate closes → immediate fail-closed).
 *
 * **Both mutations are operator/owner-gated and fail-closed** (`requireOperator` before `receive`,
 * Anti-Injection-Invariante §2.6 — only the human owner authorizes, never an agent/message). After a
 * set/revoke we call [HubState.refreshShares] so the permit takes effect without a restart; revoke is
 * fail-closed regardless of any lingering AclEntries (§6.4).
 */
fun Route.channelShareRoutes(state: HubState, shares: ChannelShareStore, tokens: TokenRegistry) {
    route("/api/channels/{id}/share") {
        get {
            call.requireParticipant(tokens)
            val id = call.parameters["id"] ?: throw BadRequestException("missing channel id")
            call.respond(shareView(state, shares, id))
        }
        put {
            call.requireOperator(tokens) // owner authorization act — gated BEFORE the body is parsed
            val id = call.parameters["id"] ?: throw BadRequestException("missing channel id")
            val channel = state.channels.firstOrNull { it.id == id }
                ?: throw NotFoundException("channel '$id' not found", code = "channel_not_found")
            val req = call.receive<AuthorizeShareRequest>()
            shares.share(id, ownerProjectId = channel.projectId, sharedWith = req.sharedWith)
            state.refreshShares() // permit takes effect now (no restart)
            call.respond(shareView(state, shares, id))
        }
        delete {
            call.requireOperator(tokens)
            val id = call.parameters["id"] ?: throw BadRequestException("missing channel id")
            shares.revoke(id)
            state.refreshShares() // gate closed → channel falls back to exact-match/fail-closed
            call.respond(shareView(state, shares, id))
        }
    }
}

/**
 * Compose the disclosure view (§2.4). `reachableScope` is computed from the channel's AclEntries whose
 * `projectId` is one of the grantee projects — the CONCRETE reached agents (+ home project + access),
 * never "project B" wholesale. Read-only default access (READ); WRITE only where an explicit entry
 * grants it. `sharedBy` is deferred S18 (single-user = the one operator) so it is absent.
 */
private fun shareView(state: HubState, shares: ChannelShareStore, channelId: String): ChannelShareView {
    val rec = shares.record(channelId) ?: return ChannelShareView(shared = false)
    val reached = state.entries
        .filter { it.channelId == channelId && it.projectId in rec.sharedWith }
        .map { ReachedAgent(it.agentId, it.projectId, if (it.canWrite) ShareAccess.WRITE else ShareAccess.READ) }
    return ChannelShareView(shared = true, sharedAt = rec.sharedAt, reachableScope = reached)
}
