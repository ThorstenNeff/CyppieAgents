package com.tneff.cyppieagents

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // CYP-151: carry an optionally-supplied operator token from the launch intent into the shared
        // config seam BEFORE composition (defaultShellConfig reads it). Absent/blank → null →
        // fail-closed participant view. Never baked into the APK, never logged.
        AndroidLaunchEnv.operatorToken = intent?.getStringExtra(EXTRA_OPERATOR_TOKEN)

        setContent {
            App()
        }
    }

    private companion object {
        /** Launch-intent extra carrying the operator token (Maestro: `launchApp: arguments:`). */
        const val EXTRA_OPERATOR_TOKEN = "CYPPIE_OPERATOR_TOKEN"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}