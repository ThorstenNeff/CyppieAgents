package com.tneff.cyppieagents.agentview

/**
 * UI-shaped event the agent window renders.
 *
 * This is deliberately a *UI* model, not the wire format. The stream-json events the
 * backend mediator emits (later: `:core` DTOs) are translated into [AgentEvent] by a thin
 * mapper — that mapper is the single change-seam when `:core` DTOs are frozen (CYP-5 spike).
 * Until then the renderer is built against [StubAgentSession].
 *
 * Sealed so new kinds (e.g. a future `PermissionRequest` for the escalation seam, 05 §5 —
 * not MVP) can be added additively without restructuring the renderer.
 *
 * Every event carries a stable [id]: it keys the LazyColumn, lets streaming text fold into
 * one growing item, and is the anchor for reconnect de-duplication.
 */
sealed interface AgentEvent {
    val id: String

    /**
     * Assistant turn text. In the event *stream* [text] is the latest delta chunk; the
     * transcript folder ([foldEvents]) concatenates deltas sharing an [id] into one rendered
     * item. [complete] flips true on the final delta of the turn (renderer drops the cursor).
     */
    data class AssistantText(
        override val id: String,
        val text: String,
        val complete: Boolean,
    ) : AgentEvent

    /** A tool invocation, shown as a single line. Re-emitted with the same [id] to update [status]. */
    data class ToolCall(
        override val id: String,
        val tool: String,
        val summary: String,
        val status: ToolStatus,
    ) : AgentEvent

    /** A tool/turn result, marked distinctly (error vs success). */
    data class Result(
        override val id: String,
        val label: String,
        val isError: Boolean,
    ) : AgentEvent

    /** System / lifecycle notice (session started, agent stopped, key changed). */
    data class Notice(
        override val id: String,
        val text: String,
    ) : AgentEvent

    /**
     * CYP-323: a human turn typed into the composer, echoed into the LOCAL transcript on send —
     * chronologically before the agent's reply. The stream never replays it (the composer turn goes
     * stdin-only, and [StreamJsonMapper] additionally drops any replayed user text), so this is the
     * SINGLE source of the user-turn row; the stable [id] keeps [foldEvent] idempotent per turn.
     */
    data class UserTurn(
        override val id: String,
        val text: String,
    ) : AgentEvent

    /**
     * CYP-326 #1: an incoming message the PLATFORM injected into the agent's session (e.g. the compact
     * orchestrator's `/compact` prepare text) — surfaced so the operator sees the trigger, not just the agent's
     * reaction. Distinct from [UserTurn] (the operator's own composer turn): it is the SYSTEM speaking to the
     * agent. Emitted by [StreamJsonMapper] only when the wire `UserEvent.injectedSource != null`; a plain replayed
     * user echo stays dropped (never a composer double-echo). Rendered in stream order → before the reaction.
     */
    data class IncomingSystem(
        override val id: String,
        val text: String,
    ) : AgentEvent
}

enum class ToolStatus { RUNNING, OK, ERROR }
