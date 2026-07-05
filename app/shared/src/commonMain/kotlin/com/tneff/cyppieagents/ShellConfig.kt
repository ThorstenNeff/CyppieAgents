package com.tneff.cyppieagents

/**
 * Runtime config for the client shell (CYP-28): hub URL + tokens come from here, not baked into the
 * UI code. The server is config/env-driven and binds **8787** (CYP-24) — the client must match.
 *
 *  - [agentToken] resolves an agent's bearer. **Only the local [dev] config** derives `dev-token-<id>` (matching
 *    the dev `TokenRegistry`, so the demo connects without setup). The deployed SPA ([forOrigin]) sends **no
 *    guessable token** (CYP-230) — a session-operator authenticates the agent-WS via the handshake session cookie.
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

        /**
         * CYP-188 — derive the shell config from the page [origin] (web: `window.location.origin`). The deployed
         * SPA is served **same-origin** with the API + Kratos proxy, so the browser `ory_kratos_session` cookie
         * flows on the shell's data reads/sockets (the P2a browser path). This is load-bearing: CORS keeps
         * `allowCredentials=false` (Cors.kt), so a cross-origin base would drop the session cookie → app dead for
         * a logged-in end-user. The WS base derives ws/wss from the origin's http/https scheme. [operatorToken]
         * stays runtime-injected — the **public** build passes `null` (tokenless); an operator serve passes the
         * host-injected token (break-glass) — never baked into the artifact (CYP-152).
         */
        fun forOrigin(origin: String, operatorToken: String? = null): ShellConfig {
            val http = origin.trimEnd('/')
            val ws = when {
                http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
                http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
                else -> http // already ws(s) / a bare host — pass through unchanged
            }
            return ShellConfig(
                hubWsBaseUrl = ws,
                hubHttpBaseUrl = http,
                // CYP-230: the deployed SPA sends NO guessable per-agent token. A session-operator authenticates the
                // agent-WS via the same-origin `ory_kratos_session` cookie the browser attaches to the WS handshake
                // (the `/ws/agent` operator-session path); a break-glass operator serve passes its operator token
                // ([isOperator]). NEVER `dev-token-<id>` — an agentId-derivable token is an agent-stream auth-bypass
                // footgun. `""` → the client omits the token entirely and relies on the handshake cookie.
                agentToken = { operatorToken ?: "" },
                operatorToken = operatorToken,
            )
        }
    }
}

/**
 * Platform default config. JVM/desktop reads it from the environment (`HUB_HOST`/`HUB_PORT`/
 * `HUB_TOKEN_<ID>`/`OPERATOR_TOKEN`), falling back to the local dev server; other targets use the
 * dev default (no ambient env). Entry points may always pass an explicit [ShellConfig].
 */
expect fun defaultShellConfig(): ShellConfig
