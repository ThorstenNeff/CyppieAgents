package com.tneff.cyppieagents.window

import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-656 (token freshness) — the PURE decision [titleBarTokenStale] that greys the context-token count when its
 * feed can't confirm the figure is current. LIVE → not stale (full colour); any non-LIVE or absent (`null` = no
 * feed) → stale (greyed). Mutation `connection == LIVE` (invert) reddens [liveFeed_isNotStale]; dropping the guard
 * (`= false`) reddens [nonLiveOrAbsentFeed_isStale].
 */
class Cyp656TokenStaleTest {

    @Test
    fun liveFeed_isNotStale() {
        assertFalse(titleBarTokenStale(ConnectionStatus.LIVE), "a LIVE feed can refresh the count → not stale")
    }

    @Test
    fun nonLiveOrAbsentFeed_isStale() {
        assertTrue(titleBarTokenStale(ConnectionStatus.DISCONNECTED), "a dropped feed can't refresh the count → stale")
        assertTrue(titleBarTokenStale(ConnectionStatus.CONNECTING), "a reconnecting feed can't confirm the count → stale")
        assertTrue(titleBarTokenStale(null), "no feed (system window / unwired) → stale, never presented as fresh")
    }
}
