package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.MentionSpan

/**
 * CYP-744 (Notify Phase-2) — the ONE server-side `@agent`-mention resolver: the single source of truth for BOTH
 * the Display overlay (spans) AND the Notify signal (`mentionsYou`), so live and history can never disagree
 * (recognition happens ONCE, here). Pure + **shape-independent** — it returns [MentionSpan]s; which frontend
 * wrapper carries them (CYP-744 (a) `DeliveredMessage`) is decided at the transport boundary, NEVER here. It never
 * touches the stored `Message` / the `/ws/hub` `WireMessage` frame.
 *
 * Recognition rules (pinned):
 *  - **SIGIL BOUNDARY:** an `@` is a mention sigil only if the char before it is NOT a [isSafeIdChar] char
 *    (start-of-body or a separator) → `foo@bar` is not a mention (its `@` follows `o`).
 *  - **LONGEST ROSTER-ID MATCH** over `SAFE_ID = [A-Za-z0-9_-]` (incl. `-`/`_`), case-**INSENSITIVE**: after a sigil
 *    `@`, take the MAXIMAL SAFE_ID run; a roster id matches iff it equals that whole run (case-insensitively) —
 *    which is exactly "the longest boundary-terminated roster id", because any proper prefix ends before a SAFE_ID
 *    char (still inside the run) and is therefore NOT boundary-terminated. `@frontend-dev` with `{frontend,
 *    frontend-dev}` → `frontend-dev` (`frontend` alone isn't boundary-terminated: next char `-` is SAFE_ID); with
 *    only `{frontend}` → NO span; `@dev5x` with `{dev5}` → NO span. NOT a maximal-alnum run (ids may contain `-`/`_`).
 *  - **CODE EXEMPT:** fenced ```` ``` ````/`~~~` blocks and inline `` `…` `` code are masked (→ spaces, length-preserving)
 *    before scanning, so an `@id` in a code sample is never a mention. `>` quote lines are INCLUDED (scanned as
 *    text). Span offsets stay ABSOLUTE into the raw body (masking blanks only the scan surface, not coordinates).
 *  - Spans are sorted ascending by [MentionSpan.start] and **non-overlapping** (a matched span consumes its run).
 *  - Offsets are **UTF-16 code units** (Kotlin String indices) into the raw body; a span covers the literal typed
 *    `@<run>`, and [MentionSpan.id] is the canonical (lowercase roster) id → `body.substring(start,end).lowercase()
 *    == "@" + id`.
 */
object MentionResolver {

    /** `[A-Za-z0-9_-]` — the mention-id charset (`Agent.id`/`slugId` may contain `-`/`_`; not wire-constrained). */
    private fun isSafeIdChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /**
     * Resolve every `@<agentId>` mention in [body] against [roster] (the channel's member ids). Returns the spans
     * ascending + non-overlapping. [roster] is matched case-insensitively; [MentionSpan.id] is the canonical roster
     * id (its stored casing — expected lowercase, ids being unique-lowercase).
     */
    fun resolve(body: String, roster: Collection<String>): List<MentionSpan> {
        if (body.isEmpty() || roster.isEmpty()) return emptyList()
        val byLower: Map<String, String> = roster.associateBy { it.lowercase() }
        val masked = maskCodeRegions(body) // same length as body; code chars → ' ' so an @ inside code isn't scanned
        val spans = ArrayList<MentionSpan>()
        var i = 0
        while (i < masked.length) {
            if (masked[i] == '@' && (i == 0 || !isSafeIdChar(masked[i - 1]))) {
                var runEnd = i + 1
                while (runEnd < masked.length && isSafeIdChar(masked[runEnd])) runEnd++
                if (runEnd > i + 1) { // at least one id char after the sigil
                    val canonical = byLower[masked.substring(i + 1, runEnd).lowercase()]
                    if (canonical != null) {
                        spans.add(MentionSpan(start = i, end = runEnd, id = canonical))
                        i = runEnd // consume the run → non-overlapping
                        continue
                    }
                }
            }
            i++
        }
        return spans
    }

    /** True iff [subject] is one of the mentioned ids — from the SAME [spans] pass, so it can never disagree with
     *  the overlay. Case-insensitive (spans carry canonical-lowercase ids). */
    fun mentionsYou(subject: String, spans: List<MentionSpan>): Boolean {
        val s = subject.lowercase()
        return spans.any { it.id == s }
    }

    /**
     * Blank the code regions of [body] to spaces (length-preserving, so span offsets stay absolute): fenced
     * ```` ``` ````/`~~~` blocks (line-based, opening fence line through the closing fence line) and inline
     * `` `…` `` backtick spans on non-fenced lines. `>` quote lines are NOT masked (scanned as text).
     */
    private fun maskCodeRegions(body: String): String {
        val out = body.toCharArray()
        val n = body.length
        var inFence = false
        var lineStart = 0
        while (lineStart <= n) {
            val lineEnd = body.indexOf('\n', lineStart).let { if (it == -1) n else it }
            val isFence = run {
                val t = body.substring(lineStart, lineEnd).trimStart()
                t.startsWith("```") || t.startsWith("~~~")
            }
            if (inFence) {
                for (k in lineStart until lineEnd) out[k] = ' ' // mask the block content + the closing fence line
                if (isFence) inFence = false
            } else if (isFence) {
                for (k in lineStart until lineEnd) out[k] = ' ' // mask the opening fence line, enter the block
                inFence = true
            } else {
                maskInlineCode(out, lineStart, lineEnd) // normal line → blank backtick spans
            }
            if (lineEnd == n) break
            lineStart = lineEnd + 1
        }
        return String(out)
    }

    /** Blank each ``` `…` ``` backtick span (delimiters + content) on the line `[from, to)`. An UNTERMINATED
     *  backtick (no closing tick on the line) exempts to the END OF LINE — **fail-closed**, matching the 704-client
     *  parser (`a`@frontend` is no mention, never a half-parsed body). */
    private fun maskInlineCode(out: CharArray, from: Int, to: Int) {
        var k = from
        while (k < to) {
            if (out[k] == '`') {
                var j = k + 1
                while (j < to && out[j] != '`') j++
                // closing backtick → mask k..j; UNTERMINATED (j==to) → mask k..to-1 (to line end), fail-closed.
                val end = if (j < to) j else to - 1
                for (m in k..end) out[m] = ' '
                k = end + 1
                continue
            }
            k++
        }
    }
}
