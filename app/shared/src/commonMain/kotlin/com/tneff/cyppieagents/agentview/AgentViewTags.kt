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

    /** CYP-392: the transcript's vertical scrollbar (Desktop/Web only; absent when the transcript fits). */
    fun scrollbar(agentId: String) = "agent.$agentId.scrollbar"

    /** The "message to the agent" input field. */
    fun input(agentId: String) = "agent.$agentId.input"

    /** The send button. */
    fun sendBtn(agentId: String) = "agent.$agentId.sendBtn"

    /** CYP-738: the composer's proactive read-only hint (agent NOT in the writable set — read, don't message). */
    fun composerReadonly(agentId: String) = "agent.$agentId.composer.readonly"

    /** CYP-738: the composer's UNKNOWN disabled-with-hint (write-right undetermined — endpoint error / pre-deploy). */
    fun composerUnknown(agentId: String) = "agent.$agentId.composer.unknown"

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

    /** CYP-629 §6.3c: honest GATED reason at the start control — the workspace is unconfigured (API key / repo),
     *  so spawning cannot succeed. GATED, not ERROR: nothing failed, the prerequisite is missing. Mirrors the
     *  [modeToggleGateHint] `.gateHint` qualifier precedent (a gate reason AT the control, not a foreign node beside it). */
    fun startBtnGateHint(agentId: String) = "agent.$agentId.startBtn.gateHint"

    /** Honest surfacing of a lifecycle-control failure (409/403/503/404). */
    fun lifecycleError(agentId: String) = "agent.$agentId.lifecycleError"

    /** CYP-333: the content-view mode toggle `[ Orchestrierung | Terminal ]` (operator-gated). */
    fun modeToggle(agentId: String) = "agent.$agentId.modeToggle"
    fun modeToggleOrchestration(agentId: String) = "agent.$agentId.modeToggle.orch"
    fun modeToggleTerminal(agentId: String) = "agent.$agentId.modeToggle.term"

    /** CYP-333: non-operator gate hint under the read-only toggle (reused `workspace_operator_only` copy). */
    fun modeToggleGateHint(agentId: String) = "agent.$agentId.modeToggle.gateHint"

    /** CYP-333/381: operator note when the interactive session is gated (hand-off motor / bash backend absent —
     *  e.g. a non-Desktop target / the kill-switch off). The Terminal segment is disabled with an honest reason. */
    fun modeToggleTerminalGated(agentId: String) = "agent.$agentId.modeToggle.terminalGated"

    /** CYP-381 (§8 rename): honest descriptor shown while the Terminal view is active — it is the agent's REAL,
     *  interactive session (claude --resume, the same session); typing goes to the agent, the hub does not mediate. */
    fun modeToggleTerminalNote(agentId: String) = "agent.$agentId.modeToggle.terminalNote"

    /** CYP-381: the hand-off command is in flight (POST issued, view NOT yet flipped — non-optimistic). */
    fun modeSwitching(agentId: String) = "agent.$agentId.modeToggle.switching"

    /** CYP-381 §4 IDLE-defer: a take-over is deferred (bounded-wait) until the running turn finishes (no hijack). */
    fun modeDeferHint(agentId: String) = "agent.$agentId.modeToggle.deferHint"

    /** CYP-381 §6: the persistent WARN "Hub vermittelt nicht" strip while the agent is INTERACTIVE (hub-blind). */
    fun handoffBanner(agentId: String) = "agent.$agentId.handoffBanner"

    /** CYP-381 §7b: the persistent WARN strip while the agent is CONTEXT_LOST (returned without prior context). */
    fun contextLostBanner(agentId: String) = "agent.$agentId.contextLostBanner"

    /** CYP-381 §7.1: the DURABLE transcript discontinuity band — present iff a CONTEXT_LOST landmark is anchored in
     *  the buffer. Unlike the live [contextLostBanner], it PERSISTS after the state recovers to MEDIATED (the
     *  memory boundary stays visible); QA asserts exactly that split. */
    fun contextLostDivider(agentId: String) = "agent.$agentId.contextLostDivider"

    /** CYP-333: the content rectangle that swaps between the transcript and the terminal. */
    fun content(agentId: String) = "agent.$agentId.content"

    /** The N-th event line in the stream. [index] is the 0-based, additive-stable render order. */
    fun event(agentId: String, index: Int) = "agent.$agentId.event.$index"

    /** As [event], qualified by event kind for type-based assertions. */
    fun event(agentId: String, index: Int, kind: EventKind) =
        "agent.$agentId.event.$index.${kind.tag}"

    /** CYP-335: the `HH:mm` gutter of the N-th event line. Additive — coordinate with QA before renaming. */
    fun eventTime(agentId: String, index: Int) = "agent.$agentId.event.$index.time"

    /** F4 (CYP-580): present ⇔ this user turn was composed while the per-agent socket was NOT LIVE ⇒ honestly
     *  "not delivered" (never rendered as sent). Absent ⇒ a LIVE (best-effort sent) turn. Additive — coordinate w/ QA. */
    fun userTurnUndelivered(agentId: String, index: Int) = "agent.$agentId.event.$index.undelivered"
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
