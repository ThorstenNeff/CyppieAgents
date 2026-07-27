package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CYP-840 (decompose of CYP-612) — the muxed workspace-status frame for `/ws/status`, shared via `:core`.
 * Decoded through [com.tneff.cyppieagents.CommJson] (classDiscriminator = "type").
 *
 * ONE sealed union that **WRAPS the 4 content-free per-agent status feeds verbatim** — lifecycle
 * ([AgentRunStateEvent]), token-usage ([AgentTokenUsageEvent]), busy ([AgentBusyStateEvent]) and
 * terminal-control ([AgentTerminalControlEvent]) — so the client opens ONE socket instead of four. This is a
 * pure routing envelope: the 4 payloads are UNCHANGED (zero reshape) and each variant carries its existing
 * frame verbatim as [LifecycleStatus.event] / … . Each substream keeps its own snapshot-then-deltas semantics
 * server-side; the client upserts by `(type, agentId)`, so the mux carries no cursor (the 4 feeds are cursor-free).
 *
 * **CONTENT-FREE-ONLY invariant (PL-ratified, CYP-840):** only the 4 content-free status feeds live here. The
 * egress-/auth-gated sockets — `/ws/comm`, `/ws/events` (operator egress), `/ws/agent` (cookie auth), and
 * `/ws/terminal` (PTY) — stay SEPARATE; folding any of them into this envelope requires re-ratification.
 * Additive-parallel: the 4 individual sockets remain until the client consumer cuts over.
 */
@Serializable
sealed interface StatusFrame

@Serializable
@SerialName("lifecycle")
data class LifecycleStatus(val event: AgentRunStateEvent) : StatusFrame

@Serializable
@SerialName("tokenUsage")
data class TokenUsageStatus(val event: AgentTokenUsageEvent) : StatusFrame

@Serializable
@SerialName("busy")
data class BusyStatus(val event: AgentBusyStateEvent) : StatusFrame

@Serializable
@SerialName("terminal")
data class TerminalStatus(val event: AgentTerminalControlEvent) : StatusFrame
