package com.tneff.cyppieagents.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-240 (B) — **in-flight request coalescing** over an [IdentityProvider]. The public tokenless SPA fires
 * ~4 shell reads concurrently on load; each takes the session axis → one live Kratos `whoami` per request,
 * with NO shared resolution. A transient Kratos blip (cold start / replication lag / a call tipping over the
 * timeout) then 401s a **subset** of the burst → the reported "all shell-loads 401 together, self-heal on
 * reload". This decorator makes the ~N concurrent calls for the **same credential** share **one** in-flight
 * [delegate] resolution, cutting whoami load ~N× — which itself makes the blip far less likely — instead of
 * amplifying it.
 *
 * **No cross-request cache (posture-preserving — the reason the TTL cache was rejected):** the shared entry
 * lives ONLY for the duration of the in-flight resolution; it is evicted the moment that resolution
 * completes. A logout / session-revoke is therefore honored on the very next non-overlapping request
 * (sub-second), exactly like the un-coalesced path — there is no stale-auth TTL window. Both a positive AND a
 * negative result are coalesced in-flight (a burst on an invalid session does one whoami, all get the same
 * null → all 401 — correct), but neither is retained past the window.
 *
 * The shared resolution runs on an app-lifetime [scope] (a supervised scope, NOT any single request's
 * coroutine), so if the request that happened to start the resolution is cancelled (client disconnect) the
 * other awaiters still get their answer.
 */
class CoalescingIdentityProvider(
    private val delegate: IdentityProvider,
    private val scope: CoroutineScope,
) : IdentityProvider {

    /** A credential is keyed by value **and** source: the same string in a header vs a cookie is a different
     *  Kratos call (v1.3.0 sends the one matching header), so they must never coalesce into one resolution. */
    private data class Key(val value: String, val source: SessionCredential.Source)

    private val inFlight = ConcurrentHashMap<Key, Deferred<ResolvedIdentity?>>()

    override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
        // No credential (or a blank one) → the delegate returns null anyway; keep it off the map (no null key,
        // no pointless shared entry, and the fail-closed contract is preserved verbatim).
        if (credential == null || credential.value.isBlank()) return delegate.resolve(credential)
        val key = Key(credential.value, credential.source)
        // computeIfAbsent is atomic: concurrent callers for the same key get the SAME Deferred (one whoami).
        // The resolution runs on [scope] (not the caller's coroutine) so a cancelled caller can't kill it for
        // the others. The mapping lambda does NOT touch `inFlight` — eviction happens in the awaiter's `finally`
        // below, NEVER inside computeIfAbsent: an instant delegate can complete the async synchronously, and a
        // map mutation from within computeIfAbsent is a ConcurrentHashMap "Recursive update" → corruption.
        val shared = inFlight.computeIfAbsent(key) { scope.async { delegate.resolve(credential) } }
        try {
            return shared.await()
        } finally {
            // Evict once the first awaiter is done → the entry never outlives the in-flight window (no
            // cross-request cache → logout/revoke honored sub-second). `remove(key, shared)` is value-checked:
            // a no-op if a newer resolution already replaced it, so a later burst is never joined to a stale one.
            inFlight.remove(key, shared)
        }
    }
}
