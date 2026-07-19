package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.ReadStateEvent
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
        var closeCode: Short? = null
        try {
            client.webSocket(
                urlString = commUrl(),
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on
                // Native) — a cross-context emit, illegal in flow{} (the CYP-115 ISE/churn) but what channelFlow
                // allows. Element emissions go to this@channelFlow; `incoming` frames stay on the session.
                this@channelFlow.send(AclLiveEvent.Connected)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            when (val event = CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())) {
                                is AclEvent -> this@channelFlow.send(AclLiveEvent.EntryChanged(event.entry))
                                is ChannelsEvent -> this@channelFlow.send(AclLiveEvent.ChannelsChanged(event.channels))
                                is MessageEvent -> Unit // consumed by the comm timeline, not the ACL matrix
                                is ReadStateEvent -> Unit // CYP-705: read-state deltas are not the ACL matrix's concern
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Ktor tears a closing socket down by cancelling the frame channel; if a close reason is
                    // already recorded (e.g. a 1008 reject) this is that teardown, not a real cancel — swallow
                    // and report the close below. Otherwise it's a genuine collector cancel and must propagate.
                    if (!closeReason.isCompleted) throw e
                }
                // CYP-293: read the close code via the shared helper (NonCancellable, so a 1008 is reported even
                // while the socket tears down) — one place for the close-code contract across all /ws/ clients.
                closeCode = readCloseCode()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-115: log a real failure instead of masking it as a silent Disconnected (a normal close does
            // not throw). The cross-context ISE that caused the churn was swallowed here before.
            logWsError("acl", e)
        }
        // CYP-289: a 1008 (VIOLATED_POLICY) close = a revoked/invalid operator token → TERMINAL AccessRevoked
        // (the VM cancels the collector; no reconnect). Any other close = a transient Disconnected (reconnects).
        this@channelFlow.send(
            if (isAccessRevoked(closeCode)) AclLiveEvent.AccessRevoked
            else AclLiveEvent.Disconnected,
        )
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
