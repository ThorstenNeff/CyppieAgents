package com.tneff.cyppieagents.transport

/**
 * CYP-458 (S2) — the **outbound-only reverse-tunnel** connector seam (RR5: the hub DIALS the relay; there is **no
 * external listener** for the tunnel). Impl (dial the relay WS → run the [ServerNoiseTerminator] → present L2 to the
 * existing Ktor routes via a `127.0.0.1`-only loopback bridge → funnel into the CYP-410 `SessionManager`) is the
 * gated build; this is the compiling seam. Carries T1/T2/T3 (see `docs/design/CYP-457-server-noise-transport.md`).
 */
interface RelayConnector {
    /** Dial the relay (outbound-only) and serve tunnelled connections. INERT until Phase-2-Remote-GO. */
    suspend fun start()
    suspend fun stop()
}

/**
 * CYP-459 (S3) — opt-in remote-transport config. **Off by default** → the hub is local-only (no relay dial), INERT;
 * parity with the `CYPPIE_MASTER_KEY`-gated S-C wiring / the resource governor. Only when [enabled] (Phase-2-Remote-GO
 * configured) does boot dial the relay.
 */
data class RemoteTransportConfig(
    val enabled: Boolean = false,
    /** The relay rendezvous URL (opaque, RR4). Ignored when [enabled] is false. */
    val relayUrl: String? = null,
)

/**
 * INERT default (the opt-in boot **stub**): no outbound dial. The local hub has no remote transport until a Phase-2
 * remote-GO wires a live [RelayConnector]. Boot uses this whenever [RemoteTransportConfig.enabled] is false — i.e.
 * always, today — so the current server behaviour is unchanged.
 */
object InertRelayConnector : RelayConnector {
    override suspend fun start() { /* Phase-2 (RR5-gated): dial the relay, terminate Noise, bridge L2 to the routes */ }
    override suspend fun stop() {}
}
