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

/**
 * M2 Seam-3 — builds the CONNECTED remote workspace's **tunnel-backed** [HubTransport]. jvm returns the real
 * `RemoteTunnelHubTransport` (loopback + `ClientLoopbackBridge` over the tunnel, CYP-457 Path-A); non-desktop
 * targets return `null` (Path-A is Desktop-only; the multiplatform engine is the ② follow-on).
 *
 * [acquireTunnel] supplies the [com.tneff.cyppieagents.net.hub.noise.NoiseTunnel] that carries the **next** accepted
 * loopback connection (the transport's `TunnelSource`). CYP-537 (M2 Option A): it is a **pooling** source —
 * `PooledTunnelSource.acquire()` establishes a **distinct** authenticated tunnel per concurrent connection (up to
 * `TUNNEL_POOL_CAP`), fixing F-M2-1. `null` ⇒ the transport RSTs that connection (fail-closed, C2). (Single-flight
 * fallback: a `suspend { session.tunnel }` still works for the 1-connection thru-cut.) It is `suspend` because
 * establishing a fresh tunnel dials + handshakes + PoP-authenticates. [sessionToken] MUST supply the operator's
 * **CP-scoped hub ticket** (identity-bound), NEVER a static MachineOperator token — the bridged request carries it
 * as the operator's authority to the hub (Reviewer Axis-1, G1).
 */
expect fun buildRemoteHubTransport(
    acquireTunnel: suspend (com.tneff.cyppieagents.net.hub.pool.TunnelLane) -> com.tneff.cyppieagents.net.hub.noise.NoiseTunnel?,
    sessionToken: () -> String?,
    scope: kotlinx.coroutines.CoroutineScope,
): HubTransport?
