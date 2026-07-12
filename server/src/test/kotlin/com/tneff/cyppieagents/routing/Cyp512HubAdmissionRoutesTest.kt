package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.boot.HubRegistration
import com.tneff.cyppieagents.boot.RegistrationTranscript
import com.tneff.cyppieagents.controlplane.HubAdmissionNonce
import com.tneff.cyppieagents.controlplane.HubAdmissionRequest
import com.tneff.cyppieagents.controlplane.HubAdmissionResult
import com.tneff.cyppieagents.controlplane.HubChallenge
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.RawKeys
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-512 — the live hub-admission endpoint, re-gating the three Reviewer targets:
 *  ★ (A) **ownerId bound to the authenticated operator** — a hub claiming a DIFFERENT owner (valid PoP over its own
 *    transcript) is `owner_mismatch`, never admitted (mut-sharp: drop the check → the forged-owner hub is admitted).
 *  ★ **nonce single-use / anti-replay** — the same nonce cannot be replayed.
 *  ★ **PoP no-bypass** — a tampered registration fails the full CYP-451 admit (`pop_invalid`).
 */
class Cyp512HubAdmissionRoutesTest {

    private val opToken = "tok-op"
    private val operator = "op-1" // the operator-token principal maps to op-1 (machineOperatorId)

    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })

    private fun ApplicationTestBuilder.app(registrar: HubRegistrar, nonces: HubAdmissionNonce, machineOpId: String = operator) {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { hubAdmissionRoutes({ registrar }, nonces, d.tokens, d, machineOperatorId = machineOpId) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private val auth = "Bearer $opToken"
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    /** A registration signed (Ed25519) over transcript(nonce + fields) with the given [ownerId]. */
    private fun signedReg(seed: ByteArray, signingPub: ByteArray, dhPub: ByteArray, hubId: String, ownerId: String, nonce: ByteArray): HubRegistration {
        val fields = HubRegistration(hubId, ownerId, "hub", 8787, b64(signingPub), b64(dhPub), pop = "")
        return fields.copy(pop = b64(RawKeys.ed25519Sign(seed, RegistrationTranscript.bytes(fields, nonce))))
    }

    private suspend fun ApplicationTestBuilder.challenge(c: io.ktor.client.HttpClient): ByteArray =
        unb64(c.get("/api/cp/challenge") { header("Authorization", auth) }.body<HubChallenge>().nonce)

    private suspend fun admit(c: io.ktor.client.HttpClient, reg: HubRegistration, nonce: ByteArray): HubAdmissionResult =
        c.post("/api/cp/admit") { header("Authorization", auth); contentType(ContentType.Application.Json); setBody(HubAdmissionRequest(reg, b64(nonce))) }.body()

    @Test fun challengeThenAdmit_owner_validPoP_admits_ownerIsOperator() = testApplication {
        val registrar = HubRegistrar()
        app(registrar, HubAdmissionNonce())
        val c = jsonClient()
        val nonce = challenge(c)
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val res = admit(c, signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId, operator, nonce), nonce)
        assertTrue(res.admitted, "a valid registration by the OWNER admits")
        assertEquals(hubId, res.hubId)
        assertEquals(operator, registrar.lookup(hubId)?.ownerId, "the admitted hub is owned by the AUTHENTICATED operator")
    }

    @Test fun ownerMismatch_rejected_hubCannotClaimAnotherOwner() = testApplication {
        val registrar = HubRegistrar()
        app(registrar, HubAdmissionNonce())
        val c = jsonClient()
        val nonce = challenge(c)
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        // valid PoP over a transcript claiming ownerId = attacker (NOT the authenticated op-1).
        val res = admit(c, signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId, "attacker-operator", nonce), nonce)
        assertFalse(res.admitted, "a hub claiming a different owner is NOT admitted (ownerId bound to the operator)")
        assertEquals("owner_mismatch", res.reason)
        assertNull(registrar.lookup(hubId), "the forged-owner hub never enters the registry")
    }

    @Test fun blankOwnerId_neverAdmits_evenWhenOperatorIdBlank() = testApplication {
        // Reviewer Finding #1 root: the degenerate opId=="" case. A hub claiming ownerId="" (valid PoP over the
        // blank-owner transcript) must NOT admit — else a blank-owner hub exists and the resolve-owner-check grants it.
        val registrar = HubRegistrar()
        app(registrar, HubAdmissionNonce(), machineOpId = "") // degenerate: blank operator id
        val c = jsonClient()
        val nonce = challenge(c)
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val res = admit(c, signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId, "", nonce), nonce)
        assertFalse(res.admitted, "a blank ownerId never admits — even when opId is also blank")
        assertEquals("owner_mismatch", res.reason)
        assertNull(registrar.lookup(hubId), "no blank-owner hub enters the registry")
    }

    @Test fun nonceReplay_rejected_singleUse() = testApplication {
        val registrar = HubRegistrar()
        app(registrar, HubAdmissionNonce())
        val c = jsonClient()
        val nonce = challenge(c)
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val reg = signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId, operator, nonce)
        assertTrue(admit(c, reg, nonce).admitted, "first admit with a fresh nonce succeeds")
        val replay = admit(c, reg, nonce)
        assertFalse(replay.admitted); assertEquals("nonce_invalid", replay.reason, "the SAME nonce cannot be replayed")
    }

    @Test fun tamperedRegistration_rejected_popNoBypass() = testApplication {
        val registrar = HubRegistrar()
        app(registrar, HubAdmissionNonce())
        val c = jsonClient()
        val nonce = challenge(c)
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val good = signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId, operator, nonce)
        val tampered = good.copy(dhPubKey = b64(RawKeys.generateX25519().publicRaw)) // swap dhPubKey, keep the PoP → transcript mismatch
        val res = admit(c, tampered, nonce)
        assertFalse(res.admitted); assertEquals("pop_invalid", res.reason, "the full CYP-451 PoP runs — a substituted field is caught, no bypass")
    }

    @Test fun noCredential_is401_operatorGated() = testApplication {
        app(HubRegistrar(), HubAdmissionNonce())
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/cp/challenge").status, "admission is operator-gated")
    }
}
