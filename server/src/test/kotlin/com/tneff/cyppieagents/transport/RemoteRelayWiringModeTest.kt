package com.tneff.cyppieagents.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-620 (step 5b) — the transport-mode feature flag resolver. The load-bearing property is FAIL-SAFE: only an
 * explicit `mux` flips the wire protocol; unset / `pool` / any unrecognized value stays on the legacy pool, so a typo
 * or a stale env can never silently put the hub on the new protocol (a half-flipped deploy would then be caught at the
 * G7 hello anyway, but the default must be safe on its own).
 */
class RemoteRelayWiringModeTest {

    private fun env(map: Map<String, String>): (String) -> String? = { map[it] }

    @Test
    fun unset_defaultsToPool() {
        assertEquals(TransportMode.POOL, RemoteRelayWiring.resolveTransportMode(env(emptyMap())))
    }

    @Test
    fun mux_selectsMux() {
        assertEquals(TransportMode.MUX, RemoteRelayWiring.resolveTransportMode(env(mapOf("CYPPIE_REMOTE_TRANSPORT" to "mux"))))
    }

    @Test
    fun pool_explicit_isPool() {
        assertEquals(TransportMode.POOL, RemoteRelayWiring.resolveTransportMode(env(mapOf("CYPPIE_REMOTE_TRANSPORT" to "pool"))))
    }

    @Test
    fun unrecognizedValue_failsSafeToPool() {
        for (v in listOf("muxx", "1", "true", "enabled", "MUX_", "", "  ", "yamux")) {
            assertEquals(TransportMode.POOL, RemoteRelayWiring.resolveTransportMode(env(mapOf("CYPPIE_REMOTE_TRANSPORT" to v))),
                "'$v' is not exactly 'mux' → must stay POOL (a typo never silently flips the wire protocol)")
        }
    }

    @Test
    fun caseInsensitiveAndTrimmed() {
        for (v in listOf("MUX", "Mux", " mux ", "\tmux\n")) {
            assertEquals(TransportMode.MUX, RemoteRelayWiring.resolveTransportMode(env(mapOf("CYPPIE_REMOTE_TRANSPORT" to v))))
        }
    }
}
