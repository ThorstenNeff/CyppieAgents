package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-351 (QA, T5) — **Unbekannt ist nie sauber.** HEUTE ROT, und das ist der Punkt.
 *
 * `EventProjector.kt:189`: `if ((exitCode ?: 0) != 0) WARN else INFO`. Ein fehlender Code wird zu „Exit 0" und
 * damit zu `INFO`. Da `RecordingSessionObserver.kt:78` **immer** `null` übergibt, landet **jeder** Prozesstod —
 * Absturz (1), OOM-Kill (137), SIGTERM (143) — als `INFO` ohne `exitCode` im `detail`.
 *
 * Severity ist laut Dok. 06 §3 der Schnellfilter beim Belastungstest. Wer auf `severity >= WARN` filtert, sieht
 * **keinen einzigen Prozesstod**. Das ist fail-open.
 *
 * Dieser Test bleibt auch nach dem Fix stehen: **er ist gegen die Rückkehr von `?: 0` gerichtet**, nicht gegen
 * den heutigen Fehler.
 */
class Cyp351UnknownExitIsNeverCleanTest {

    private val projector = EventProjector(ContextUsageBander(contextWindowTokens = 1_000_000L), projectId = "team-1")

    @Test
    fun processExit_withUnknownCode_isNeverInfo() {
        val draft = projector.processExit("backend", "sess-1", exitCode = null)

        assertEquals(EventType.PROCESS_EXIT, draft.type)
        assertTrue(
            draft.severity >= Severity.WARN,
            "Unbekannt ist nie sauber: ein Prozesstod ohne ermittelbaren Exit-Code darf niemals INFO sein " +
                "(fail-open). Gemessen: ${draft.severity}. Wer `?: 0` wieder einbaut, macht diesen Test rot.",
        )
    }

    @Test
    fun processExit_withNonZeroCode_carriesTheCodeInDetail() {
        val draft = projector.processExit("backend", "sess-1", exitCode = 137) // SIGKILL
        assertTrue(draft.severity >= Severity.WARN, "ein SIGKILL ist kein INFO")
        assertTrue(
            draft.detail.toString().contains("137"),
            "der Exit-Code gehört ins detail — Dok. 06 §4 verspricht `process.exit (Code)`; gemessen: ${draft.detail}",
        )
    }
}
