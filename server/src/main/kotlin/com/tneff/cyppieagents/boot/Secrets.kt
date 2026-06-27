package com.tneff.cyppieagents.boot

/**
 * Boot secrets resolved from the host environment (Spec §14): one bearer token per agent, one
 * operator token, and the per-team API key. NEVER hardcoded, NEVER logged — [toString] is masked
 * so an accidental log line can't leak anything (Reviewer #1/#2).
 */
class Secrets(
    /** bearer token → agentId */
    val agentTokens: Map<String, String>,
    val operatorToken: String?,
    /** ANTHROPIC_API_KEY for this team; injected into each session's ENV (never a CLI arg). */
    val apiKey: String?,
) {
    override fun toString(): String =
        "Secrets(agentTokens=${agentTokens.size} masked, operatorToken=${mask(operatorToken)}, apiKey=${mask(apiKey)})"

    private fun mask(value: String?): String =
        if (value.isNullOrEmpty()) "unset" else "***${value.takeLast(4)}"

    companion object {
        /**
         * Resolves secrets from env. Per agent, requires `HUB_TOKEN_<ID>`; requires `OPERATOR_TOKEN`.
         * Fails closed (throws) on a missing required token rather than booting an unauthenticated hub.
         * `ANTHROPIC_API_KEY` is optional here (a real agent run needs it; tests / dry boots don't).
         */
        fun fromEnv(agentIds: List<String>, env: (String) -> String? = System::getenv): Secrets {
            val tokens = agentIds.associate { id ->
                val key = "HUB_TOKEN_${id.uppercase()}"
                val token = env(key)?.takeIf { it.isNotBlank() }
                    ?: error("missing required env $key (no hardcoded fallback)")
                token to id
            }
            val operator = env("OPERATOR_TOKEN")?.takeIf { it.isNotBlank() }
                ?: error("missing required env OPERATOR_TOKEN")
            return Secrets(tokens, operator, env("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() })
        }
    }
}
