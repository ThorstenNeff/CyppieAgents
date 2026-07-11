package com.tneff.cyppieagents.net.hub

/**
 * CYP-411 — the resolved address of a hub: the `http(s)` and `ws(s)` base URLs a [HubTransport] connects to.
 *
 * **Why it holds the resolved URL pair and not `(host, port)`.** The deployed web build
 * (`ShellConfig.forOrigin`) derives its base from `window.location.origin`, which has **no explicit port** and a
 * scheme fixed by the page (`https://app.example.com` → `wss://…`). Reconstructing that from `(host, port)` would
 * change the string (`…:443`) and break the same-origin `ory_kratos_session` cookie / CORS. So the endpoint keeps
 * the exact URLs; [local] is the canonical Phase-1 constructor for the default-port local-hub case (R5=loopback:
 * local-connect targets `127.0.0.1`/`localhost`, LAN is Phase-2 remote).
 */
data class HubEndpoint(
    val httpBaseUrl: String,
    val wsBaseUrl: String,
) {
    companion object {
        /** The hub's default local port (CYP-24 — the server binds 8787; the client must match). */
        const val DEFAULT_LOCAL_PORT: Int = 8787

        /**
         * The canonical Phase-1 local endpoint: direct HTTP/WS on [host]:[port] (default `localhost:8787`).
         * [secure] flips `http/ws` → `https/wss`. This is the structured `(host, port)` form the later
         * registry-driven connect (S-J: `HubDescriptor.defaultPort`) plugs into.
         */
        fun local(host: String = "localhost", port: Int = DEFAULT_LOCAL_PORT, secure: Boolean = false): HubEndpoint {
            val httpScheme = if (secure) "https" else "http"
            val wsScheme = if (secure) "wss" else "ws"
            return HubEndpoint(httpBaseUrl = "$httpScheme://$host:$port", wsBaseUrl = "$wsScheme://$host:$port")
        }
    }
}
