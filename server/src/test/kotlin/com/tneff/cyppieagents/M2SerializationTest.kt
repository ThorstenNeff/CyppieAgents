package com.tneff.cyppieagents

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-145 / M2 — the **three** `sendTurn` injectors that can target the same agent concurrently —
 * the operator WS ([com.tneff.cyppieagents.routing.agentSocket]), the Warden
 * ([com.tneff.cyppieagents.warden.MediatorActuator]), and the [com.tneff.cyppieagents.mediation.MessageDeliverer]
 * drain — all converge on `ConnectorSession.sendTurn`, which runs inside [SessionTurnQueue.runTurn] keyed
 * by the stable agentId. This pins that single-flight: concurrent injections for one session **serialize**
 * (never interleave a half-written turn, never lose a turn). Different sessions stay concurrent.
 *
 * **Mutation:** drop the per-session lock in [SessionTurnQueue.runTurn] (run `block()` unguarded) →
 * the three injectors overlap → `maxConcurrent > 1` → this test reddens.
 */
class M2SerializationTest {

    @Test
    fun concurrentInjectorsForOneSessionSerialize() = runBlocking {
        val queue = SessionTurnQueue()
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val completed = CopyOnWriteArrayList<Int>()

        // Three concurrent injectors (operator WS + Warden actuator + deliverer drain) on the SAME agent.
        val injectors = (1..3).map { i ->
            launch(Dispatchers.Default) {
                queue.runTurn("backend") {
                    val now = active.incrementAndGet()
                    maxConcurrent.updateAndGet { max(it, now) }
                    delay(40) // hold the turn in flight
                    completed.add(i)
                    active.decrementAndGet()
                }
            }
        }
        injectors.joinAll()

        assertEquals(1, maxConcurrent.get(), "turns for one session must never overlap (single-flight, no interleave)")
        assertEquals(3, completed.size, "every injected turn ran — none lost")
    }

    @Test
    fun differentSessionsRunConcurrently() = runBlocking {
        // The lock is PER session — distinct agents are not serialized against each other.
        val queue = SessionTurnQueue()
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val jobs = listOf("po", "frontend", "backend").map { agent ->
            launch(Dispatchers.Default) {
                queue.runTurn(agent) {
                    val now = active.incrementAndGet()
                    maxConcurrent.updateAndGet { max(it, now) }
                    delay(40)
                    active.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        assertEquals(3, maxConcurrent.get(), "different sessions run concurrently (the lock is per-session)")
    }
}
