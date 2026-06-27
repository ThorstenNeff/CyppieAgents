package com.tneff.cyppieagents

/** JVM/desktop: read the hub URL + tokens from the environment, falling back to the local dev server. */
actual fun defaultShellConfig(): ShellConfig {
    val host = System.getenv("HUB_HOST") ?: "localhost"
    val port = System.getenv("HUB_PORT")?.toIntOrNull() ?: 8787
    return ShellConfig(
        hubWsBaseUrl = "ws://$host:$port",
        hubHttpBaseUrl = "http://$host:$port",
        agentToken = { id -> System.getenv("HUB_TOKEN_${id.uppercase()}") ?: "dev-token-$id" },
        // Privileged token only if explicitly provided — never a baked literal.
        operatorToken = System.getenv("OPERATOR_TOKEN"),
    )
}
