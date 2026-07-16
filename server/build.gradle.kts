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
    applicationDefaultJvmArgs = listOf(
        "-Dio.netty.jfr.enabled=false",
        // CYP-417 (S-G / D8): a real, bounded heap so the JVM has a knowable ceiling (the ResourceGovernor's
        // capacity estimate needs a non-unbounded maxMemory()) AND the OOM lesson is enforced at the JVM level,
        // not just at the spawn gate. 75% of container RAM (deploy may override with an explicit -Xmx).
        "-XX:MaxRAMPercentage=75.0",
    )
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
    // CYP-366: OidcProviderConfigTest also reads deploy/kratos/oidc.github.jsonnet at RUNTIME (the repoFile walk
    // asserts the shipped GitHub OIDC mapper is present), but it was NOT a declared input — so editing/removing
    // ONLY the jsonnet left `test` UP-TO-DATE and the guard stale-green on the exact file it exists to catch.
    // Declaring it re-runs the test on a jsonnet change, closing the same gap the kratos.reference.yml wiring
    // above closes for the yml (CYP-178 CC2).
    inputs.file(rootProject.file("deploy/kratos/oidc.github.jsonnet"))
        .withPropertyName("oidcGithubMapperJsonnet")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // CYP-409: the committed AsyncAPI export is read at RUNTIME by ContractExportDriftTest (a repoFile walk), so
    // Gradle can't otherwise see it as a test input — without this, editing ONLY the export leaves `test`
    // UP-TO-DATE and the drift guard is stale-green on the exact surface it exists to catch. Optional so a
    // pre-export checkout still configures (the drift test itself reports the missing file).
    inputs.file(rootProject.file("web-ts/contract/asyncapi.json"))
        .withPropertyName("asyncApiContractExport")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)
    // CYP-426: the committed OpenAPI (REST) export, same stale-green wiring as the asyncapi one above.
    inputs.file(rootProject.file("web-ts/contract/openapi.json"))
        .withPropertyName("openApiContractExport")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)
}

// CYP-409/CYP-426 (W1 producer): export the AsyncAPI (WS) + OpenAPI (REST) contracts (generated from :core via
// ContractGenerator) to committed files the TS consumer (Dev5) reads. OFFLINE + secret-free — it runs the
// generators DIRECTLY, not the auth-gated `/docs/*.json` routes, so the build needs no live server, no network,
// no token. The bytes are single-sourced with the `/docs` serialization (docsJson) and guarded against drift by
// ContractExportDriftTest. Writes both files relative to workingDir (the repo root).
tasks.register<JavaExec>("exportContract") {
    group = "contract"
    description = "Generate web-ts/contract/{asyncapi,openapi}.json from :core via ContractGenerator (offline, no live server)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tneff.cyppieagents.contract.ContractExportKt")
    workingDir = rootProject.projectDir
    outputs.file(rootProject.file("web-ts/contract/asyncapi.json"))
    outputs.file(rootProject.file("web-ts/contract/openapi.json"))
}

// CYP-506 (Epic CYP-427 activation): the untrusted relay-server is a SEPARATE deployable process (its own main,
// co-located on OakHost, stood up by `deploy`). It links no hub store/secret — a dumb opaque-frame pipe. Binds
// CYPPIE_RELAY_HOST:CYPPIE_RELAY_PORT (defaults 0.0.0.0:8788); public exposure/reverse-proxy posture is deploy-owned.
tasks.register<JavaExec>("relayRun") {
    group = "application"
    description = "Run the CYP-506 untrusted rendezvous relay-server (register/pair/forward opaque Noise frames)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tneff.cyppieagents.relay.RelayServerKt")
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
    // CYP-457 (Phase-2 remote): Noise Protocol Framework for the JVM — the hub-side Noise_NK **responder**
    // terminator (mirror of the client's INITIATOR in :app:shared). Same pinned suite
    // (Noise_NK_25519_ChaChaPoly_BLAKE2s), consuming the S-C `dhKey` X25519 static. Pure-Java, MIT, no runtime deps.
    implementation(libs.noise.java)
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
    // CYP-458: the hub DIALS the relay WS outbound (reverse tunnel) — client WebSockets on the JVM engine (CIO).
    implementation(libs.ktor.clientWebsockets)
    // CYP-512: the hub's outbound admission client (HubAdmissionClient) speaks JSON to the CP /api/cp/* endpoints.
    implementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock) // CYP-179 C2: hermetic wiring assertion for the register-backend seam
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
}

// ── CYP-626 — self-contained hub installer (jlink runtime + jpackage) ────────────────────────────────────────────
// Proven by the CYP-625 spike (docs/design/CYP-625-packaging-spike-result.md): the two runtime-extracted native
// deps — sqlite-jdbc (JNI) + pty4j (per-OS PTY helper → JNA) — self-extract and run under a jlink JRE 21 AND inside a
// jpackage app-image. This packages the FULL :server (Netty/Tink/Flyway/… atop those two natives) into an OS-native
// installer: Windows `.msi` (needs WiX 3.x on the build host), else `dmg`/`app-image`. One config, `--type` per OS.
run {
    val jdkHome = System.getProperty("java.home") // the JDK running Gradle (must be a JDK 17+/21 with jlink+jpackage)
    val installerName = "CyppieHub"
    val hubMainClass = "com.tneff.cyppieagents.ApplicationKt"
    // CYP-625 finding: `jdk.unsupported` is REQUIRED (pty4j → JNA → sun.misc.Unsafe). `jdk.crypto.ec` for the JDK EC
    // provider (Ed25519/X25519), `jdk.jfr` for Netty's JFR class refs (disabled via the arg below, module kept for
    // safety). `java.se` is the generous-but-proven aggregate (all java.* incl. sql/naming/xml/management); a later
    // pass can minimize once each target OS's full-boot module needs are profiled.
    val jlinkModules = "java.se,jdk.unsupported,jdk.crypto.ec,jdk.jfr"
    // CYP-623 §1: BOTH mandatory :server JVM args MUST ride the generated launcher (a custom `java -jar`/service
    // command that drops `-Dio.netty.jfr.enabled=false` hits NoClassDefFoundError FreeChunkEvent on a stripped JDK).
    val launcherArgs = listOf("-Dio.netty.jfr.enabled=false", "-XX:MaxRAMPercentage=75.0")

    // Absolute paths + flags captured at CONFIG time (Strings/Booleans → configuration-cache-safe; the task actions
    // touch NO Project/script objects — only java.io.File — so the tasks serialize cleanly under the config cache).
    val runtimePath = layout.buildDirectory.dir("hub-runtime").get().asFile.absolutePath
    val installerPath = layout.buildDirectory.dir("hub-installer").get().asFile.absolutePath
    val distLibPath = layout.buildDirectory.dir("install/server/lib").get().asFile.absolutePath
    val versionStr = project.version.toString()
    val mainJar = "server-$versionStr.jar"
    val os = org.gradle.internal.os.OperatingSystem.current()
    val installerType = when { os.isWindows -> "msi"; os.isMacOsX -> "dmg"; else -> "app-image" }
    val isWindows = os.isWindows

    val hubJlink = tasks.register<Exec>("hubJlink") {
        group = "distribution"
        description = "CYP-626: jlink a minimized JRE 21 for the hub installer."
        outputs.dir(runtimePath)
        doFirst { File(runtimePath).deleteRecursively() } // jlink refuses to write into an existing dir
        commandLine(
            "$jdkHome/bin/jlink", "--add-modules", jlinkModules,
            "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", runtimePath,
        )
    }

    tasks.register<Exec>("hubInstaller") {
        group = "distribution"
        description = "CYP-626: jpackage the hub into an OS-native installer ($installerType: msi on Windows, dmg on macOS, app-image on Linux)."
        dependsOn("installDist", hubJlink) // installDist → build/install/server/lib/*.jar (all runtime jars in one dir)
        doFirst { File(installerPath).apply { deleteRecursively(); mkdirs() } }
        val args = mutableListOf(
            "$jdkHome/bin/jpackage",
            "--type", installerType,
            "--name", installerName,
            "--app-version", versionStr,
            "--input", distLibPath,
            "--main-jar", mainJar,
            "--main-class", hubMainClass,
            "--runtime-image", runtimePath,
            "--dest", installerPath,
        )
        launcherArgs.forEach { args += listOf("--java-options", it) }
        // Windows: a console app (the hub logs to stdout; the CYP-627 service wrapper captures it) + install chooser.
        if (isWindows) args += listOf("--win-console", "--win-dir-chooser", "--win-menu", "--win-shortcut")
        commandLine(args)
    }
}