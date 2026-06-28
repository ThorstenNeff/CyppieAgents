import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.app.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.tneff.cyppieagents.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // Human-friendly launcher/app name (was the raw reverse-DNS id) + minimal metadata (CYP-72 S9).
            packageName = "CyppieAgents"
            packageVersion = "1.0.0"
            description = "CyppieAgents — Multi-Agent Desktop"
            vendor = "tneff"
            // Linux .deb package names must be lowercase; keep the macOS bundle id at the reverse-DNS appId.
            linux {
                packageName = "cyppieagents"
                appCategory = "Development"
            }
            macOS {
                bundleID = "com.tneff.cyppieagents"
            }
        }
    }
}