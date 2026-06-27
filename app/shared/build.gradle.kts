import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    
    androidLibrary {
       namespace = "com.tneff.cyppieagents.app.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // Android Ktor engine so the live agent WS connects on Android too (CYP-27 follow-up).
            implementation(libs.ktor.clientOkhttp)
        }
        jvmMain.dependencies {
            // Desktop/JVM Ktor engine so the agent WS (CYP-6 swap) actually connects at runtime.
            // Other targets (web/ios/android) need their own engine — flagged follow-up.
            implementation(libs.ktor.clientCio)
        }
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientWebsockets)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        jvmTest.dependencies {
            // Desktop UI-test runner + Skiko native runtime for the current OS, so commonTest
            // Compose UI assertions actually execute on JVM (runComposeUiTest).
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // Embedded Ktor server to e2e-verify the live AgentWsClient socket path (CYP-6 swap).
            implementation(libs.ktor.serverCore)
            implementation(libs.ktor.serverNetty)
            implementation(libs.ktor.serverWebsockets)
        }
        jsMain.dependencies {
            implementation(libs.wrappers.browser)
            // Browser Ktor engine so the live agent WS (CYP-6 swap) connects on JS web too (CYP-27).
            implementation(libs.ktor.clientJs)
        }
        wasmJsMain.dependencies {
            // Browser Ktor engine for the Wasm web target — the gating target for the live stream (CYP-27).
            implementation(libs.ktor.clientJs)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}