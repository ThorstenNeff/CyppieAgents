package com.tneff.cyppieagents

import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Comm-Hub core (S4): REST + ACL enforcement + JSON error envelope.
    installComm(CommConfig.dev())
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
    }
}