package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.LoopbackHubLock
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.bootPlatform
import com.tneff.cyppieagents.routing.installComm
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Production entrypoint (Slice S8): config-driven boot bound to **localhost** (Reviewer #4) using
 * the real [bootPlatform] (production [com.tneff.cyppieagents.routing.tokenAuthorize] over the
 * env-sourced TokenRegistry). Requires `platform.config.json` + per-agent `HUB_TOKEN_*` /
 * `OPERATOR_TOKEN` in the host env (fail-closed); a real agent run also needs `ANTHROPIC_API_KEY`.
 */
fun main() {
    // CYP-206: silence Netty 4.2's JFR buffer-pool telemetry BEFORE any Netty class loads. Netty 4.2 emits
    // JFR events for buffer alloc/free (`io.netty.buffer.FreeChunkEvent` etc., which `extends jdk.jfr.Event`);
    // on a JVM where that class can't be defined (a stripped/quirky jdk.jfr) it throws
    // `NoClassDefFoundError: …FreeChunkEvent` off the critical path (pool metrics only — buffers still work).
    // This is NOT a Netty version mismatch: all `io.netty:*` resolve to a single 4.2.13.Final and the class is
    // present in the jar. The emit sites (`PooledByteBufAllocator`, `AdaptivePoolingAllocator$Chunk`) gate on
    // `PlatformDependent.isJfrEnabled()` (= `jdk.jfr.FlightRecorder.isAvailable()` AND `-Dio.netty.jfr.enabled`,
    // cached once at class-init) and `ifeq`-skip the whole event block when false — so setting this property
    // false here, before `embeddedServer(Netty)` triggers Netty's class-load, guarantees the event classes are
    // never touched. We do not use Netty's JFR pool telemetry, so this is a pure no-op for behaviour.
    if (System.getProperty("io.netty.jfr.enabled") == null) System.setProperty("io.netty.jfr.enabled", "false")

    val configFile = File(System.getenv("PLATFORM_CONFIG") ?: "platform.config.json")
    val gitRoot = File(System.getenv("PLATFORM_GIT_ROOT") ?: ".cyppie")
    // CYP-417 (S-G / D8): a BOUNDED dispatcher for the app scope (was the unbounded Dispatchers.IO) — caps
    // concurrent parallelism so a runaway can't explode threads/memory (productizes the OOM lesson at the scope
    // level, complementing the ResourceGovernor's spawn gate). 64 = Dispatchers.IO's own default ceiling, made
    // explicit; deploy can retune. @OptIn: limitedParallelism.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(64) + SupervisorJob())
    // CYP-415 (D6): the HTTP/WS bind is config-driven (`hub.port`/`hub.host`, defaults 8787/127.0.0.1) instead
    // of compile-time constants. Loaded here so `embeddedServer` can read them before it binds; `bootPlatform`
    // re-loads the same file for the rest of the wiring (cheap, single source of truth = the file).
    val config = PlatformConfig.load(configFile)
    // CYP-811 (PL-0110) — fail-closed-by-construction against the on-loopback multi-hub cookie-jar hole: a hub on a
    // loopback IP takes a box-wide exclusive lock keyed by that IP BEFORE it binds. A 2nd hub on the SAME loopback IP
    // (cookies ignore port → shared jar → cross-hub operator-cookie replay, §9.5) is REJECTED here, loud, at boot. The
    // returned handle is held for the process lifetime (kept referenced so the FileLock is not released/GC'd).
    @Suppress("UNUSED_VARIABLE") val loopbackHubLock = LoopbackHubLock.acquireOrReject(config.hub.host)
    // CYP-427 (M2): TWO connectors on ONE Application (one shared platform / store set — NOT a second
    // installPlatform, which would fork divergent in-memory stores). The tunnel-scoped connector is loopback-only
    // and is the ONLY port the LoopbackBridge dials; installTunnelGodTokenGuard refuses the static operator token
    // on it (port-discriminated, server-side-trusted — the dumb byte-pump stays dumb).
    embeddedServer(
        Netty,
        serverConfig { module { bootPlatform(configFile, gitRoot, scope) } },
    ) {
        connector { port = config.hub.port; host = config.hub.host }   // public: local operator UI + agents
        connector { port = config.hub.tunnelPort; host = "127.0.0.1" } // tunnel-scoped: God-token refused here
    }.start(wait = true)
}

/**
 * Dev/test wiring only (NOT the running server): comm hub with dev defaults, used by the test
 * suite. The production server runs via [main] → [bootPlatform] on [bootHost].
 */
fun Application.module() {
    installComm(CommConfig.dev())
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
    }
}