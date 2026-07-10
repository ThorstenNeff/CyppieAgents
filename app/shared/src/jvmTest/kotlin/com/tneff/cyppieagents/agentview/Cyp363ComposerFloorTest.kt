package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
    private fun ComposeUiTest.renderWindow(width: Float, height: Float) {
        val src = Lc()
        setContent {
            MaterialTheme {
                Box(Modifier.width((width + 40).dp).height((height + 40).dp)) {
                    FloatingWindow(
                        window = WindowState("backend", "Backend", 0f, 0f, width, height),
                        isFocused = true, zOrder = 0f, onFocus = {}, onMove = { _, _ -> }, onResize = { _, _ -> },
                    ) {
                        val v = remember {
                            AgentViewModel(emptySession(), "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                        }
                        AgentWindow(agentId = "backend", viewModel = v)
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
