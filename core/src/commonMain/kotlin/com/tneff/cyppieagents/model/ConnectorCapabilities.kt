package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Connector capability vocabulary (Decision Record 10 — "Connector-Vertrag & Capabilities").
 *
 * The Mediator speaks every agent through ONE uniform connector contract, but concrete connectors
 * differ in **fidelity**: what signals they can actually produce. Doc 10 §3 makes the capability
 * declaration a **mandatory part of the contract** — every connector MUST declare, per dimension,
 * whether the underlying transport gives the Mediator what a dependent function needs. The Mediator
 * then enables a function only when its capability is [CapabilityStatus.AVAILABLE], runs it degraded
 * (marked) on [CapabilityStatus.LIMITED], and switches it off (logged) on [CapabilityStatus.UNAVAILABLE].
 *
 * These DTOs live in `:core` so the same shape compiles into `:server` (the connectors that declare
 * them) and `:app:shared` (the UI that surfaces per-agent degradation, Doc 10 §6.4) — no wire drift,
 * exactly like [Message]/[Event].
 */

/** Per-capability fidelity, Doc 10 §3: verfügbar / eingeschränkt / nicht verfügbar. */
@Serializable
enum class CapabilityStatus {
    @SerialName("available") AVAILABLE,
    @SerialName("limited") LIMITED,
    @SerialName("unavailable") UNAVAILABLE,
}

/** Which concrete connector produced a session (Doc 10 §1) — surfaced to UI + events. */
@Serializable
enum class ConnectorKind {
    /** Connector A — Claude Code via stream-json (API/tokens); first-class default, full fidelity. */
    @SerialName("stream_json") STREAM_JSON,
    /** Connector B — Claude Code via MCP (subscription, interactive); opt-in, declared lower fidelity. */
    @SerialName("mcp") MCP,
}

/**
 * The five fidelity dimensions every connector MUST declare (Doc 10 §3). Each maps to a Mediator
 * function it feeds:
 *  - [structuredUsage]  — per-turn token usage → token thresholds & compact orchestration (06 §5)
 *  - [toolGranularity]  — `tool.call`/`tool.result` events → event-log depth (06)
 *  - [reliableResult]   — clean turn/result end → "agent done" detection, mediation handover
 *  - [rateLimitSignal]  — structured rate-limit (vs. text) → Warden stall detection (07)
 *  - [coordination]     — how the agent talks to the hub → mediation/coordination (05 §2)
 *
 * [kind] tags the concrete connector behind the declaration (UI + events, Doc 10 §1/§6.4).
 */
@Serializable
data class Capabilities(
    val structuredUsage: CapabilityStatus,
    val toolGranularity: CapabilityStatus,
    val reliableResult: CapabilityStatus,
    val rateLimitSignal: CapabilityStatus,
    val coordination: CapabilityStatus,
    val kind: ConnectorKind,
)
