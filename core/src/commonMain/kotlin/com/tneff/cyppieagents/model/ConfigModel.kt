package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * Project-settings wire contract (S15 / CYP-96 — PROJECT-SETTINGS §2). One definition compiled into
 * `:server` (the config endpoints) and `:app:shared` (the Settings UI, CYP-84/85) via `:core`, so the
 * shape can never drift.
 *
 * **Security by structure (Reviewer):** the API key is **write-only** on the wire. [ApiKeyRequest]
 * carries the plaintext key INBOUND (PUT only); [ApiKeyView] is the OUTBOUND view and carries **only**
 * `set` + `masked` (`***<last4>`) — there is no field that could ever transport the plaintext key back
 * to a client. The repo URL is not a secret and round-trips in full.
 */

/** GET /api/config/repo response: the active project's repo, or `configured=false` when unset. */
@Serializable
data class RepoConfigView(
    val configured: Boolean,
    val url: String? = null,
    val branch: String? = null,
)

/** PUT /api/config/repo body. */
@Serializable
data class RepoConfigRequest(
    val url: String,
    val branch: String = "main",
)

/**
 * GET /api/config/apikey response — the ONLY outbound shape for the key. `set` = a key is stored;
 * `masked` = `***<last4>` (or null when unset). The plaintext key is never present here.
 */
@Serializable
data class ApiKeyView(
    val set: Boolean,
    val masked: String? = null,
)

/** PUT /api/config/apikey body (write-only inbound). */
@Serializable
data class ApiKeyRequest(
    val apiKey: String,
)
