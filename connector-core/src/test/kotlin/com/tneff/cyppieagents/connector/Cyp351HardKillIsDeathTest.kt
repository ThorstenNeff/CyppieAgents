package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-351 (QA, T4/AC 8) — **Der harte Tod MUSS gemeldet werden.** Gegenstück zu [Cyp351EofIsNotDeathTest].
 *
 * Die beiden Tests stellen dieselbe Frage — *lebt der Prozess, wenn `onProcessExit` gemeldet wird?* — und
 * verlangen die entgegengesetzte Antwort:
 *
 *  - T7 (EOF, Prozess lebt weiter):  **kein** `onProcessExit`.
 *  - T4 (SIGKILL, Prozess ist tot):  **genau ein** `onProcessExit`, und der Prozess ist dabei tot.
 *
 * **Dieser Test ist heute GRÜN — und zwar aus dem falschen Grund.** Der kaputte Mechanismus (Tail hinter dem
 * `collect`) liefert beim harten Tod zufällig das richtige Ergebnis, weil der Kill den Stream ohnehin beendet.
 * Er belegt also nichts über den heutigen Code.
 *
 * **Sein Zweck ist die Über-Korrektur.** Wer T7 grün bekommen will, indem er den Tail einfach entfernt, macht
 * diesen Test rot: dann meldet niemand mehr den echten Tod. Erst beide zusammen zwingen zur einzig richtigen
 * Lösung — den Tod aus `waitFor()` zu **beobachten**, statt ihn aus dem Streamende zu **erschließen**.
 *
 * Ein einzelner Test kann diese Naht nicht sichern. Ein Paar kann es.
 *
 * **Was dieser Test NICHT prüfen kann:** ob der Exit-Code ankommt (AC 5/6). [SessionObserver.onProcessExit]
 * hat keinen Parameter dafür — die Signatur kann die Beobachtung gar nicht tragen. Das ist kein Testproblem,
 * es ist der Befund. Der Exit-Code wird erst prüfbar, wenn der Vertrag ihn führt.
 */
class Cyp351HardKillIsDeathTest {

    private class RecordingObserver(private val proc: () -> Process) : SessionObserver {
        val exits = AtomicInteger(0)

        /** Die eigentliche Messung: lebte der Prozess in dem Moment, in dem sein Tod gemeldet wurde? */
        val aliveAtExitReport = AtomicBoolean(false)

        override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {}
        override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
        override fun onProcessExit(agentId: String, sessionId: String?) {
            if (proc().isAlive) aliveAtExitReport.set(true)
            exits.incrementAndGet()
        }
        override fun onStopped(agentId: String) {}
    }

    /** Nachbau des echten Spawners (`AgentProcess.kt:66-86`), identisch zu T7 — derselbe Pfad, andere Frage. */
    private fun spawn(cmd: String): Pair<Process, AgentProcess> {
        val p = ProcessBuilder("sh", "-c", cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val ap = object : AgentProcess {
            override val stdoutLines: Flow<String> = flow {
                p.inputStream.bufferedReader().useLines { lines -> for (l in lines) emit(l) }
            }.flowOn(Dispatchers.IO)
            override suspend fun writeLine(line: String) {}
            override fun destroy() { p.destroy() }
            override suspend fun awaitTerminated() { withContext(Dispatchers.IO) { p.waitFor() } }
        }
        return p to ap
    }

    @Test
    fun childIsHardKilled_mustReportProcessExitExactlyOnce_andOnlyWhenItIsActuallyDead() = runBlocking {
        // `exec`, damit sh selbst durch sleep ersetzt wird und der Kill den wirklichen Prozess trifft.
        val (proc, agent) = spawn("exec sleep 30")
        val observer = RecordingObserver { proc }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        ClaudeCodeSession(
            agentId = "backend", process = agent, turnQueue = SessionTurnQueue(),
            scope = scope, observer = observer,
        ).start()

        delay(300)
        // Vorbedingung: Der Test prüft nur dann etwas, wenn hier wirklich ein lebender Prozess getötet wird.
        val aliveBeforeKill = proc.isAlive
        val exitsBeforeKill = observer.exits.get()

        proc.destroyForcibly()
        withContext(Dispatchers.IO) { proc.waitFor() }

        // Auf die Meldung warten, statt eine Frist zu raten. Bleibt sie aus, schlägt die Assertion fehl — nicht
        // der Test-Harness. Ein Timeout, der als Erfolg durchgeht, wäre genau die Vakuum-Assertion.
        val deadline = System.currentTimeMillis() + 2000
        while (observer.exits.get() == 0 && System.currentTimeMillis() < deadline) delay(20)

        val exitsAfterKill = observer.exits.get()
        val aliveAtReport = observer.aliveAtExitReport.get()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertTrue(aliveBeforeKill, "Vorbedingung: vor dem Kill muss der Prozess leben (sonst prüft der Test nichts)")
        assertEquals(0, exitsBeforeKill, "Vorbedingung: vor dem Kill darf kein Exit gemeldet worden sein")

        assertEquals(
            1, exitsAfterKill,
            "Der harte Tod muss genau einmal gemeldet werden. Kam nichts an, hat jemand den Tail entfernt, " +
                "um T7 grün zu bekommen — der echte Tod wird dann nie beobachtet.",
        )
        assertFalse(
            aliveAtReport,
            "onProcessExit wurde gemeldet, während der Prozess noch lief — dieselbe Ableitung wie in T7.",
        )
    }
}
