package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.ed25519SpkiToRaw
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import java.nio.file.Files
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-525 Inc 2 Axis-2 — QA independent persist-over-restart, the CONNECTED-survives-restart tooth.
 *
 * Fixture-fidelity delta over `Cyp525PersistentDeviceKeyTest` ([[prod-wiring-fixture-fidelity]]): Backend asserts the
 * reloaded key's `.encoded` BYTES equal across a relaunch. That is necessary but not the CONNECTED property — what
 * matters is **signing continuity**: a PoP signed by the key AFTER a restart must verify against the public key the hub
 * enrolled BEFORE the restart (raw-32B). These teeth drive that end-to-end with a real disk reload (a fresh instance =
 * a relaunch) + real JCA sign/verify, so a persist bug that preserves bytes but breaks signing (or silently regenerates)
 * is caught as "the hub's enrolled anchor no longer matches → never CONNECTED again", not a green byte-compare.
 *
 * **CYP-583:** a present-but-corrupt/truncated custody file now fails closed as [DeviceKeyCustody.Corrupt] (no silent
 * regenerate) — the former "corrupt → regenerate a usable key" teeth are inverted to assert the fail-closed posture.
 */
class QaCyp525PersistRestartTest {

    private val h = ByteArray(32) { (it + 1).toByte() }
    private val hubId = "hub_c1d6f5ffd892a03d"
    private fun tmpKeyFile() = Files.createTempDirectory("cyp525-axis2").resolve("operator-device.key")
    private fun sign(priv: java.security.PrivateKey, msg: ByteArray) =
        Signature.getInstance("Ed25519").run { initSign(priv); update(msg); sign() }
    private fun verify(pub: java.security.PublicKey, msg: ByteArray, sig: ByteArray) =
        Signature.getInstance("Ed25519").run { initVerify(pub); update(msg); verify(sig) }

    @Test
    fun postRestartKey_signsPoP_thatVerifiesAgainstPreRestartEnrolledPublic() {
        val file = tmpKeyFile()
        val enrolled = assertIs<DeviceKeyCustody.Ready>(PersistentOperatorDeviceKey(file).loadOrGenerate()).keyPair.public // the pubkey the hub enrolls (raw-32B)
        // ── a REAL restart: a brand-new instance reads the SAME custody file back from disk ──
        val afterRestart = assertIs<DeviceKeyCustody.Ready>(PersistentOperatorDeviceKey(file).loadOrGenerate()).keyPair
        val nonce = ByteArray(32) { 4 }
        val pop = sign(afterRestart.private, operatorAuthChallenge(h, hubId, nonce)) // signed by the POST-restart key
        // ★ the CONNECTED-survives-restart invariant: the enrolled (pre-restart) public still verifies the new PoP.
        assertTrue(
            verify(enrolled, operatorAuthChallenge(h, hubId, nonce), pop),
            "a PoP signed after a restart verifies against the pre-restart enrolled public — CONNECTED survives a relaunch",
        )
        // raw-32B continuity (the wire/enroll form the hub actually stores).
        assertContentEquals(
            ed25519SpkiToRaw(enrolled.encoded), ed25519SpkiToRaw(afterRestart.public.encoded),
            "the raw-32B enrolled key is identical across the restart",
        )
    }

    @Test
    fun corruptFile_failsClosedAsCorrupt_notRegenerated() {
        // CYP-583 (inverts the former "corrupt → usable regenerated key"): a present-but-corrupt custody file fails
        // closed as DeviceCustodyCorrupt — never a silent regenerate (the tamper→re-enroll seizure vector).
        val file = tmpKeyFile()
        Files.write(file, ByteArray(40) { 0x7f }) // garbage that is NOT a valid [len‖pkcs8‖x509] blob
        assertIs<DeviceKeyCustody.Corrupt>(
            PersistentOperatorDeviceKey(file).loadOrGenerate(),
            "a corrupt custody file fails closed as Corrupt, never silently regenerated",
        )
    }

    @Test
    fun truncatedBlob_failsClosedAsCorrupt_notRegenerated() {
        val file = tmpKeyFile()
        // a plausible-but-truncated blob: a 4-byte length header claiming a large body, then too few bytes.
        Files.write(file, byteArrayOf(0, 0, 2, 0, 1, 2, 3))
        assertIs<DeviceKeyCustody.Corrupt>(
            PersistentOperatorDeviceKey(file).loadOrGenerate(), // load() throws internally → Corrupt (CYP-583), no regen
            "a truncated custody blob fails closed as Corrupt",
        )
    }

    @Test
    fun twoIndependentPaths_holdDistinctKeys_noCrossContamination() {
        val a = assertIs<DeviceKeyCustody.Ready>(PersistentOperatorDeviceKey(tmpKeyFile()).loadOrGenerate()).keyPair
        val b = assertIs<DeviceKeyCustody.Ready>(PersistentOperatorDeviceKey(tmpKeyFile()).loadOrGenerate()).keyPair
        // distinct custody files → distinct keys (a sanity guard that persistence is path-scoped, not a static/global).
        assertFalse(a.public.encoded.contentEquals(b.public.encoded), "distinct custody paths hold distinct device keys")
    }
}
