package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import com.tneff.cyppieagents.boot.HubRegistration
import com.tneff.cyppieagents.boot.NoOpControlPlaneConnector
import com.tneff.cyppieagents.boot.RegistrationTranscript
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
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-451 (S-D) teeth — the INERT Control Plane. Hermetic (S-C RawKeys, already runtime-verified). The two hard
 * Reviewer carry-forward AC are the headline teeth:
 * ★ [dhPubKeySubstitution_rejected] — a PoP is over the TRANSCRIPT, so a substituted dhPubKey can't ride a valid
 *   PoP (drop the transcript binding → the substitution rides through = red).
 * ★ [hubIdNotSelfCertifying_rejected] — the CP VERIFIES `hubId == deriveHubId(signingPub)` even for a validly-
 *   signed (over its own lying transcript) registration (drop the check → the forged hubId is admitted = red).
 * ★ [mintedCpJwt_isAcceptedByS_E] — the minter and the S-E verifier agree end-to-end (the remote auth path works).
 */
class Cyp451ControlPlaneTest {

    private val cpNonce = "cp-issued-nonce-123".encodeToByteArray()
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** Build a registration signed over its transcript by [signingSeed] (an attacker can set any [hubId]). */
    private fun signedReg(
        signingSeed: ByteArray, signingPub: ByteArray, dhPub: ByteArray, hubId: String,
        ownerId: String = "owner-1", name: String = "hub-1", port: Int = 8787,
    ): HubRegistration {
        val fields = HubRegistration(hubId, ownerId, name, port, b64(signingPub), b64(dhPub), pop = "")
        val pop = b64(RawKeys.ed25519Sign(signingSeed, RegistrationTranscript.bytes(fields, cpNonce)))
        return fields.copy(pop = pop)
    }

    // ---- ★ carry-forward AC 1: transcript PoP (dhPubKey not substitutable) ----

    @Test fun validRegistration_admitted() {
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val result = HubRegistrar().admit(signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId), cpNonce)
        assertIs<AdmitResult.Admitted>(result)
        assertEquals(hubId, result.hub.hubId)
    }

    @Test fun dhPubKeySubstitution_rejected() {
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        val hubId = HubIdentityProvisioner.deriveHubId(s.publicRaw)
        val good = signedReg(s.privateRaw, s.publicRaw, d.publicRaw, hubId)
        // swap in a DIFFERENT dhPubKey but keep the PoP → the transcript no longer matches → PoP invalid.
        val attackerDh = RawKeys.generateX25519()
        val tampered = good.copy(dhPubKey = b64(attackerDh.publicRaw))
        val result = HubRegistrar().admit(tampered, cpNonce)
        assertIs<AdmitResult.Rejected>(result)
        assertEquals("pop_invalid", result.reason)
    }

    // ---- ★ carry-forward AC 2: hubId == deriveHubId (verified, not stored) ----

    @Test fun hubIdNotSelfCertifying_rejected() {
        val s = RawKeys.generateEd25519(); val d = RawKeys.generateX25519()
        // an attacker hub signs a VALID transcript but claims a hubId that does NOT derive from its signing key.
        val forgedHubId = "hub_0000000000000000"
        val reg = signedReg(s.privateRaw, s.publicRaw, d.publicRaw, forgedHubId)
        assertTrue(ControlPlaneRegistrar.verifyPop(reg, cpNonce), "the PoP is valid over its own (lying) transcript")
        val result = HubRegistrar().admit(reg, cpNonce)
        assertIs<AdmitResult.Rejected>(result)
        assertEquals("hubid_not_self_certifying", result.reason)
    }

    @Test fun realHubRegistrar_producesCpAdmittableRegistration() {
        // End-to-end: the REAL S-C hub-side registrar (over the S-B SecretStore) yields a registration the CP admits
        // — proving the transcript PoP + self-certifying hubId agree across both sides.
        val dir = Files.createTempDirectory("cyp451")
        SqliteSecretStore(dir.resolve("s.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() }).use { store ->
            val prov = HubIdentityProvisioner(store, dir.resolve(".cyppie/hub-identity.json"))
            val id = prov.ensure()
            val registrar = ControlPlaneRegistrar(id, prov, NoOpControlPlaneConnector, "owner-1", "hub-1", 8787)
            val reg = runBlocking { registrar.register(cpNonce) }
            val result = HubRegistrar().admit(reg, cpNonce)
            assertIs<AdmitResult.Admitted>(result)
            assertEquals(id.hubId, result.hub.hubId)
        }
    }

    // ---- ★ CpJwt mint ↔ S-E verify round-trip ----

    @Test fun mintedCpJwt_isAcceptedByS_E() {
        val cp = RawKeys.generateEd25519() // the CP's own signing key
        val minter = CpJwtMinter(cp.privateRaw, kid = "cp1", issuer = "cp")
        val hubId = "hub_abcdef0123456789"
        val operatorId = "operator-1"
        val h = byteArrayOf(7, 7, 7, 7, 1, 2, 3)
        val cb = TokenPredicates.expectedChannelBinding(h, hubId)
        val nowMs = 1_000_000_000_000L

        val token = minter.mint(hubId, operatorId, cb, issuedAtMs = nowMs, ttlMs = 300_000)

        val principal = CpJwtVerifier().verify(
            token,
            VerifierContext(
                hubId = hubId, pinnedOperatorId = operatorId, handshakeHash = h, expectedIssuer = "cp",
                cpPublicKey = { kid -> if (kid == "cp1") cp.publicRaw else null }, nowMs = nowMs,
            ),
        )
        assertIs<AuthPrincipal.Human>(principal)
        assertEquals(operatorId, principal.identityId)
        assertEquals(AuthRole.OPERATOR, principal.role)
    }

    @Test fun mint_alwaysPinsEdDSA() {
        val cp = RawKeys.generateEd25519()
        val token = CpJwtMinter(cp.privateRaw, "cp1", "cp").mint("hub_x", "op", "cb", 1000, 1000)
        val header = String(Base64.getUrlDecoder().decode(token.split(".")[0]))
        assertTrue(header.contains("\"alg\":\"EdDSA\""), "the minter always pins alg=EdDSA (no downgrade)")
    }

    // ---- device-code operator PIN ----

    @Test fun deviceCode_approvesOnlyPinnedOperator() {
        val flow = DeviceCodeFlow(pinnedOperatorId = "op-1", codes = { "dc-1" to "UC-1" })
        val grant = flow.issue(nowMs = 0, ttlMs = 60_000)
        assertEquals(DeviceCodeState.Pending, flow.poll(grant.deviceCode, 1))

        // a NON-pinned operator is denied (fail-closed pin) — never a token for the wrong operator.
        assertFalse(flow.approve("UC-1", "attacker", 2))
        assertEquals(DeviceCodeState.Denied, flow.poll(grant.deviceCode, 3))
    }

    @Test fun deviceCode_pinnedOperator_approves() {
        val flow = DeviceCodeFlow(pinnedOperatorId = "op-1", codes = { "dc-2" to "UC-2" })
        val grant = flow.issue(0, 60_000)
        assertTrue(flow.approve("UC-2", "op-1", 2))
        assertEquals(DeviceCodeState.Approved("op-1"), flow.poll(grant.deviceCode, 3))
    }

    @Test fun deviceCode_expires() {
        val flow = DeviceCodeFlow("op-1", codes = { "dc-3" to "UC-3" })
        val grant = flow.issue(0, 10)
        assertEquals(DeviceCodeState.Expired, flow.poll(grant.deviceCode, 100))
    }

    @Test fun deviceCodeGrant_toString_redactsCodes() {
        val g = DeviceCodeGrant("secret-device", "SECRET-USER", 5)
        assertFalse(g.toString().contains("secret-device"))
        assertFalse(g.toString().contains("SECRET-USER"))
    }
}
