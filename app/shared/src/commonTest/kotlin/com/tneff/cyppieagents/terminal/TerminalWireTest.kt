package com.tneff.cyppieagents.terminal

import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-334 — the PTY-over-WS wire mapping against the CYP-332 contract: standard Base64 for the raw byte payloads
 * and the shared `CommJson` sealed-frame (de)serialisation (`classDiscriminator = "type"`). These pin exactly the
 * bytes-through-JSON round-trip the JediTerm `TtyConnector` relies on, without needing a live socket.
 */
class TerminalWireTest {

    @Test
    fun input_encodesRawBytes_asStandardBase64_frame() {
        // Raw bytes incl. a high/non-ASCII byte and a control byte — JSON can't carry these, hence Base64.
        val bytes = byteArrayOf(0x1B, 0x5B, 0x41, 0x00, 0xFF.toByte(), 0x7F)
        val wire = TerminalWire.encode(TerminalWire.input(bytes))
        assertTrue(wire.contains("\"type\":\"input\""), "client frame carries the discriminator: $wire")
        assertTrue(wire.contains(Base64.Default.encode(bytes)), "payload is standard Base64 of the raw bytes")
        // Decoding the embedded Base64 yields the exact original bytes.
        val payload = Regex("\"dataBase64\":\"([^\"]*)\"").find(wire)!!.groupValues[1]
        assertEquals(bytes.toList(), Base64.Default.decode(payload).toList())
    }

    @Test
    fun output_decodesToRawBytes_fromContractWire() {
        val bytes = byteArrayOf(0x68, 0x69, 0x0A, 0xC3.toByte(), 0xA9.toByte()) // "hi\n" + é (UTF-8)
        val wire = """{"type":"output","dataBase64":"${Base64.Default.encode(bytes)}"}"""
        val frame = TerminalWire.decode(wire)
        assertTrue(frame is TerminalOutput)
        assertEquals(bytes.toList(), TerminalWire.outputBytes(frame as TerminalOutput).toList())
    }

    @Test
    fun exit_decodesWithCode() {
        val frame = TerminalWire.decode("""{"type":"exit","code":137}""")
        assertTrue(frame is TerminalExit)
        assertEquals(137, (frame as TerminalExit).code)
    }

    @Test
    fun resize_encodesColsAndRows() {
        val wire = TerminalWire.encode(TerminalResize(cols = 120, rows = 40))
        assertTrue(wire.contains("\"type\":\"resize\""))
        assertTrue(wire.contains("\"cols\":120") && wire.contains("\"rows\":40"), "resize carries cols/rows: $wire")
    }
}
