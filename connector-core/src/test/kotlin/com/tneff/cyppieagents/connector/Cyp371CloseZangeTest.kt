package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-371 (QA) — **Die Zange um `ClaudeCodeSession.closeAndAwait()`.** Zwei Tests, die in **entgegengesetzte**
 * Richtungen ziehen; nur der richtige Fix macht beide grün.
 *
 * Heute lautet der Rumpf:
 * ```kotlin
 * override suspend fun closeAndAwait() {
 *     readerJob?.cancelAndJoin()   // (1) wartet auf den Reader
 *     process.destroy()            // (2) killt den Prozess ERST danach
 *     process.awaitTerminated()
 *     ...
 * }
 * ```
 *
 * * **T-Term** will den Reader **loswerden**: Ist der Prozess still und lebendig, steht der Reader in einem
 *   blockierenden `readLine()`, das nie zurückkehrt. `cancelAndJoin` wartet auf einen Thread, der nie fertig
 *   wird — **Deadlock (CYP-371)**. Heute ROT.
 * * **T-Flush** will den Reader **ausreden lassen**: Eine Zeile, die kurz vor dem Tod noch verarbeitet wird,
 *   darf nicht verloren gehen — das ist CYP-247s Zusage, die genau das `cancelAndJoin` (statt `cancel`)
 *   garantiert. Heute GRÜN.
 *
 * **Die Backen ziehen gegeneinander:**
 *
 * | Fix in `closeAndAwait()` | T-Term | T-Flush |
 * |---|---|---|
 * | heute: `cancelAndJoin` VOR `destroy` | **ROT** (Deadlock) | grün |
 * | faul: `cancel()` statt `cancelAndJoin` | grün | **ROT** (Zeile verloren, CYP-247 kassiert) |
 * | richtig: `destroy` VOR `cancelAndJoin` | grün | grün |
 *
 * Der `destroy`-zuerst-Fix schließt die Pipe, `readLine()` bekommt EOF, der Reader läuft **normal** aus (liefert
 * seine letzte Zeile) und *dann* kehrt der Join zurück. Nur er befriedigt beide Backen. **Ein Fix, der nur
 * T-Term grün macht, hat den Reader abgeschnitten.**
 *
 * Werkzeuge bewusst verschieden: T-Term braucht einen **echten** Prozess (der Deadlock lebt in einem nativen,
 * thread-blockierenden Read — ein Fake mit `emptyFlow()`/Channel ist immer abbrechbar und kann ihn nie zeigen).
 * T-Flush braucht ein **deterministisches Rendezvous** (ein Fake, der eine Zeile *in-flight* hält) — mit einem
 * echten Prozess wäre das Zeitfenster ein Rennen. Jede Backe nimmt das Werkzeug, das **ihre** Eigenschaft
 * deterministisch macht.
 */
class Cyp371CloseZangeTest {

    private object SilentObs : SessionObserver {
        override fun onEvent(a: String, s: String?, c: String?, e: StreamJsonEvent) {}
        override fun onTurnStart(a: String, s: String?, c: String) {}
        override fun onProcessExit(a: String, s: String?) {}
        override fun onStopped(a: String) {}
    }

    // ---------------------------------------------------------------- T-Term

    /**
     * `sh -c 'while read _; do :; done'` liest stdin, druckt **nie**, endet **nie** bis `destroy()` — die
     * Modell-Form eines echten `claude --resume`, das still auf einen Turn wartet (CYP-170).
     *
     * **Hartes Zeitlimit auf THREAD-Ebene, nicht `withTimeout` im Körper.** Ein `withTimeout` kann einen
     * Thread, der in einem nativen `readLine()` steht, nicht unterbrechen (die Lehre aus CYP-362:
     * `BridgeLazyInitE2eTest` hatte zwei `withTimeout` und hing trotzdem). `Thread.join(ms)` kehrt **immer**
     * zurück; der Daemon-Worker wird aufgegeben und blockiert den JVM-Exit nicht. So wird ein Hang **rot statt
     * still**.
     */
    @Test
    fun tTerm_closeAndAwait_returnsAgainstASilentLiveProcess() {
        val cwd = File(System.getProperty("java.io.tmpdir"))
        val proc = ProcessBuilderSpawner().spawn(listOf("sh", "-c", "while read _; do :; done"), cwd, emptyMap())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = ClaudeCodeSession("backend", proc, SessionTurnQueue(), scope, observer = SilentObs)
        session.start()
        Thread.sleep(200) // der Reader steht jetzt im blockierenden readLine()

        val worker = Thread { runBlocking { session.closeAndAwait() } }.apply { isDaemon = true }
        worker.start()
        worker.join(3_000)
        val hung = worker.isAlive
        runCatching { proc.destroy() } // aufraeumen, egal was war

        if (hung) fail(
            "closeAndAwait() kehrte gegen einen stillen, lebenden Prozess nach 3 s nicht zurueck — CYP-371. " +
                "cancelAndJoin wartet auf einen Reader, der in readLine() blockiert. destroy() muss VOR den Join.",
        )
    }

    // ---------------------------------------------------------------- T-Flush

    /** Fake, der eine Zeile *in-flight* hält: der Observer-Body läuft, während `closeAndAwait` gerufen wird. */
    private class FeedableProc : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }
    }

    /**
     * Eine Zeile ist im Reader **in-flight** (der Observer-Body verarbeitet sie, nicht-suspendierend, wie der
     * echte Body). `closeAndAwait` muss ihn **ausreden lassen** — die Zeile darf nicht verloren gehen.
     *
     * Der faule T-Term-Fix (`cancel()` statt `cancelAndJoin`) kehrt zurück, **bevor** der Body fertig ist ⇒
     * `bodyCompleted == false` ⇒ ROT. Genau die CYP-247-Zusage, die er kassieren würde.
     */
    @Test
    fun tFlush_closeAndAwait_letsAnInFlightLineFinish() = runBlocking<Unit> {
        val bodyStarted = CompletableDeferred<Unit>()
        val bodyCompleted = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val observer = object : SessionObserver {
            override fun onEvent(a: String, s: String?, c: String?, e: StreamJsonEvent) {
                if (e is ResultEvent) {
                    bodyStarted.complete(Unit) // der Reader-Body ist jetzt IN-FLIGHT
                    Thread.sleep(300)          // haelt ihn in-flight, nicht-suspendierend (wie der echte Body)
                    bodyCompleted.set(true)    // die "letzte Zeile" ist erst HIER fertig verarbeitet
                }
            }
            override fun onTurnStart(a: String, s: String?, c: String) {}
            override fun onProcessExit(a: String, s: String?) {}
            override fun onStopped(a: String) {}
        }

        val proc = FeedableProc()
        val session = ClaudeCodeSession("backend", proc, SessionTurnQueue(), scope, observer = observer, onBind = {})
        session.start()
        proc.feed("""{"type":"system","subtype":"init","session_id":"s1"}""") // bind
        proc.feed("""{"type":"result","subtype":"success","is_error":false,"session_id":"s1","result":"ack"}""")
        withTimeout(5_000) { bodyStarted.await() } // der Body laeuft jetzt

        session.closeAndAwait()

        assertTrue(
            bodyCompleted.get(),
            "closeAndAwait() kehrte zurueck, BEVOR die in-flight-Zeile fertig verarbeitet war — der Reader wurde " +
                "abgeschnitten statt ausgeredet. Das kassiert CYP-247. cancelAndJoin (nicht cancel) haelt die Zusage.",
        )
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
