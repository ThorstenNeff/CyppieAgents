package com.tneff.cyppieagents.mediation

/**
 * CYP-131 — single-sourced names for the agent-facing `hub_send` tool. The stream-json extractor
 * (Connector A, [MediationRouter.onHubSend]) and the MCP tool schema (Connector B,
 * [com.tneff.cyppieagents.connector.HubMcpTools]) must use the **same** tool name + arg keys, so the
 * persona (CYP-133), MCP, and stream-json can't drift (reviewer m3). Pinned here once.
 */
object HubTools {
    /** The tool an agent calls to post into the hub on its own behalf. */
    const val SEND: String = "hub_send"

    /** Target channel id (e.g. `po-backend`). Required, non-blank. */
    const val ARG_CHANNEL: String = "channel"

    /** Message body. Required, non-blank. */
    const val ARG_TEXT: String = "text"

    /** Optional message kind (`TASK`/`STATUS`/`NOTE`); ignored if absent or unrecognized. */
    const val ARG_KIND: String = "kind"
}
