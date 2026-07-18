package com.tneff.cyppieagents.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CYP-667 S7 — pins the gateway to a **loopback-only** bind. The gateway speaks PLAIN (post-TLS-termination cleartext:
 * session tokens, `?token`/`?ticket`, PTY code-exec bytes) behind the Caddy TLS edge, which proxies to `127.0.0.1`.
 * Binding a non-loopback host (`0.0.0.0`, a LAN/public IP) would expose that cleartext unencrypted over the network —
 * the exact worst case the Caddy edge exists to prevent. The resolver is **fail-closed**: default loopback, and a
 * configured non-loopback host is REFUSED (throws), not silently honored.
 *
 * Mutation-proven: change the default to `0.0.0.0` → [default_isLoopback] reds; drop the loopback `require` → the
 * non-loopback cases stop throwing → [nonLoopbackHost_failsClosed] reds.
 */
class GatewayBindHostTest {

    @Test
    fun default_isLoopback() {
        // ★ the DEFAULT (no CYPPIE_GATEWAY_HOST set) must be loopback — never 0.0.0.0.
        assertEquals("127.0.0.1", resolveGatewayBindHost(null), "the default gateway bind host must be loopback")
        assertEquals("127.0.0.1", resolveGatewayBindHost(""), "a blank host must fall back to loopback")
        assertEquals("127.0.0.1", resolveGatewayBindHost("   "), "a whitespace host must fall back to loopback")
    }

    @Test
    fun explicitLoopbackForms_areAccepted() {
        // every loopback form is honored (returned as-is, no throw) — the resolver refuses only NON-loopback hosts.
        for (h in listOf("127.0.0.1", "127.1.2.3", "localhost", "::1", "[::1]", "LOCALHOST")) {
            assertEquals(h, resolveGatewayBindHost(h), "loopback host '$h' must be accepted and honored")
        }
    }

    @Test
    fun nonLoopbackHost_failsClosed() {
        // ★ a non-loopback bind must be REFUSED, not honored — else the gateway would serve cleartext over the network.
        for (h in listOf("0.0.0.0", "::", "192.168.1.10", "10.0.0.5", "0:0:0:0:0:0:0:0", "8.8.8.8")) {
            assertFailsWith<IllegalArgumentException>("non-loopback host '$h' must fail closed (refuse to start)") {
                resolveGatewayBindHost(h)
            }
        }
    }
}
