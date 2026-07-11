package com.tneff.cyppieagents.net.hub

import io.ktor.client.HttpClient

/** CYP-411 — the two hub connection modes. Phase 1 ships only [LOCAL]; [REMOTE] is the fail-loud seam. */
enum class HubTransportMode { LOCAL, REMOTE }

/**
 * CYP-411 — picks the [HubTransport] for a mode, following the `AuthFlip` precedent (a small resolver over a
 * flip, not scattered `if`s). Phase 1 [defaultMode] is always [HubTransportMode.LOCAL]; S-J will drive the mode
 * from the hub-picker's choice, and S-M gates [HubTransportMode.REMOTE] with an honest "coming soon" screen
 * BEFORE it ever reaches [create]. Tests bypass this entirely by injecting a `HubTransport` into `AgentShell`.
 */
object TransportModeResolver {

    /** Phase 1 default — Local mode. (S-J replaces this with the user's hub-picker mode selection.) */
    fun defaultMode(): HubTransportMode = HubTransportMode.LOCAL

    /**
     * Build the transport for [mode]. [endpoint] + [sessionToken] feed [LocalHubTransport]; [injectedClient] lets
     * a caller/test supply its own Ktor client (else Local builds+owns the shared one). [HubTransportMode.REMOTE]
     * yields the fail-loud [RemoteHubTransport] (Phase 2).
     */
    fun create(
        mode: HubTransportMode,
        endpoint: HubEndpoint,
        sessionToken: () -> String? = { null },
        injectedClient: HttpClient? = null,
    ): HubTransport = when (mode) {
        HubTransportMode.LOCAL -> LocalHubTransport(endpoint, sessionToken, injectedClient)
        HubTransportMode.REMOTE -> RemoteHubTransport()
    }
}
