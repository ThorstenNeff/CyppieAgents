package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    override fun events(): Flow<CommLiveEvent> = flow {
        try {
            client.webSocket(
                urlString = commUrl(),
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                emit(CommLiveEvent.Connected)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        when (val event = CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())) {
                            is MessageEvent -> emit(CommLiveEvent.MessageReceived(event.message))
                            is ChannelsEvent -> emit(CommLiveEvent.ChannelsChanged(event.channels))
                            is AclEvent -> Unit // consumed by the ACL-matrix UI (S7), not the timeline
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Connection failed/dropped — fall through to Disconnected instead of crashing the panel.
        }
        // Always end honestly: the timeline stops claiming "live" once the socket is gone (CYP-17 §5).
        emit(CommLiveEvent.Disconnected)
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
