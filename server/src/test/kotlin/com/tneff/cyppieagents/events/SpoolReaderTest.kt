package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive

/**
 * ST4 (CYP-38) hook-spool ingestion: JSONL lines → content-free `hook.fired` drafts, `sourceTs`
 * preserved while `ts`/`seq` are assigned at append, idempotent across a restart, partial lines
 * deferred, bad lines skipped.
 */
class SpoolReaderTest {

    private inline fun withSpool(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("spool-st4")
        try {
            block(dir.resolve("hooks.spool").toFile())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun File.appendLine(json: String) = appendText("$json\n")
    private fun List<EventDraft>.names() = map { it.detail["name"]!!.jsonPrimitive.content }

    @Test
    fun parsesHookLine_preservesSourceTs_metadataOnly() = runBlocking {
        withSpool { spool ->
            spool.appendLine("""{"name":"PreCompact","outcome":"ok","sourceTs":1719000000000,"agentId":"backend","secret":"sk-leak"}""")
            val drafts = SpoolReader(spool.toPath()).readNew()
            assertEquals(1, drafts.size)
            val d = drafts.first()
            assertEquals(EventType.HOOK_FIRED, d.type)
            assertEquals(1719000000000L, d.sourceTs)
            assertEquals("backend", d.agentId)
            assertEquals("PreCompact", d.detail["name"]!!.jsonPrimitive.content)
            assertEquals("ok", d.detail["outcome"]!!.jsonPrimitive.content)
            // Metadata-only: a non-whitelisted field (here a planted secret) must NOT reach detail.
            assertEquals(setOf("name", "outcome"), d.detail.keys)
            assertFalse(d.detail.toString().contains("sk-leak"))
        }
    }

    @Test
    fun appendAssignsTsAndSeq_sourceTsStaysSeparate() = runBlocking {
        withSpool { spool ->
            spool.appendLine("""{"name":"SessionStart","sourceTs":111}""")
            val sink = InMemoryEventSink(SystemTimeSource())
            val recorder = EventRecorder(sink, this, capacity = 64, batchSize = 8)
            recorder.start()
            SpoolReader(spool.toPath()).readNew().forEach { recorder.record(it) }
            recorder.stop()

            val ev = sink.query(EventFilter(type = EventType.HOOK_FIRED), Page(limit = 10)).events.single()
            assertEquals(111L, ev.sourceTs) // observed time preserved
            assertTrue(ev.seq >= 1L) // seq assigned at append
            assertTrue(ev.ts > 111L) // ts is wall-clock now(), not the sourceTs
        }
    }

    @Test
    fun idempotentAcrossRestart_noDuplicates() = runBlocking {
        withSpool { spool ->
            spool.appendLine("""{"name":"PreCompact","sourceTs":1}""")
            spool.appendLine("""{"name":"PostCompact","sourceTs":2}""")
            val r1 = SpoolReader(spool.toPath())
            assertEquals(2, r1.readNew().size)
            assertEquals(0, r1.readNew().size) // nothing new

            // "Restart": a fresh reader on the same paths loads the persisted offset marker.
            val r2 = SpoolReader(spool.toPath())
            assertEquals(0, r2.readNew().size, "re-tail after restart must not duplicate")

            spool.appendLine("""{"name":"SessionStart","sourceTs":3}""")
            assertEquals(listOf("SessionStart"), r2.readNew().names())
        }
    }

    @Test
    fun trailingPartialLine_deferredUntilNewline() = runBlocking {
        withSpool { spool ->
            // Second line is mid-write: NO trailing newline yet.
            spool.writeText("""{"name":"A","sourceTs":1}""" + "\n" + """{"name":"B","sourceTs":2}""")
            val reader = SpoolReader(spool.toPath())
            assertEquals(listOf("A"), reader.readNew().names()) // only the complete line

            spool.appendText("\n") // hook finishes writing line B
            assertEquals(listOf("B"), reader.readNew().names())
        }
    }

    @Test
    fun badOrNamelessLines_skipped_notFatal() = runBlocking {
        withSpool { spool ->
            spool.appendLine("not json at all")
            spool.appendLine("""{"outcome":"ok","sourceTs":1}""") // no name → skipped
            spool.appendLine("""{"name":"Valid","sourceTs":2}""")
            assertEquals(listOf("Valid"), SpoolReader(spool.toPath()).readNew().names())
        }
    }

    @Test
    fun missingSpoolFile_isEmpty_notError() = runBlocking {
        withSpool { spool ->
            // spool file never created
            assertEquals(emptyList(), SpoolReader(spool.toPath()).readNew())
        }
    }
}
