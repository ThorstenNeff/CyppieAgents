package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    MaterialTheme {
        // CYP-15: the app shell — floating agent windows (CYP-10 window manager) each hosting the
        // agent renderer (CYP-6) as content.
        //
        // TODO(CYP-15): once the CYP-11 follow-up (enableTestTagsAsResourceId) is on develop, apply
        //   `.enableTestTagsAsResourceId()` here at the root so Maestro can address testTags on Wasm.
        AgentShell(modifier = Modifier.safeContentPadding().fillMaxSize())
    }
}
