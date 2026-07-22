package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-441 (S-C / R3, F3) — the **mandatory Register-PoP** teeth on [ControlPlaneRegistrar].
 *
 * ★ [verifyPop_rejects_missing_wrongNonce_forged] is the non-vacuous mandatory-PoP gate: a blank / wrong-nonce /
 * forged PoP is rejected, a genuine one accepted. Weaken [ControlPlaneRegistrar.verifyPop] to `pop.isNotBlank()`
 * (drop the Ed25519 verification) → the forged/wrong-nonce cases go green → this test reds.
 * ★ [register_emptyNonce_failsClosed]: the client refuses to emit a registration with no PoP challenge.
 */
class Cyp441RegistrarPopTest {

    private class CapturingConnector : ControlPlaneConnector {
        val sent = mutableListOf<String>()
        override suspend fun egress(payload: String) { sent.add(payload) }
    }

    // A stable master key per test instance (JUnit4 = fresh instance per method) — parity with the crypto test;
    // keeps the SecretStore decryptable if reopened, and never a fresh-key canary-reject for the wrong reason.
    private val masterKeyset = SecretCipherFactory.newBoxKeyset()

    private fun registrar(
        dir: java.nio.file.Path,
        connector: ControlPlaneConnector,
        issuerTrust: HubIssuerTrust? = null,
    ): Pair<ControlPlaneRegistrar, HubIdentityProvisioner> {
        val store = SqliteSecretStore(dir.resolve("secrets.db"), MasterKeyCustody { masterKeyset })
        val prov = HubIdentityProvisioner(store, dir.resolve(".cyppie/hub-identity.json"))
        val identity = prov.ensure()
        return ControlPlaneRegistrar(identity, prov, connector, ownerId = "owner-1", name = "hub-1", defaultPort = 8787, issuerTrust = issuerTrust) to prov
    }

    // Block (Unit-returning) bodies: an expression body `= runBlocking { assertFailsWith { … } }` returns the
    // exception (non-Unit), which JUnit4 rejects as an invalid test method — failing the WHOLE class at init and
    // silently skipping every tooth here (Reviewer CYP-441 blocker-1). Block bodies keep each method `void`/Unit.

    @Test fun register_producesValidPoP_accepted_andEgressed() {
        runBlocking {
            val dir = Files.createTempDirectory("cyp441-reg")
            val connector = CapturingConnector()
            val (reg, _) = registrar(dir, connector)
            val nonce = "cp-nonce-42".encodeToByteArray()

            val registration = reg.register(nonce)
            assertTrue(ControlPlaneRegistrar.verifyPop(registration, nonce), "a genuine PoP over the nonce is accepted")
            assertEquals(1, connector.sent.size, "the registration is egressed through the (masked) CP connector")
            assertTrue(connector.sent.first().contains(registration.hubId))
        }
    }

    @Test fun verifyPop_rejects_missing_wrongNonce_forged() {
        runBlocking {
            val dir = Files.createTempDirectory("cyp441-reg")
            val (reg, _) = registrar(dir, CapturingConnector())
            val nonce = "cp-nonce-42".encodeToByteArray()
            val registration = reg.register(nonce)

            // genuine → accepted
            assertTrue(ControlPlaneRegistrar.verifyPop(registration, nonce))
            // blank PoP → rejected (PoP is mandatory)
            assertFalse(ControlPlaneRegistrar.verifyPop(registration.copy(pop = ""), nonce))
            // wrong nonce → rejected (the signature is bound to the exact CP-issued nonce)
            assertFalse(ControlPlaneRegistrar.verifyPop(registration, "different-nonce".encodeToByteArray()))
            // forged PoP (a valid signature by a DIFFERENT key over the same nonce) → rejected
            val attacker = RawKeys.generateEd25519()
            val forged = registration.copy(pop = Base64.getEncoder().encodeToString(RawKeys.ed25519Sign(attacker.privateRaw, nonce)))
            assertFalse(ControlPlaneRegistrar.verifyPop(forged, nonce), "a PoP by a key other than signingPubKey is rejected")
            // empty nonce on the verify side → rejected
            assertFalse(ControlPlaneRegistrar.verifyPop(registration, ByteArray(0)))
        }
    }

    @Test fun cyp804_issuerTrust_isBoundInThePoP_flippingItInvalidatesTheSignature() {
        runBlocking {
            val dir = Files.createTempDirectory("cyp804-bind")
            val (reg, _) = registrar(dir, CapturingConnector(), issuerTrust = HubIssuerTrust.NOT_TRUSTED)
            val nonce = "cp-nonce-42".encodeToByteArray()
            val registration = reg.register(nonce)
            assertEquals(HubIssuerTrust.NOT_TRUSTED, registration.issuerTrust, "the hub self-reports its posture in the registration")

            // genuine → accepted: the bind does NOT break the PoP (the hub signs the SAME transcript the CP verifies).
            assertTrue(ControlPlaneRegistrar.verifyPop(registration, nonce), "a genuine PoP over the bound posture verifies")
            // ★ a MITM flips NOT_TRUSTED→TRUSTED in transit (to SUPPRESS the client's owned-but-issuer-not-trusted warning)
            // → the transcript bytes change → the hub's PoP no longer verifies → CAUGHT. The bind gives security VALUE,
            // not just hygiene (PL-0108). Mutation: drop the issuerTrust `lp(...)` line from RegistrationTranscript → the
            // flip stops changing the transcript → this assertion goes green with a tampered posture → RED here.
            assertFalse(
                ControlPlaneRegistrar.verifyPop(registration.copy(issuerTrust = HubIssuerTrust.TRUSTED), nonce),
                "flipping the bound issuerTrust posture must invalidate the PoP (else the binding is vacuous)",
            )
            // dropping it (→ null) likewise changes the transcript → rejected.
            assertFalse(ControlPlaneRegistrar.verifyPop(registration.copy(issuerTrust = null), nonce))
        }
    }

    @Test fun register_emptyNonce_failsClosed() {
        runBlocking {
            val dir = Files.createTempDirectory("cyp441-reg")
            val (reg, _) = registrar(dir, CapturingConnector())
            assertFailsWith<IllegalArgumentException> { reg.register(ByteArray(0)) }
        }
    }
}
