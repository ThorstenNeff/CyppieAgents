package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.authenticatedApi
import com.tneff.cyppieagents.avatar.AvatarLimits
import com.tneff.cyppieagents.avatar.AvatarRejected
import com.tneff.cyppieagents.boot.AgentManagement
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ClaudeMdUpdate
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Agent-management CRUD endpoints (S14 / CYP-97 — AGENT-MANAGEMENT §2). The list `GET /api/agents`
 * stays in [commRoutes] (it already reads the now-mutable `HubState.agents`, so it reflects add/remove
 * — the dynamic window list, §9.5). This adds the edit-prefill detail + the operator-gated mutations.
 *
 * Gates: detail GET = **participant** (the management list is read-only visible without an operator
 * token); POST/PUT/DELETE = **operator, fail-closed** — gated STRUCTURALLY under the [authenticatedApi]
 * group (CYP-178), so the operator check runs **before** the body is received and cannot be forgotten by a
 * new endpoint added inside the group; a non-operator is rejected (401/403) without the request being parsed.
 */
// CYP-255 (.4b): the concrete overload (dev/test) delegates to the resolver form with a constant provider,
// so existing call-sites are unchanged; production passes { runtimeRegistry.active().agentManagement }.
fun Route.agentMgmtRoutes(mgmt: AgentManagement, registry: TokenRegistry, deps: AuthDeps = AuthDeps(registry), apiBase: String = "/api") =
    agentMgmtRoutes({ mgmt }, registry, deps, apiBase)

// CYP-255 (.4b): [mgmt] resolves the ACTIVE project's AgentManagement per request, so a CRUD op (add/edit/
// remove/avatar) lands in the switched-to project's runtime (own lifecycle/configs/worktrees) — a same-id
// agent in another project is untouched.
fun Route.agentMgmtRoutes(mgmt: () -> AgentManagement, registry: TokenRegistry, deps: AuthDeps = AuthDeps(registry), apiBase: String = "/api") {
    route("$apiBase/agents") {
        // Detail = participant (the management list is read-only visible without an operator token).
        get("/{id}") {
            call.requireParticipant(deps) // CYP-234b: deps carries the participant-token axis alongside the registry
            call.respond(mgmt().detail(call.parameters.getOrFail("id"))) // 404 agent_not_found
        }
        // CYP-310: read the agent's LIVE worktree CLAUDE.md — participant-gated, the SAME read posture as the
        // detail above. Read fresh each call (reflects external/agent self-edits); absent/unreadable → empty,
        // never the stored persona (fail-closed). 404 agent_not_found · 409 agent_not_local (remote/BYOA).
        get("/{id}/claude-md") {
            call.requireParticipant(deps)
            call.respond(mgmt().readClaudeMd(call.parameters.getOrFail("id")))
        }
        // CYP-215: serve the agent's avatar PNG = participant-gated (same read posture as the detail/list).
        // Upload → stored re-encoded blob; Preset → self-hosted DiceBear bytes; none/unknown → 404 (the client
        // falls back to its default). ETag = the content-hash ref (cache-busting on re-upload).
        get("/{id}/avatar") {
            // CYP-232: read-tier gate (token OR verified human session) so the tokenless public SPA renders the
            // real avatar via its same-origin Kratos cookie — token-only requireParticipant → 401 → fallback icon.
            call.requireCommReader(deps, registry)
            val served = mgmt().serveAvatar(call.parameters.getOrFail("id"))
                ?: return@get call.respond(HttpStatusCode.NotFound)
            served.ref?.let { call.response.header(HttpHeaders.ETag, "\"$it\"") }
            call.respondBytes(served.png, ContentType.Image.PNG)
        }
        // CYP-219: DiceBear preset PREVIEW = participant-gated, SAME read posture as the serve. Resolves an
        // arbitrary (style, seed) to a self-hosted bundled PNG (egress-free — the client NEVER calls
        // api.dicebear.com; this same-origin route is the only source). Unknown/unsupported style OR no bundled
        // asset → 404 → the grid degrades to a placeholder. Deterministic → ETag + long cache for the grid.
        get("/{id}/avatar/preview") {
            // CYP-232: read-tier gate (token OR verified human session) — the preset preview grid must render for
            // the tokenless SPA (same read posture as the serve). token-only → 401 → placeholder-only grid.
            call.requireCommReader(deps, registry)
            val style = call.request.queryParameters["style"].orEmpty()
            val seed = call.request.queryParameters["seed"].orEmpty()
            val served = mgmt().previewAvatar(style, seed)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            served.ref?.let { call.response.header(HttpHeaders.ETag, "\"$it\"") }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(served.png, ContentType.Image.PNG)
        }
        // CYP-178: the mutations are gated STRUCTURALLY under the group — fail-closed BEFORE the body is
        // received (a non-operator is 401/403, unparsed). The RC1 route-enumeration meta-test is the net.
        authenticatedApi(deps, AuthRole.OPERATOR) {
            post {
                val spec = call.receive<NewAgentSpec>()
                call.respond(HttpStatusCode.Created, mgmt().add(spec)) // 400/409 per the guard
            }
            put("/{id}") {
                val edit = call.receive<AgentEdit>()
                call.respond(mgmt().edit(call.parameters.getOrFail("id"), edit)) // 404/409 per the guard
            }
            delete("/{id}") {
                // Default fate = KEEP (safe); only an explicit ?worktree=delete is the destructive path.
                val fate = if (call.request.queryParameters["worktree"].equals("delete", ignoreCase = true)) {
                    WorktreeFate.DELETE
                } else {
                    WorktreeFate.KEEP
                }
                mgmt().remove(call.parameters.getOrFail("id"), fate) // 404/409 last_po per the guard
                call.respond(HttpStatusCode.NoContent)
            }
            // CYP-310: HARD overwrite the worktree CLAUDE.md — operator-gated (structural). Bounded (256 KB →
            // 413 too_large, fail-closed BEFORE the write). Optimistic concurrency: expectedVersion vs the live
            // file hash — mismatch → 409 claude_md_stale, NO write (an unseen external/agent edit is preserved).
            // EFFECT_DEFERRED: the file changes now, but a running session already read it → next spawn.
            post("/{id}/claude-md") {
                val body = call.receive<ClaudeMdUpdate>()
                if (body.content.encodeToByteArray().size > CLAUDE_MD_MAX_BYTES) {
                    throw PayloadTooLargeException("CLAUDE.md exceeds the ${CLAUDE_MD_MAX_BYTES / 1024} KB cap", code = "too_large")
                }
                call.respond(mgmt().writeClaudeMd(call.parameters.getOrFail("id"), body.content, body.expectedVersion))
            }
            // CYP-215: custom-avatar upload — operator-gated (structural), UNTRUSTED bytes. Read the file part
            // with a HARD ceiling (a lying Content-Length can't blow past it), then hand it to the authoritative
            // validation pipeline. A reject → UNIFORM 400 (no parser-mapping leak) while the SERVER logs the
            // exact check that fired (diagnostics). Success logs bytes-in/out + dims; responds the updated agent.
            post("/{id}/avatar") {
                val id = call.parameters.getOrFail("id")
                val bytes = call.readFirstFilePartBounded(AvatarLimits.MAX_BYTES)
                    ?: throw BadRequestException("no file part in the multipart body", code = "avatar_no_file")
                try {
                    val r = mgmt().uploadAvatar(id, bytes)
                    avatarLog.info(
                        "avatar upload OK agent={} ref={} in={}B out={}B src={} {}x{} -> {}x{}",
                        r.agentId, r.ref, r.bytesIn, r.bytesOut, r.srcFormat, r.srcWidth, r.srcHeight, r.outWidth, r.outHeight,
                    )
                    call.respond(mgmt().detail(id))
                } catch (rej: AvatarRejected) {
                    avatarLog.warn("avatar upload REJECTED agent={} check={} — {}", id, rej.check, rej.message)
                    throw BadRequestException("avatar rejected", code = "avatar_rejected") // uniform to the client
                }
            }
            // CYP-215: clear the avatar back to the default (operator-gated) — drops metadata + blob.
            delete("/{id}/avatar") {
                mgmt().clearAvatar(call.parameters.getOrFail("id")) // 404 agent_not_found
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private val avatarLog = LoggerFactory.getLogger("routing.avatar")

/** CYP-310 — the bounded size cap for a `POST .../claude-md` body (fail-closed, per the backend role). */
private const val CLAUDE_MD_MAX_BYTES = 256 * 1024

/**
 * Read the FIRST multipart file part into memory with a HARD byte ceiling: at most [maxBytes]+1 bytes are
 * materialised (so an oversized upload is caught as > cap by the pipeline without ever buffering the whole
 * thing). Returns null if the body has no file part. Every part is disposed.
 */
private suspend fun ApplicationCall.readFirstFilePartBounded(maxBytes: Int): ByteArray? {
    var bytes: ByteArray? = null
    receiveMultipart().forEachPart { part ->
        try {
            if (part is PartData.FileItem && bytes == null) {
                // Bounded: read AT MOST maxBytes+1 (an oversized upload is then caught as > cap by the pipeline
                // without ever buffering the whole thing). Blocking javaio bridge → off the event loop.
                bytes = withContext(Dispatchers.IO) { part.provider().toInputStream().readNBytes(maxBytes + 1) }
            }
        } finally {
            part.dispose()
        }
    }
    return bytes
}

private fun io.ktor.http.Parameters.getOrFail(name: String): String =
    this[name] ?: throw BadRequestException("missing path parameter '$name'")
