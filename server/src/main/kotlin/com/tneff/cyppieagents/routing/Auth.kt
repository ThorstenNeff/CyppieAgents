package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.ParticipantPrincipal
import com.tneff.cyppieagents.auth.resolvePrincipal
import com.tneff.cyppieagents.auth.sessionCredential
import com.tneff.cyppieagents.comm.HubState
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.path
import org.slf4j.LoggerFactory

/**
 * MVP auth (Spec 02 §14): a bearer token per agent maps to an agent id; one separate operator token
 * authorizes ACL changes. The server maps token → agentId; the agent's identity is therefore the
 * *token*, never anything the client sends in a body (Reviewer Gate #1).
 *
 * CYP-171 / E2.6 (S3): the map is **runtime-mutable** — boot seeds it from the env ([Secrets.agentTokens])
 * + the persisted remote tokens, and [mint] adds a server-generated per-agent token at runtime (operator
 * pre-provision of a remote/BYOA agent). The lookup semantics are UNCHANGED: [agentFor] stays the SOLE
 * identity source; mint only adds an entry, never a second identity path. Thread-safe.
 */
class TokenRegistry(
    seed: Map<String, String>,
    private val operatorToken: String?,
    /**
     * CYP-828 (god-token invariant §2.1b / v3.3) — the loopback posture (`= isLoopbackHost(config.hub.host)`, the same
     * single source as [com.tneff.cyppieagents.auth.AuthDeps.browserOperatorPostureEnabled] for the cookie axis).
     * ★ **Default `false` = fail-closed** (PL-ruled, mirrors S-AAL2b): a forgotten/future construction must get a
     * VISIBLE over-denial (401), NEVER a silent off-loopback operator grant. Production passes the real value
     * (`BootOrchestrator` / `CommRoutes` wiring); tests that exercise operator behaviour pass `loopbackPosture = true`.
     */
    private val loopbackPosture: Boolean = false,
) {
    private val tokenToAgent = java.util.concurrent.ConcurrentHashMap(seed)

    fun agentFor(token: String?): String? = token?.let { tokenToAgent[it] }

    /** ★ Raw god-token **IDENTITY** (host-independent) — for the reject-guard ([com.tneff.cyppieagents.routing.
     *  installTunnelGodTokenGuard]) and the [mint] collision-check ONLY. It IDENTIFIES the static token; it does NOT
     *  grant. Every god-token→OPERATOR **grant** path MUST route [operatorEligible] instead (the loopback-gated seam). */
    fun isOperator(token: String?): Boolean = operatorToken != null && token == operatorToken

    /**
     * ★ CYP-828 (§2.1b) — the god-token→OPERATOR **ELIGIBILITY** predicate: the raw identity AND the loopback posture.
     * Off-loopback ([loopbackPosture]==false) this is `false` at every consumer — no `MachineOperator`, no
     * `TerminalPrincipal.Operator`, no `/ws/agent` grant, no `OPERATOR_ID`, no `/me`-OPERATOR (fail-closed). ALL five
     * grant seams route this (not [isOperator]); the closure tooth pins that raw [isOperator] is called nowhere else.
     */
    fun operatorEligible(token: String?): Boolean = isOperator(token) && loopbackPosture

    /**
     * CYP-171 — mint a cryptographically-random per-agent bearer token (256-bit `SecureRandom`,
     * base64url), register `token→agentId`, and return it. Server-generated, never client-chosen; the
     * returned value is the credential disclosed once. Regenerates on the (astronomically unlikely)
     * collision with an existing token or the operator token, so the new credential is always unique.
     */
    fun mint(agentId: String): String {
        var token: String
        do {
            token = secureToken()
        } while (tokenToAgent.containsKey(token) || token == operatorToken)
        tokenToAgent[token] = agentId
        return token
    }

    /** CYP-171 — revoke every token bound to [agentId] (idempotent; on agent removal). After this,
     *  `agentFor(<that token>)` is null → the next wire connect closes VIOLATED_POLICY (fail-closed). */
    fun revoke(agentId: String) {
        tokenToAgent.entries.removeIf { it.value == agentId }
    }

    /** CYP-171 — boot load of a persisted `(token→agentId)` binding (the secret-at-rest restore). */
    fun bind(token: String, agentId: String) {
        tokenToAgent[token] = agentId
    }

    private fun secureToken(): String {
        val bytes = ByteArray(32) // 256-bit
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Extracts the bearer token from the Authorization header, or null. */
fun ApplicationCall.bearerToken(): String? {
    val header = request.header(HttpHeaders.Authorization) ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().ifBlank { null }
}

/** Resolves the caller's agent id from the bearer token, or throws 401. */
fun ApplicationCall.requireAgent(registry: TokenRegistry): String {
    val token = bearerToken() ?: throw UnauthorizedException()
    return registry.agentFor(token) ?: throw UnauthorizedException()
}

/**
 * Resolves a token to a comm **participant** id: an agent id, or [HubState.OPERATOR_ID] for the
 * operator token, or null. The operator is a privileged participant in the ACL — not a bypass —
 * so downstream the same [com.tneff.cyppieagents.model.AclMatrix] checks apply (CYP-18).
 */
fun TokenRegistry.participantFor(token: String?): String? =
    // CYP-828 (§2.1b, seam #4): the god-token→OPERATOR_ID grant is loopback-gated via operatorEligible (this extension
    // has `this: TokenRegistry`, so the posture rides the registry — its 4 callers need no threading). Off-loopback the
    // static token maps to null → no OPERATOR_ID → no event-log-egress of message bodies (CYP-432 boundary).
    agentFor(token) ?: if (operatorEligible(token)) HubState.OPERATOR_ID else null

/**
 * CYP-234b — resolve a CYP-234b participant token to its read-SUBJECT, applying the per-subject rate-limit
 * (#7). Returns null if [token] is not a participant token (the caller falls through to the session axis);
 * throws [TooManyRequestsException] (429) if it IS a participant token whose per-subject bucket is empty. The
 * bucket key is the RESOLVED subject (bounded, RC3-safe) — an invalid token resolves to null and never touches
 * the limiter, so the limiter map cannot be grown by unauthenticated traffic.
 */
private fun ApplicationCall.participantSubject(deps: AuthDeps, token: String?): String? {
    val subject = deps.participantTokens.subjectFor(token) ?: return null
    if (!deps.participantRateLimiter.tryAcquire(subject)) throw TooManyRequestsException()
    // CYP-297 (Layer 1, load-bearing): carry the resolved principal under the reserved `participant:` namespace so
    // it can NEVER equal an agentId / OPERATOR_ID / identityId. canRead/canWrite for it is then a distinct,
    // fail-closed-empty row (inherits no foreign grant) — a token minted `subject="operator"`/`subject="<agentId>"`
    // resolves to `participant:operator`/`participant:<agentId>`, not the privileged principal. Applied at THIS one
    // chokepoint, so every read resolver (requireCommReader/requireParticipant/wsReaderOrNull) inherits it.
    return ParticipantPrincipal.of(subject)
}

/**
 * Resolves the caller (agent / operator token, OR a CYP-234b participant token → its read-SUBJECT) for a
 * participant-tier read, or throws 401. The participant token only widens WHO reaches the ACL check; the
 * downstream `canRead` is fail-closed-empty for its subject until granted (never beyond read).
 */
fun ApplicationCall.requireParticipant(deps: AuthDeps): String =
    deps.tokens.participantFor(bearerToken())
        ?: participantSubject(deps, bearerToken()) // CYP-234b: BYO participant token → read-subject (rate-limited)
        ?: throw UnauthorizedException()

/**
 * CYP-186 BE2 — resolve the comm-**READ** participant. Token axis first (agent / operator — unchanged), then a
 * verified **human** session: a human OPERATOR maps to [HubState.OPERATOR_ID] (member-of-all, like the operator
 * token); a human MEMBER maps to their **identityId** — a first-class ACL read-subject, so
 * `acl.canRead(channel, identityId)` is **fail-closed empty** until an OPERATOR grants them per-channel read.
 * The READ vs WRITE distinction is NOT this gate but the downstream ACL check (`canRead` vs `canWrite`); the
 * send path uses [requireCommWriter] (CYP-188), which resolves identically but is gated by `canWrite`.
 */
suspend fun ApplicationCall.requireCommReader(deps: AuthDeps, registry: TokenRegistry): String {
    registry.participantFor(bearerToken())?.let { return it } // agent / operator token — unchanged
    participantSubject(deps, bearerToken())?.let { return it } // CYP-234b: BYO participant token → read-subject
    return when (val p = resolvePrincipal(deps)) {
        is AuthPrincipal.Human -> if (p.role == AuthRole.OPERATOR) HubState.OPERATOR_ID else p.identityId
        else -> throw UnauthorizedException()
    }
}

/**
 * CYP-188 P2b-iii (a) — the comm **WRITE** gate. Resolves the caller **identically to [requireCommReader]** (an
 * agent / operator token, OR a verified human session → [HubState.OPERATOR_ID] / identityId): the READ vs WRITE
 * difference is NOT the gate but the downstream ACL check. [com.tneff.cyppieagents.comm.Hub.postAsAgent] — the
 * **single write chokepoint** — enforces `canWrite(channelId, participant)`: **deny WITHOUT a grant → 403,
 * allow WITH a per-channel `canWrite:true` grant → 201** (a human MEMBER a `PUT /api/acl` operator-granted).
 * The deny is a **uniform 403** — `canWrite` is false for a non-granted AND for a non-existent channel, so there
 * is no 404-vs-403 channel-existence tell. So this gate only widens WHO reaches the chokepoint; the write authz
 * (and its fail-closed uniform deny) stays exactly where it already is.
 */
suspend fun ApplicationCall.requireCommWriter(deps: AuthDeps, registry: TokenRegistry): String {
    registry.participantFor(bearerToken())?.let { return it } // agent / operator token — unchanged
    // CYP-297 (Layer 3): a participant token is READ-tier BY CONSTRUCTION — it NEVER writes, even if an operator
    // mistakenly granted canWrite to its `participant:` principal (Layer 1 already makes accidental collision
    // impossible; this makes tier=READ ENFORCED, not cosmetic). Reject at the WRITE gate before the chokepoint —
    // the send path (POST + WS) is the ONLY writer, so this closes participant-writes everywhere.
    if (deps.participantTokens.resolve(bearerToken()) != null) {
        throw ForbiddenException("participant tokens are read-only", code = "participant_read_only")
    }
    return when (val p = resolvePrincipal(deps)) {
        is AuthPrincipal.Human -> if (p.role == AuthRole.OPERATOR) HubState.OPERATOR_ID else p.identityId
        else -> throw UnauthorizedException()
    }
}

/**
 * CYP-188 B — the WS-handshake read-tier resolver, **non-throwing** (returns null; the caller closes the socket).
 * Same tier as [requireCommReader] — an agent / operator **token** (via `Authorization` OR the `?token=` query,
 * since a browser WebSocket can't set an Authorization header), OR a **verified human session** (the same-origin
 * `ory_kratos_session` cookie on browsers, or `X-Session-Token` natively): OPERATOR → [HubState.OPERATOR_ID],
 * MEMBER → identityId (a first-class ACL read-subject; comm streams stay ACL-`canRead`-filtered, fail-closed
 * empty until granted). READ-ONLY: the send path stays token-only — no session path is weaker than the `/api`
 * guard. Returns null → the WS handler closes VIOLATED_POLICY (no app frame delivered).
 */
/**
 * CYP-607 DIAGNOSTIC INSTRUMENTATION (dogfood 2026-07-15) — TEMPORARY. The remote-tunnel dogfood showed the hub
 * silently `close(1008)`-ing 6-of-7 concurrent inner loopback WS (→ [com.tneff.cyppieagents.transport.LoopbackBridge]
 * `socket.reset()` RST → client "reset by peer" / "server prematurely closed"), with NO log of WHY (all resetting
 * routes share this silent gate). This logs, per WS auth attempt, the ROUTE + which auth AXIS resolved — or, on the
 * reject, NULL + the credential-PRESENCE booleans (which axis was even PRESENT). It **never** logs a token/session
 * VALUE — only the axis name, boolean presence, and the non-secret principal *role*. Because it runs INSIDE the
 * `webSocket{}` block, its presence proves the WS upgrade (101) completed → an ABSENT log for a reset connection is
 * itself the signal the reject was pre-101 (connection-level), not this auth gate. Remove once the root is fixed.
 */
private val wsAuthDiagLog = LoggerFactory.getLogger("cyp607-diag")

suspend fun ApplicationCall.wsReaderOrNull(deps: AuthDeps, registry: TokenRegistry): String? {
    val diagPath = request.path() // CYP-607: route only, never the query (no `?token=` value in the log)
    // CYP-286: a short-lived, single-use `?ticket=` (minted at POST /api/ws-ticket by an already-authenticated
    // caller, bound to its OWN read subject) — consumed ATOMICALLY here so a browser need not carry a long-lived
    // bearer in the loggable `?token=` query. No escalation: it resolves to the subject the minter already had.
    // Tried first (the preferred, exposure-minimising path); an invalid/expired/spent ticket falls through.
    request.queryParameters["ticket"]?.let { deps.wsTickets.consume(it) }?.let {
        wsAuthDiagLog.info("ws-auth OK path={} axis=ticket", diagPath) // CYP-607
        return it
    }
    // CYP-292 (deploy hygiene, human-gated): a `?token=` query is exposure-sensitive (CYP-190 class) — the
    // reverse-proxy access log MUST strip/mask the query before the query-token WS surfaces go public. App-side
    // is clean (no CallLogging; audit uses request.path() sans query), so this is a deploy-path condition only.
    val token = bearerToken() ?: request.queryParameters["token"]
    registry.participantFor(token)?.let {
        wsAuthDiagLog.info("ws-auth OK path={} axis=token(agent/operator)", diagPath) // CYP-607
        return it
    }
    participantSubject(deps, token)?.let { // CYP-234b: participant token via ?token= (browser WS can't set Authorization)
        wsAuthDiagLog.info("ws-auth OK path={} axis=participant-subject", diagPath) // CYP-607
        return it
    }
    return when (val p = resolvePrincipal(deps)) {
        is AuthPrincipal.Human -> {
            wsAuthDiagLog.info("ws-auth OK path={} axis=session role={}", diagPath, p.role) // CYP-607 (role, not identityId)
            if (p.role == AuthRole.OPERATOR) HubState.OPERATOR_ID else p.identityId
        }
        else -> {
            // CYP-607: the SILENT reject the dogfood was blind to — surface WHY without any secret value. The
            // presence booleans discriminate the runtime cause: hasSessionCred=true but session-fell-through ⇒ the
            // whoami returned null/unverified at runtime (Kratos-side); hasSessionCred=false ⇒ the tunnel never
            // forwarded the credential for this connection (client/tunnel side).
            wsAuthDiagLog.warn(
                "ws-auth NULL path={} axis=session-fell-through principal={} hasBearer={} hasQueryToken={} hasSessionCred={}",
                diagPath, p?.let { it::class.simpleName } ?: "null",
                bearerToken() != null, request.queryParameters["token"] != null, sessionCredential() != null,
            )
            null
        }
    }
}
