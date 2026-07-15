package com.tneff.cyppieagents.transport.mux

/**
 * CYP-620 — the **G7 mode/version hello** (§4.2 / §4.8.10). Exchanged as the FIRST message on the tunnel, INSIDE the
 * Noise channel (AEAD-authenticated), BEFORE any yamux frame. It pins the transport mode + protocol version so a
 * mixed-mode deploy (one peer `mux`, one `pool`) or a version skew is refused **fail-closed at the handshake** — the
 * untrusted relay (RR4) cannot tamper the marker to force a downgrade, because it rides the encrypted channel.
 *
 * Wire (7 bytes): `[ magic: 4B = "CYMX" ][ version: u16 ][ mode: u8 ]`, big-endian. A hello that does not match
 * byte-for-byte (bad magic / unknown version / unexpected mode / wrong length / absent) → the peer is not a
 * compatible mux peer → refuse.
 */
object MuxHello {
    /** `CYMX` — the mux hello magic. */
    private val MAGIC = byteArrayOf(0x43, 0x59, 0x4D, 0x58) // 'C','Y','M','X'

    /** The mux wire-protocol version. Bump on a breaking §4.2 frame-contract change (PO-brokered, both ends flip). */
    const val VERSION: Int = 1

    /** Transport mode marker — 0 = mux. (`pool` peers never send a hello; the absence itself fails the handshake.) */
    const val MODE_MUX: Int = 0

    const val SIZE: Int = 7

    /** Our hello to send to the peer. */
    val ENCODED: ByteArray = MAGIC + byteArrayOf(
        ((VERSION ushr 8) and 0xFF).toByte(),
        (VERSION and 0xFF).toByte(),
        MODE_MUX.toByte(),
    )

    /**
     * Verify a peer's first tunnel message is a matching mux hello. Fail-closed: any deviation (length, magic,
     * version, mode) → `false` → the caller refuses the tunnel. Constant, no allocation beyond the check.
     */
    fun verify(msg: ByteArray): Boolean {
        if (msg.size != SIZE) return false
        for (i in MAGIC.indices) if (msg[i] != MAGIC[i]) return false
        val version = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        val mode = msg[6].toInt() and 0xFF
        return version == VERSION && mode == MODE_MUX
    }

    /**
     * CYP-620 instrumentation (logs-only) — classify WHY a peer's hello would be rejected, for the [MuxBridge] refuse
     * log. Returns a stable category plus a benign numeric where useful (size / version / mode). It NEVER returns byte
     * content: the hello carries only magic+version+mode — no secret — and this deliberately surfaces none of the raw
     * bytes. `"ok"` means it verifies (the caller would not log a reject).
     */
    fun rejectReason(msg: ByteArray?): String {
        if (msg == null) return "absent"
        if (msg.size != SIZE) return "bad-length:${msg.size}"
        for (i in MAGIC.indices) if (msg[i] != MAGIC[i]) return "bad-magic"
        val version = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        if (version != VERSION) return "version-skew:$version"
        val mode = msg[6].toInt() and 0xFF
        if (mode != MODE_MUX) return "mode-mismatch:$mode"
        return "ok"
    }
}
