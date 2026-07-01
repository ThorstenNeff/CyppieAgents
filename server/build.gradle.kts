plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    // Required so @Serializable classes defined in :server (e.g. PlatformConfig) get generated
    // serializers — without it PlatformConfig.load() throws at runtime and the boot crashes (CYP-33).
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"
application {
    mainClass = "com.tneff.cyppieagents.ApplicationKt"
}

// CYP-157 (onboarding): the application plugin's `run` task otherwise uses the module dir as its
// working directory, so Application.main's relative defaults — `platform.config.json` and `.cyppie`
// (PLATFORM_CONFIG / PLATFORM_GIT_ROOT), which live at the REPO ROOT per SETUP.md §2 — are not found,
// and `:server:run` aborts with FileNotFoundException unless the user exports an absolute PLATFORM_CONFIG.
// Run from the repo root so a fresh clone's `:server:run` just works (no manual env var).
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// CYP-178 CC2 (teeth-hygiene): Rc2ConfigAssertionTest reads deploy/kratos/kratos.reference.yml at RUNTIME
// (a repoFile() walk), so Gradle can't see the yml as a test input. Without this, editing ONLY the yml
// leaves the `test` task UP-TO-DATE → the config-drift guard is stale-green on a pure-yml change (a false
// green on the exact surface RC2 guards). Declaring it as a task input makes a yml change re-run the tests.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("deploy/kratos/kratos.reference.yml"))
        .withPropertyName("kratosReferenceConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    api(projects.core)
    api(projects.connectorCore)
    implementation(libs.logback)
    implementation(libs.kotlinx.coroutinesCore)
    // Event-Log persistence (CYP-35): xerial sqlite-jdbc, pinned. Full WAL/batch control behind the
    // EventSink seam at the smallest dependency; SQLDelight stays the documented upgrade path (02 §15).
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverWebsockets)
    // CYP-178: an HTTP client to validate Kratos sessions (GET /sessions/whoami).
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}