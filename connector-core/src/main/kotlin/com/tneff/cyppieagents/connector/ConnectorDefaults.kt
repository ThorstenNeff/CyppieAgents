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
     * CYP-110: bumped 2.1.193 → 2.1.195 (the installed version; no host downgrade). CYP-160: the host
     * `claude` moved on to **2.1.196**, so the pin follows it (reality-match, not host-downgrade). The
     * stream-json surface we depend on is **identical across 2.1.193↔2.1.196** — the rate-limit `status`
     * enum (`allowed`/`allowed_warning`/`blocked`/`rejected`) is unchanged (CYP-59/61). A patch bump,
     * low drift risk; RB1 Run #5 validates 196 live. This is what the RB1 real-agent harness asserts.
     */
    const val PINNED_CLI_VERSION = "2.1.196"

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
     * CYP-167 — `--resume <id>` single-source. Prepended (verified flag position: the spike resumed with
     * `claude --resume <id> -p …`) when a durable [SessionStore] entry exists, so BOTH arg paths
     * ([streamJsonArgs] and [sandboxBypassStreamJsonArgs]) emit it identically and can't drift. Null/blank
     * id ⇒ no flag (the first-start invariant: nothing to resume ⇒ a fresh session).
     */
    private fun withResume(resumeSessionId: String?, args: List<String>): List<String> =
        if (resumeSessionId.isNullOrBlank()) args else listOf("--resume", resumeSessionId) + args

    /**
     * Builds the spawn args for a production session. Fails closed if a caller tries to make
     * `bypassPermissions` the mode. MVP keeps partial-messages OFF (no `--include-partial-messages`).
     *
     * CYP-167: [resumeSessionId] non-blank ⇒ `--resume <id>` is prepended (resume after restart).
     */
    fun streamJsonArgs(
        allowedTools: List<String> = DEFAULT_ALLOWED_TOOLS,
        permissionMode: String = DEFAULT_PERMISSION_MODE,
        resumeSessionId: String? = null,
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
        return withResume(resumeSessionId, args)
    }

    /** True if a built arg list would bypass permissions — used to assert the default never does. */
    fun bypassesPermissions(args: List<String>): Boolean =
        args.any { it == DANGEROUS_FLAG || it == FORBIDDEN_PERMISSION_MODE }

    /**
     * CYP-163 — the NARROW, sandbox-only escape hatch: build spawn args WITH `bypassPermissions`, permitted
     * ONLY when the caller presents an explicit [SandboxBypassGrant]. This is the **single** path that may
     * emit bypass. The production [streamJsonArgs] keeps its fail-closed `require` (Gate #4), so bypass can
     * **never** leak into the prod default — the two paths are disjoint and the grant is a non-defaulted,
     * greppable parameter, so the exception is explicit at every call site, never implicit.
     *
     * Authorized: Auftraggeber (human, out-of-band) for the RB1 **throwaway-sandbox** worker spawn ONLY;
     * reviewer co-signed (CYP-163). NOT for any product-repo / S8-prod spawn.
     */
    fun sandboxBypassStreamJsonArgs(
        grant: SandboxBypassGrant,
        allowedTools: List<String> = DEFAULT_ALLOWED_TOOLS,
        resumeSessionId: String? = null,
    ): List<String> {
        // The grant's presence IS the authorization (its type can only be produced via the named factory).
        require(grant.reason.isNotBlank()) { "sandbox bypass grant must state its reason" }
        val args = BASE_STREAM_JSON_FLAGS.toMutableList()
        args += "--permission-mode"
        args += FORBIDDEN_PERMISSION_MODE // bypassPermissions — sandbox-only, grant-gated
        if (allowedTools.isNotEmpty()) {
            args += "--allowedTools"
            args += allowedTools.joinToString(",")
        }
        // CYP-167: same `--resume` single-source as the prod path — both paths emit it identically.
        return withResume(resumeSessionId, args)
    }
}

/**
 * CYP-163 — an explicit, sandbox-signed capability token that authorizes [ConnectorDefaults
 * .sandboxBypassStreamJsonArgs] to emit `bypassPermissions`. The **private constructor + single named
 * factory** mean a grant cannot be produced implicitly or by accident: it documents intent at the call
 * site and is trivially greppable. Production code never constructs one, so the prod spawn path stays
 * sharp (Gate #4). Auftraggeber-authorized (human) + reviewer co-signed for the RB1 sandbox worker ONLY.
 */
class SandboxBypassGrant private constructor(val reason: String) {
    companion object {
        /** The ONLY sanctioned grant: the RB1 **throwaway** sandbox worker (a disposable, non-product repo). */
        fun rb1Sandbox(): SandboxBypassGrant =
            SandboxBypassGrant("RB1 throwaway-sandbox worker (CYP-163; human + reviewer signed)")
    }
}
