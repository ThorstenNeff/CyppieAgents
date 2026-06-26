package com.tneff.cyppieagents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tneff.cyppieagents.agentview.AgentWindowDemo

@Composable
@Preview
fun App() {
    MaterialTheme {
        // CYP-6: agent renderer over a stubbed stream-json event stream (no backend yet).
        AgentWindowDemo(modifier = Modifier.safeContentPadding().fillMaxSize())
    }
}