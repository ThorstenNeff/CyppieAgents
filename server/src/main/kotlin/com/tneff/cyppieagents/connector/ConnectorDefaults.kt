package com.tneff.cyppieagents.connector

/**
 * Canonical, pinned spawn configuration for the Claude-Code connector (CYP-5 verified). Kept as
 * code (not just prose in a report) so the verified flag set is the single source and the
 * dangerous default can't be copy-pasted back in.
 *
 * Reviewer Gate #4: `bypassPermissions` must NOT leak into the connector default — the spike's
 * `system/init` showing `permissionMode: bypassPermissions` is a SCRATCH artifact. Production
 * spawns use a tight, explicit permission mode; the wide `--allowedTools` set is decided with the
 * reviewer before any agent works autonomously on the real repo (S8).
 */
object ConnectorDefaults {

    /**
     * Pinned CLI version (CYP-5). The connector should assert the runtime matches.
     *
     * CYP-110: bumped 2.1.193 → **2.1.195** (the installed version; no host downgrade). The stream-json
     * surface we depend on is **identical across 2.1.193↔2.1.195** — the rate-limit `status` enum
     * (`allowed`/`allowed_warning`/`blocked`/`rejected`) is binary-identical in both (CYP-59/61 reviewer
     * grep of the shipped binaries). Pinning the installed 195 also closes the latent prod pin-drift
     * (installed=195 vs pin was 193) and is what the RB1 real-agent harness asserts.
     */
    const val PINNED_CLI_VERSION = "2.1.195"

    /** Verified long-lived-session flags (CYP-5). `--verbose` is mandatory with stream-json. */
    val BASE_STREAM_JSON_FLAGS: List<String> = listOf(
        "-p",
        "--input-format", "stream-json",
        "--output-format", "stream-json",
        "--verbose",
    )

    /** MVP default: tight (empty) tool allowlist — widened only with reviewer sign-off (S8). */
    val DEFAULT_ALLOWED_TOOLS: List<String> = emptyList()

    /** Safe default permission mode. NEVER `bypassPermissions`. */
    const val DEFAULT_PERMISSION_MODE: String = "default"

    const val FORBIDDEN_PERMISSION_MODE: String = "bypassPermissions"
    const val DANGEROUS_FLAG: String = "--dangerously-skip-permissions"

    /**
     * Builds the spawn args for a production session. Fails closed if a caller tries to make
     * `bypassPermissions` the mode. MVP keeps partial-messages OFF (no `--include-partial-messages`).
     */
    fun streamJsonArgs(
        allowedTools: List<String> = DEFAULT_ALLOWED_TOOLS,
        permissionMode: String = DEFAULT_PERMISSION_MODE,
    ): List<String> {
        require(permissionMode != FORBIDDEN_PERMISSION_MODE) {
            "bypassPermissions must not be the connector default (Gate #4)"
        }
        val args = BASE_STREAM_JSON_FLAGS.toMutableList()
        if (permissionMode.isNotBlank()) {
            args += "--permission-mode"
            args += permissionMode
        }
        if (allowedTools.isNotEmpty()) {
            args += "--allowedTools"
            args += allowedTools.joinToString(",")
        }
        return args
    }

    /** True if a built arg list would bypass permissions — used to assert the default never does. */
    fun bypassesPermissions(args: List<String>): Boolean =
        args.any { it == DANGEROUS_FLAG || it == FORBIDDEN_PERMISSION_MODE }
}
