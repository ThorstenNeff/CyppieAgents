package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Message
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-415 (S-F / D4) — [SqliteMessageStore] durability + query parity with [InMemoryMessageStore].
 *
 * **Money tooth ([messages_survive_a_restart]):** write messages, CLOSE the store, open a NEW store on the SAME
 * db file → the messages are still there. This is the D4 gap (production was [InMemoryMessageStore], so messages
 * were lost on every boot). Non-vacuity: an in-memory / non-persisting append (the mutation) makes the reopened
 * store empty → this test reds.
 */
class Cyp415SqliteMessageStoreTest {

    private val dir = Files.createTempDirectory("cyp415-msg")
    private val db = dir.resolve("messages.db")
    private var store: SqliteMessageStore? = null

    @AfterTest fun tearDown() {
        store?.close()
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun open(): SqliteMessageStore = SqliteMessageStore(db).also { store = it }

    @Test
    fun messages_survive_a_restart() {
        open().apply {
            append(Message("m1", "po-backend", "po", "hello", ts = 1))
            append(Message("m2", "po-backend", "backend", "world", ts = 2))
            close()
        }
        // "restart": a fresh store instance over the SAME db file.
        val reopened = open()
        val got = reopened.byChannel("po-backend")
        assertEquals(listOf("m1", "m2"), got.map { it.id }, "messages persisted across a store restart (D4)")
        assertEquals("hello", got[0].body)
    }

    @Test
    fun byChannel_filters_by_channel_and_since_in_append_order() {
        val s = open()
        s.append(Message("a1", "po-backend", "po", "b", ts = 10))
        s.append(Message("b1", "po-frontend", "po", "b", ts = 11))
        s.append(Message("a2", "po-backend", "po", "b", ts = 20))

        assertEquals(listOf("a1", "a2"), s.byChannel("po-backend").map { it.id }, "only this channel, in append order")
        assertEquals(listOf("a2"), s.byChannel("po-backend", since = 10).map { it.id }, "strictly after `since`")
        assertEquals(emptyList(), s.byChannel("nope").map { it.id })
    }

    @Test
    fun acrossChannels_aggregates_and_since_and_empty() {
        val s = open()
        s.append(Message("a1", "po-backend", "po", "b", ts = 1))
        s.append(Message("b1", "po-frontend", "po", "b", ts = 2))
        s.append(Message("c1", "po-other", "po", "b", ts = 3))

        assertEquals(
            listOf("a1", "b1"),
            s.acrossChannels(listOf("po-backend", "po-frontend")).map { it.id },
            "inbox aggregation across channels, append order",
        )
        assertEquals(listOf("b1"), s.acrossChannels(listOf("po-backend", "po-frontend"), since = 1).map { it.id })
        assertEquals(emptyList(), s.acrossChannels(emptyList()).map { it.id }, "no channels → empty, no SQL")
    }
}
