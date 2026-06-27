import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// DEDICATED Maestro test/demo web entry — NOT the prod web build. Renders AgentShell in an operator
// context with fully STUB sources so the operator-only Event-Log windows are addressable for the
// maestro/eventlog-*.yaml flows without any live server. The prod :app:webApp stays operatorToken=null
// (Event-Log windows omitted); this separate artifact is the ONLY place the demo wiring exists.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.app.shared)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
