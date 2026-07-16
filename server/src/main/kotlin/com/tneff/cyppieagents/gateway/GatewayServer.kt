package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.contract.ContractGenerator
import com.tneff.cyppieagents.contract.RestContract
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** CYP-638 S6 — the gateway's ONLY logger. It must NEVER be handed a request URI/query (carries `?token=`) or any
 *  header/frame body — only structural facts (an exception's class name, a status). A token on any log path is the
 *  CYP-190 leak class the S6 sentinel tooth forbids. */
private val gwLog = LoggerFactory.getLogger("gateway")

/**
 * CYP-638 S0 — the **isolated Gateway process** scaffold (A2, Auftraggeber-ratified §8).
 *
 * A separate deployable process (precedent: the CYP-506 relay, `relay/RelayServer.kt`) that sits in front of the hub as
 * the same-origin front-door: the browser speaks plain HTTP(S)/WS to the gateway, and the gateway forwards to the hub —
 * so the machine/remote-operate surface is terminated here and never reachable from the browser. Isolating the (later,
 * S5/S6) cleartext operator↔hub termination into this dedicated, auditable process IS the ratified trust boundary.
 *
 * **S0 scope (this file):** the process + the **verbatim forward** to the hub + the **default-DENY allowlist seam**.
 * The allow decision is behind [GatewayAllowlist]; S0 ships the minimal stub (`/api/health` only) so the scaffold is
 * demonstrable (forward works) AND secure by construction (everything else 404s at the edge). Later stories fill the
 * seam: **S1** the REST allowlist single-sourced from `RestContract.REST_OPS`; **S2** the 8 WS sockets from
 * `ContractGenerator`; **S3** the Kratos self-service proxy; **S5/S6** `/ws/terminal` + the cleartext hardening.
 */

/**
 * The edge allow decision. **Default-DENY**: only an explicitly-allowed `(method, path)` is forwarded to the hub; every
 * other request is refused at the edge (404) BEFORE it reaches the hub — so the `/api/cp` prefix, `/ws/hub`, `/mcp/hub` etc. are
 * unreachable through the gateway even though the hub also gates them (defense-in-depth, contract §5). **S1** fills the
 * REST data-plane from `RestContract.REST_OPS` (see [fromRestContract]); **S2** adds the WS sockets from
 * `ContractGenerator` — both single-sourced so the edge surface can never drift from the hub's real routes.
 */
fun interface GatewayAllowlist {
    fun isAllowed(method: HttpMethod, path: String): Boolean

    companion object {
        /** CYP-638 §5 — the machine control-plane prefix in `/api`. These ops ARE in [RestContract.REST_OPS] (the hub
         *  serves them, OPERATOR + owner-gated) but they are the remote-operate surface the gateway MUST NOT expose to
         *  the browser, so they are excluded from the data-plane allowlist below. */
        const val CONTROL_PLANE_PREFIX = "/api/cp/"

        /**
         * S1 — the REST data-plane allowlist **single-sourced from [RestContract.REST_OPS]**: every op whose path is NOT
         * the [CONTROL_PLANE_PREFIX] control surface, matched by `(method, path-template)` and accepting BOTH the `/api`
         * and `/api/v1` dual-mount. **Derived at construction, never hand-copied** — a new data-plane op in `REST_OPS`
         * is auto-allowed and a new `/api/cp/` op is auto-denied, so the edge can't drift from the hub's real routes.
         * (WebSocket sockets are added in S2 from `ContractGenerator`.)
         */
        fun fromRestContract(ops: List<RestContract.Op> = RestContract.REST_OPS): GatewayAllowlist {
            val dataPlane = ops.filter { !it.path.startsWith(CONTROL_PLANE_PREFIX) }
            val matchers = dataPlane.map { it.method to it.path.split('/') }
            return GatewayAllowlist { method, path ->
                // fold the `/api/v1` dual-mount onto the canonical `/api` the REST_OPS templates are written in.
                val canonical = if (path.startsWith("/api/v1/")) "/api/" + path.removePrefix("/api/v1/") else path
                // ★ Resolve the path the HUB will actually route to: decode `%XX` (so `%2f`→`/`, `%63`→`c`) THEN
                //   normalize `.`/`..` dot-segments. Order is the whole fix (CYP-638 F5): `/api/agents/..%2fcp%2fchallenge`
                //   decodes to `/api/agents/../cp/challenge`, which normalizes to `/api/cp/challenge` — caught by the deny;
                //   without the normalize the raw form doesn't start with `/api/cp/` and slips through as `/api/agents/{id}`
                //   while the hub's own `..` normalization lands it on the control plane (a measured bypass).
                val resolved = normalizeDotSegments(decodePercent(canonical))
                // ★ CYP-638 §5 request-side control-plane deny (the SAME [CONTROL_PLANE_PREFIX] constant — never a second
                //   literal that could drift): refuse ANY path that resolves to `/api/cp/…`, so `%63p`-encoding AND
                //   `..`-traversal are both denied regardless of the current REST_OPS shape.
                if (resolved.startsWith(CONTROL_PLANE_PREFIX)) return@GatewayAllowlist false
                // Match on the SAME resolved path the deny used — the allow decision is over what the hub will route to.
                val segs = resolved.split('/')
                matchers.any { (m, tmpl) -> m.equals(method.value, ignoreCase = true) && segmentsMatch(tmpl, segs) }
            }
        }

        /** Decode `%XX` escapes only (never `+`→space, which is query-encoding not path-encoding) so the control-plane
         *  deny sees the true path — e.g. `/api/%63p/…` → `/api/cp/…`. Idempotent for un-escaped paths. */
        private fun decodePercent(s: String): String {
            if ('%' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) { sb.append(hex.toChar()); i += 3; continue }
                }
                sb.append(c); i++
            }
            return sb.toString()
        }

        /** Resolve `.`/`..` dot-segments in an absolute path (RFC 3986 §5.2.4 style): `.` drops, `..` pops the previous
         *  segment (never above root), and empty segments (`//`) collapse — so the gateway decides on the SAME path the
         *  hub will route to. e.g. `/api/agents/../cp/challenge` → `/api/cp/challenge`; `/api/a/../../cp` → `/cp`. */
        private fun normalizeDotSegments(p: String): String {
            if (!p.contains("..") && !p.contains("/.") && !p.contains("//")) return p // fast path: nothing to resolve
            val out = ArrayDeque<String>()
            for (seg in p.split('/')) when (seg) {
                "", "." -> {} // collapse `//` and drop `.`
                ".." -> if (out.isNotEmpty()) out.removeLast() // pop; never escape above root
                else -> out.addLast(seg)
            }
            return "/" + out.joinToString("/")
        }

        /** A concrete request path matches a `REST_OPS` template iff they have the same segment count and each template
         *  segment is either literally equal or a `{param}` placeholder filled by a non-empty concrete segment. */
        private fun segmentsMatch(template: List<String>, actual: List<String>): Boolean {
            if (template.size != actual.size) return false
            return template.indices.all { i ->
                val t = template[i]
                if (t.startsWith("{") && t.endsWith("}")) actual[i].isNotEmpty() else t == actual[i]
            }
        }
    }
}

/** RFC 7230 §6.1 hop-by-hop headers — owned by the transport, never blindly relayed across the proxy hop. */
private val HOP_BY_HOP: Set<String> = setOf(
    HttpHeaders.Connection, HttpHeaders.TransferEncoding, HttpHeaders.ContentLength, HttpHeaders.Upgrade,
    HttpHeaders.TE, HttpHeaders.Trailer, "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
).map { it.lowercase() }.toSet()

/**
 * The gateway Ktor module: a default-deny reverse-proxy to [hubBaseUrl]. Allowed requests are forwarded **verbatim**
 * (method + path + query + body + headers, minus hop-by-hop); everything else is `404` at the edge.
 */
fun Application.gatewayModule(
    hubBaseUrl: String,
    // CYP-638 S3 — the Kratos PUBLIC base (e.g. `http://127.0.0.1:4433`). The browser drives the FULL self-service
    // surface (login/registration/recovery/verification/settings/logout) same-origin under `/.ory/kratos/public/*`
    // (`HttpAuthRepository.kt:54`); the gateway proxies that prefix here so the httpOnly `ory_kratos_session` Set-Cookie
    // lands same-origin. Blank ⇒ the Kratos leg is not mounted (Kratos not configured).
    kratosBaseUrl: String = "",
    allowlist: GatewayAllowlist = GatewayAllowlist.fromRestContract(),
    client: HttpClient = HttpClient(CIO) { install(ClientWebSockets) },
) {
    val hubWsBase = wsBaseOf(hubBaseUrl)
    monitor.subscribe(ApplicationStopped) { client.close() }
    install(WebSockets)
    routing {
        // S2 — WS: a transparent proxy per **single-sourced** `ContractGenerator.WS_CHANNELS` (the 8 data sockets).
        // Only these paths get a WS handler; a denied WS-upgrade (e.g. `/ws/hub`, in `EXCLUDED_WS_PATHS`) has NO route
        // here → it falls through to the REST catch-all below → 404 at the edge. No hand-list — a channel added to
        // `WS_CHANNELS` is auto-proxied; `/ws/hub` stays excluded there, so it can't drift into the browser surface.
        ContractGenerator.WS_CHANNELS.forEach { channel ->
            webSocket(channel.path) { proxyWebSocketToHub(client, hubWsBase) }
        }
        // S3 — the Kratos self-service same-origin proxy: `/.ory/kratos/public/*` → `{kratosBaseUrl}/*` (the Kratos
        // PUBLIC API only — never the admin port). Preserves method + path-suffix + query + Cookie/CSRF + body, and
        // relays the response headers incl. `Set-Cookie` so the browser's `ory_kratos_session` is set same-origin.
        if (kratosBaseUrl.isNotBlank()) {
            route("$KRATOS_PUBLIC_PREFIX/{...}") {
                handle { forwardToUpstream(call, client, kratosBaseUrl, stripPrefix = KRATOS_PUBLIC_PREFIX) }
            }
        }
        route("{...}") { // catch-all: every non-WS method + path is decided by the REST allowlist (default-deny)
            handle {
                val method = call.request.httpMethod
                val path = call.request.path()
                if (!allowlist.isAllowed(method, path)) {
                    // ★ default-deny — the control surface (and any un-allowlisted WS-upgrade path) never reaches the hub.
                    call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                    return@handle
                }
                forwardToUpstream(call, client, hubBaseUrl)
            }
        }
    }
}

/** CYP-638 S3 — the same-origin path prefix the browser dials the Kratos PUBLIC API under (matches the client's
 *  `kratosBaseUrl = "$platformBaseUrl/.ory/kratos/public"`). Stripped before forwarding to the real Kratos base. */
private const val KRATOS_PUBLIC_PREFIX = "/.ory/kratos/public"

/** Derive the hub's `ws(s)://` base from its `http(s)://` base for the WS proxy leg. */
private fun wsBaseOf(httpBaseUrl: String): String = when {
    httpBaseUrl.startsWith("https://") -> "wss://" + httpBaseUrl.removePrefix("https://")
    httpBaseUrl.startsWith("http://") -> "ws://" + httpBaseUrl.removePrefix("http://")
    else -> httpBaseUrl
}.trimEnd('/')

/** WS-handshake headers the client's own upgrade regenerates — never relayed from the browser's request. The auth
 *  headers (`Cookie`, `Authorization`) are deliberately NOT here: the cookie MUST survive the upgrade (CYP-230/515). */
private val WS_HANDSHAKE_STRIP: Set<String> = setOf(
    "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-accept",
    "sec-websocket-protocol", HttpHeaders.Host,
).map { it.lowercase() }.toSet()

/**
 * Transparently proxy one accepted browser WS ([this]) to the hub over a client WS, until either side closes. The
 * browser's `Cookie`/auth + the query (`token/ticket/since/agentId/projectId`, verbatim in [ApplicationCall.request] uri)
 * ride the upgrade unmodified; frames relay both ways and the close reason (e.g. 1008 auth-revoked) propagates unmasked.
 */
private suspend fun DefaultWebSocketServerSession.proxyWebSocketToHub(client: HttpClient, hubWsBase: String) {
    val browser = this
    val browserCall = call
    val target = hubWsBase + browserCall.request.uri // path + query verbatim
    try {
        client.clientWebSocket(urlString = target, request = {
            browserCall.request.headers.forEach { name, values ->
                if (name.lowercase() !in WS_HANDSHAKE_STRIP && name.lowercase() !in HOP_BY_HOP) {
                    values.forEach { header(name, it) } // ★ Cookie/Authorization forwarded — NOT stripped on the WSS handshake
                }
            }
        }) {
            relayFrames(browser, this)
        }
    } catch (e: Exception) {
        // ★ CYP-638 S6 — same leak class as the REST leg: an unreachable hub WS must not let the token-bearing URL reach
        //   a log. Log only the exception class; close the (already-upgraded) browser socket cleanly.
        gwLog.warn("gateway: upstream WS failed [{}]", e::class.simpleName)
        runCatching { browser.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "upstream_unavailable")) }
    }
}

/** Bidirectional frame relay between two WS sessions; on either close, the peer's close reason is propagated to the
 *  other (unmasked — 1008 auth-revoked drives the client's offline banner). */
private suspend fun relayFrames(a: DefaultWebSocketSession, b: DefaultWebSocketSession) = coroutineScope {
    val normal = CloseReason(CloseReason.Codes.NORMAL, "")
    val aToB = launch {
        runCatching { for (frame in a.incoming) if (frame !is Frame.Close) b.send(frame) }
        runCatching { b.close(a.closeReason.await() ?: normal) }
    }
    val bToA = launch {
        runCatching { for (frame in b.incoming) if (frame !is Frame.Close) a.send(frame) }
        runCatching { a.close(b.closeReason.await() ?: normal) }
    }
    aToB.join()
    bToA.join()
}

/** Forward one allowed call to [upstreamBase] verbatim and relay the response back to the browser. [stripPrefix] is
 *  removed from the path before forwarding (S3 Kratos leg: `/.ory/kratos/public/X` → `{kratos}/X`); empty for the hub
 *  leg (path forwarded as-is). Response headers incl. `Set-Cookie` are relayed so a same-origin cookie is set. */
private suspend fun forwardToUpstream(call: ApplicationCall, client: HttpClient, upstreamBase: String, stripPrefix: String = "") {
    val uri = call.request.uri // path + query
    val forwardUri = if (stripPrefix.isNotEmpty()) uri.removePrefix(stripPrefix).ifEmpty { "/" } else uri
    val target = upstreamBase.trimEnd('/') + forwardUri
    val requestBody: ByteArray = runCatching { call.receive<ByteArray>() }.getOrDefault(ByteArray(0))
    val hubResp: HttpResponse = try {
        client.request(target) {
            method = call.request.httpMethod
            call.request.headers.forEach { name, values ->
                if (name.lowercase() !in HOP_BY_HOP && !name.equals(HttpHeaders.Host, ignoreCase = true)) {
                    values.forEach { header(name, it) }
                }
            }
            if (requestBody.isNotEmpty()) setBody(requestBody)
        }
    } catch (e: Exception) {
        // ★ CYP-638 S6 — the hub being unreachable is a NORMAL operational state (e.g. before/after a restart). Fail
        //   closed with a bounded 502 (not an unhandled 500), logging ONLY the exception's class name — never the request
        //   URI/query/headers/body. Defense-in-depth on top of the `%redactedMsg` output seam. (Verified: Ktor 3.5's own
        //   unhandled-exception log is method+PATH only, no query, so this does not itself prevent a `?token` log leak —
        //   its provable value is the fail-closed 502 and not letting any future/other exception path see the request line.)
        gwLog.warn("gateway: upstream request failed [{}] — 502", e::class.simpleName)
        call.respondBytes(ByteArray(0), status = HttpStatusCode.BadGateway)
        return
    }
    val respBody: ByteArray = hubResp.body()
    hubResp.headers.forEach { name, values -> // relay response headers (Set-Cookie etc.), minus hop-by-hop + content-type
        if (name.lowercase() !in HOP_BY_HOP && !name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
            values.forEach { call.response.header(name, it) }
        }
    }
    call.respondBytes(respBody, contentType = hubResp.contentType(), status = hubResp.status)
}

/**
 * Standalone entrypoint (run task `:server:gatewayRun`, or the `GatewayServerKt.main` class in a deploy unit). Binds the
 * browser-facing edge to `CYPPIE_GATEWAY_HOST`:`CYPPIE_GATEWAY_PORT` (defaults `0.0.0.0:8080`) and forwards to the hub at
 * `CYPPIE_HUB_URL` (default `http://127.0.0.1:8787`). TLS / public-exposure posture is deploy-owned (like the relay).
 */
fun main() {
    val port = System.getenv("CYPPIE_GATEWAY_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("CYPPIE_GATEWAY_HOST") ?: "0.0.0.0"
    val hubUrl = System.getenv("CYPPIE_HUB_URL") ?: "http://127.0.0.1:8787"
    // CYP-638 S3 — the Kratos PUBLIC base for the same-origin self-service proxy. Blank ⇒ the Kratos leg is unmounted.
    val kratosUrl = System.getenv("CYPPIE_KRATOS_URL") ?: ""
    embeddedServer(Netty, port = port, host = host) { gatewayModule(hubUrl, kratosUrl) }.start(wait = true)
}
