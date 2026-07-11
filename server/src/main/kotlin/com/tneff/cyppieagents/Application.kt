package com.tneff.cyppieagents

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
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // CYP-415 (D6): the HTTP/WS bind is config-driven (`hub.port`/`hub.host`, defaults 8787/127.0.0.1) instead
    // of compile-time constants. Loaded here so `embeddedServer` can read them before it binds; `bootPlatform`
    // re-loads the same file for the rest of the wiring (cheap, single source of truth = the file).
    val config = PlatformConfig.load(configFile)
    embeddedServer(Netty, port = config.hub.port, host = config.hub.host) {
        bootPlatform(configFile, gitRoot, scope)
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