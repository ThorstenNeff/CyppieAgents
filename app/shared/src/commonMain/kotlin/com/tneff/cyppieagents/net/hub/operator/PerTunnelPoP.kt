package com.tneff.cyppieagents.net.hub.operator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-538 (WS3, Epic CYP-427 M2-A · contract §4 **C1**, locked @ `889e6919`) — PoP-per-tunnel **UV-caching** +
 * per-tunnel PoP derivation.
 *
 * **The problem (F-M2-1 auth cost-driver):** Option A opens N Noise tunnels, each PoP-authenticated. The existing
 * per-tunnel authenticator ([ClientOperatorAuth]) calls [OperatorDeviceKeyStore.sign], and `sign` performs a **UV
 * inside every call** → N tunnels would trigger **N user-verification prompts**.
 *
 * **The C1 answer (this file):** the UV is a **presence/access-gate performed ONCE per session and cached**; the N
 * per-tunnel PoPs are then plain signs over each tunnel's own caller-assembled challenge (no extra prompts).
 * Realized as a bounded-window decorator on the existing [UserVerification] seam ([CachingUserVerification]) + a thin
 * [PerTunnelPoPProvider] — **zero change to the coupled-core** ([OperatorDeviceKeyStore]/[OperatorPopBuilder]/
 * [ClientOperatorAuth]); the store's literal "UV-then-sign, never silent" invariant is preserved (the FIRST verify is
 * a real prompt).
 *
 * **My surface is deliberately narrow (locked C1 + PO1 refinements): a binding-agnostic PURE SIGNER.**
 *  - **WS2 owns challenge assembly** (it holds `h_i`/`nonce_i`/`cpJwt_i`, so nonce↔sig stay coupled); this provider
 *    signs whatever channel-bound `challenge` bytes it is handed and **does not construct the binding**. The per-tunnel
 *    anti-replay (R1/R2/R3) lives in `h_i`+`nonce_i` **inside** those bytes and is server/WS2-guarded — structurally
 *    unreachable to get wrong here.
 *  - **`rvid` is correlation/logging ONLY — crypto-inert, it MUST NOT enter the signature.**
 *  - **UV-cache = presence/access-gate ONLY, never a signature substitute:** one ceremony unlocks the key; each tunnel
 *    still gets its OWN plain sign over its OWN challenge. Only the UV is cached — **never a signature** (a reused PoP
 *    across tunnels is the forbidden "cached-UV = unbounded reuse").
 *  - **bounded reuse:** the cache expires ([UvAssertion.expiresAtMs]); the provider is session-scoped (a new session =
 *    a fresh provider = an empty cache).
 *  - **fail-closed:** UV denied / no device enrolled / no authenticator ⇒ **no PoP, never a fallback**; [openSession]
 *    returns `null`. Side-effect-free — `popFor` returns the signature for the caller to send as the pre-first-byte
 *    authenticated frame; it sends nothing itself.
 */

/**
 * A cached user-verification assertion: proof the operator was UV-verified at [verifiedAtMs], reusable to **unlock
 * signing** (never as a signature) only within the **bounded** window ending at [expiresAtMs] (C1: cached-UV is NOT
 * an unbounded reuse window). [reason] pins what the single prompt was for.
 */
data class UvAssertion(
    val reason: UvReason,
    /** Monotonic-ms at which the single UV prompt succeeded. */
    val verifiedAtMs: Long,
    /** Monotonic-ms past which the cache is invalid — the bounded reuse window (never unbounded). */
    val expiresAtMs: Long,
) {
    /** Still within the bounded window? Half-open: exactly-at-expiry is already invalid (fail-closed edge). */
    fun validAt(nowMs: Long): Boolean = nowMs < expiresAtMs
}

/**
 * The fail-closed outcome of a per-tunnel PoP derivation (locked C1: `Ready(bytes) | UvFailed | NotEnrolled |
 * Unavailable`). C1 **mandates fail-closed** and a bare `ByteArray` has no fail-closed channel, so this is a Result
 * type mirroring the existing [PopBuildOutcome] taxonomy — and it preserves the CYP-525 honesty rail (a local UV
 * failure is retryable, NOT a hub reject). Client-side seam only (WS3↔WS2, feeds the CYP-533 per-tunnel chrome);
 * the WS1↔WS3 **wire** is unchanged (the server still sees a valid signature or nothing → RST).
 */
sealed interface PerTunnelPopOutcome {
    /** Signed: the raw PoP bytes (`Ed25519.sign(deviceKey, challenge)`) over THIS tunnel's caller-assembled challenge. */
    data class Ready(val pop: ByteArray) : PerTunnelPopOutcome {
        override fun equals(other: Any?): Boolean = this === other || (other is Ready && pop.contentEquals(other.pop))
        override fun hashCode(): Int = pop.contentHashCode()
    }
    /** Local user-verification failed — RETRYABLE (wrong PIN / cancelled). Never a hub reject; nothing is sent. */
    data class UvFailed(val reason: UvFailReason) : PerTunnelPopOutcome
    /** No operator device-key enrolled on this device → the enroll flow, not an auth failure (checked before UV). */
    data object NotEnrolled : PerTunnelPopOutcome
    /** No authenticator available here → fail-closed (the caller RSTs this tunnel; never a fallback). */
    data object Unavailable : PerTunnelPopOutcome
}

/**
 * Locked C1 seam (WS1 server-verify ↔ WS3 client-PoP): `openSession()` runs the one UV ceremony; `popFor` derives a
 * per-tunnel PoP by **purely signing** the caller-assembled [challenge]. The exact challenge byte-format is the
 * published RR3 PoP (`:core operatorAuthChallenge`, CYP-473) — `LP(h_i)‖LP(hubId)‖LP(nonce_i)‖LP("operator-auth")` —
 * but this provider treats it as opaque bytes to sign, so the `h_i` binding cannot be got wrong here.
 */
interface PerTunnelPoPProvider {
    /**
     * The **one UV ceremony per session** (a real presence prompt). On success returns the cached [UvAssertion] that
     * unlocks the N subsequent [popFor] signs within its bounded window; on UV denial / no authenticator returns
     * `null` (fail-closed — the caller aborts before dialing tunnels). Idempotent within the window: a second call
     * returns the still-valid assertion **without** re-prompting.
     */
    suspend fun openSession(): UvAssertion?

    /**
     * Derive tunnel [rvid]'s PoP by signing its caller-assembled channel-bound [challenge]. [rvid] is
     * **correlation/logging ONLY and never enters the signature**. Reuses the open UV gate (one ceremony → N signs);
     * if no gate is open yet it opens one (still exactly one ceremony). Fail-closed per outcome.
     */
    suspend fun popFor(rvid: String, challenge: ByteArray): PerTunnelPopOutcome

    /** The currently-cached **and still-valid** UV assertion, or `null` (none yet / expired). Pure observability for
     *  the WS5/CYP-533 status chrome — the read re-checks the bounded window, never returns a stale grant. */
    val cachedUv: UvAssertion?
}

/**
 * CYP-538 — the UV-caching engine: a **bounded-window** decorator on the [UserVerification] seam.
 *
 * The FIRST [verify] delegates to the real [delegate] (a real prompt — user presence, **never silent**) and caches
 * a `Verified` for [reuseWindowMs]. Within the window, [verify] replays the cached `Verified` **without** re-prompting
 * (so N per-tunnel derivations cost ONE prompt). Past the window the cache is dropped and the next [verify]
 * re-prompts (bounded reuse — never unbounded). A non-`Verified` outcome is **never** cached (fail-closed: a denial
 * must not silently satisfy the next tunnel).
 *
 * Concurrency: a [Mutex] serializes the pool's concurrent per-tunnel derivations on the UV — the first caller
 * prompts and caches; the rest await and hit the fresh cache → still **exactly one** prompt, never a torn double-prompt.
 *
 * @param nowMs a monotonic millisecond source (jvm wiring: `System.nanoTime() / 1_000_000`; tests inject a counter).
 *   Monotonic (not wall-clock) so a clock adjustment cannot silently widen the bounded window.
 */
class CachingUserVerification(
    private val delegate: UserVerification,
    private val reuseWindowMs: Long,
    private val nowMs: () -> Long,
) : UserVerification {

    private val mutex = Mutex()
    private var cached: UvAssertion? = null

    /** The cached assertion iff still within the bounded window — else `null` (the read re-validates, never stale). */
    val cachedAssertion: UvAssertion?
        get() = cached?.takeIf { it.validAt(nowMs()) }

    override suspend fun verify(reason: UvReason): UvOutcome = mutex.withLock {
        val now = nowMs()
        // A valid cached assertion for the SAME reason satisfies without a prompt (the bounded-reuse access-gate hit).
        cached?.let { if (it.reason == reason && it.validAt(now)) return@withLock UvOutcome.Verified }
        // Expired / none / different reason → a real prompt (never silent).
        when (val out = delegate.verify(reason)) {
            UvOutcome.Verified -> {
                cached = UvAssertion(reason, verifiedAtMs = now, expiresAtMs = now + reuseWindowMs)
                UvOutcome.Verified
            }
            // Fail-closed: a denial / unavailability clears any prior cache and is NEVER cached — the next tunnel
            // re-prompts rather than inheriting a stale grant.
            else -> {
                cached = null
                out
            }
        }
    }
}

/**
 * CYP-538 (WS3) — the real UV-caching [PerTunnelPoPProvider]. Signs each tunnel's caller-assembled challenge via the
 * injected [store] behind [cachingUv] (one UV/session, bounded window). Caches **only the UV, never a signature**:
 * every [popFor] re-signs its distinct per-tunnel challenge. `rvid` is correlation-only and never touches the crypto.
 *
 * Wire [store] and [cachingUv] so the store's [UserVerification] **is** [cachingUv] (else the cache would not gate the
 * store's UV) — use [wrap] to single-source that binding and make drift impossible.
 */
class UvCachingPerTunnelPoPProvider(
    private val store: OperatorDeviceKeyStore,
    private val cachingUv: CachingUserVerification,
) : PerTunnelPoPProvider {

    override val cachedUv: UvAssertion? get() = cachingUv.cachedAssertion

    override suspend fun openSession(): UvAssertion? =
        // The one ceremony: prompt (or reuse an unexpired gate) → the assertion that unlocks the N signs, else null.
        when (cachingUv.verify(UvReason.OPERATOR_AUTH)) {
            UvOutcome.Verified -> cachingUv.cachedAssertion
            else -> null // UV denied / no authenticator ⇒ fail-closed, session not opened.
        }

    override suspend fun popFor(rvid: String, challenge: ByteArray): PerTunnelPopOutcome =
        // store.sign = UV-then-sign; with cachingUv the UV is one-prompt-per-bounded-window. The signature is over
        // THIS tunnel's caller-assembled challenge ONLY and is never reused across tunnels (only the UV is cached).
        // rvid is intentionally unused here — it is correlation-only and MUST NOT enter the signature.
        when (val r = store.sign(challenge)) {
            is PopResult.Signed -> when (val pop = r.pop) {
                // RR3/MVP branch = raw Ed25519 signature bytes (the locked C1 transcript).
                is DevicePoP.Raw -> PerTunnelPopOutcome.Ready(pop.signature)
                // Platform-authenticator (Fido2) PoP cannot be expressed as a single raw-signature `Ready(bytes)` in
                // the RR3 seam (its verification also needs authenticatorData) → fail-closed here rather than emit an
                // under-specified frame. Progressive enhancement is a later extension (macOS/Win), not the MVP path.
                is DevicePoP.Fido2 -> PerTunnelPopOutcome.Unavailable
            }
            is PopResult.UvFailed -> PerTunnelPopOutcome.UvFailed(r.reason)
            PopResult.NotEnrolled -> PerTunnelPopOutcome.NotEnrolled
            PopResult.AuthenticatorUnavailable -> PerTunnelPopOutcome.Unavailable
        }

    companion object {
        /**
         * Single-source the UV-caching wiring: builds ONE [CachingUserVerification] over the raw [rawUv] and threads
         * it into **both** the store (via [storeFor]) and this provider, so the store's UV and the provider's cache
         * can never drift onto two different instances. [reuseWindowMs] is the bounded reuse window; [nowMs] a
         * monotonic-ms source.
         */
        fun wrap(
            rawUv: UserVerification,
            reuseWindowMs: Long,
            nowMs: () -> Long,
            storeFor: (UserVerification) -> OperatorDeviceKeyStore,
        ): UvCachingPerTunnelPoPProvider {
            val caching = CachingUserVerification(rawUv, reuseWindowMs, nowMs)
            return UvCachingPerTunnelPoPProvider(storeFor(caching), caching)
        }
    }
}
