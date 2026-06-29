package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.reconnecting
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-115 diagnosis (global/commonMain vs iOS-Darwin): a `/ws/comm`-like server that sends the initial
 * snapshot then **holds the socket open + idle** (no further frames, never closes) — exactly the reported
 * condition ("Server hält offen, alle Closes NORMAL"). The client, wrapped in `.reconnecting()` like
 * `CommViewModel`, should keep ONE stable session (one `Connected`). If the JVM/CIO engine (the Desktop
 * engine) churns this idle socket, the reconnect count climbs → the bug reproduces on Desktop = GLOBAL.
 */
class CommWsChurnReproTest {

    @Test
    fun idleHeldOpenSocket_doesNotChurn_onJvmEngine() = runBlocking {
        val snapshot = CommJson.encodeToString(
            CommWsServerEvent.serializer(),
            ChannelsEvent(listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))),
        )
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/comm") {
                    send(Frame.Text(snapshot))
                    for (frame in incoming) { /* hold open + idle: never push, never close */ }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = CommWsClient(client, "ws://127.0.0.1:$port", token = "op")
                var connectedCount = 0
                // Fast backoff so churn (if any) accumulates quickly within the observation window.
                val job = launch {
                    ws.events().reconnecting(Backoff(initialMs = 50L, maxMs = 100L)).collect {
                        if (it is CommLiveEvent.Connected) connectedCount++
                    }
                }
                delay(1_500)
                job.cancel()
                // One stable session = no churn. >1 here means the idle held-open socket reconnects on the
                // JVM engine → the bug is global/commonMain, not Darwin-specific.
                assertEquals(1, connectedCount, "idle held-open /ws/comm socket churned $connectedCount× on the JVM engine")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
