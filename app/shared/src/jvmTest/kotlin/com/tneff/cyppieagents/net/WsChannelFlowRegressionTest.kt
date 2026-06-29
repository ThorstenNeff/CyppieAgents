package com.tneff.cyppieagents.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CYP-115 **mechanism** regression. The WS adapters emit from inside `client.webSocket { … }`, whose body runs
 * on the engine dispatcher. On Darwin that dispatcher (IO MultiWorker) differs from the collector
 * (`Main.immediate`), so a plain `flow { emit() }` throws `IllegalStateException: Flow invariant is violated` —
 * which the adapters' `catch(Throwable)` swallowed → graceful 1000 close → `reconnecting()` churn. The fix is
 * `channelFlow { send() }`, which permits cross-context emission.
 *
 * This proves the MECHANISM on the JVM (cross-context emit via `withContext`). It does NOT reproduce the
 * Darwin-specific dispatcher mismatch — the JVM/CIO engine co-locates the webSocket body with the collector,
 * so production JVM never churned (hence `CommWsChurnReproTest` is clean). The real proof remains iOS-dev's
 * Darwin re-verify (liveness + one stable session per socket).
 */
class WsChannelFlowRegressionTest {

    @Test
    fun channelFlow_allowsCrossContextEmission() = runBlocking {
        val out = channelFlow {
            // The WS-adapter shape: emit from a different dispatcher than the collector.
            withContext(Dispatchers.Default) {
                send("connected")
                send("event")
            }
        }.toList()
        assertEquals(listOf("connected", "event"), out, "channelFlow must deliver cross-context sends")
    }

    @Test
    fun plainFlow_throwsTheInvariantViolation_thatWasSwallowed() = runBlocking {
        // The exact failure the adapters used to hit — and silently swallow — on Darwin.
        assertFailsWith<IllegalStateException> {
            flow {
                withContext(Dispatchers.Default) { emit("x") }
            }.toList()
        }
        Unit
    }
}
