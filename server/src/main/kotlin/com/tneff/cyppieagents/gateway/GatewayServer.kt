package com.tneff.cyppieagents.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
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
 * unreachable through the gateway even though the hub also gates them (defense-in-depth, contract §5). S0 ships a
 * minimal stub; S1 (REST ← `RestContract.REST_OPS`) and S2 (WS ← `ContractGenerator`) replace the impl with a
 * single-sourced allowlist so the edge surface can never drift from the hub's real routes.
 */
fun interface GatewayAllowlist {
    fun isAllowed(method: HttpMethod, path: String): Boolean

    companion object {
        /** S0 stub — the one PUBLIC, no-auth route (`/api/health`, both mount prefixes), enough to prove forward +
         *  default-deny end-to-end. Superseded by S1/S2's single-sourced allowlist. */
        val S0_HEALTH_ONLY = GatewayAllowlist { method, path ->
            method == HttpMethod.Get && (path == "/api/health" || path == "/api/v1/health")
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
    allowlist: GatewayAllowlist = GatewayAllowlist.S0_HEALTH_ONLY,
    client: HttpClient = HttpClient(CIO),
) {
    monitor.subscribe(ApplicationStopped) { client.close() }
    routing {
        route("{...}") { // catch-all: every method + path is decided by the allowlist (default-deny)
            handle {
                val method = call.request.httpMethod
                val path = call.request.path()
                if (!allowlist.isAllowed(method, path)) {
                    // ★ default-deny — the control surface never reaches the hub.
                    call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
                    return@handle
                }
                forwardToHub(call, client, hubBaseUrl)
            }
        }
    }
}

/** Forward one allowed call to the hub verbatim and relay the hub's response back to the browser. */
private suspend fun forwardToHub(call: ApplicationCall, client: HttpClient, hubBaseUrl: String) {
    val target = hubBaseUrl.trimEnd('/') + call.request.uri // path + query, verbatim
    val requestBody: ByteArray = runCatching { call.receive<ByteArray>() }.getOrDefault(ByteArray(0))
    val hubResp: HttpResponse = client.request(target) {
        method = call.request.httpMethod
        call.request.headers.forEach { name, values ->
            if (name.lowercase() !in HOP_BY_HOP && !name.equals(HttpHeaders.Host, ignoreCase = true)) {
                values.forEach { header(name, it) }
            }
        }
        if (requestBody.isNotEmpty()) setBody(requestBody)
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
    embeddedServer(Netty, port = port, host = host) { gatewayModule(hubUrl) }.start(wait = true)
}
