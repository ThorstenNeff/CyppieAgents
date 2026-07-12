package com.tneff.cyppieagents.net.hub.operator

/**
 * CYP-443 Slice 2 (Epic CYP-427 Phase-2) — the operator **Proof-of-Possession** (RR2-B). A discriminated union:
 * two branches, **OS-selected, server-authoritative (no client downgrade)**, both channel-bound to the LIVE Noise
 * `h` via [operatorAuthChallenge]. The CP forges the identity assertion but **never** this — the operator holds the
 * key (anti-CP-seizure holds fully). Transport-independent: this builds the PoP; sending it over the tunnel + the
 * hub-grant round-trip is the transport-dependent wiring that waits on the RR5 decision.
 */
sealed interface DevicePoP {
    /**
     * Cross-platform base (incl. Linux): a raw EdDSA signature by the **software device-key** over the challenge.
     * Anti-CP-seizure FULL; anti-local-malware weaker than hardware-UV (the app-PIN is keyloggable) — the conscious
     * no-hardware trade-off (Auftraggeber). Verifier = plain Ed25519 verify over the challenge.
     */
    data class Raw(val signature: ByteArray) : DevicePoP

    /**
     * Progressive enhancement where the OS has a platform authenticator (macOS Touch-ID / Windows Hello via
     * libfido2/FFM). CTAP2 assertion; verifier = raw-CTAP (sig over `authenticatorData ‖ SHA-256(challenge)`, UV
     * flag set, signCount lenient). Not runtime-reachable on Linux (no platform authenticator).
     */
    data class Fido2(
        val credentialId: ByteArray,
        val authenticatorData: ByteArray,
        val signature: ByteArray,
    ) : DevicePoP
}

// CYP-473 H2: `operatorAuthChallenge` + `OPERATOR_AUTH_PURPOSE` moved to the SINGLE `:core`
// `com.tneff.cyppieagents.operator` source — shared byte-identically by client (PoP build) and server (verify),
// so a field-reorder/delimiter change can no longer drift the two hand-mirrored copies. Import it from `:core`.
