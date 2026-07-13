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

/** M2 Seam-3 jvm (Path-A): the REAL tunnel-backed transport — loopback + [ClientLoopbackBridge] over the session tunnel. */
actual fun buildRemoteHubTransport(
    currentTunnel: () -> com.tneff.cyppieagents.net.hub.noise.NoiseTunnel?,
    sessionToken: () -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
): HubTransport? = RemoteTunnelHubTransport(
    tunnelSource = { currentTunnel() },
    sessionTokenProvider = sessionToken,
    scope = scope,
)
