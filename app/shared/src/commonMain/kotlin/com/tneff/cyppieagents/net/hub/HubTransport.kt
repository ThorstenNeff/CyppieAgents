package com.tneff.cyppieagents.net.hub

import io.ktor.client.HttpClient

/**
 * CYP-411 (Epic CYP-395, Doc 15 §6 slice S-H) — the ONE line between mode-aware and mode-blind client code.
 *
 * Every WS live-source and REST repository consumes only these members — the same `(httpClient, baseUrl)` shape
 * they take today — so they are **identical in Local and Remote mode** ("Geschäftslogik einmal, zwei Transporte",
 * concept `13-cyppie-hub-architektur.md`). The transport *produces* what sits below it; nothing below the seam
 * knows which mode is active.
 *
 * Phase 1 ships [LocalHubTransport] (direct HTTP/WS on the hub's default port). [RemoteHubTransport] is the
 * `expect` seam whose Phase-1 actual is fail-loud ([NotYetAvailableException]) — the Noise-E2E relay is Phase 2.
 * Selection is via [TransportModeResolver]; tests inject a `HubTransport` directly (the stub path).
 */
interface HubTransport {
    /** REST base, e.g. `http(s)://host:port` — exactly what repos pass as their `baseUrl` today. */
    val httpBaseUrl: String

    /** WS base, e.g. `ws(s)://host:port` — exactly what live-sources pass as their `wsBaseUrl` today. */
    val wsBaseUrl: String

    /**
     * The Ktor client for **REST** repos, carrying the `X-Session-Token` DefaultRequest seam. CYP-610: on the remote
     * tunnel transport this is connection-capped ([com.tneff.cyppieagents.net.hub.pool.REST_DEDICATED_CONNS]) so the
     * dozen+ REST repos share ONE loopback socket = ≤1 Noise tunnel; Local/stub modes leave it a plain shared client.
     */
    val httpClient: HttpClient

    /**
     * The Ktor client for **WebSocket** live-sources, carrying the same `X-Session-Token` seam. CYP-610: distinct from
     * [httpClient] so each long-lived WS opens its OWN loopback connection = its own Noise tunnel (one socket per tunnel,
     * no mux), unthrottled by the REST connection cap. Local/stub modes alias this to [httpClient] (no split needed —
     * only the remote tunnel pool is slot-constrained).
     */
    val wsHttpClient: HttpClient

    /**
     * The session identity presented to the hub (native `X-Session-Token`). Phase 1 = the Kratos session token
     * (via the client's `DefaultRequest`); S-K (ratified R1=A) evolves this to the CP-issued, hub-scoped ticket
     * JWT — client presents, hub verifies. The per-agent / operator bearer tokens are a SEPARATE authz concern
     * threaded from `ShellConfig`, not this method (they stay unchanged in Phase 1).
     */
    fun sessionToken(): String?

    /** Release transport-owned resources (an owned [httpClient]). Called once on shell dispose; always safe. */
    fun close()
}
