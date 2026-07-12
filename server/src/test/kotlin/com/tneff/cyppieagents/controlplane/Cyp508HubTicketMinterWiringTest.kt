package com.tneff.cyppieagents.controlplane

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * CYP-508 — the §3 wiring swap ([buildHubTicketMinter]) is **fail-closed to [InertHubTicketMinter]**: the LIVE
 * [LiveHubTicketMinter] is built ONLY when the activation gate + the FULL CP signing config are present; ANY missing
 * or malformed piece → Inert (never a half-configured live mint). Parity with `buildRemoteTransport`.
 *
 * ★ Mutation: drop any one `?: return InertHubTicketMinter` guard → the matching missing-env case builds Live → reds.
 */
class Cyp508HubTicketMinterWiringTest {

    private val seed = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }) // valid 32-byte Ed25519 seed
    private val full = mapOf(
        "CYPPIE_REMOTE_RELAY_URL" to "wss://r.test/relay",
        "CYPPIE_CP_SIGNING_SEED" to seed,
        "CYPPIE_CP_KID" to "cp1",
        "CYPPIE_CP_ISSUER" to "cp",
    )

    private fun build(env: Map<String, String?>) =
        buildHubTicketMinter(HubRegistrar(), operatorAuthenticate = { it.sessionToken.ifBlank { null } }, env = env::get)

    @Test fun allConfigPresent_buildsLive() {
        assertIs<LiveHubTicketMinter>(build(full), "the full activation gate + CP signing config → LIVE minter")
    }

    @Test fun missingRelayUrl_failsClosedToInert() {
        assertSame(InertHubTicketMinter, build(full - "CYPPIE_REMOTE_RELAY_URL"), "no activation gate → INERT")
    }

    @Test fun missingSigningSeed_failsClosedToInert() {
        assertSame(InertHubTicketMinter, build(full - "CYPPIE_CP_SIGNING_SEED"))
    }

    @Test fun malformedSigningSeed_wrongLength_failsClosedToInert() {
        assertSame(InertHubTicketMinter, build(full + ("CYPPIE_CP_SIGNING_SEED" to Base64.getEncoder().encodeToString(ByteArray(16)))), "a non-32-byte seed is rejected (not a valid Ed25519 seed)")
    }

    @Test fun missingKid_failsClosedToInert() {
        assertSame(InertHubTicketMinter, build(full - "CYPPIE_CP_KID"))
    }

    @Test fun missingIssuer_failsClosedToInert() {
        assertSame(InertHubTicketMinter, build(full - "CYPPIE_CP_ISSUER"))
    }
}
