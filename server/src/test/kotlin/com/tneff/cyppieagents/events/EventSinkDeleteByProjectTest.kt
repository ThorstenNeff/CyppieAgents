package com.tneff.cyppieagents.events

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The event partition of the project cascade-delete (S13 / CYP-91). The heaviest proofs for the most
 * destructive op: the teardown must take out EXACTLY project A's events and leave project B's
 * partition completely intact (**no-cross-project**), remove all of A's (**no-orphan**), and never
 * wipe the table on a blank key (**fail-closed**).
 *
 * Run against BOTH the in-memory double and the SQLite impl so the seam's semantics are identical.
 */
class EventSinkDeleteByProjectTest {

    /** Seed A with [aCount] events and B with [bCount]; return the live ids of B for an exact-survival check. */
    private suspend fun seedAB(sink: EventSink, aCount: Int, bCount: Int): Set<String> {
        repeat(aCount) { sink.append(draft(agent = "a$it", team = "alpha")) }
        val bIds = (0 until bCount).map { sink.append(draft(agent = "b$it", team = "beta")).id }
        return bIds.toSet()
    }

    private suspend fun aEvents(sink: EventSink) =
        sink.query(EventFilter.ALL, Page(limit = 10_000)).events.filter { it.projectId == "alpha" }

    private suspend fun bEvents(sink: EventSink) =
        sink.query(EventFilter.ALL, Page(limit = 10_000)).events.filter { it.projectId == "beta" }

    private suspend fun assertCascade(sink: EventSink) {
        val bIdsBefore = seedAB(sink, aCount = 5, bCount = 3)
        assertEquals(5, aEvents(sink).size, "precondition: A seeded")

        val removed = sink.deleteByProject("alpha")

        assertEquals(5, removed, "return count = exactly A's events removed")
        assertEquals(0, aEvents(sink).size, "no-orphan: every A event gone")
        // no-cross-project: B's partition is byte-for-byte intact — same count AND same ids.
        assertEquals(bIdsBefore, bEvents(sink).map { it.id }.toSet(), "no-cross-project: B untouched")
    }

    private suspend fun assertBlankFailClosed(sink: EventSink) {
        seedAB(sink, aCount = 4, bCount = 2)
        // A stray event WITH a blank projectId (must not exist in prod, but defense-in-depth). It gives
        // the blank guard teeth: without the `isBlank() → return 0` short-circuit, a blank-key delete
        // would run `team_id = ''` and sweep exactly THIS event → the assert below reddens.
        sink.append(draft(team = ""))
        val removed = sink.deleteByProject("")
        assertEquals(0, removed, "fail-closed: a blank projectId deletes nothing — not even blank-projectId events")
        assertEquals(7, sink.query(EventFilter.ALL, Page(limit = 10_000)).events.size, "no event wiped on blank key")
    }

    @Test
    fun inMemory_cascade_isScopedToProject() = runBlocking { assertCascade(InMemoryEventSink(SystemTimeSource())) }

    @Test
    fun inMemory_blankProjectId_deletesNothing() =
        runBlocking { assertBlankFailClosed(InMemoryEventSink(SystemTimeSource())) }

    @Test
    fun sqlite_cascade_isScopedToProject() = runBlocking {
        withTempDb { db ->
            SqliteEventSink(db, SystemTimeSource()).use { assertCascade(it) }
        }
    }

    @Test
    fun sqlite_blankProjectId_deletesNothing() = runBlocking {
        withTempDb { db ->
            SqliteEventSink(db, SystemTimeSource()).use { assertBlankFailClosed(it) }
        }
    }

    private inline fun withTempDb(block: (java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("events-del-cyp91")
        try {
            block(dir.resolve("events.db"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
