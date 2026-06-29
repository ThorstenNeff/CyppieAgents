// DEDICATED Maestro test/demo iOS entry — NOT the prod :app:iosApp build. Produces its OWN
// `SharedDemo.framework` (a SEPARATE binary from the prod `Shared.framework`) consumed by a side-by-side
// `iosAppDemo.xcodeproj` (bundle id com.tneff.cyppieagents.demo). The demo wiring (tab switcher + STUB
// sources) lives ONLY here, so the prod `Shared.framework` stays byte-unchanged with NO demo symbol —
// the iOS mirror of the :app:androidAppDemo / :app:webAppDemo guardrail (PO 2026-06-27, CYP-69). NOT a
// prod-flippable switch.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedDemo"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.app.shared)
            // Demo entry renders Compose directly (panels/WindowHost from :app:shared) — shared exposes
            // these as `implementation` (not transitive), so the demo declares them explicitly (as
            // :app:androidAppDemo / :app:webAppDemo do).
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
