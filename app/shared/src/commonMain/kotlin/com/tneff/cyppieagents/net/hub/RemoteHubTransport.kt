package com.tneff.cyppieagents.net.hub

/**
 * CYP-411 — thrown by the Phase-1 [RemoteHubTransport] actual on any use. **Fail-loud on purpose:** the
 * Remote-Modus (Noise-E2E via the Control Plane) is Phase 2, so the seam exists and compiles but must never
 * silently pretend to connect. S-M wires an honest "Remote — kommt bald" UI gate that checks the mode BEFORE
 * touching a remote transport; this exception is the safety net if anything reaches it anyway.
 */
class NotYetAvailableException(message: String) : IllegalStateException(message)

/** The single message + throw site, shared by every platform's [RemoteHubTransport] actual (kept DRY). */
internal const val REMOTE_TRANSPORT_UNAVAILABLE_REASON: String =
    "Remote-Modus (Noise-E2E über die Control Plane) ist noch nicht verfügbar — Phase 2 (Epic CYP-395)."

internal fun remoteTransportNotYetAvailable(): Nothing =
    throw NotYetAvailableException(REMOTE_TRANSPORT_UNAVAILABLE_REASON)

/**
 * CYP-411 — the Remote-Modus [HubTransport] seam. `expect`/`actual` from day 1 because the real Phase-2 transport
 * (Noise handshake + relayed frames) will need platform-specific crypto/networking. In Phase 1 **every** actual is
 * fail-loud ([remoteTransportNotYetAvailable]); [close] stays a safe no-op. Referenced by [TransportModeResolver]
 * so it is a real, compiled part of the mode surface, not a comment.
 */
expect class RemoteHubTransport() : HubTransport
