package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.transport.RemoteRelayWiring.RemoteIssuerTrustState
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-747 S1b — the SERVER-INTERNAL issuer-trust classification (§5-C2 axis c). The no-issuer case is a DISTINCT
 * observable reason (ISSUER_NOT_TRUSTED = owned-but-issuer-not-trusted), never a silent INERT (§5-b) and never
 * collapsed into "not configured for remote". `:server`-only — NO `:core` wire type here (the client-facing typed
 * connect-cause is deferred to S1c / CYP-798, the joint Team-1/Team-2 `:core` promotion).
 */
class Cyp747IssuerTrustStateTest {

    private val pub = Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })
    private fun envOf(m: Map<String, String>): (String) -> String? = { m[it] }

    private val issuerSet = mapOf(
        "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to pub,
    )

    @Test
    fun remoteConfigured_noIssuer_isDistinct_issuerNotTrusted() {
        // Remote INTENDED (relay URL set) but NO issuer pinned → the DISTINCT owned-but-issuer-not-trusted state.
        // Mutations: collapse into REMOTE_NOT_CONFIGURED (silent — treat it as "not doing remote") OR into
        // ISSUER_TRUSTED (silently accept a missing issuer) → this assertion reddens either way (the §5-b non-silent leg).
        assertEquals(
            RemoteIssuerTrustState.ISSUER_NOT_TRUSTED,
            RemoteRelayWiring.classifyIssuerTrust(envOf(mapOf("CYPPIE_REMOTE_RELAY_URL" to "wss://relay"))),
        )
    }

    @Test
    fun remoteConfigured_withIssuer_isIssuerTrusted() {
        assertEquals(
            RemoteIssuerTrustState.ISSUER_TRUSTED,
            RemoteRelayWiring.classifyIssuerTrust(envOf(mapOf("CYPPIE_REMOTE_RELAY_URL" to "wss://relay") + issuerSet)),
        )
    }

    @Test
    fun noRelayUrl_isRemoteNotConfigured_notIssuerNotTrusted() {
        // Without a relay URL the hub isn't attempting remote at all → REMOTE_NOT_CONFIGURED, a DISTINCT reason from
        // ISSUER_NOT_TRUSTED (the latter would falsely imply "remote is set up but only the issuer is missing").
        // Mutation: drop the relay-URL intent gate (classify no-relay as ISSUER_NOT_TRUSTED) → red.
        assertEquals(RemoteIssuerTrustState.REMOTE_NOT_CONFIGURED, RemoteRelayWiring.classifyIssuerTrust(envOf(emptyMap())))
        // Even WITH a complete issuer set, no relay URL → still not configured for remote (relay-URL is the intent gate).
        assertEquals(RemoteIssuerTrustState.REMOTE_NOT_CONFIGURED, RemoteRelayWiring.classifyIssuerTrust(envOf(issuerSet)))
    }
}
