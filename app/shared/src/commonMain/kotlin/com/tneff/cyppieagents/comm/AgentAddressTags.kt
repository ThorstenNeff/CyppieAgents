package com.tneff.cyppieagents.comm

/**
 * CYP-884 (OS-D) — `testTag` contract for the recipient-addressing picker (parity with web-ts `agent-address.*`).
 * Single source of truth shared with the tester.
 */
object AgentAddressTags {
    const val ROOT = "agentAddress.root"

    /** A candidate-recipient option. */
    fun option(agentId: String) = "agentAddress.option.$agentId"

    /** The "open DM" action — enabled ONLY when the target resolves to exactly one DIRECT spoke. */
    const val DM = "agentAddress.dm"

    /** Honest "not directly addressable" — no fabricated channel (a new direct link is OS-C channel-creation). */
    const val UNREACHABLE = "agentAddress.unreachable"

    /** The AMBIGUOUS flag (role=alert): >1 DIRECT spoke — the action is disabled, NEVER a silent-first pick. */
    const val AMBIGUOUS = "agentAddress.ambiguous"
}
