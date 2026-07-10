plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"

// CYP-106 — the hermetic embedded-server E2E module. It is the ONE place that has BOTH the real server
// (:server → installPlatform/BootedPlatform) and the real client repos (:app:shared) on the test
// classpath. Kept OUT of the default `check` path (the Tester/CI runs `:e2e:test` as its own gate), so
// per-ticket gates stay fast. Additive: it changes no existing module's dependencies.
dependencies {
    testImplementation(projects.core)
    testImplementation(projects.server)
    testImplementation(projects.app.shared)
    // CYP-334 client-stack E2E: WsTtyConnector (the JediTerm bridge, jvmMain of :app:shared) implements
    // com.jediterm.terminal.TtyConnector — a jvmMain `implementation` dep, so not transitive here. Add jediterm-core
    // (TtyConnector + TermSize) test-only so the E2E can drive the REAL bridge against a live PTY. Repo is the
    // JetBrains intellij-dependencies (group-scoped) already declared in settings.gradle.kts (CYP-334).
    testImplementation(libs.jediterm.core)

    testImplementation(libs.kotlinx.coroutinesCore)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.logback)
    testImplementation(libs.sqlite.jdbc)

    // Real embedded server + real client (no mocks): the harness boots installPlatform and talks to it.
    testImplementation(libs.ktor.serverCore)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverContentNegotiation)
    testImplementation(libs.ktor.serverStatusPages)
    testImplementation(libs.ktor.serverCors)
    testImplementation(libs.ktor.serverWebsockets)
    testImplementation(libs.ktor.serializationKotlinxJson)
    testImplementation(libs.ktor.clientCio)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientWebsockets)

    testImplementation(libs.kotlin.testJunit)
}
