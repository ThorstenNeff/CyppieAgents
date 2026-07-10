package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.tneff.cyppieagents.window.CONTENT_WINDOW_MIN_HEIGHT
import com.tneff.cyppieagents.window.FloatingWindow
import com.tneff.cyppieagents.window.TILED_CONTENT_WINDOW_MIN_WIDTH
import com.tneff.cyppieagents.window.WindowState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-363 — **Der Guard ist die Lieferung; die Konstante ist nur ihr heutiger Wert.**
 *
 * `AgentWindowMinHeightTest` (CYP-338) rendert die **gekachelte** Untergrenze bei Standardbreite und prüft
 * `assertIsDisplayed()`. Beides ist zu wenig:
 *
 * * **Er fährt nie den Resize-Boden.** Ein Content-Fenster darf auf `TILED_CONTENT_WINDOW_MIN_WIDTH` × 320 dp
 *   **und** [CONTENT_WINDOW_MIN_HEIGHT] gezogen werden — beide Invarianten gleichzeitig an ihrem Boden. Diese
 *   **Ecke** rendert kein Test.
 * * **`assertIsDisplayed()` kann „zu klein zum Benutzen" nicht sehen.** Ein 33 dp hoher Composer *ist*
 *   angezeigt. Ein 0 dp hohes Transkript ist es nicht — aber niemand rendert die Stelle, an der es 0 wird.
 *
 * Gemessen am **echten** Stapel (`FloatingWindow` + `AgentWindow`), nicht an der Konstantenarithmetik:
 *
 * ```
 * Fenster 520 x 301   composer = 57 dp   transcript = 84 dp    gesund
 * Fenster 400 x 301   composer = 33 dp   transcript =  0 dp
 * Fenster 320 x 301   composer = 33 dp   transcript =  0 dp    <- die Invarianten-Ecke
 * Fenster 320 x 283   composer = 15 dp   transcript =  0 dp    <- der CYP-350-Zielwert
 * Fenster 320 x 260   composer =  0 dp   transcript =  0 dp
 * ```
 *
 * **Die Höhe 301 trägt nur ab ~520 dp Breite** — genau dort, wo der Header aufhört umzubrechen. Der KDoc der
 * Konstante sagt das über den Header („width-dependent … 320→164, 480→104, 520→56"), aber **die Konstante ist
 * eine Zahl ohne Breite**, und kein Test hält die beiden zusammen.
 *
 * Diese Tests prüfen deshalb **Höhen, keine Konstanten**. Sie überleben jeden neuen Wert von
 * [CONTENT_WINDOW_MIN_HEIGHT] und sterben an jeder Chrome-Zeile, die dazukommt.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp363ComposerFloorTest {

    /** Das kleinste bedienbare Ziel (Material/WCAG). Ein Eingabefeld darunter ist nicht „vorhanden", nur sichtbar. */
    private val minTouchTarget = 48.dp

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private class Lc : AgentLifecycleApi, AgentLifecycleSource {
        private val e = MutableSharedFlow<AgentLifecycleEvent>(replay = 0, extraBufferCapacity = 8)
        override suspend fun snapshot() = mapOf("backend" to AgentLifecycleState.RUNNING)
        override fun events(): Flow<AgentLifecycleEvent> = e.asSharedFlow()
        override suspend fun start(agentId: String) {}
        override suspend fun stop(agentId: String) {}
        override suspend fun restart(agentId: String) {}
    }

    /** Rendert den ECHTEN Stapel: Fenster-Chrome (Titelleiste) + AgentWindow — genau das sieht der Operator. */
    /**
     * **Dieser Stapel ist von Hand zusammengesetzt und damit eine *vereinfachte* Komposition.** Die echte
     * `AgentShell` liefert mehr Chrome: Avatar, Busy-Marker, Token-Zähler und `⋮` machen die Titelleiste
     * **64 dp statt 56**, und `WORKTREE_SHELL_LIVE_ENABLED = false` rendert den `terminal_gated_pending`-Hinweis,
     * der die Toggle-Zeile von 48 auf 84 dp treibt.
     *
     * ```
     * Titelleiste 56 (schlank)      -> composer 33 dp     <- was DIESER Test rendert
     * Titelleiste 64 (echte Shell)  -> composer  0 dp     <- was der Operator sieht (UIUX, Developer5)
     * ```
     *
     * **Deshalb ist [full] = `false` der Default, und das ist kein Mangel, sondern die Beweisrichtung:**
     * der schlanke Stapel hat *weniger* Chrome, also gilt `composer(schlank) >= composer(echt)`.
     *
     * * Ein **rotes** Ergebnis hier ist ein **Beweis für die echte Shell** (dort ist es nur schlimmer).
     * * Ein **grünes** Ergebnis hier beweist **nichts** über die echte Shell.
     *
     * Die Zahlen in den Fehlermeldungen sind entsprechend **obere Schranken** für das, was der Operator hat.
     * Wer eine belastbare *positive* Aussage braucht, misst gegen `AgentShell` — so wie Developer5s Guard.
     */
    private fun ComposeUiTest.renderWindow(width: Float, height: Float, full: Boolean = false) {
        val src = Lc()
        setContent {
            MaterialTheme {
                Box(Modifier.width((width + 40).dp).height((height + 40).dp)) {
                    FloatingWindow(
                        window = WindowState("backend", "Backend", 0f, 0f, width, height),
                        isFocused = true, zOrder = 0f, onFocus = {}, onMove = { _, _ -> }, onResize = { _, _ -> },
                        contextTokens = if (full) 12_345 else null,
                        busy = full,
                        titleBarLeading = if (full) ({ Text("BE") }) else null,
                        onSettings = if (full) ({}) else null,
                    ) {
                        val v = remember {
                            AgentViewModel(emptySession(), "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                        }
                        AgentWindow(agentId = "backend", viewModel = v, terminalGatedNote = full)
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.heightOf(tag: String): Dp =
        onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    /**
     * **Die Invarianten-Ecke.** Beide Böden gleichzeitig. Wenn die Konstanten ein Versprechen sind, muss es
     * genau hier gelten — nicht nur bei komfortabler Breite.
     */
    @Test
    fun atBothInvariantFloors_theComposerKeepsAUsableTouchTarget() = runComposeUiTest {
        renderWindow(width = TILED_CONTENT_WINDOW_MIN_WIDTH, height = CONTENT_WINDOW_MIN_HEIGHT)

        val composer = heightOf(AgentViewTags.input("backend"))
        assertTrue(
            composer >= minTouchTarget,
            "Bei ${TILED_CONTENT_WINDOW_MIN_WIDTH.toInt()}x${CONTENT_WINDOW_MIN_HEIGHT.toInt()} dp — beide " +
                "Invarianten an ihrem Boden — misst der Composer $composer statt >= $minTouchTarget. " +
                "`assertIsDisplayed()` bleibt hier grün: er IST angezeigt, nur nicht benutzbar. " +
                "Die Höhe 301 trägt erst ab ~520 dp Breite, wo der Header aufhört umzubrechen.",
        )
    }

    /** Ein Agentenfenster ohne Transkript ist kein Agentenfenster. Der `weight(1f)`-Bereich darf nicht verschwinden. */
    @Test
    fun atBothInvariantFloors_theTranscriptDoesNotCollapseToZero() = runComposeUiTest {
        renderWindow(width = TILED_CONTENT_WINDOW_MIN_WIDTH, height = CONTENT_WINDOW_MIN_HEIGHT)

        val transcript = heightOf(AgentViewTags.stream("backend"))
        assertTrue(
            transcript > 0.dp,
            "Das Transkript misst $transcript. Das Chrome füllt das Fenster, bevor der `weight(1f)`-Bereich " +
                "irgendetwas bekommt. Ein Fenster ohne Transkript ist kein Agentenfenster.",
        )
    }

    /**
     * **Hält die Schranke fest, gegen die alle anderen Assertions hier zu lesen sind.**
     *
     * Zwei Messungen widersprachen sich — 33 dp (dieser Stapel) gegen 0 dp (die echte Shell). **Keine war
     * falsch; sie maßen verschiedene Kompositionen.** Mit voller Titelleisten-Bestückung und dem
     * `gated`-Hinweis misst derselbe Aufbau hier ebenfalls 0 dp. Die Differenz ist der Befund, nicht der
     * Fehler: **ein von Hand zusammengesetzter Stapel ist eine Ableitung mit Extraschritten.**
     */
    @Test
    fun theLeanStackIsAnUpperBound_theRealShellIsAlwaysWorse() {
        var lean = 0.dp
        var full = 0.dp
        runComposeUiTest {
            renderWindow(TILED_CONTENT_WINDOW_MIN_WIDTH, CONTENT_WINDOW_MIN_HEIGHT, full = false)
            lean = heightOf(AgentViewTags.input("backend"))
        }
        runComposeUiTest {
            renderWindow(TILED_CONTENT_WINDOW_MIN_WIDTH, CONTENT_WINDOW_MIN_HEIGHT, full = true)
            full = heightOf(AgentViewTags.input("backend"))
        }
        assertTrue(
            lean >= full,
            "Der schlanke Stapel muss mindestens so viel Composer übrig lassen wie der voll bestückte " +
                "(schlank=$lean, voll=$full). Trägt diese Ungleichung nicht, trägt die Beweisrichtung " +
                "der anderen Tests hier nicht — dann ist ein rotes Ergebnis kein Beweis für die echte Shell.",
        )
        assertTrue(full == 0.dp, "Voll bestückt stirbt der Composer ganz (gemessen: $full) — die Zahl von UIUX und Developer5.")
    }

    /**
     * **Die Kontrolle.** Bei komfortabler Breite ist heute alles gesund. Ohne diesen Test wüsste man nicht, ob
     * die beiden oben eine *Ecke* zeigen oder einen generellen Bruch — und ob der Fix die Ecke repariert oder
     * bloß die Konstante hochdreht, bis auch der breite Fall zufällig passt.
     */
    @Test
    fun atAComfortableWidth_theSameFloorIsAlreadyHealthy() = runComposeUiTest {
        renderWindow(width = 520f, height = CONTENT_WINDOW_MIN_HEIGHT)

        val composer = heightOf(AgentViewTags.input("backend"))
        val transcript = heightOf(AgentViewTags.stream("backend"))
        assertTrue(composer >= minTouchTarget, "Vorbedingung: bei 520 dp Breite ist der Composer gesund ($composer)")
        assertTrue(transcript > 0.dp, "Vorbedingung: bei 520 dp Breite lebt das Transkript ($transcript)")
    }
}
