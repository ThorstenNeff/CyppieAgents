package com.tneff.cyppieagents.net.hub

import io.ktor.client.HttpClient

/** CYP-411 — Phase-1 fail-loud actual (iOS). Real Noise-E2E transport = Phase 2. */
actual class RemoteHubTransport actual constructor() : HubTransport {
    override val httpBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val wsBaseUrl: String get() = remoteTransportNotYetAvailable()
    override val httpClient: HttpClient get() = remoteTransportNotYetAvailable()
    override fun sessionToken(): String? = remoteTransportNotYetAvailable()
    override fun close() = Unit
}

/** M2 Seam-3: Path-A (Ktor-over-Noise loopback) is Desktop/JVM-only — no tunnel-backed transport here (② multiplatform-engine follow-on). */
actual fun buildRemoteHubTransport(
    currentTunnel: () -> com.tneff.cyppieagents.net.hub.noise.NoiseTunnel?,
    sessionToken: () -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
): HubTransport? = null
