package com.tneff.cyppieagents.acl

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
 * Live `/ws/comm` adapter for the ACL-matrix UI (CYP-48) — same socket + frame contract as
 * [com.tneff.cyppieagents.comm.CommWsClient], but the inverse projection: it surfaces the [AclEvent]
 * (and membership-affecting [ChannelsEvent]) frames the comm timeline drops, and ignores message
 * frames. Operator-token only — the server gates `/ws/comm` visibility and ACL scope; the UI does not
 * authorize locally. Decodes each masked `:core` [CommWsServerEvent] via [CommJson].
 */
class AclWsClient(
    private val client: HttpClient,
    private val hubWsBaseUrl: String,
    private val token: String,
) : AclLiveSource {

    override fun events(): Flow<AclLiveEvent> = channelFlow {
        try {
            client.webSocket(
                urlString = commUrl(),
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on
                // Native) — a cross-context emit, illegal in flow{} (the CYP-115 ISE/churn) but what channelFlow
                // allows. Element emissions go to this@channelFlow; `incoming` frames stay on the session.
                this@channelFlow.send(AclLiveEvent.Connected)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        when (val event = CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())) {
                            is AclEvent -> this@channelFlow.send(AclLiveEvent.EntryChanged(event.entry))
                            is ChannelsEvent -> this@channelFlow.send(AclLiveEvent.ChannelsChanged(event.channels))
                            is MessageEvent -> Unit // consumed by the comm timeline, not the ACL matrix
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-115: log a real failure instead of masking it as a silent Disconnected (a normal close does
            // not throw). The cross-context ISE that caused the churn was swallowed here before.
            logWsError("acl", e)
        }
        this@channelFlow.send(AclLiveEvent.Disconnected)
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
