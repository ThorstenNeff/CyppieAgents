package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.net.logWsError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Live `/ws/comm` adapter (CYP-21 swap, against the CYP-18 frame contract). Decodes each masked
 * `:core` [CommWsServerEvent] frame via [CommJson] and maps it onto the UI [CommLiveEvent] seam — so
 * [CommViewModel]/[CommReducer]/the panel are unchanged from the stub. Replaces [StubCommLiveSource].
 *
 * Contract (CYP-18): `GET {hubWsBaseUrl}/ws/comm?token=<t>` sends an initial [ChannelsEvent] snapshot
 * (readable channels) then live messages/ACL/channel updates, ACL-filtered server-side; idempotency
 * by `message.id` lives in [CommReducer]. The token also goes in the `?token=` query for the browser
 * (WS upgrade headers aren't settable there). [AclEvent] is for the ACL-matrix UI (S7), not the
 * timeline, so it is dropped here.
 */
class CommWsClient(
    private val client: HttpClient,
    private val hubWsBaseUrl: String,
    private val token: String,
) : CommLiveSource {

    override fun events(): Flow<CommLiveEvent> = channelFlow {
        try {
            client.webSocket(
                urlString = commUrl(),
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on
                // Native), so emitting from here is a cross-context send — illegal in flow{} (the ISE behind
                // the CYP-115 Darwin churn) but exactly what channelFlow allows. Element emissions go to
                // this@channelFlow; `incoming` frames stay on the WebSocketSession.
                this@channelFlow.send(CommLiveEvent.Connected)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        when (val event = CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())) {
                            is MessageEvent -> this@channelFlow.send(CommLiveEvent.MessageReceived(event.message))
                            is ChannelsEvent -> this@channelFlow.send(CommLiveEvent.ChannelsChanged(event.channels))
                            is AclEvent -> Unit // consumed by the ACL-matrix UI (S7), not the timeline
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-115: log a real failure instead of masking it as a silent Disconnected (a normal close does
            // not throw). The cross-context ISE that caused the churn was swallowed here before.
            logWsError("comm", e)
        }
        // Always end honestly: the timeline stops claiming "live" once the socket is gone (CYP-17 §5).
        this@channelFlow.send(CommLiveEvent.Disconnected)
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
