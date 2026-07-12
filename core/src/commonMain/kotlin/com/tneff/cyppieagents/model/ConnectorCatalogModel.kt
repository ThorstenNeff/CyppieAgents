package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-462 — the connector CATALOG: the selectable connectors and each one's DECLARED fidelity profile, so the
 * connector picker can preview "choosing MCP degrades these dimensions" BEFORE the choice (UIUX-confirmed). Read
 * at `GET /api/connectors`.
 *
 * **Static + single-sourced.** Each [ConnectorKind] has a compile-time declared [Capabilities] profile (the
 * connector impls' `*_CAPABILITIES` constants), surfaced verbatim through `ConnectorRouter.capabilitiesForKind`
 * — the SAME function the real spawn/opt-in path uses to populate an agent's caps, so the preview can never
 * drift from what a new agent actually gets. No agent instance / session is needed to answer this.
 *
 * **The trust boundary (design note, not encoded here).** The value is the DECLARED profile = the effective
 * profile for a LOCAL agent (the MVP default; `CapabilityCeiling` clamp is the identity for LOCAL). A
 * REMOTE/BYOA agent's effective caps are clamped further (`CapabilityCeiling.ceilingFor(REMOTE)`); remote lands
 * dark until the E2.2 wire, so the preview intentionally shows the local-declared profile. If the picker later
 * offers remote-create, extend the descriptor with the clamped variant — additive.
 */
@Serializable
data class ConnectorDescriptor(
    val kind: ConnectorKind,
    val capabilities: Capabilities,
)

/**
 * CYP-462 — response of `GET /api/connectors`: every selectable connector with its declared fidelity, plus the
 * first-class [default] the picker pre-selects. A wrapper (not a bare list) so a per-kind display label / an
 * `optInAudited` flag / a remote-clamped variant can be added later without breaking the client shape.
 */
@Serializable
data class ConnectorsView(
    val connectors: List<ConnectorDescriptor>,
    val default: ConnectorKind = ConnectorKind.STREAM_JSON,
)
