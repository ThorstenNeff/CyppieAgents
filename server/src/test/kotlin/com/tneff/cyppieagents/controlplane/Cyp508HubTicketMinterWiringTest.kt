package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.transport.RemoteRelayWiring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    // ---- CYP-563 — the wiring threads the Op-Session-TTL override into the ticket exp (single-source, no drift) ----

    @Test fun opSessionTtlOverride_reachesTheTicketExp_singleSourcedWithTunnelCap() {
        // The drift bug: buildHubTicketMinter omitted ttlMs → the ticket exp was hard-wired to the default constant
        // while the hub tunnel-cap honored CYPPIE_OP_SESSION_TTL_MIN → the two legs of min(ticket-exp, tunnel-cap)
        // drifted. Fix: the wiring passes RemoteRelayWiring.resolveOpSessionTtlMs(env) into the ticket exp. With the
        // override set, the minted ticket lifetime honors it. MUT (drop the ttlMs arg) → ticket stays 15 min → reds.
        val hubId = "hub_x"; val owner = "op-x"; val now = 1_700_000_000_000L
        val registrar = HubRegistrar(ConcurrentHashMap(mapOf(hubId to RegisteredHub(hubId, owner, "h", 8787, "spub", "dpub"))))
        val minter = buildHubTicketMinter(
            registrar,
            operatorAuthenticate = { it.sessionToken.ifBlank { null } },
            nowMs = { now },
            env = (full + ("CYPPIE_OP_SESSION_TTL_MIN" to "60"))::get,
        )
        val token = assertNotNull(
            (minter as LiveHubTicketMinter).mint(HubTicketRequest(hubId, "cb"), CpOperatorSession(owner)).cpJwt,
            "the owner still mints under the override env",
        )
        val payload = Json.parseToJsonElement(Base64.getUrlDecoder().decode(token.split(".")[1]).decodeToString()).jsonObject
        val ttlMs = (payload["exp"]!!.jsonPrimitive.long - payload["nbf"]!!.jsonPrimitive.long) * 1000L
        assertEquals(60 * 60_000L, ttlMs, "the ticket exp honors CYPPIE_OP_SESSION_TTL_MIN=60 (single-sourced with the tunnel-cap)")
        assertNotEquals(RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS, ttlMs, "the override must NOT collapse back to the default constant (the drift bug)")
    }
}
