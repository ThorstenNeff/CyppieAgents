import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

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
            // Other targets now provide their own engine per source set (android=okhttp, ios=Darwin, web=js).
            implementation(libs.ktor.clientCio)
            // CYP-334: JediTerm terminal widget for the Desktop `TerminalView` actual (Swing, jvm-only). We feed it
            // a WsTtyConnector over PTY-over-WS — NOT pty4j (that is the server's PTY). coroutines-swing supplies the
            // Compose-Desktop Main/EDT dispatcher backing `rememberCoroutineScope()`; the byte-pump itself runs on
            // Dispatchers.IO (never the EDT — a full-pipe blocking write on the UI thread would freeze it).
            implementation(libs.jediterm.core)
            implementation(libs.jediterm.ui)
            implementation(libs.kotlinx.coroutinesSwing)
            // CYP-443 (Phase-2 remote): Noise_NK transport for the ClientNoiseTransport JVM actual (Desktop first).
            implementation(libs.noise.java)
            // CYP-542/B1: BouncyCastle Argon2id (Argon2BytesGenerator) for the operator-passphrase KEK (JVM actual;
            // pure-Java, no native/JNI). iOS/web KDF actuals are the ② follow-on.
            implementation(libs.bouncycastle.prov)
        }
        iosMain.dependencies {
            // iOS Ktor client engine (Darwin) so the engine-less HttpClient {} in AgentShell can
            // auto-select an engine on iOS — without it, engine discovery fails and the app aborts
            // before the first frame (CYP-56, regression since CYP-27; same role as okhttp/cio/js on
            // the other targets). CYP-56 brought app-start; CYP-68 extended Darwin to the live WS
            // streams (/ws/comm, /ws/lifecycle, /ws/events) — iOS live-stream parity is landed.
            implementation(libs.ktor.clientDarwin)
        }
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            // Window Size Classes for the phone-pager breakpoint (CYP-50/S10) — multiplatform, commonMain.
            implementation(libs.compose.material3.windowSizeClass)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientWebsockets)
            // CYP-216 avatar images: Coil 3 (KMP incl. wasmJs) + Ktor-3 network engine (reuses our authed client).
            implementation(libs.coil.compose)
            implementation(libs.coil.networkKtor3)
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

// CYP-342 — make the browser timezone a REPRODUCIBLE part of the build, not an ambient env var.
//
// `TZ=… ./gradlew wasmJsBrowserTest` is not a gate: TZ is not a task input, so an UP-TO-DATE task replays a
// result recorded under a different zone. `karma.config.d/timezone.js` sets the zone the browser actually runs
// in (karma launches Chrome as a child process, which inherits its `process.env`) — but Gradle does not track
// that directory for the test task by default. Measured: editing the zone left the task UP-TO-DATE.
//
// Declaring it as an input closes the loop: change the zone, the tests re-run. The gate command then needs no
// environment variable and no `--rerun-tasks`.
tasks.withType<KotlinJsTest>().configureEach {
    inputs.dir(layout.projectDirectory.dir("karma.config.d"))
        .withPropertyName("karmaConfigD")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// CYP-365 — the same disease, one level up: a check whose real input the build cannot see.
//
// Five `jvmTest` tests READ FILES FROM THE WORKING TREE at runtime, not from the classpath:
//   · OutlineTextColorGuardTest      — scans commonMain sources for `outline` used as a text colour
//   · TertiarySourceGuardTest        — scans commonMain sources for semantic `tertiary` uses
//   · Cyp336NoUnlabelledUtcGuardTest — scans commonMain sources for unlabelled-UTC timestamp rendering
//   · CommI18nDisclosureTest         — reads the composeResources `strings.xml`
//   · I18nKeyParityTest              — reads both `strings.xml` and compares the key sets
//
// Gradle's input for a test task is the compiled classpath. A source edit that leaves the classes
// byte-identical is therefore INVISIBLE to it, and the task is served UP-TO-DATE / FROM-CACHE — the guard does
// not run, and `BUILD SUCCESSFUL` is indistinguishable from a passing guard. Measured, both directions:
//   · swapping two imports in `EventVisuals.kt`            → `:app:shared:jvmTest UP-TO-DATE`
//   · inserting an XML comment into `values/strings.xml`   → `:app:shared:jvmTest UP-TO-DATE`
//
// A real violation always changes bytecode and does re-run the task, so the guards' main direction held. What
// slipped through was the *stale* direction — a permit or exemption entry left behind after its use changed.
// That assertion is the one keeping a guard from passing vacuously, so it was the least protected of all.
//
// Declaring the scanned directories closes it. This does NOT change what the guards check; it makes the build
// see what they read.
tasks.named("jvmTest") {
    inputs.dir(layout.projectDirectory.dir("src/commonMain/kotlin"))
        .withPropertyName("guardScannedCommonMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/commonMain/composeResources"))
        .withPropertyName("i18nScannedComposeResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}