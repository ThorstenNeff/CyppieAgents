package com.tneff.cyppieagents.net.hub

import com.tneff.cyppieagents.net.sharedWsHttpClient
import io.ktor.client.HttpClient

/**
 * CYP-411 — the Phase-1 (Lokal-Modus) [HubTransport]: today's direct-connect, with the endpoint chosen at
 * **runtime** (from a [HubEndpoint]) instead of hardcoded. Behaviour-identical to the pre-seam shell:
 *
 *  - [httpBaseUrl]/[wsBaseUrl] are the endpoint's URLs verbatim (so every repo/live-source below the seam is
 *    unchanged);
 *  - [httpClient] is the same [sharedWsHttpClient] built from the same [sessionTokenProvider] (so the
 *    `X-Session-Token` DefaultRequest + CYP-115 keep-alive + same-origin credential seam are all preserved).
 *
 * The client is **owned** (built here, closed by [close]) unless one is injected — tests inject their own and the
 * transport must not close it. The per-agent / operator bearer tokens are NOT this transport's concern; they stay
 * threaded from `ShellConfig` at the repo/source call sites, unchanged in Phase 1.
 */
class LocalHubTransport(
    endpoint: HubEndpoint,
    private val sessionTokenProvider: () -> String? = { null },
    injectedClient: HttpClient? = null,
) : HubTransport {
    override val httpBaseUrl: String = endpoint.httpBaseUrl
    override val wsBaseUrl: String = endpoint.wsBaseUrl

    private val ownsClient: Boolean = injectedClient == null
    override val httpClient: HttpClient = injectedClient ?: sharedWsHttpClient(sessionTokenProvider)

    override fun sessionToken(): String? = sessionTokenProvider()

    override fun close() {
        // Only tear down a client we created — an injected (test-owned) client is the caller's to close.
        if (ownsClient) httpClient.close()
    }
}
