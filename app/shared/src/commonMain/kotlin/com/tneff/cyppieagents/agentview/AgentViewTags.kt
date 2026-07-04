package com.tneff.cyppieagents.agentview

/**
 * `testTag` contract for the agent renderer — follows the shared Test-Contract v0.5
 * (`docs/TEST-CONTRACT.md` §2): schema `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
 * prefixless. The `agent` area is instance-scoped, so tags read `agent.<agentId>.<element>…`.
 * Single source of truth shared with the tester (CYP-7); tags are an API between Dev and QA —
 * not renamed silently.
 *
 * Segment values are `[A-Za-z0-9-]+` (no dots — they collide with the separator and Maestro's
 * regex selector); callers must pass `agentId`s that satisfy that.
 */
object AgentViewTags {
    /** The scrolling stream-json transcript (LazyColumn) for the given agent. */
    fun stream(agentId: String) = "agent.$agentId.stream"

    /** The "message to the agent" input field. */
    fun input(agentId: String) = "agent.$agentId.input"

    /** The send button. */
    fun sendBtn(agentId: String) = "agent.$agentId.sendBtn"

    /** The window header bar (status + lifecycle controls), CYP-73. */
    fun header(agentId: String) = "agent.$agentId.header"

    /** The non-gated lifecycle status indicator (RUNNING/STOPPED/ERROR/UNKNOWN). */
    fun status(agentId: String) = "agent.$agentId.status"

    /** CYP-204: the reconnecting indicator, present only while the per-agent WS is not LIVE (auto-reconnect). */
    fun reconnecting(agentId: String) = "agent.$agentId.reconnecting"

    /** Operator-gated lifecycle controls. */
    fun startBtn(agentId: String) = "agent.$agentId.startBtn"
    fun stopBtn(agentId: String) = "agent.$agentId.stopBtn"
    fun restartBtn(agentId: String) = "agent.$agentId.restartBtn"

    /** Honest surfacing of a lifecycle-control failure (409/403/503/404). */
    fun lifecycleError(agentId: String) = "agent.$agentId.lifecycleError"

    /** The N-th event line in the stream. [index] is the 0-based, additive-stable render order. */
    fun event(agentId: String, index: Int) = "agent.$agentId.event.$index"

    /** As [event], qualified by event kind for type-based assertions. */
    fun event(agentId: String, index: Int, kind: EventKind) =
        "agent.$agentId.event.$index.${kind.tag}"
}

/** Event-kind qualifier vocabulary from Test-Contract v0.4 §2 (`assistantText` · `toolCall` · `toolResult`). */
enum class EventKind(val tag: String) {
    ASSISTANT_TEXT("assistantText"),
    TOOL_CALL("toolCall"),
    TOOL_RESULT("toolResult"),
}
