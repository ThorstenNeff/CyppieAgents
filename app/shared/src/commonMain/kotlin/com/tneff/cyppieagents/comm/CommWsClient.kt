package com.tneff.cyppieagents.comm

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
import kotlinx.serialization.SerializationException

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
        // CYP-786: set when a frame fails to decode against the client schema (app-schema skew). A skew is TERMINAL:
        // it is emitted as the final event INSTEAD of the trailing Disconnected, so the VM does not reconnect-churn.
        var skew: CommLiveEvent.ProtocolSkew? = null
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
                            // CYP-786: decode may throw SerializationException (superclass of MissingFieldException —
                            // an unknown discriminator or a missing required field = an app-schema SKEW). Catch it HERE
                            // and STOP with a TERMINAL ProtocolSkew, rather than letting it unwind to the outer
                            // catch(Throwable) → Disconnected → .reconnecting() churn (which replays the same undecodable
                            // frame forever under a generic "offline" banner). Do NOT skip-and-continue: a skew does not
                            // resolve without a deploy, so continuing would re-hit it — break and surface it named.
                            val event = try {
                                CommJson.decodeFromString(CommWsServerEvent.serializer(), frame.readText())
                            } catch (e: SerializationException) {
                                skew = CommLiveEvent.ProtocolSkew(e.message)
                                break
                            }
                            when (event) {
                                // CYP-744: the frame wraps a DeliveredMessage now; the CMP client takes the bare message.
                                is MessageEvent -> this@channelFlow.send(CommLiveEvent.MessageReceived(event.delivered.message))
                                is ChannelsEvent -> this@channelFlow.send(CommLiveEvent.ChannelsChanged(event.channels))
                                // CYP-273/S7: surface a content-free ACL-changed signal so the VM re-fetches the
                                // writable set (composer enable/disable live). The pushed row is NOT trusted as the
                                // write authority — the VM asks the server (GET /api/channels/writable) instead.
                                is AclEvent -> this@channelFlow.send(CommLiveEvent.AclChanged)
                                is ReadStateEvent -> Unit // CYP-705: read-state deltas (CMP unread not wired yet)
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
        // CYP-786: a decode skew is TERMINAL and takes precedence — emit it INSTEAD of Disconnected so the VM does
        // not reconnect (a skew won't resolve without a deploy). Else CYP-291: a 1008 (VIOLATED_POLICY) close = a
        // revoked/invalid token → TERMINAL AccessRevoked (no reconnect); any other close = transient Disconnected.
        this@channelFlow.send(
            skew ?: if (isAccessRevoked(closeCode)) CommLiveEvent.AccessRevoked else CommLiveEvent.Disconnected,
        )
    }

    private fun commUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/comm?token=$token"
    }
}
