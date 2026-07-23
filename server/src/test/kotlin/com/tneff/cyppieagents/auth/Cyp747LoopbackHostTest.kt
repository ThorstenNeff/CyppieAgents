package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-747 S-AAL2b — [isLoopbackHost], the single source for the browser-AAL2-OPERATOR loopback posture (§9.4/§9.5).
 * The load-bearing tooth is the DISCRIMINATOR: it must use `InetAddress.getByName(host).isLoopbackAddress` (the
 * LoopbackBridge T3 idiom), NOT a brittle string `== "127.0.0.1"`. Mutation: replace the impl with `host == "127.0.0.1"`
 * → `::1` / `localhost` / `127.0.0.2` (all genuinely loopback) wrongly report false → those asserts red. And an
 * off-loopback / unresolvable host must be false (fail-closed → posture disabled).
 */
class Cyp747LoopbackHostTest {

    @Test
    fun loopbackForms_allTrue_notJustDottedQuad() {
        assertTrue(isLoopbackHost("127.0.0.1"), "the canonical loopback")
        assertTrue(isLoopbackHost("::1"), "IPv6 loopback — a string==127.0.0.1 impl misses it")
        assertTrue(isLoopbackHost("localhost"), "resolves to loopback — a string compare misses it")
        assertTrue(isLoopbackHost("127.0.0.2"), "the WHOLE 127.0.0.0/8 block is loopback — a string compare misses it")
    }

    @Test
    fun offLoopback_andUnresolvable_areFalse_failClosed() {
        assertFalse(isLoopbackHost("0.0.0.0"), "the any-address bind is NOT loopback — browser-operator posture must be off")
        assertFalse(isLoopbackHost("192.168.1.10"), "a LAN address is off-loopback")
        assertFalse(isLoopbackHost("10.0.0.5"), "a private but non-loopback address")
        assertFalse(isLoopbackHost(""), "an empty/unresolvable host → false (fail-closed, treat as off-loopback)")
        assertFalse(isLoopbackHost("no such host at all . invalid"), "an unresolvable host → false (fail-closed)")
    }
}
