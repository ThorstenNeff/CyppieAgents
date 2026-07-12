package com.tneff.cyppieagents.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * CYP-484 (② Session-Lifetime & Revocation, Decision 4) — the registry of **live, RR3-authenticated tunnel sessions**.
 * It holds a close-handle per active tunnel keyed by the authenticated `operatorId`, so a **revocation** can
 * immediately tear down every active relay session for that operator — dropping the live tunnel over the existing
 * relay connection, with **NO per-request phone-home** to the Control Plane (offline verification R1-A is unaffected;
 * a revocation is a rare push, not a hot-path check). The passive [Op-Session-TTL] teardown lives in the tunnel
 * handler; this registry provides the **active** teardown path. Thread-safe; INERT until a live relay session exists.
 */
class TunnelSessionRegistry {
    private class Session(val id: Long, val operatorId: String, val close: () -> Unit)

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val seq = AtomicLong()

    /** Register a live session; returns its id (pass to [unregister] when the tunnel ends). [close] drops the tunnel. */
    fun register(operatorId: String, close: () -> Unit): Long {
        val id = seq.incrementAndGet()
        sessions[id] = Session(id, operatorId, close)
        return id
    }

    fun unregister(id: Long) { sessions.remove(id) }

    /**
     * ★ Decision 4 — **immediate revocation teardown**: drop EVERY active tunnel for [operatorId] right now (no
     * phone-home, no waiting for the passive TTL). Returns the number torn down. Idempotent; a session that ends
     * concurrently is simply absent.
     */
    fun revokeOperator(operatorId: String): Int {
        val victims = sessions.values.filter { it.operatorId == operatorId }
        victims.forEach {
            runCatching { it.close() }
            sessions.remove(it.id)
        }
        return victims.size
    }

    fun activeCount(): Int = sessions.size
}
