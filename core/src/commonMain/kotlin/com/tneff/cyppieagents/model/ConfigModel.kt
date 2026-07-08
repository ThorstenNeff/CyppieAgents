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

/** GET/PUT /api/config/repo response: the active project's repo, or `configured=false` when unset. */
@Serializable
data class RepoConfigView(
    val configured: Boolean,
    val url: String? = null,
    val branch: String? = null,
    /**
     * CYP-247 S2 — `true` when a repo change is pending re-provision: the config now points at a new
     * repo/branch, but the live clone still tracks the old one until the project's agents are (re)started
     * (the clone is torn down + re-cloned). EFFECT_DEFERRED (analog CYP-310) — the UI shows a
     * "takes effect on next restart" hint. Additive (default false); the field is set by the config route.
     */
    val reprovisionPending: Boolean = false,
)

/** PUT /api/config/repo body. */
@Serializable
data class RepoConfigRequest(
    val url: String,
    val branch: String = "main",
    /**
     * CYP-247 S2 / D5 — the explicit opt-in to DISCARD uncommitted/unpushed `agent/<name>` work when the
     * repo change re-provisions (tears down the old clone). Default `false` → the §2d work-guard BLOCKS the
     * re-provision if any agent has a dirty tree or unpushed commits (no silent loss). `true` = the operator
     * accepts the loss (they have pushed / don't need it) and the re-provision proceeds regardless.
     */
    val discardUnpushed: Boolean = false,
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
