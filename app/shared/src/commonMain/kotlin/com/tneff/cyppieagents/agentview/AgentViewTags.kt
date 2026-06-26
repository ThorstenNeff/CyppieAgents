package com.tneff.cyppieagents.agentview

/**
 * `testTag` contract for the agent renderer — follows the shared Test-Contract v0.2
 * (`test/TEST-CONTRACT.md` §2): schema `<area>.<element>[.<id>][.<qualifier>]`, prefixless,
 * area `agent`, addressed per `agentId`. This is the single source of truth shared with the
 * tester (CYP-7); tags are an API between Dev and QA — not renamed silently.
 *
 * Only the three elements the contract names for the `agent` area are defined here
 * (`stream`, `input`, `sendBtn`). Per-event-row addressing inside the stream is intentionally
 * NOT invented here — it is flagged to the tester (schema authority) for a contract addition.
 */
object AgentViewTags {
    /** The scrolling stream-json transcript (LazyColumn) for the given agent. */
    fun stream(agentId: String) = "agent.$agentId.stream"

    /** The "message to the agent" input field. */
    fun input(agentId: String) = "agent.$agentId.input"

    /** The send button. */
    fun sendBtn(agentId: String) = "agent.$agentId.sendBtn"
}
