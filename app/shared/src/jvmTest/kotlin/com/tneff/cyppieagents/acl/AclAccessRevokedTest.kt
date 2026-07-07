package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-289 guard (QA, atomic with the ACL terminal-revoke mirror fix) — a **terminal** 1008-revoke on the
 * ACL `/ws/comm` stream must NOT reconnect.
 *
 * The ACL stream (AclWsClient) closes with 1008 (VIOLATED_POLICY) on a revoked/invalid operator token; the
 * mirror fix maps that to [AclLiveEvent.AccessRevoked] (previously it degraded to a generic Disconnected,
 * indistinguishable from a transient drop, so CYP-289's `.reconnecting()` re-opened /ws/comm with the doomed
 * token forever — measured 1001 re-opens/1s before the fix). This pins the invariant: a source that emits
 * Connected → AccessRevoked → complete must leave subscriptions == 1 (the collect is cancelled, no reconnect).
 * Twin of `eventlog.EventTailAccessRevokedTest`; the shared blind-`.reconnecting()`-over-a-terminal-close trap.
 */
class AclAccessRevokedTest {

    /** Source: Connected → AccessRevoked → complete — the real 1008 path once the fix maps the close code. */
    private class AccessRevokedThenComplete : AclLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<AclLiveEvent> = flow {
            subscriptions.value += 1
            emit(AclLiveEvent.Connected)
            emit(AclLiveEvent.AccessRevoked)
        }
    }

    @Test
    fun accessRevoked_terminates_doesNotReconnectLoop() = runTest {
        val source = AccessRevokedThenComplete()
        AclViewModel(StubAclHub(), source, backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, source.subscriptions.value, "ACL AccessRevoked (1008) is terminal — subscriptions>1 means a reconnect LOOP on /ws/comm")
    }
}
