package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.net.isAccessRevoked
import com.tneff.cyppieagents.net.logWsError
import com.tneff.cyppieagents.net.readCloseCode
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
 * (WS upgrade headers aren't settable there). [AclEvent] carries no timeline data, but CYP-273/S7 maps it
 * to a content-free [CommLiveEvent.AclChanged] so the VM re-fetches the writable set (composer live).
 */
class CommWsClient(
    private val client: HttpClient,
    private val hubWsBaseUrl: String,
    private val token: String,
) : CommLiveSource {

    override fun events(): Flow<CommLiveEvent> = channelFlow {
        var closeCode: Short? = null
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
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            when (val event = CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())) {
                                is MessageEvent -> this@channelFlow.send(CommLiveEvent.MessageReceived(event.message))
                                is ChannelsEvent -> this@channelFlow.send(CommLiveEvent.ChannelsChanged(event.channels))
                                // CYP-273/S7: surface a content-free ACL-changed signal so the VM re-fetches the
                                // writable set (composer enable/disable live). The pushed row is NOT trusted as the
                                // write authority — the VM asks the server (GET /api/channels/writable) instead.
                                is AclEvent -> this@channelFlow.send(CommLiveEvent.AclChanged)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Ktor cancels the frame channel to tear down a closing socket; if a close reason is already
                    // recorded (e.g. a 1008 reject) this is that teardown, not a real cancel — swallow and report
                    // the close below. Otherwise it's a genuine collector cancel and must propagate.
                    if (!closeReason.isCompleted) throw e
                }
                closeCode = readCloseCode()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-115: log a real failure instead of masking it as a silent Disconnected (a normal close does
            // not throw). The cross-context ISE that caused the churn was swallowed here before.
            logWsError("comm", e)
        }
        // CYP-291: a 1008 (VIOLATED_POLICY) close = a revoked/invalid token → TERMINAL AccessRevoked (the VM
        // cancels the collector; no reconnect). Any other close = a transient Disconnected (reconnects, CYP-73).
        this@channelFlow.send(
            if (isAccessRevoked(closeCode)) CommLiveEvent.AccessRevoked else CommLiveEvent.Disconnected,
        )
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
