package com.tneff.cyppieagents

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.AgentAvatarView
import kotlin.test.Test

/**
 * CYP-216 wasm-incident regression guard. A REAL Compose render — skiko text ([Text], the very
 * `TextStyle`/`setFontEdging` paragraph path that crashed live) + the shared [AgentAvatarView] — that runs on
 * **`wasmJsBrowserTest` (headless Chrome)**, not just JVM. This is exactly the gate compile + jvmTest missed: a
 * skiko JS-glue ↔ wasm-binary mismatch (e.g. a Compose transitive dragging the runtime past the plugin's skiko)
 * makes the composition throw HERE, in a real browser, while everything still compiles.
 */
@OptIn(ExperimentalTestApi::class)
class AvatarWasmRenderSmokeTest {

    @Test
    fun composeRendersTextAndAvatar_inRealBrowser() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    Text("render-smoke")
                    AgentAvatarView(id = "backend", size = 28.dp, displayName = "Backend Dev")
                }
            }
        }
        onNodeWithText("render-smoke").assertExists()
    }
}
