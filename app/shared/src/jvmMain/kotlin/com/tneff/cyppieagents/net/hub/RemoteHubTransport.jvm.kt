package com.tneff.cyppieagents.net.hub

import io.ktor.client.HttpClient

/** CYP-411 — Phase-1 fail-loud actual (JVM/Desktop). Real Noise-E2E transport = Phase 2. */
actual class RemoteHubTransport actual constructor() : HubTransport {
    override val httpBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val wsBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val httpClient: HttpClient get() = remoteTransportNotYetAvailable()
    override val wsHttpClient: HttpClient get() = remoteTransportNotYetAvailable()
    override fun sessionToken(): String? = remoteTransportNotYetAvailable()
    override fun close() = Unit
}

/** M2 Seam-3 jvm (Path-A): the REAL tunnel-backed transport — loopback + [ClientLoopbackBridge] over the tunnel(s).
 *  CYP-537: [acquireTunnel] is the pooling `PooledTunnelSource.acquire` (a distinct tunnel per connection). CYP-556
 *  made the accept-loop **per-connection** (each connection's `acquire()`+`pump()` in its own coroutine), so N live WS
 *  are served concurrently over N tunnels — true N-tunnel concurrency (the F-M2-1 fix). A per-connection `acquire()`
 *  alone was NOT enough: before CYP-556 the *inline* pump serialized every WS behind the first (the workspace hang). */
actual fun buildRemoteHubTransport(
    acquireTunnel: suspend () -> com.tneff.cyppieagents.net.hub.noise.NoiseTunnel?,
    sessionToken: () -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
): HubTransport? = RemoteTunnelHubTransport(
    tunnelSource = { acquireTunnel() },
    sessionTokenProvider = sessionToken,
    scope = scope,
)
