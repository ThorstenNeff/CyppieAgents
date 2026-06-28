package com.tneff.cyppieagents.settings

/**
 * Project-settings data port (S15, CYP-84 repo-config + CYP-85 API-key) — the contract from
 * `docs/PROJECT-SETTINGS.md §2` that the **backend must still build** (greenfield, tracked as CYP-96).
 * The UI depends only on this abstraction, so the live REST client is a later **stub→real swap** with
 * no UI change. Kept as plain Kotlin types (not `@Serializable`) because `:app:shared` carries no
 * serialization plugin — the wire DTOs are `:core`/backend territory and are reconciled when CYP-96
 * lands; the contract shapes below mirror the design spec exactly so that swap is mechanical.
 *
 * **Security contract the UI relies on (PROJECT-SETTINGS §1.2/§2):** the API key NEVER round-trips in
 * clear — [getApiKey] returns only `{set, masked}` (`***<last4>`); a write is one-way via [putApiKey].
 * Writes are operator-gated; the server enforces 403 `operator_required` (the UI also disables controls
 * fail-closed), and a rejected/invalid write throws [ConfigException] with the server's reason code.
 */
interface ConfigRepository {
    /** Current repo config, or [RepoConfigState.NotConfigured] when none is set (agents cannot start). */
    suspend fun getRepo(): RepoConfigState

    /** Operator-only: persist the repo URL/branch. Throws [ConfigException] on `invalid_repo_url` / gate. */
    suspend fun putRepo(url: String, branch: String): RepoConfigState.Configured

    /** API-key presence, masked only — NEVER the clear key (PROJECT-SETTINGS §1.2). */
    suspend fun getApiKey(): ApiKeyState

    /** Operator-only: store a new key (one-way). Throws [ConfigException] on `invalid_api_key` / gate. */
    suspend fun putApiKey(apiKey: String): ApiKeyState
}

/** Repo-config state (CYP-84). `NotConfigured` is the honest "agents cannot start" case, not a blank field. */
sealed interface RepoConfigState {
    data object NotConfigured : RepoConfigState
    data class Configured(val url: String, val branch: String) : RepoConfigState
}

/** API-key presence (CYP-85): whether a key is stored and, if so, its server-masked `***<last4>` form. */
data class ApiKeyState(val set: Boolean, val masked: String?)

/**
 * A settings write was rejected. [code] is the server's reason — the exact wire codes from
 * PROJECT-SETTINGS §2: `invalid_repo_url`, `invalid_api_key`, `operator_required` (403), `unauthorized`
 * (401). The VM maps these to honest, spec-defined disclosure (field error vs gate denial).
 */
class ConfigException(val code: String) : Exception(code)
