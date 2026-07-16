package com.tneff.cyppieagents.window

import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-656 substrate tooth — the title-bar connection lookup ([titleBarConnection]) is a PURE decision so the
 * fail-closed contract is pinned without a compose render.
 *
 * The load-bearing property: an **agent** window resolves its real `session.connection`, but a **system** window
 * (comm/acl/event-log/settings) or an unknown/not-yet-open id is ABSENT → **`null` = "no live feed"**, NEVER a
 * fabricated [ConnectionStatus.LIVE]. That fail-open fallback ("no info → claim fresh") is exactly the bug class
 * CYP-656 removes; re-adding a `?: ConnectionStatus.LIVE` reddens [systemWindowOrUnknownId_isNull_notFabricatedLive].
 */
class Cyp656TitleBarConnectionTest {

    @Test
    fun agentWindow_returnsItsRealConnection() {
        val map = mapOf(
            "backend" to ConnectionStatus.DISCONNECTED,
            "frontend" to ConnectionStatus.LIVE,
            "po" to ConnectionStatus.CONNECTING,
        )
        assertEquals(ConnectionStatus.DISCONNECTED, titleBarConnection(map, "backend"))
        assertEquals(ConnectionStatus.LIVE, titleBarConnection(map, "frontend"))
        assertEquals(ConnectionStatus.CONNECTING, titleBarConnection(map, "po"))
    }

    @Test
    fun systemWindowOrUnknownId_isNull_notFabricatedLive() {
        // The fail-closed core: absent id → null, never LIVE. Mutation `connections[id] ?: LIVE` → these redden.
        assertNull(titleBarConnection(emptyMap(), "comm"), "a system window (no per-agent feed) must be null, not LIVE")
        assertNull(
            titleBarConnection(mapOf("backend" to ConnectionStatus.LIVE), "settings"),
            "an unknown/absent id must be null (no feed), never a fabricated LIVE",
        )
    }
}
