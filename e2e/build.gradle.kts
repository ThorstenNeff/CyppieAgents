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

// web-e2e (§8 test infra) — boot the hermetic platform (FakeSpawner/FakeGit, no real claude/key/repo) on a
// FIXED port for Playwright's `webServer` to start and drive a real browser against. Runs the boot main from
// the TEST source set (where the CYP-106 harness lives), NOT the default `check` path. workingDir = repo root
// so the boot main can read the reference fixture at `web-e2e/fixture/index.html`. Stop with Ctrl-C / process kill.
tasks.register<JavaExec>("webE2eServer") {
    group = "verification"
    description = "web-e2e: boot the hermetic E2E platform on \$WEB_E2E_PORT (default 8791) for Playwright."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.tneff.cyppieagents.e2e.WebE2eServerMainKt")
    workingDir = rootProject.projectDir
    System.getenv("WEB_E2E_PORT")?.let { environment("WEB_E2E_PORT", it) }
    // CYP-407: the boot main reads this too, and JavaExec does NOT inherit the Gradle process environment — an
    // un-forwarded variable is silently ignored, which reads exactly like "the feature does not work". (Measured:
    // the seed booted with one channel and no hint that the request had been dropped. Same class as CYP-342's
    // un-forwarded TZ.) Forward it explicitly, or not at all.
    System.getenv("WEB_E2E_EXTRA_AGENT")?.let { environment("WEB_E2E_EXTRA_AGENT", it) }
    standardOutput = System.out
    errorOutput = System.err
}

