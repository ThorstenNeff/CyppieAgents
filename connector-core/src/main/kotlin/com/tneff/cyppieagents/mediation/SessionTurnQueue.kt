package com.tneff.cyppieagents.mediation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-flight per session (Reviewer Gate #5). A user-message must not be injected into a CLI
 * session while a turn is already in flight — that races the running turn (untested CLI behaviour,
 * CYP-5). This serializes turns per session: a second [runTurn] for the same session suspends
 * until the first completes. Different sessions run concurrently.
 */
class SessionTurnQueue {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(sessionId: String): Mutex =
        mutexes.getOrPut(sessionId) { Mutex() }

    /** Runs [block] holding the session's turn lock; queues if a turn is already running. */
    suspend fun <T> runTurn(sessionId: String, block: suspend () -> T): T =
        mutexFor(sessionId).withLock { block() }

    /** True while a turn is in flight for [sessionId] (for observability/tests). */
    fun isBusy(sessionId: String): Boolean = mutexes[sessionId]?.isLocked == true

    fun forget(sessionId: String) {
        mutexes.remove(sessionId)
    }
}
