package com.tneff.cyppieagents.boot

/**
 * Boot secrets resolved from the host environment (Spec §14): one bearer token per agent, one
 * operator token, and the API key. NEVER hardcoded, NEVER logged — [toString] is masked so an
 * accidental log line can't leak anything (Reviewer #1/#2).
 *
 * The API key is resolved **per project** ([apiKeyFor], S12 / CYP-82 — Doc 05 D3 / Doc 08 §3), never
 * as a single global server constant. MVP=1 has no per-project override, so it falls back to the team
 * key — but the resolution point is keyed by `projectId`, which is the seam CYP-96 backs with an
 * operator-settable per-project store.
 */
class Secrets(
    /** bearer token → agentId */
    val agentTokens: Map<String, String>,
    val operatorToken: String?,
    /** Team/default ANTHROPIC_API_KEY; the fallback for a project without an explicit key. */
    val apiKey: String?,
    /** Per-project ANTHROPIC_API_KEY overrides (S12 / CYP-82). The single backing for [apiKeyFor]. */
    private val apiKeysByProject: Map<String, String> = emptyMap(),
) {
    /**
     * The ANTHROPIC_API_KEY for [projectId] — resolved PER PROJECT, never a global constant. An
     * explicit per-project override wins; otherwise the team key. This is the one point the spawn
     * resolves the key, so CYP-96 can swap the backing without touching the call site. Injected into
     * the session ENV (never a CLI arg).
     */
    fun apiKeyFor(projectId: String): String? =
        apiKeysByProject[projectId]?.takeIf { it.isNotBlank() } ?: apiKey

    override fun toString(): String =
        "Secrets(agentTokens=${agentTokens.size} masked, operatorToken=${mask(operatorToken)}, " +
            "apiKey=${mask(apiKey)}, apiKeysByProject=${apiKeysByProject.size} masked)"

    companion object {
        /**
         * Below this length a value is too short to reveal ANY plaintext (CYP-104): `mask` drops the
         * `<last4>` tail entirely, and [ProjectConfigStore.setApiKey] rejects new keys as implausible.
         * Single-sourced here so the redaction floor and the key-plausibility floor can't drift.
         */
        const val MIN_SECRET_LEN: Int = 12

        /**
         * The single masking helper (Reviewer): `***<last4>`, or `unset` when empty. **CYP-104 redaction
         * floor:** a value shorter than [MIN_SECRET_LEN] renders as `****` with NO plaintext tail — for a
         * short value `takeLast(4)` would otherwise expose most/all of it (e.g. a 4-char value → `***abcd`).
         * Reused by the per-project config store (CYP-96) so a key value is never rendered in full anywhere.
         */
        fun mask(value: String?): String = when {
            value.isNullOrEmpty() -> "unset"
            value.length < MIN_SECRET_LEN -> "****"
            else -> "***${value.takeLast(4)}"
        }

        /**
         * Resolves secrets from env. Per agent, requires `HUB_TOKEN_<ID>`; requires `OPERATOR_TOKEN`.
         * Fails closed (throws) on a missing required token rather than booting an unauthenticated hub.
         * `ANTHROPIC_API_KEY` is optional here (a real agent run needs it; tests / dry boots don't).
         *
         * [projectIds] (S12 / CYP-82) optionally resolves a per-project key from
         * `ANTHROPIC_API_KEY_<PROJECTID>`; absent → that project falls back to the team key. Defaulted
         * empty so existing callers are unchanged (MVP=1 with only the global key behaves as before).
         */
        fun fromEnv(
            agentIds: List<String>,
            projectIds: List<String> = emptyList(),
            env: (String) -> String? = System::getenv,
        ): Secrets {
            val tokens = agentIds.associate { id ->
                val key = "HUB_TOKEN_${id.uppercase()}"
                val token = env(key)?.takeIf { it.isNotBlank() }
                    ?: error("missing required env $key (no hardcoded fallback)")
                token to id
            }
            val operator = env("OPERATOR_TOKEN")?.takeIf { it.isNotBlank() }
                ?: error("missing required env OPERATOR_TOKEN")
            val perProject = projectIds.mapNotNull { pid ->
                env("ANTHROPIC_API_KEY_${pid.uppercase()}")?.takeIf { it.isNotBlank() }?.let { pid to it }
            }.toMap()
            return Secrets(tokens, operator, env("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }, perProject)
        }
    }
}
