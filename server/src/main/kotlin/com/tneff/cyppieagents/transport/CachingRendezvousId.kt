package com.tneff.cyppieagents.transport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CYP-526 — caches the hub's rendezvous id so the reconnect loop **reuses** it instead of re-registering. This is the
 * ANTI-ROTATION invariant: `LiveRelayRendezvous.register` mints a FRESH epoch every call (unlinkability, CYP-501 §2),
 * so re-registering per re-dial would rotate the id and strand the client's already-resolved id (no pairing at the
 * relay). Register ONCE (the first successful [get]); reuse the cached id on every reconnect. Purely hub-side — the
 * client resolves once and stays paired, no client change.
 *
 * [invalidate] forces a re-register on the next [get] — reserved for a future rendezvous-TTL (the design flag; no CP
 * "binding gone" signal exists today, so it is unused in the MVP transport proof).
 */
class CachingRendezvousId(private val register: suspend () -> String?) {
    private val mutex = Mutex()
    private var cached: String? = null

    /** The cached id, registering ONCE on the first successful call; a failed register leaves the cache empty so the
     *  next reconnect retries the register (never caches a `null`). */
    suspend fun get(): String? = mutex.withLock {
        cached ?: register()?.also { cached = it }
    }

    suspend fun invalidate() = mutex.withLock { cached = null }
}

/**
 * CYP-536 (M2 Option A, WS1) — the N-tunnel analogue of [CachingRendezvousId]: caches the hub's rendezvous-id **SET**
 * (the epoch-derived `[id_0..id_{cap-1}]`, C4 develop `889e6919`) so the N concurrent responders and their reconnect
 * loops **reuse** the same set instead of re-registering. Same ANTI-ROTATION invariant: a re-register mints a fresh
 * epoch → a different set → the client's already-resolved set would no longer pair. Register ONCE (the first
 * successful [get]); reuse the cached set on every reconnect. A failed/empty register is never cached (the next
 * attempt retries). Backs the [SessionRendezvousSource] seam.
 */
class CachingRendezvousIdSet(private val registerSet: suspend () -> List<String>?) {
    private val mutex = Mutex()
    private var cached: List<String>? = null

    /** The cached set, registering ONCE on the first successful (non-null, non-empty) call; never caches null/empty. */
    suspend fun get(): List<String>? = mutex.withLock {
        cached ?: registerSet()?.takeIf { it.isNotEmpty() }?.also { cached = it }
    }

    suspend fun invalidate() = mutex.withLock { cached = null }
}
