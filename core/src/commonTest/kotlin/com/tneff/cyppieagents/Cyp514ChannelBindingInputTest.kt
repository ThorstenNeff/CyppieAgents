package com.tneff.cyppieagents

import com.tneff.cyppieagents.operator.channelBindingInput
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * CYP-514 — the shared `:core` channel-binding INPUT is `h ‖ hubId.utf8` — RAW concat, `h` FIRST, no length-prefix.
 * This is the order/delimiter definition both the server and the client (CpJwtProvider) hash; single-sourcing it here
 * (commonMain, all targets) removes the drift class. Runs on every target so the client's derivation is compiler-tied.
 */
class Cyp514ChannelBindingInputTest {

    @Test
    fun input_isHFirst_rawConcat_noLengthPrefix() {
        val h = ByteArray(32) { it.toByte() }
        val hubId = "hub_x"
        val input = channelBindingInput(h, hubId)
        assertContentEquals(h + hubId.encodeToByteArray(), input, "input = h ‖ hubId.utf8, h FIRST, raw concat")
        assertContentEquals(h, input.copyOfRange(0, 32), "the 32-byte handshake hash comes first")
        assertContentEquals(hubId.encodeToByteArray(), input.copyOfRange(32, input.size), "then the hubId utf8, no length-prefix")
    }
}
