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
