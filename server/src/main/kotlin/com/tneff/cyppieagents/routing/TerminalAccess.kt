package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.resolvePrincipal
import io.ktor.server.application.ApplicationCall

/**
 * CYP-394 — the authorization seam for `GET /ws/terminal` (the PTY-over-WebSocket transport, [terminalSocket]).
 *
 * **Why a dedicated seam and not [wsReaderOrNull].** `/ws/terminal` is a **bidirectional** PTY: the client writes
 * keystrokes to **stdin** ([com.tneff.cyppieagents.model.TerminalInput]) and reads **stdout** of a real
 * interactive process in the agent's git-worktree (interim launch = `bash -l`). Opening it is **arbitrary code
 * execution in the repo working tree** — a WRITE, not a read. It must therefore be authorized at write-tier, NOT
 * through the read-tier resolver [wsReaderOrNull] (which admits a human MEMBER session → identityId and a
 * participant token → read-subject; both are read-only classes that must NOT reach an interactive shell).
 *
 * **Policy today = operator-only, built as a delegable capability (Auftraggeber CYP-394).** The gate is a
 * capability check [mayOpenTerminal] over a [TerminalGrantStore]:
 *  - **operator ALWAYS true, store-independent** — an operator token or a verified OPERATOR Kratos session opens
 *    any agent's terminal regardless of the store (an empty store must not lock the operator out).
 *  - **every other principal is default-DENY** — a non-operator ("delegable") principal is admitted ONLY if the
 *    store grants it; the production store ([NoTerminalGrants]) is empty, so today every non-operator is denied
 *    fail-closed. A later per-agent member-grant is then **additive** (a real store + an operator-only grant
 *    endpoint), NOT a gate rewrite.
 *  - **revoke kills the live session** — a grant revoked while a shell is open must END the running session
 *    server-side (ACL-takes-effect-now, like the comm gate), so [terminalSocket] registers each delegable
 *    session's server-side kill-switch via [TerminalGrantStore.track]; a future revoke fires it.
 */

/** The resolved caller of a `/ws/terminal` connection, reduced to what the capability check needs. */
sealed interface TerminalPrincipal {
    /** An operator token OR a verified OPERATOR Kratos session — full terminal access, store-independent. */
    data object Operator : TerminalPrincipal

    /**
     * Any OTHER authenticated principal (a human MEMBER, a participant token, an agent token): a **delegable**
     * subject, DENIED today (the store is empty) and grantable later per-agent. [subject] is the stable grant
     * key: a human MEMBER's bare `identityId` (the id an operator would grant a terminal to), or a namespaced
     * `participant:<subject>` / `agent:<id>` for the machine classes (namespaced so a member-grant keyed by a
     * bare identityId can never accidentally match a machine principal — fail-closed).
     */
    data class Delegable(val subject: String) : TerminalPrincipal
}

/**
 * CYP-394 — the per-agent terminal-access grant store (delegable capability). **Fail-closed from day one** so a
 * later member-delegation lands additively:
 *  - [mayOpen] is **default-DENY**: no grant ⇒ `false`, never a permissive default. It is consulted ONLY for a
 *    non-operator (delegable) principal — operators bypass it in [mayOpenTerminal].
 *  - [track] registers a live delegable session's server-side kill-switch keyed by (agentId, subject); a future
 *    revoke of that grant MUST invoke every tracked kill so the running shell ends immediately (not just the
 *    next connect). The returned handle deregisters the kill on the session's normal close.
 *
 * Production wires [NoTerminalGrants] (empty — operator-only). A real store + an operator-only grant endpoint
 * are the additive follow-up; this interface is the seam they slot into without touching the gate.
 */
interface TerminalGrantStore {
    /** May this NON-operator [subject] open [agentId]'s terminal? Default-DENY: `false` unless explicitly granted. */
    fun mayOpen(agentId: String, subject: String): Boolean

    /**
     * Register a live delegable terminal session's server-side kill-switch. A revoke of (agentId, subject) MUST
     * call [kill] so the open shell is torn down server-side. Returns a handle that deregisters [kill] on the
     * session's normal close (so a later revoke never fires a stale kill).
     */
    fun track(agentId: String, subject: String, kill: () -> Unit): AutoCloseable
}

/**
 * The production grant store: **empty**, so terminal access is operator-only. Every non-operator is denied and
 * nothing is ever tracked (no grants exist to revoke). The kill-on-revoke seam lives in [terminalSocket] +
 * [TerminalGrantStore.track]; when a real grant store lands it implements grant + revoke → the tracked kills fire.
 */
object NoTerminalGrants : TerminalGrantStore {
    override fun mayOpen(agentId: String, subject: String): Boolean = false
    override fun track(agentId: String, subject: String, kill: () -> Unit): AutoCloseable = AutoCloseable {}
}

/**
 * The `/ws/terminal` capability check (CYP-394). **Operator ⇒ always allowed, independent of [grants]** (an empty
 * store must never lock the operator out); every other principal is allowed **only** with an explicit per-agent
 * grant (default-DENY). Single-sourced so the gate and its tooth read the same predicate.
 */
fun mayOpenTerminal(agentId: String, principal: TerminalPrincipal, grants: TerminalGrantStore): Boolean =
    when (principal) {
        TerminalPrincipal.Operator -> true
        is TerminalPrincipal.Delegable -> grants.mayOpen(agentId, principal.subject)
    }

/**
 * Resolve the `/ws/terminal` caller to a [TerminalPrincipal], or null (unauthenticated → the socket closes
 * fail-closed). A token arrives via `Authorization: Bearer` OR the `?token=` query (a browser WebSocket can't
 * set the Authorization header); the human axis is the Kratos session (cookie / `X-Session-Token`).
 *
 * Operator token / verified OPERATOR session → [TerminalPrincipal.Operator]. Every other authenticated caller →
 * [TerminalPrincipal.Delegable] with its stable grant subject (member = bare identityId, participant/agent =
 * namespaced) — denied today, grantable later. Unknown/absent credential → null.
 */
suspend fun ApplicationCall.terminalPrincipalOrNull(deps: AuthDeps, registry: TokenRegistry): TerminalPrincipal? {
    val token = bearerToken() ?: request.queryParameters["token"]
    if (registry.isOperator(token)) return TerminalPrincipal.Operator
    registry.agentFor(token)?.let { return TerminalPrincipal.Delegable("agent:$it") }
    deps.participantTokens.subjectFor(token)?.let { return TerminalPrincipal.Delegable("participant:$it") }
    // No machine token → the human session axis (resolvePrincipal reads the cookie / X-Session-Token).
    return when (val p = resolvePrincipal(deps)) {
        AuthPrincipal.MachineOperator -> TerminalPrincipal.Operator // (token already handled above; defensive)
        is AuthPrincipal.Human ->
            if (p.role == AuthRole.OPERATOR) TerminalPrincipal.Operator
            else TerminalPrincipal.Delegable(p.identityId) // bare identityId = the member-grant key
        is AuthPrincipal.MachineAgent -> TerminalPrincipal.Delegable("agent:${p.agentId ?: "unknown"}")
        null -> null
    }
}
