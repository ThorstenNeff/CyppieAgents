import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// DEDICATED Maestro test/demo Android entry — NOT the prod :app:androidApp build. Renders AgentShell in
// an OPERATOR context with fully STUB sources so the operator-only Event-Log windows are addressable for
// the maestro/eventlog-*-android.yaml flows without any live server. The prod :app:androidApp stays
// operatorToken=null (Event-Log windows omitted); this separate, NON-SHIPPED artifact (distinct
// applicationId com.tneff.cyppieagents.demo) is the ONLY place the demo wiring exists. NOT a
// prod-flippable switch (PO guardrail 2026-06-27). Mirrors :app:webAppDemo.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.app.shared)

    implementation(libs.androidx.activity.compose)

    // Demo entry renders Compose directly (MaterialTheme/Modifier/layout) — shared exposes these as
    // `implementation` (not transitive), so the demo declares them explicitly (as :app:webAppDemo does).
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutinesCore)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.tneff.cyppieagents.demo"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Distinct from prod com.tneff.cyppieagents → installs side-by-side, addressable as its own
        // Maestro appId; structurally separate from the shipped app.
        applicationId = "com.tneff.cyppieagents.demo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
