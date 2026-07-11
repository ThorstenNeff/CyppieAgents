package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-421 (c) — the body of `PUT`/`DELETE /api/agents/{id}/terminal-grants`: the delegable [subject] to grant or
 * revoke a terminal to. The subject is the stable grant key the socket gate keys on (a human MEMBER's bare
 * `identityId`, or a namespaced `participant:<x>` / `agent:<id>` for machine classes — see `TerminalPrincipal`).
 */
@Serializable
data class TerminalGrantRequest(val subject: String)

/**
 * CYP-421 (c) — the response of the `/api/agents/{id}/terminal-grants` operations: the full set of subjects
 * currently granted a terminal on [agentId] (sorted, stable). Content-free: only grant keys, no session detail.
 */
@Serializable
data class TerminalGrants(val agentId: String, val subjects: List<String>)
