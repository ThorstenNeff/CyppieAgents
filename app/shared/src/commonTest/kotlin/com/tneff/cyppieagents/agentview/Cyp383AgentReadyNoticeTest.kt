package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.SystemEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-383 — the transcript "agent ready" line after (re)start.
 *
 * The one sentence the ticket hangs on (spec §0): **"bereit" darf nur eine Beobachtung sein, nie das Echo des
 * Neustart-Befehls.** So the trigger is the UIUX-signed predicate — the **first** stream-json `SystemEvent` of a
 * session with a **non-blank `session_id`**, fired **once per session** — an observation that stdin is served
 * (BOUND), subtype-agnostic (so it also catches the resume-bind, which need not be `subtype:"init"`), and
 * guarded against the compaction over-fire that "any session_id" would cause.
 *
 * Each test names the mutation it reds. The localized label is INJECTED (the caller resolves `stringResource`),
 * so the mapper holds no user-facing literal; here the marker `"READY"` stands in for the resolved string.
 */
class Cyp383AgentReadyNoticeTest {

    private fun mapper() = StreamJsonMapper(readyNoticeText = "READY", turnErrorLabel = "TURNERR")

    /** §2/§4.3 — the ready label is the injected (localized) text + the wire model suffix; NO literal in the mapper.
     *  Mutation: restore the hardcoded `"Session gestartet"` literal ⇒ the text is not `"READY · …"` ⇒ red. */
    @Test
    fun firstSystemEventWithSessionId_emitsInjectedLabelPlusModel() {
        val rows = mapper().map(SystemEvent(subtype = "init", sessionId = "s", uuid = "u1", model = "claude-opus-4-8"), 1_000L)
        val notice = rows.single() as AgentEvent.Notice
        assertEquals("READY · claude-opus-4-8", notice.text, "injected label + ' · <model>' suffix; no hardcoded literal")
        // CYP-385: "bereit" is a neutral OBSERVATION, never an error — it must NOT carry the error tone (the other
        // half of the distinction the error notices need: neutral stays neutral). Mutation: flag it isError ⇒ red.
        assertEquals(false, notice.isError, "the 'ready' line is a neutral INFO notice, never the error tone (CYP-385)")
    }

    /** §0/§4.1 — subtype-agnostic: the ready line is the OBSERVATION, so it fires on the first session_id-bearing
     *  SystemEvent even when that event is NOT `subtype:"init"` (the resume-bind path, spec §5).
     *  Mutation: narrow the predicate back to `subtype == "init"` ⇒ this non-init bind event fires nothing ⇒ red. */
    @Test
    fun firstSystemEvent_firesRegardlessOfSubtype_forResumeBind() {
        val rows = mapper().map(SystemEvent(subtype = "resume", sessionId = "s", uuid = "u1", model = "m"), 1_000L)
        assertEquals(1, rows.count { it is AgentEvent.Notice }, "a non-'init' first SystemEvent with a session_id still means BOUND → ready")
    }

    /** §0/§4.1 (the honesty test, in mapper terms) — "session_id present" ALONE over-fires: a `subtype:"status"`
     *  compaction event (CYP-326) carries the SAME session_id mid-session. It must NOT re-fire "ready".
     *  Mutation: drop the once-per-session guard (fire on any session_id) ⇒ the compaction event yields a second
     *  Notice ⇒ red. id-dedup can't catch this — the status event has its own uuid. */
    @Test
    fun compactionStatusEvent_inSameSession_doesNotRefireReady() {
        val m = mapper()
        m.map(SystemEvent(subtype = "init", sessionId = "s", uuid = "u1", model = "m"), 1_000L)
        val duringCompact = m.map(SystemEvent(subtype = "status", sessionId = "s", uuid = "u2", status = "compacting"), 2_000L)
        assertTrue(duringCompact.none { it is AgentEvent.Notice }, "a mid-session compaction SystemEvent must not re-fire 'ready'")
    }

    /** §4.2 (real restart) — two DISTINCT sessions each fire exactly once.
     *  Mutation: key the fire-once set on a constant instead of the session_id ⇒ the second (real) restart shows
     *  no line ⇒ red. */
    @Test
    fun twoDistinctSessions_eachFireReadyOnce() {
        val m = mapper()
        val first = m.map(SystemEvent(subtype = "init", sessionId = "s1", uuid = "a", model = "m"), 1_000L)
        val second = m.map(SystemEvent(subtype = "init", sessionId = "s2", uuid = "b", model = "m"), 2_000L)
        assertEquals(1, first.count { it is AgentEvent.Notice })
        assertEquals(1, second.count { it is AgentEvent.Notice }, "a new session (real restart) fires its own ready line")
    }

    /** §4.2 (replay) — a reconnect builds a FRESH mapper and the server replays history: the same init (same uuid)
     *  is mapped again. The per-mapper fire-once set is fresh, so it re-emits — and `foldEvent`'s id-dedup collapses
     *  it to ONE line (distinct from the within-session over-fire, which the mapper stops).
     *  Mutation: make the Notice id a per-instance sequence instead of `idOf(event.uuid)` ⇒ two ids ⇒ two lines ⇒ red. */
    @Test
    fun sameInitReplayedAcrossReconnect_dedupsToOneLine() {
        val init = SystemEvent(subtype = "init", sessionId = "s", uuid = "u-sys", model = "m")
        val connection1 = mapper().map(init, 1_000L)
        val afterReconnect = mapper().map(init, 2_000L) // fresh mapper replays the same event
        val folded = foldEvents(connection1 + afterReconnect)
        assertEquals(1, folded.count { it is AgentEvent.Notice }, "same init uuid replayed on reconnect → one Notice after fold")
    }

    /** The non-blank clause: a SystemEvent without a session_id is not proof stdin is served → no line.
     *  (The mutation is compile-enforced — dropping the null/blank check fails to compile against `HashSet<String>` —
     *  so this is a behavior anchor for the clause, not a red-able guard on its own.) */
    @Test
    fun systemEventWithoutSessionId_firesNothing() {
        val rows = mapper().map(SystemEvent(subtype = "init", sessionId = null, uuid = "u1", model = "m"), 1_000L)
        assertTrue(rows.isEmpty(), "no session_id → not yet BOUND → no ready line")
    }
}
