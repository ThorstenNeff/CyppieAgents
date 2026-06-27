package com.tneff.cyppieagents

import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.bootHost
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
    val configFile = File(System.getenv("PLATFORM_CONFIG") ?: "platform.config.json")
    val gitRoot = File(System.getenv("PLATFORM_GIT_ROOT") ?: ".cyppie")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    embeddedServer(Netty, port = 8787, host = bootHost) {
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