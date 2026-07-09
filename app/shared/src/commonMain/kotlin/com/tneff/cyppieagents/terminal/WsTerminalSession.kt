package com.tneff.cyppieagents.terminal

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.net.logWsError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/**
 * CYP-334 — the real `/ws/terminal` [TerminalSession] over the CYP-332 PTY-over-WS contract. Encodes keystrokes
 * as [TerminalInput] (raw bytes → standard Base64), decodes [TerminalOutput] back to raw bytes for the renderer,
 * forwards [TerminalResize]; a [TerminalExit] (or a socket close) **completes** [incoming] so the renderer tears
 * down. All frames go through the shared [CommJson] (`classDiscriminator = "type"`) so the wire can't drift.
 *
 * [incoming] connects **lazily on first collect** (the renderer's read loop drives it); [send]/[resize] queue
 * onto a buffered [outbound] channel that the sender coroutine drains once connected, so an early keystroke isn't
 * lost. Mirrors the other client WS sources (e.g. `AgentLifecycleLiveSource`): `logWsError` on a real failure,
 * `CancellationException` rethrown.
 */
class WsTerminalSession(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val agentId: String,
    private val token: String,
) : TerminalSession {

    private val outbound = Channel<TerminalClientFrame>(Channel.BUFFERED)

    /** The PTY exit code once a [TerminalExit] is seen, else null. Not on [TerminalSession] — teardown keys off
     *  [incoming] completing; this only exposes the code for callers that want it. */
    var lastExitCode: Int? = null
        private set

    override val incoming: Flow<ByteArray> = channelFlow {
        val producer = this // ProducerScope<ByteArray> — disambiguates send() from the WS session's send(Frame)
        try {
            client.webSocket(urlString = terminalUrl()) {
                val sender = launch {
                    for (frame in outbound) send(Frame.Text(TerminalWire.encode(frame)))
                }
                try {
                    for (frame in incoming) { // the WS session's incoming (Frame stream)
                        if (frame is Frame.Text) {
                            when (val f = TerminalWire.decode(frame.readText())) {
                                is TerminalOutput -> producer.send(TerminalWire.outputBytes(f))
                                is TerminalExit -> { lastExitCode = f.code; break } // last frame — socket closes next
                            }
                        }
                    }
                } finally {
                    sender.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logWsError("terminal", e)
        }
    }

    override fun send(bytes: ByteArray) { outbound.trySend(TerminalWire.input(bytes)) }
    override fun resize(cols: Int, rows: Int) { outbound.trySend(TerminalResize(cols, rows)) }
    override fun close() { outbound.close() }

    private fun terminalUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/terminal?agentId=$agentId&token=$token"
    }
}

/**
 * The pure PTY-over-WS wire mapping (CYP-332 contract), factored out so it is unit-testable without a live
 * socket: standard Base64 for the raw byte payloads, and the shared [CommJson] sealed-frame (de)serialisation.
 */
internal object TerminalWire {
    fun encode(frame: TerminalClientFrame): String =
        CommJson.encodeToString(TerminalClientFrame.serializer(), frame)

    fun decode(text: String): TerminalServerFrame =
        CommJson.decodeFromString(TerminalServerFrame.serializer(), text)

    /** Keystroke/paste bytes → a [TerminalInput] frame (standard Base64, per the contract). */
    fun input(bytes: ByteArray): TerminalInput = TerminalInput(Base64.Default.encode(bytes))

    /** A [TerminalOutput] frame → the raw PTY stdout bytes the renderer consumes. */
    fun outputBytes(frame: TerminalOutput): ByteArray = Base64.Default.decode(frame.dataBase64)
}
