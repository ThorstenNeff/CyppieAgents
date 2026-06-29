package com.tneff.cyppieagents.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * S17 / CYP-94 — the operator-only cross-project event read override policy ([resolveEventScope]), the
 * SINGLE source shared by `/api/events` (REST) and `/ws/events` (WS). Returns the `EventFilter.projectId`
 * to scope by (null = unscoped). Pure, so the policy is mutation-tested directly and platform-neutrally.
 */
class EventScopeTest {

    private val authorized = setOf("alpha", "beta")

    // default = forced-active (CYP-102 unchanged)
    @Test fun noOverride_forcedActive() = assertEquals("alpha", resolveEventScope(null, "alpha", authorized))
    @Test fun blankOverride_forcedActive() = assertEquals("alpha", resolveEventScope("", "alpha", authorized))

    // an authorized other project is honored
    @Test fun authorizedProject_honored() = assertEquals("beta", resolveEventScope("beta", "alpha", authorized))

    // `all` → unscoped (MVP single-tenant = the operator's own projects)
    @Test fun all_unscoped() = assertNull(resolveEventScope("all", "alpha", authorized))
    @Test fun all_withNoAuthorized_failsClosedToActive() =
        assertEquals("alpha", resolveEventScope("all", "alpha", emptySet()))

    // the load-bearing guard: an UNAUTHORIZED / garbage id never widens — falls back to active
    @Test fun unauthorizedProject_failsClosedToActive() =
        assertEquals("alpha", resolveEventScope("outsider", "alpha", authorized))
    @Test fun garbage_failsClosedToActive() =
        assertEquals("alpha", resolveEventScope("'; DROP", "alpha", authorized))
}
