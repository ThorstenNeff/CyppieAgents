package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId

@Composable
@Preview
fun App() {
    MaterialTheme {
        // CYP-15: the app shell — floating agent windows (CYP-10 window manager) each hosting the
        // agent renderer (CYP-6) as content. enableTestTagsAsResourceId() at the root exposes every
        // testTag as a resource-id so Maestro can address them on Wasm/Android (CYP-11).
        AgentShell(
            modifier = Modifier
                .enableTestTagsAsResourceId()
                .safeContentPadding()
                .fillMaxSize(),
        )
    }
}
