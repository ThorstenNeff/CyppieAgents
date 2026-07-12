package com.tneff.cyppieagents.net.hub.operator

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-513/514 — the client channel-binding GOLDEN VECTOR. Mirrors Backend's hub-verifier vector byte-for-byte:
 * for `h = 0x00..0x1F` (32B) and `hubId = "cyppie-golden-hub"`, the `cb` the client sends MUST equal what the hub
 * re-derives. Locks the cross-side contract (`h` FIRST ‖ `hubId.utf8`, raw concat, NO length-prefix, base64url NO
 * padding) so `HttpCpJwtProvider`'s ticket binds to this session and the hub's RR3 gate accepts it — no drift.
 */
class Cyp513ChannelBindingGoldenTest {

    @Test
    fun coreChannelBinding_matchesHubVerifierGoldenVector() {
        val h = ByteArray(32) { it.toByte() } // 0x00..0x1F
        assertEquals(
            "JVDXE7HhN-U3H8Rg61ShpLmVkJ9FQ4KpS5GLRWvnqFA",
            coreChannelBinding().compute(h, "cyppie-golden-hub"),
        )
    }
}
