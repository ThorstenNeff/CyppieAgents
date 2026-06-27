package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive

/** ST3 (CYP-37) spool tailing + the N2 truncate-reset guard requested in the CYP-38 review. */
class SpoolTailerTest {

    private inline fun withSpool(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("spool-tailer")
        try {
            block(dir.resolve("hooks.spool").toFile())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun tailerRecordsHookFiredIntoSink() = runBlocking {
        withSpool { spool ->
            spool.appendText("""{"name":"PreCompact","outcome":"ok","sourceTs":1}""" + "\n")
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val sink = InMemoryEventSink(SystemTimeSource())
            val recorder = EventRecorder(sink, scope).also { it.start() }
            val tailer = SpoolTailer(SpoolReader(spool.toPath()), recorder, scope, intervalMs = 20).also { it.start() }

            withTimeout(5_000) {
                while (sink.query(EventFilter(type = EventType.HOOK_FIRED), Page(limit = 10)).events.isEmpty()) delay(10)
            }
            val ev = sink.query(EventFilter(type = EventType.HOOK_FIRED), Page(limit = 10)).events.first()
            assertEquals("PreCompact", ev.detail["name"]!!.jsonPrimitive.content)
            assertEquals(1L, ev.sourceTs)

            tailer.stop()
            recorder.stop()
            scope.cancel()
        }
    }

    @Test
    fun spoolReader_truncateResetsOffset() = runBlocking {
        withSpool { spool ->
            spool.appendText("""{"name":"PreCompact","sourceTs":1}""" + "\n")
            spool.appendText("""{"name":"PostCompact","sourceTs":2}""" + "\n")
            val reader = SpoolReader(spool.toPath())
            assertEquals(2, reader.readNew().size)

            // File replaced with a SHORTER one → offset > length → reader resets to start and re-reads.
            spool.writeText("""{"name":"Fresh","sourceTs":9}""" + "\n")
            assertEquals(listOf("Fresh"), reader.readNew().map { it.detail["name"]!!.jsonPrimitive.content })
        }
    }
}
