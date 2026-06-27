package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommReducerTest {

    private fun msg(id: String, ts: Long, body: String = "x", from: String = "po") =
        Message(id = id, channelId = "c", from = from, body = body, ts = ts)

    @Test
    fun merge_dedupesById() {
        val once = CommReducer.merge(emptyList(), msg("m1", 1))
        val twice = CommReducer.merge(once, msg("m1", 1)) // reconnect replay
        assertEquals(1, twice.size)
    }

    @Test
    fun merge_ordersByTsThenId() {
        var items = emptyList<MessageItem>()
        items = CommReducer.merge(items, msg("b", 2))
        items = CommReducer.merge(items, msg("a", 1))
        items = CommReducer.merge(items, msg("c", 2)) // ts tie with "b" → id tiebreaker
        assertEquals(listOf("a", "b", "c"), items.map { it.message.id })
    }

    @Test
    fun mergeAll_isIdempotentAcrossReplays() {
        val batch = listOf(msg("m1", 1), msg("m2", 2))
        val first = CommReducer.mergeAll(emptyList(), batch)
        val replayed = CommReducer.mergeAll(first, batch) // history catch-up after reconnect
        assertEquals(2, replayed.size)
        assertEquals(listOf("m1", "m2"), replayed.map { it.message.id })
    }

    @Test
    fun addOptimistic_marksPending() {
        val items = CommReducer.addOptimistic(emptyList(), msg("local-0", 5))
        assertTrue(items.single().pending)
    }

    @Test
    fun confirm_replacesTempWithServerMessage() {
        val optimistic = CommReducer.addOptimistic(emptyList(), msg("local-0", 5, body = "hi"))
        val confirmed = CommReducer.confirm(optimistic, "local-0", msg("real-9", 6, body = "hi"))
        assertEquals(1, confirmed.size)
        assertEquals("real-9", confirmed.single().message.id)
        assertFalse(confirmed.single().pending)
    }

    @Test
    fun statusOf_mapsConnectionEvents() {
        assertEquals(ConnectionStatus.LIVE, CommReducer.statusOf(CommLiveEvent.Connected))
        assertEquals(ConnectionStatus.DISCONNECTED, CommReducer.statusOf(CommLiveEvent.Disconnected))
        assertEquals(null, CommReducer.statusOf(CommLiveEvent.MessageReceived(msg("m", 1))))
    }
}
