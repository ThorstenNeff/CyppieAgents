package com.tneff.cyppieagents

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reviewer Gate #5: single-flight per session — a turn injection never races a running turn. */
class SessionTurnQueueTest {

    @Test
    fun turnsForSameSessionRunSerially() = runBlocking {
        val queue = SessionTurnQueue()
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val jobs = (1..6).map {
            launch(Dispatchers.Default) {
                queue.runTurn("session-A") {
                    val now = active.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, now) }
                    delay(15)
                    active.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        // Never two turns of the same session in flight at once.
        assertEquals(1, maxConcurrent.get())
    }

    @Test
    fun differentSessionsAreNotBlocked() = runBlocking {
        val queue = SessionTurnQueue()
        val hold = CompletableDeferred<Unit>()

        // Hold session-A's turn open.
        val a = launch(Dispatchers.Default) { queue.runTurn("session-A") { hold.await() } }
        while (!queue.isBusy("session-A")) yield()

        // session-B must still complete while A is held.
        var bDone = false
        queue.runTurn("session-B") { bDone = true }
        assertTrue(bDone)

        hold.complete(Unit)
        a.join()
    }
}
