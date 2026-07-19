package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.MentionResolver
import com.tneff.cyppieagents.model.MentionSpan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-744 §3-ii — the PARITY tooth (the migration proof): the server [MentionResolver] must reproduce the CYP-704
 * CLIENT resolver **bit-exact**, proven against the COMMITTED oracle `docs/design/cyp744-mention-parity-fixtures.json`
 * (25 cases GENERATED from the client, MentionSpan form, **UTF-16 code units, `end` EXCLUSIVE**). Loaded as DATA —
 * NOT a hand-port of the TS (that would be a second interpretation = the very drift CYP-744 ends).
 *
 * Distinct from [MentionResolverTest] (own-spec correctness): a resolver can be spec-correct AND deviate from the
 * client in an edge (it did — the unterminated-inline-backtick case). Only `server-output == client-fixtures` proves
 * the migration doesn't SILENTLY drift the chips/cue under users. The astral-char (emoji) cases force UTF-16 offsets:
 * ASCII agrees under code-unit AND code-point, so a code-point bug would ship silently — the emoji case reddens it.
 */
class MentionParityTest {

    @Serializable
    private data class Fixtures(val cases: List<Case>)

    @Serializable
    private data class Case(val name: String, val body: String, val rosterIds: List<String>, val expected: List<MentionSpan>)

    /** Walk up from the test working dir to the repo root to load the ONE committed oracle (single-source, no copy). */
    private fun oracleFile(): File {
        val rel = "docs/design/cyp744-mention-parity-fixtures.json"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("CYP-744 parity oracle not found walking up from '${System.getProperty("user.dir")}': $rel")
    }

    @Test fun serverResolverMatchesEveryClientFixtureBitExact() {
        val fixtures = Json { ignoreUnknownKeys = true }.decodeFromString(Fixtures.serializer(), oracleFile().readText())
        assertTrue(fixtures.cases.size >= 25, "expected the full oracle (>=25 cases), got ${fixtures.cases.size}")
        val mismatches = fixtures.cases.mapNotNull { c ->
            val actual = MentionResolver.resolve(c.body, c.rosterIds)
            if (actual != c.expected) "  ${c.name}: body=${jsonSafe(c.body)} expected=${c.expected} actual=$actual" else null
        }
        assertEquals(emptyList(), mismatches, "CYP-744 parity mismatches vs the client oracle:\n${mismatches.joinToString("\n")}")
        // Mutation: any resolver deviation in a single oracle case (e.g. code-point offsets, or a mis-masked backtick)
        // → that case appears in `mismatches` → RED. "Server finds mentions" is NOT sufficient; it must match EXACTLY.
    }

    private fun jsonSafe(s: String) = s.replace("\n", "\\n").take(48)
}
