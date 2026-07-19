package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.MentionResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-744 — the recognition teeth (SPEC-correctness; Backend2 owns them from commit 1, blast-radius = all clients
 * since detection moved into this ONE resolver). Distinct from UIUX2's PARITY tooth (bit-exact vs the old 704-client
 * `mentionSegments` fixtures = the migration proof), which lands with the client fixtures. Each test's `Mutation:`
 * note names what reddens it.
 */
class MentionResolverTest {

    private val roster = setOf("frontend", "frontend-dev", "backend", "dev5", "po")
    private fun spans(body: String, r: Collection<String> = roster) = MentionResolver.resolve(body, r)
    private fun ids(body: String, r: Collection<String> = roster) = spans(body, r).map { it.id }

    @Test fun longestRosterIdMatchNotMaximalAlnumRun() {
        // @frontend-dev with BOTH ids present → the whole hyphenated id, NOT "frontend" (the alnum-run drift).
        val s = spans("hi @frontend-dev please")
        assertEquals(1, s.size)
        assertEquals("frontend-dev", s[0].id)
        assertEquals("@frontend-dev", "hi @frontend-dev please".substring(s[0].start, s[0].end))
        // Mutation: match a maximal-alnum run instead → id="frontend" → reddens.
    }

    @Test fun prefixNotBoundaryTerminatedIsNoSpan() {
        // @frontend-dev with ONLY "frontend" in roster → NO span ("frontend" is followed by '-', a SAFE_ID char).
        assertTrue(spans("@frontend-dev", setOf("frontend", "backend")).isEmpty())
    }

    @Test fun trailingCharsBreakOrAllowTheMatch() {
        assertTrue(spans("@dev5x").isEmpty())        // dev5x∉roster; dev5 is a prefix but not boundary-terminated
        assertEquals(listOf("dev5"), ids("@dev5!"))  // '!' is a boundary → dev5 matches
    }

    @Test fun sigilBoundaryEmailIsNotAMention() {
        assertTrue(spans("mail foo@backend.com").isEmpty()) // '@' follows 'o' (SAFE_ID) → not a sigil
        assertEquals(listOf("backend"), ids("(@backend)")) // '(' before '@' → sigil
    }

    @Test fun caseInsensitiveCanonicalId() {
        val body = "@Backend and @BACKEND"
        val s = spans(body)
        assertEquals(listOf("backend", "backend"), s.map { it.id }) // canonical lowercase id
        assertEquals("@Backend", body.substring(s[0].start, s[0].end)) // span covers the literal typed casing
    }

    @Test fun fencedCodeIsExempt() {
        val body = "before @backend\n```\n@frontend here\n```\nafter @dev5"
        assertEquals(listOf("backend", "dev5"), ids(body)) // @frontend inside the fence → no span
        // Mutation: skip code-masking → @frontend inside the fence reddens (false notify from a code sample).
    }

    @Test fun inlineCodeIsExempt() {
        assertEquals(listOf("backend"), ids("use `@frontend` verbatim but @backend really"))
    }

    @Test fun quoteLineIsIncluded() {
        assertEquals(listOf("po"), ids("> @po said hi")) // '>' quotes are scanned as text (INCLUDE)
    }

    @Test fun emojiPrefixUtf16Offsets() {
        val body = "😀 @dev5" // 😀 (surrogate pair = 2 UTF-16 code units) + space + @dev5
        val s = spans(body)
        assertEquals(1, s.size)
        assertEquals(3, s[0].start) // 😀 = indices 0..1, space = 2, '@' = 3
        assertEquals("@dev5", body.substring(s[0].start, s[0].end))
        // Mutation: count code POINTS not UTF-16 units → start=2 → reddens.
    }

    @Test fun spansSortedAscendingNonOverlapping() {
        val s = spans("@po @backend @dev5")
        assertEquals(listOf("po", "backend", "dev5"), s.map { it.id })
        for (k in 1 until s.size) assertTrue(s[k].start >= s[k - 1].end) // ascending + non-overlapping
    }

    @Test fun mentionsYouFromTheSamePass() {
        val s = spans("@backend and @po")
        assertTrue(MentionResolver.mentionsYou("backend", s))
        assertTrue(MentionResolver.mentionsYou("PO", s))    // case-insensitive subject
        assertFalse(MentionResolver.mentionsYou("dev5", s)) // not mentioned
    }

    @Test fun spanInvariantHolds() {
        val body = "ping @Frontend-Dev now"
        for (span in spans(body)) assertEquals("@" + span.id, body.substring(span.start, span.end).lowercase())
    }

    @Test fun emptyBodyAndEmptyRoster() {
        assertTrue(spans("").isEmpty())
        assertTrue(MentionResolver.resolve("@backend", emptySet()).isEmpty())
    }

    @Test fun nonMemberMentionIsNoSpan() {
        assertTrue(spans("@dev5", setOf("po", "backend")).isEmpty()) // roster = channel members only
    }
}
