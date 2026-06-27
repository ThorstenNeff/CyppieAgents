package com.tneff.cyppieagents

/**
 * Runtime config for the client shell (CYP-28): hub URL + tokens come from here, not baked into the
 * UI code. The server is config/env-driven and binds **8787** (CYP-24) — the client must match.
 *
 *  - [agentToken] resolves an agent's bearer; the dev fallback matches the server's `TokenRegistry`
 *    dev default (`dev-token-<id>`), so the local demo connects without extra setup.
 *  - [operatorToken] (the privileged token for ACL/operator actions) is **never baked** — it is
 *    `null` unless supplied at runtime (env / operator input), pending CYP-18 operator auth.
 */
data class ShellConfig(
    val hubWsBaseUrl: String,
    val hubHttpBaseUrl: String,
    val agentToken: (agentId: String) -> String,
    val operatorToken: String? = null,
) {
    companion object {
        /** Dev default: a local server on the CYP-24 port (8787). No operator token baked. */
        fun dev(host: String = "localhost", port: Int = 8787) = ShellConfig(
            hubWsBaseUrl = "ws://$host:$port",
            hubHttpBaseUrl = "http://$host:$port",
            agentToken = { id -> "dev-token-$id" },
            operatorToken = null,
        )
    }
}

/**
 * Platform default config. JVM/desktop reads it from the environment (`HUB_HOST`/`HUB_PORT`/
 * `HUB_TOKEN_<ID>`/`OPERATOR_TOKEN`), falling back to the local dev server; other targets use the
 * dev default (no ambient env). Entry points may always pass an explicit [ShellConfig].
 */
expect fun defaultShellConfig(): ShellConfig
