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
 * CYP-421 (c) — the operator-only ADMIN side of the grant store (behind PUT/DELETE/GET
 * `/api/agents/{id}/terminal-grants`), separate from the [TerminalGrantStore] READ side ([mayOpen]/[track]) that
 * the socket uses. A **null** admin at the route means the delegation flag is OFF → the endpoint denies
 * (fail-closed). Only the real [InMemoryTerminalGrants] implements it.
 */
interface TerminalGrantAdmin {
    /** Grant [subject] a terminal on [agentId] (idempotent). Returns the agent's full granted-subject set after. */
    fun grant(agentId: String, subject: String): List<String>
    /** Revoke [subject]'s grant on [agentId] AND end any live shell it holds (kill-on-revoke). Returns the set after. */
    fun revoke(agentId: String, subject: String): List<String>
    /** The subjects currently granted a terminal on [agentId] (sorted, stable). */
    fun listGrants(agentId: String): List<String>
}

/**
 * CYP-421 (c) — the real in-memory grant store: default-DENY [mayOpen], live-session [track], and the operator
 * [TerminalGrantAdmin] side. Wired ONLY when `CYPPIE_TERMINAL_DELEGATION_ENABLED` is on (c2); otherwise the
 * platform keeps [NoTerminalGrants] and this is never constructed.
 *
 * **c1 — the CYP-394 admit→track TOCTOU, closed.** A revoke landing in the window between the socket's admit
 * ([mayOpenTerminal] → [mayOpen] == true) and its [track] registration would naively fire NO kill (the kill isn't
 * registered yet) → the just-opened shell survives a revoke. Closed under ONE [lock]: [track] registers the kill
 * AND re-checks the grant atomically — if the grant is already gone (revoke won the race) it does NOT register and
 * signals the caller to kill NOW; [revoke] removes the grant AND snapshots the live kills atomically. Either
 * interleaving ends the shell (no lost revoke).
 */
class InMemoryTerminalGrants : TerminalGrantStore, TerminalGrantAdmin {
    private val lock = Any()
    private val granted = HashMap<String, MutableSet<String>>()               // agentId -> granted subjects
    private val live = HashMap<Pair<String, String>, MutableList<() -> Unit>>() // (agentId,subject) -> live kills

    override fun mayOpen(agentId: String, subject: String): Boolean =
        synchronized(lock) { granted[agentId]?.contains(subject) == true }

    override fun track(agentId: String, subject: String, kill: () -> Unit): AutoCloseable {
        val registered = synchronized(lock) {
            if (granted[agentId]?.contains(subject) == true) {
                live.getOrPut(agentId to subject) { mutableListOf() }.add(kill)
                true
            } else {
                false // revoke won the admit→track race: the grant is already gone — do NOT register
            }
        }
        if (!registered) {
            kill() // c1: end the shell the in-window revoke could not yet see
            return AutoCloseable {}
        }
        return AutoCloseable { synchronized(lock) { live[agentId to subject]?.remove(kill) } }
    }

    override fun grant(agentId: String, subject: String): List<String> = synchronized(lock) {
        granted.getOrPut(agentId) { sortedSetOf() }.add(subject)
        granted[agentId]!!.sorted()
    }

    override fun revoke(agentId: String, subject: String): List<String> {
        val kills = synchronized(lock) {
            granted[agentId]?.remove(subject)
            if (granted[agentId]?.isEmpty() == true) granted.remove(agentId)
            live.remove(agentId to subject).orEmpty().toList()
        }
        kills.forEach { it() } // fire OUTSIDE the lock — a kill's close() re-enters track's deregister (lock)
        return listGrants(agentId)
    }

    override fun listGrants(agentId: String): List<String> =
        synchronized(lock) { granted[agentId]?.sorted() ?: emptyList() }
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
    if (registry.operatorEligible(token)) return TerminalPrincipal.Operator // CYP-828 (§2.1b, seam #2): loopback-gated (PTY take-over off-loopback denied)
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
