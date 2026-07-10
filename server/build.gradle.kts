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
    // CYP-225: disable Netty 4.2's JFR buffer telemetry via a JVM LAUNCH ARG, not only the in-code
    // System.setProperty in Application.main (CYP-206). Root cause of the persistent NoClassDefFoundError
    // FreeChunkEvent: `PlatformDependent.JFR` is a `static final` computed in `<clinit>` (cached at class-load)
    // and the emit sites guard `new FreeChunkEvent` with `isJfrEnabled()`; the in-code setProperty only takes
    // effect if it runs BEFORE PlatformDependent is class-initialized — which the deploy runtime does not
    // guarantee, so JFR cached `true` and the fix was a no-op (symptom identical to pre-fix). A launch arg is
    // applied before ANY class loads → `<clinit>` always caches JFR=false → FreeChunkEvent (extends
    // jdk.jfr.Event; NoClassDefFoundError on a stripped/quirky jdk.jfr JVM) is never referenced. Covers the
    // generated distribution start scripts + `:server:run`. (NOT a netty version/transitive issue — single
    // 4.2.13.Final; the in-code guard stays as belt-and-suspenders.) The deploy launch must carry the same arg
    // if it does not use the generated start script (java -jar / custom command) — flagged to deploy.
    applicationDefaultJvmArgs = listOf("-Dio.netty.jfr.enabled=false")
}

// CYP-157 (onboarding): the application plugin's `run` task otherwise uses the module dir as its
// working directory, so Application.main's relative defaults — `platform.config.json` and `.cyppie`
// (PLATFORM_CONFIG / PLATFORM_GIT_ROOT), which live at the REPO ROOT per SETUP.md §2 — are not found,
// and `:server:run` aborts with FileNotFoundException unless the user exports an absolute PLATFORM_CONFIG.
// Run from the repo root so a fresh clone's `:server:run` just works (no manual env var).
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// CYP-178 CC2 / CYP-366 (teeth-hygiene): two tests read shipped deploy/kratos files at RUNTIME via a
// repoFile() walk, so Gradle cannot see them as test inputs. Without declaring them, editing (or deleting)
// ONLY such a file leaves the `test` task UP-TO-DATE → the guard is stale-green on the exact surface it
// guards. Rc2ConfigAssertionTest reads kratos.reference.yml; OidcProviderConfigTest also asserts the
// oidc.github.jsonnet mapper ships — that file was UNDECLARED (CYP-366), so a probe change to it recycled a
// stale green (measured: the task went UP-TO-DATE on a real content change). Declaring both makes a change
// to EITHER re-run the tests.
tasks.named<Test>("test") {
    inputs.file(rootProject.file("deploy/kratos/kratos.reference.yml"))
        .withPropertyName("kratosReferenceConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("deploy/kratos/oidc.github.jsonnet"))
        .withPropertyName("oidcGithubMapper")
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
    // CYP-332: pty4j — real pseudo-terminal per agent for the interactive `claude` TUI (reverses 05-D4
    // piped-stdio; 02 §10 PTY-manager). Bundles its native helpers (Linux/macOS/Windows).
    implementation(libs.pty4j)
    // CYP-215 (avatar backend): Thumbnailator — thin, no-native-deps, ImageIO-based resize/crop/re-encode.
    // We control the decode (header-dim gate ourselves, then hand it a bounded BufferedImage). png+jpg only;
    // no native webp decoder pulled in (smallest attack surface on the untrusted upload path).
    implementation(libs.thumbnailator)
    // CYP-220 Phase 2a: Google Tink — misuse-resistant AEAD + KMS envelope encryption for secrets at rest
    // (API keys / remote tokens / DSN secrets). Master key (KEK) never in a user PG; AAD-bound ciphertext.
    implementation(libs.tink)
    // CYP-220 Phase 2b: per-instance JDBC connection pools + the Postgres driver (the pools the ConnectionProvider
    // hands per bound store; one HikariDataSource per DSN instance). No store is wired to a pool yet.
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    // CYP-220 Phase 3: Flyway migrates each bound Postgres instance's schema (per-datasource, baselineOnMigrate).
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    // Phase 3 tests: a real embedded Postgres binary in-process (no Docker) to prove the PG store end-to-end.
    testImplementation(libs.zonky.embeddedPostgres)
    // CYP-178: an HTTP client to validate Kratos sessions (GET /sessions/whoami).
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock) // CYP-179 C2: hermetic wiring assertion for the register-backend seam
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientWebsockets)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}