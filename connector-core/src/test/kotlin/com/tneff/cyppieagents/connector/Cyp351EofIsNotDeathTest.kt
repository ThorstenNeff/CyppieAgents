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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-351 (QA, T7) — **EOF ist kein Tod.** Dieser Test ist HEUTE ROT und soll es sein: er zeigt den Defekt,
 * bevor der Fix existiert. Danach ist er sein Wächter.
 *
 * `ClaudeCodeSession` hängt `onProcessExit` hinter den `collect` von [AgentProcess.stdoutLines] und nennt das
 * „NORMAL stdout completion (the process exited on its own)". Der Stream endet aber, wenn der **Stream** endet.
 * Ein Kind, das seinen stdout schließt und weiterlebt, erzeugt so die Meldung seines eigenen Todes.
 *
 * Drei Verträge behaupten dieselbe Beobachtung und keiner hat sie:
 *  - `ClaudeCodeSession.kt:107`  „the process exited on its own"
 *  - `AgentProcess.stdoutLines`  „The flow completes when the process ends."
 *  - `SessionObserver.onProcessExit`  „The process exited on its own (stdout completed)."
 *
 * Der letzte gibt es sogar zu: „(stdout completed)". Die Ableitung steht im Interface.
 *
 * Die einzige Beobachtung des Prozesses heißt `waitFor()` — und sie liefert im selben Zug den Exit-Code, den
 * AC 5 ohnehin braucht.
 */
class Cyp351EofIsNotDeathTest {

    private class RecordingObserver : SessionObserver {
        val exits = AtomicInteger(0)
        override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {}
        override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
        override fun onProcessExit(agentId: String, sessionId: String?) { exits.incrementAndGet() }
        override fun onStopped(agentId: String) {}
    }

    /** Nachbau des echten Spawners (`AgentProcess.kt:66-86`), damit der Test denselben Pfad fährt. */
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
    fun childClosesStdoutButLivesOn_mustNotReportProcessExit() = runBlocking {
        // `exec`, damit kein Enkelprozess die Pipe offenhält (shell-abhängig, nie darauf verlassen).
        val (proc, agent) = spawn("exec 1>&-; sleep 3")
        val observer = RecordingObserver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        ClaudeCodeSession(
            agentId = "backend", process = agent, turnQueue = SessionTurnQueue(),
            scope = scope, observer = observer,
        ).start()

        delay(700) // stdout ist längst am EOF; der Prozess läuft noch ~2,3 s
        val aliveNow = proc.isAlive
        val exitsWhileAlive = observer.exits.get()

        proc.destroyForcibly()
        withContext(Dispatchers.IO) { proc.waitFor() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        assertTrue(aliveNow, "Vorbedingung: der Prozess muss noch leben (sonst prüft der Test nichts)")
        assertEquals(
            0, exitsWhileAlive,
            "EOF ist kein Tod: onProcessExit wurde gemeldet, während der Prozess noch lief. " +
                "Der Tod muss aus waitFor() beobachtet werden, nicht aus dem Ende des stdout-Streams.",
        )
    }
}
