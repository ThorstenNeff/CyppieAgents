package com.tneff.cyppieagents.net.hub

import io.ktor.client.HttpClient

/** CYP-411 — Phase-1 fail-loud actual (JVM/Desktop). Real Noise-E2E transport = Phase 2. */
actual class RemoteHubTransport actual constructor() : HubTransport {
    override val httpBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val wsBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val httpClient: HttpClient get() = remoteTransportNotYetAvailable()
    override fun sessionToken(): String? = remoteTransportNotYetAvailable()
    override fun close() = Unit
}

/** M2 Seam-3 jvm (Path-A): the REAL tunnel-backed transport — loopback + [ClientLoopbackBridge] over the tunnel(s).
 *  CYP-537: [acquireTunnel] is the pooling `PooledTunnelSource.acquire` (a distinct tunnel per connection), so the
 *  accept-loop's per-connection `tunnelSource.acquire()` gives true N-tunnel concurrency (F-M2-1 fix). */
actual fun buildRemoteHubTransport(
    acquireTunnel: suspend () -> com.tneff.cyppieagents.net.hub.noise.NoiseTunnel?,
    sessionToken: () -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
): HubTransport? = RemoteTunnelHubTransport(
    tunnelSource = { acquireTunnel() },
    sessionTokenProvider = sessionToken,
    scope = scope,
)
