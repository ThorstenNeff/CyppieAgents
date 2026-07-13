package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.operator.ed25519SpkiToRaw
import com.tneff.cyppieagents.operator.operatorAuthChallenge
import java.nio.file.Files
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
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
        val enrolled = PersistentOperatorDeviceKey(file).loadOrGenerate().public // the pubkey the hub enrolls (raw-32B)
        // ── a REAL restart: a brand-new instance reads the SAME custody file back from disk ──
        val afterRestart = PersistentOperatorDeviceKey(file).loadOrGenerate()
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
    fun corruptFile_regeneratesA_USABLE_key_notBricked() {
        val file = tmpKeyFile()
        Files.write(file, ByteArray(40) { 0x7f }) // garbage that is NOT a valid [len‖pkcs8‖x509] blob
        val kp = PersistentOperatorDeviceKey(file).loadOrGenerate() // must not throw
        val nonce = ByteArray(32) { 5 }
        val msg = operatorAuthChallenge(h, hubId, nonce)
        // sharper than "an Ed25519 key": the regenerated key must actually SIGN + VERIFY (usable for a fresh enroll).
        assertTrue(verify(kp.public, msg, sign(kp.private, msg)), "the regenerated key is a usable signer (corrupt file never bricks connect)")
        // and it persisted the NEW key → a subsequent reload reuses it (not another regen).
        val reloaded = PersistentOperatorDeviceKey(file).loadOrGenerate()
        assertContentEquals(kp.public.encoded, reloaded.public.encoded, "the regenerated key was persisted + is reused")
    }

    @Test
    fun truncatedBlob_regenerates_notBricked() {
        val file = tmpKeyFile()
        // a plausible-but-truncated blob: a 4-byte length header claiming a large body, then too few bytes.
        Files.write(file, byteArrayOf(0, 0, 2, 0, 1, 2, 3))
        val kp = PersistentOperatorDeviceKey(file).loadOrGenerate() // load() throws internally → regen, no brick
        val msg = operatorAuthChallenge(h, hubId, ByteArray(32) { 6 })
        assertTrue(verify(kp.public, msg, sign(kp.private, msg)), "a truncated custody blob regenerates a usable key")
    }

    @Test
    fun twoIndependentPaths_holdDistinctKeys_noCrossContamination() {
        val a = PersistentOperatorDeviceKey(tmpKeyFile()).loadOrGenerate()
        val b = PersistentOperatorDeviceKey(tmpKeyFile()).loadOrGenerate()
        // distinct custody files → distinct keys (a sanity guard that persistence is path-scoped, not a static/global).
        assertFalse(a.public.encoded.contentEquals(b.public.encoded), "distinct custody paths hold distinct device keys")
    }
}
