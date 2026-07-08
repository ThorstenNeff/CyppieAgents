package com.tneff.cyppieagents.connector

/**
 * Canonical, pinned spawn configuration for the Claude-Code connector (CYP-5 verified). Kept as
 * code (not just prose in a report) so the verified flag set is the single source.
 *
 * **Gate #4 — RE-POINTED for the MVP (CYP-321, Auftraggeber-authorized 2026-07-08):** the former default
 * ("`bypassPermissions` must NEVER be the connector default") is DELIBERATELY inverted for the MVP. Local
 * agents run headless (stream-json) and cannot answer an interactive permission prompt, so the PO/worker
 * agents stall on approvals during dogfood. The MVP decision is to spawn every local config agent with
 * [DANGEROUS_FLAG] (`--dangerously-skip-permissions`) — see [MVP_SKIP_PERMISSIONS]. This is scoped to the
 * MVP and **must be reverted before multi-tenant / public exposure** (the CYP-179 line): flip
 * [MVP_SKIP_PERMISSIONS] to `false` and the tight permission-mode default returns. The guard on the
 * `--permission-mode` value (Gate #4) is kept, not removed — the sanctioned MVP bypass is the FLAG, never
 * the `bypassPermissions` MODE string, and the disjoint grant-gated sandbox path (CYP-163) is untouched.
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

    /** Post-MVP tight permission mode (used only when [MVP_SKIP_PERMISSIONS] is false). NEVER `bypassPermissions`. */
    const val DEFAULT_PERMISSION_MODE: String = "default"

    const val FORBIDDEN_PERMISSION_MODE: String = "bypassPermissions"
    const val DANGEROUS_FLAG: String = "--dangerously-skip-permissions"

    /**
     * CYP-321 — MVP-wide permission bypass for **LOCAL** agent spawns (**Auftraggeber-authorized 2026-07-08,
     * MVP-scope**). This is the value the **local** [ClaudeCodeConnector] passes as `streamJsonArgs(skipPermissions=…)`
     * so its headless config agents work autonomously without an interactive approval. **Revert to `false` before
     * multi-tenant / public exposure (CYP-179 line)** → the tight [DEFAULT_PERMISSION_MODE] path returns.
     *
     * **Scoping (CYP-321 security review):** the flag lives on the `skipPermissions` PARAMETER, defaulted `false`,
     * NOT baked into the shared [streamJsonArgs] default — so a direct caller like the remote `BridgeMain` (which
     * spawns the USER's own Claude Code on the USER's machine) does NOT inherit the bypass. The Auftraggeber
     * authorization covers the local connector's spawns only, never a foreign user machine (CYP-197/BYOA).
     *
     * Verified against the CLI docs (Context7 / code.claude.com): `--dangerously-skip-permissions` is
     * **equivalent to and supersedes** `--permission-mode bypassPermissions`, so we emit ONLY the flag (no
     * `--permission-mode` — no double directive); `--allowedTools` is moot under a full bypass but kept
     * harmlessly (it still pre-registers `mcp__hub__hub_send`, no conflict). The flag **refuses to run as
     * root/sudo** on Linux/macOS (skipped inside a sandbox) — staging runs as the non-root `customer` user,
     * so it applies. Min CLI v2.1.142 ≤ pinned [PINNED_CLI_VERSION]. A non-const `val` so the reversal
     * branch stays live (no dead-code fold). */
    val MVP_SKIP_PERMISSIONS: Boolean = true

    /**
     * CYP-167 — `--resume <id>` single-source. Prepended (verified flag position: the spike resumed with
     * `claude --resume <id> -p …`) when a durable [SessionStore] entry exists, so BOTH arg paths
     * ([streamJsonArgs] and [sandboxBypassStreamJsonArgs]) emit it identically and can't drift. Null/blank
     * id ⇒ no flag (the first-start invariant: nothing to resume ⇒ a fresh session).
     */
    private fun withResume(resumeSessionId: String?, args: List<String>): List<String> =
        if (resumeSessionId.isNullOrBlank()) args else listOf("--resume", resumeSessionId) + args

    /**
     * Builds the spawn args for a production session. MVP keeps partial-messages OFF (no
     * `--include-partial-messages`). CYP-167: [resumeSessionId] non-blank ⇒ `--resume <id>` is prepended.
     *
     * CYP-321 (MVP): [skipPermissions] `true` (passed ONLY by the local [ClaudeCodeConnector], value
     * [MVP_SKIP_PERMISSIONS]) makes the args carry [DANGEROUS_FLAG] instead of a `--permission-mode` (the flag
     * supersedes it — no double directive). It **defaults to `false`**, so the shared default and any direct
     * caller (e.g. the remote `BridgeMain` on the user's machine) stay bypass-free (scoping, CYP-321 review).
     * The `require` below is KEPT (Gate #4, re-pointed): passing `bypassPermissions` as the MODE value is still
     * fail-closed rejected, so that vector can never re-appear via the `permissionMode` param.
     */
    fun streamJsonArgs(
        allowedTools: List<String> = DEFAULT_ALLOWED_TOOLS,
        permissionMode: String = DEFAULT_PERMISSION_MODE,
        resumeSessionId: String? = null,
        skipPermissions: Boolean = false,
    ): List<String> {
        require(permissionMode != FORBIDDEN_PERMISSION_MODE) {
            "bypassPermissions must not be passed as the --permission-mode value (Gate #4); the MVP bypass is DANGEROUS_FLAG"
        }
        val args = BASE_STREAM_JSON_FLAGS.toMutableList()
        if (skipPermissions) {
            // CYP-321: LOCAL-only bypass — one flag, supersedes --permission-mode (no double directive).
            args += DANGEROUS_FLAG
        } else if (permissionMode.isNotBlank()) {
            args += "--permission-mode"
            args += permissionMode
        }
        if (allowedTools.isNotEmpty()) {
            // Moot under a full bypass, but kept: pre-registers mcp__hub__hub_send (no conflict with the flag).
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
