package com.tneff.cyppieagents.net.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * CYP-647 — the **client mirror** of the server `transport/BridgeBlocking` (CYP-633). The client loopback datapath's
 * socket reads/writes, the `server.accept()` loop, and the pump loops are BLOCKING and long-lived: a per-connection
 * `input.read()` parks a thread for the whole life of a workspace WS, and each `server.accept()` parks one waiting for
 * the next connection. Running them on the shared, **capped** `Dispatchers.IO` (default 64) means ~N threads for N live
 * WS; at N≈30+ the IO dispatcher is exhausted and a further bridge op — or ANY other `Dispatchers.IO` user — starves
 * (the same latent-HIGH thread-model root the server hit; bites before any scale-up / the mux default-flip).
 *
 * Isolating the client bridge's blocking ops onto a **dedicated cached (elastic) daemon pool** removes the contention:
 * it grows to ~N as needed (bounded in practice by the tunnel-pool cap), reaps idle threads, and no longer competes
 * with the rest of the JVM's I/O. `:app/shared` has no `:server` dependency, so this is a module-LOCAL twin of
 * `BridgeBlocking` — the SAME pattern, mirrored here. Threads are **daemon** so a parked (uninterruptible) blocking read
 * can never keep the JVM alive.
 */
object ClientBridgeBlocking {
    val dispatcher: CoroutineDispatcher = Executors.newCachedThreadPool { r ->
        Thread(r, "cyp-client-bridge-io-${threadSeq.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val threadSeq = AtomicLong()
}
