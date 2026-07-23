package com.tneff.cyppieagents.boot

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-811 — the boot-time fail-closed lock: a second hub on the SAME loopback IP is REJECTED at boot (the on-loopback
 * cookie-jar-sharing hole, §9.5). Non-vacuous: the FIRST acquire succeeds (positive control — not always-reject), a
 * SECOND on the same IP throws (the reject), a DISTINCT loopback IP is allowed (separate cookie jars), and an
 * off-loopback host is not applicable (null). Mutation: remove the `throw` in [LoopbackHubLock.acquireOrReject] → the
 * second acquire returns instead of rejecting → `secondHubSameLoopbackIp_rejected` reds (green-but-dead caught).
 */
class Cyp811LoopbackHubLockTest {

    private val dir = Files.createTempDirectory("cyp811-lock")

    @Test
    fun firstHubAcquires_secondHubSameLoopbackIp_rejected_thenReleaseAllowsReacquire() {
        // positive control: the FIRST hub on 127.0.0.1 takes the lock.
        val first = LoopbackHubLock.acquireOrReject("127.0.0.1", dir)
        assertNotNull(first, "the first hub on a loopback IP acquires the boot lock")
        // ★ the reject: a SECOND hub on the SAME loopback IP → boot rejected (shared cookie jar → replay).
        assertFailsWith<IllegalStateException> { LoopbackHubLock.acquireOrReject("127.0.0.1", dir) }
        // releasing the first frees the IP → a later hub may re-acquire (the lock is process-lifetime, not permanent).
        first.close()
        val reacquired = LoopbackHubLock.acquireOrReject("127.0.0.1", dir)
        assertNotNull(reacquired); reacquired.close()
    }

    @Test
    fun distinctLoopbackIp_isAllowed_separateCookieJars() {
        val a = LoopbackHubLock.acquireOrReject("127.0.0.1", dir)
        assertNotNull(a)
        // 127.0.0.2 is a DIFFERENT loopback IP → a different cookie jar → allowed to co-exist.
        val b = LoopbackHubLock.acquireOrReject("127.0.0.2", dir)
        assertNotNull(b, "a distinct loopback IP is a separate cookie jar — co-existence is safe")
        a.close(); b.close()
    }

    @Test
    fun offLoopbackHost_isNotApplicable_null() {
        // off-loopback: S-AAL2b already disables the browser-operator posture → no cookie-jar hole → no lock needed.
        assertNull(LoopbackHubLock.acquireOrReject("0.0.0.0", dir))
        assertNull(LoopbackHubLock.acquireOrReject("192.168.1.10", dir))
    }
}
