package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-247 S3 — the cap=1 teardown-on-switch BEHAVIOUR tooth (real embedded platform + real HTTP switch): at
 * the S3 production default (cap=1), switching away from a project stops the OUTGOING project's sessions.
 * alpha's `backend` is live from boot; after the switch to beta returns, alpha's session is gone (D3=B: only
 * the active project has live sessions → `active()==owner`).
 *
 * Scope note (honest): this pins the end-to-end teardown-on-switch outcome, NOT the *synchronous-before-rescope
 * ordering* — a liveness check cannot distinguish the r3 synchronous drain from the async `onActivated` eviction
 * (which also stops alpha, just after `rescope`). The ordering that makes in-flight attribution correct (drain
 * BEFORE rescope, joining the reader to quiescence — `destroy()` then `join()` since CYP-371, not the old
 * `cancelAndJoin`) is the load-bearing, mutation-verified [com.tneff.cyppieagents.connector
 * .Cyp247SwitchAttributionTest] (both `active()`-read axes attribute to the OUTGOING project).
 */
class Cyp247TeardownOnSwitchE2eTest {

    private fun twoProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("po", Role.PO), SeedAgent("worker"))),
        ),
        // no runtimeSuspensionCap → the S3 production default = 1 (teardown-on-switch).
    )

    @Test
    fun switch_synchronouslyDrainsTheOutgoingProject_beforeReturning() = runBlocking {
        twoProjects().use { p ->
            val alphaRt = requireNotNull(p.booted.runtimeRegistry.of("alpha"))
            assertNotNull(alphaRt.connectorSessions.session("backend"), "alpha's backend is live from boot")

            p.switchActive("beta") // awaits the real POST /api/projects/switch → the synchronous drain of alpha

            // The instant the switch returns, alpha's outgoing session is ALREADY stopped (drained before rescope) —
            // NOT still-live-awaiting-async-eviction. No delay/poll: the drain is part of the switch, synchronously.
            assertNull(alphaRt.connectorSessions.session("backend"),
                "cap=1 teardown-on-switch: alpha's session was drained synchronously before the switch returned")
        }
    }
}
