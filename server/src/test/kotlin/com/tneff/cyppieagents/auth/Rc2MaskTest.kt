package com.tneff.cyppieagents.auth

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-178 / RC2 — **hermetic mask-correctness proof** for [Rc2Mask] (the Tester's load-bearing concern:
 * prove the mask, don't assert it in prose). Two directions, over bodies mirroring the real Kratos v1.3.0
 * login-flow shape:
 *
 *  - **positive:** two branches differing ONLY in per-flow nonces (`id`, timestamps, `action`, csrf) and the
 *    echoed `identifier` (attacker input) — both the generic `4000006` — mask to a **byte-identical** result,
 *    and the enumeration signal `4000006` SURVIVES the mask;
 *  - **negative:** a generic `4000006` body vs an account-exists `4000007` body must STAY different after the
 *    mask — the mask must never hide the enumeration tell (else a real leak would read as parity).
 *
 * Full-body byte-equality is the gate (fail-safe): anything the mask does not explicitly normalise that
 * differs will fail parity, which is the safe direction.
 */
class Rc2MaskTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Two login branches identical except per-flow nonces + the echoed identifier; both generic 4000006.
    private val absentBody = loginBody(id = "AAA", ts = "2026-07-01T00:00:00Z", csrf = "csrfA", identifier = "rc2-absent@cyppie.dev", msgId = 4000006, msgText = "The provided credentials are invalid")
    private val wrongPwBody = loginBody(id = "BBB", ts = "2026-07-01T11:11:11Z", csrf = "csrfB", identifier = "rc2-test@cyppie.dev", msgId = 4000006, msgText = "The provided credentials are invalid")
    // An account-exists leak (register 4000007) — must remain distinct after masking.
    private val existsBody = loginBody(id = "CCC", ts = "2026-07-01T22:22:22Z", csrf = "csrfC", identifier = "rc2-test@cyppie.dev", msgId = 4000007, msgText = "An account with the same identifier exists already")

    @Test
    fun positive_nonceOnlyDifferences_maskToIdentical_andSignalSurvives() {
        val a = Rc2Mask.maskBody(json, absentBody)
        val b = Rc2Mask.maskBody(json, wrongPwBody)
        assertEquals(a, b, "two enum-safe branches differing only in nonces + identifier echo must mask byte-identical")
        assertTrue(a.contains("4000006"), "the enumeration signal (message id 4000006) must SURVIVE the mask")
        assertTrue(a.contains("<IDENTIFIER>") && a.contains("<masked>"), "the identifier echo + nonces must be normalised")
        assertTrue(!a.contains("rc2-absent@cyppie.dev") && !a.contains("csrfA") && !a.contains("AAA"), "no raw nonce/identifier value may survive")
    }

    @Test
    fun recovery_echoedEmailNode_isMasked_soPresentAndAbsentMatch() {
        // The recovery flow echoes the submitted address under a node named "email" (not "identifier");
        // both branches reach the generic sent_email state, differing ONLY in that echo + nonces.
        val present = recoveryBody(id = "RRR", ts = "2026-07-01T00:00:00Z", email = "rc2-test@cyppie.dev")
        val absent = recoveryBody(id = "SSS", ts = "2026-07-01T09:09:09Z", email = "rc2-absent@cyppie.dev")
        val a = Rc2Mask.maskBody(json, present)
        val b = Rc2Mask.maskBody(json, absent)
        assertEquals(a, b, "recovery present vs absent must mask byte-identical (the echoed email is attacker input)")
        assertTrue(!a.contains("rc2-test@cyppie.dev") && !a.contains("rc2-absent@cyppie.dev"), "the echoed email must be normalised")
        assertTrue(a.contains("1060003") && a.contains("sent_email"), "the generic recovery signal must survive the mask")
    }

    @Test
    fun negative_differentEnumSignal_staysDistinctAfterMask() {
        val safe = Rc2Mask.maskBody(json, absentBody) // 4000006
        val leak = Rc2Mask.maskBody(json, existsBody) // 4000007
        assertNotEquals(safe, leak, "the mask must NOT hide the enumeration tell: 4000006 vs 4000007 must stay distinct")
        assertTrue(leak.contains("4000007"), "the account-exists tell (4000007) must survive the mask")
    }

    /** A compact body mirroring the Kratos v1.3.0 login-flow response: nonce fields + csrf/identifier nodes + a message. */
    private fun loginBody(id: String, ts: String, csrf: String, identifier: String, msgId: Int, msgText: String): String =
        """{"id":"$id","type":"api","expires_at":"$ts","issued_at":"$ts","request_url":"http://k/self-service/login/api",""" +
            """"ui":{"action":"http://k/self-service/login?flow=$id","method":"POST","nodes":[""" +
            """{"type":"input","group":"default","attributes":{"name":"csrf_token","type":"hidden","value":"$csrf","node_type":"input"}},""" +
            """{"type":"input","group":"default","attributes":{"name":"identifier","type":"text","value":"$identifier","node_type":"input"},"meta":{"label":{"id":1070002,"text":"E-Mail","type":"info"}}}],""" +
            """"messages":[{"id":$msgId,"text":"$msgText","type":"error"}]},""" +
            """"created_at":"$ts","updated_at":"$ts","state":"choose_method"}"""

    /** A compact recovery-flow response: the echoed address is under a node named "email"; generic sent_email. */
    private fun recoveryBody(id: String, ts: String, email: String): String =
        """{"id":"$id","type":"api","expires_at":"$ts","issued_at":"$ts","request_url":"http://k/self-service/recovery/api","active":"code",""" +
            """"ui":{"action":"http://k/self-service/recovery?flow=$id","method":"POST","nodes":[""" +
            """{"type":"input","group":"code","attributes":{"name":"email","type":"submit","value":"$email","node_type":"input"}}],""" +
            """"messages":[{"id":1060003,"text":"An email containing a recovery code has been sent.","type":"info"}]},""" +
            """"created_at":"$ts","updated_at":"$ts","state":"sent_email"}"""
}
