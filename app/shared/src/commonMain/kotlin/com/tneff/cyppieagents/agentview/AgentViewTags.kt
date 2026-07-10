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

    /** CYP-333: the content-view mode toggle `[ Orchestrierung | Terminal ]` (operator-gated). */
    fun modeToggle(agentId: String) = "agent.$agentId.modeToggle"
    fun modeToggleOrchestration(agentId: String) = "agent.$agentId.modeToggle.orch"
    fun modeToggleTerminal(agentId: String) = "agent.$agentId.modeToggle.term"

    /** CYP-333: non-operator gate hint under the read-only toggle (reused `workspace_operator_only` copy). */
    fun modeToggleGateHint(agentId: String) = "agent.$agentId.modeToggle.gateHint"

    /** CYP-333: operator note when the live worktree-shell connection is gated (bash backend absent — e.g. a
     *  non-Desktop target / the kill-switch off). The Shell segment is disabled with an honest reason. */
    fun modeToggleTerminalGated(agentId: String) = "agent.$agentId.modeToggle.terminalGated"

    /** CYP-333 live flip: honest descriptor shown while the live Shell view is active — it is a bash worktree
     *  shell, not the agent's session (the claude same-session terminal reuses the slot later, BE-2). */
    fun modeToggleShellNote(agentId: String) = "agent.$agentId.modeToggle.shellNote"

    /** CYP-333: the content rectangle that swaps between the transcript and the terminal. */
    fun content(agentId: String) = "agent.$agentId.content"

    /** The N-th event line in the stream. [index] is the 0-based, additive-stable render order. */
    fun event(agentId: String, index: Int) = "agent.$agentId.event.$index"

    /** As [event], qualified by event kind for type-based assertions. */
    fun event(agentId: String, index: Int, kind: EventKind) =
        "agent.$agentId.event.$index.${kind.tag}"

    /** CYP-335: the `HH:mm` gutter of the N-th event line. Additive — coordinate with QA before renaming. */
    fun eventTime(agentId: String, index: Int) = "agent.$agentId.event.$index.time"
}

/** Event-kind qualifier vocabulary from Test-Contract v0.4 §2 (`assistantText` · `toolCall` · `toolResult`).
 *  CYP-323 adds `userTurn` (the locally-echoed human turn); CYP-326 #1 adds `system` (the injected incoming/
 *  system message) — additive, coordinate with QA before renaming. */
enum class EventKind(val tag: String) {
    ASSISTANT_TEXT("assistantText"),
    TOOL_CALL("toolCall"),
    TOOL_RESULT("toolResult"),
    USER_TURN("userTurn"),
    INCOMING_SYSTEM("system"),
}
