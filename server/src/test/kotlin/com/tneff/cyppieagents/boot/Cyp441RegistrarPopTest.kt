package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
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

    private fun registrar(dir: java.nio.file.Path, connector: ControlPlaneConnector): Pair<ControlPlaneRegistrar, HubIdentityProvisioner> {
        val store = SqliteSecretStore(dir.resolve("secrets.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() })
        val prov = HubIdentityProvisioner(store, dir.resolve(".cyppie/hub-identity.json"))
        val identity = prov.ensure()
        return ControlPlaneRegistrar(identity, prov, connector, ownerId = "owner-1", name = "hub-1", defaultPort = 8787) to prov
    }

    @Test fun register_producesValidPoP_accepted_andEgressed() = runBlocking {
        val dir = Files.createTempDirectory("cyp441-reg")
        val connector = CapturingConnector()
        val (reg, _) = registrar(dir, connector)
        val nonce = "cp-nonce-42".encodeToByteArray()

        val registration = reg.register(nonce)
        assertTrue(ControlPlaneRegistrar.verifyPop(registration, nonce), "a genuine PoP over the nonce is accepted")
        assertEquals(1, connector.sent.size, "the registration is egressed through the (masked) CP connector")
        assertTrue(connector.sent.first().contains(registration.hubId))
    }

    @Test fun verifyPop_rejects_missing_wrongNonce_forged() = runBlocking {
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

    @Test fun register_emptyNonce_failsClosed() = runBlocking {
        val dir = Files.createTempDirectory("cyp441-reg")
        val (reg, _) = registrar(dir, CapturingConnector())
        assertFailsWith<IllegalArgumentException> { reg.register(ByteArray(0)) }
    }
}
