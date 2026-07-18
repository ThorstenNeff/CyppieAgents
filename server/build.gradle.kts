plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    // Required so @Serializable classes defined in :server (e.g. PlatformConfig) get generated
    // serializers — without it PlatformConfig.load() throws at runtime and the boot crashes (CYP-33).
    alias(libs.plugins.kotlinSerialization)
}

group = "com.tneff.cyppieagents"
version = "1.0.0"

// CYP-678 — SINGLE SOURCE for the hub's JVM launch args. BOTH consumers derive from this ONE file:
//   • `applicationDefaultJvmArgs` below → Gradle `run` / `installDist` (the generated start scripts + `:server:run`);
//   • the `.deb` jpackage `--java-options` (`launcherArgs`, the CYP-626 installer).
// Before CYP-678 these were TWO hand-copied lists = the CYP-623 drift trap (a flag changed in one, forgotten in the
// other). `gatewayRun` reads its own `gateway.jvmargs` the same way (CYP-667). `deploy/hub/hub.jvmargs` carries the
// full set — `-Dio.netty.jfr.enabled=false` (CYP-206/225: a LAUNCH arg, not the in-code setProperty that races
// class-init, so `<clinit>` caches JFR=false and FreeChunkEvent is never referenced on a stripped jdk.jfr), the
// bounded heap (CYP-417), AND the CYP-670 D2 hardening (HeapDump/CoreDump-off; the hub holds the master key in RAM on
// Linux too — D2 is platform-independent). Editing the argfile now updates BOTH launchers. Pinned by
// HubJvmArgsSingleSourceTest (both sides derive from the file — delete a flag → both red); `hub.jvmargs` is a declared
// `test` input (CC2) so an argfile-only edit re-runs that guard.
val hubJvmArgs: List<String> = rootProject.file("deploy/hub/hub.jvmargs").readLines()
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

application {
    mainClass = "com.tneff.cyppieagents.ApplicationKt"
    applicationDefaultJvmArgs = hubJvmArgs // CYP-678: single-sourced from deploy/hub/hub.jvmargs (see above)
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
    // CYP-638 S7: GatewayLaunchHardeningTest reads deploy/gateway/gateway.jvmargs at RUNTIME (a repoFile walk), so
    // Gradle can't otherwise see it as a test input — without this, editing ONLY the argfile leaves `test` UP-TO-DATE
    // and the launch-hardening guard is stale-green on the exact file it exists to pin (same CC2 fix as the yml/jsonnet
    // wiring above).
    inputs.file(rootProject.file("deploy/gateway/gateway.jvmargs"))
        .withPropertyName("gatewayJvmArgs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // CYP-667 S7: GatewayLaunchdPlistTest reads the macOS launchd wrapper + plist at RUNTIME (repoFile walk) — declare
    // them as inputs so editing ONLY the wrapper/plist re-runs the guard (same CC2 stale-green fix as above).
    inputs.file(rootProject.file("deploy/launchd/gateway-run.sh"))
        .withPropertyName("gatewayLaunchdWrapper")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("deploy/launchd/com.cyppie.gateway.plist"))
        .withPropertyName("gatewayLaunchdPlist")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // CYP-670: CyppieDaemonBootPersistenceTest reads the Hub+Relay launchd plists/wrappers/argfiles at RUNTIME —
    // declare them so editing ONLY a config file re-runs the boot-persistence guard (same CC2 stale-green fix).
    listOf(
        "deploy/launchd/com.cyppie.hub.plist", "deploy/launchd/com.cyppie.relay.plist",
        "deploy/launchd/hub-run.sh", "deploy/launchd/relay-run.sh",
        "deploy/hub/hub.jvmargs", "deploy/relay/relay.jvmargs",
    ).forEachIndexed { i, p ->
        inputs.file(rootProject.file(p)).withPropertyName("cyp670Config$i").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // CYP-678: HubJvmArgsSingleSourceTest reads server/build.gradle.kts at RUNTIME to assert BOTH the Gradle
    // `run`/`installDist` args AND the `.deb` `launcherArgs` derive from `hub.jvmargs` — so declare the build script an
    // input, else hard-coding one side back to a literal list (the drift mutation) could leave `test` UP-TO-DATE =
    // false-green ([[gate-undeclared-input-uptodate]]).
    inputs.file(rootProject.file("server/build.gradle.kts"))
        .withPropertyName("serverBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // CYP-680: HubSystemdCoreDumpTest reads the .deb systemd units at RUNTIME — declare them so removing LimitCORE=0
    // from a unit (the drift mutation) re-runs the guard instead of leaving `test` UP-TO-DATE (CC2 stale-green fix).
    listOf("deploy/linux/cyppiehub.service", "deploy/linux/cyppiehub-test.service").forEachIndexed { i, p ->
        inputs.file(rootProject.file(p)).withPropertyName("cyp680Unit$i").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // CYP-708: DebTwinPinTest reads the .deb maintainer scripts of BOTH packages at RUNTIME — declare all six so a
    // drift in either twin re-runs the guard. Measured, not assumed: with these undeclared, three real mutations to
    // the -test postinst (chmod 0600→0644, a deleted `systemctl daemon-reload`, --host 127.0.0.1→0.0.0.0) ALL left
    // `test` UP-TO-DATE and the guard reported green. A twin-pin that cannot see the twins is worse than no guard —
    // it certifies the drift it was built to catch (same CC2 stale-green class as the units above).
    listOf(
        "deploy/linux/deb-resources/postinst", "deploy/linux/deb-resources/prerm", "deploy/linux/deb-resources/postrm",
        "deploy/linux/deb-resources-test/postinst", "deploy/linux/deb-resources-test/prerm",
        "deploy/linux/deb-resources-test/postrm",
    ).forEachIndexed { i, p ->
        inputs.file(rootProject.file(p)).withPropertyName("cyp708DebResource$i").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // CYP-681: GitignoreSecretHygieneTest reads the root .gitignore at RUNTIME — declare it so removing a secret
    // pattern (the drift mutation) re-runs the guard instead of leaving `test` UP-TO-DATE (CC2 stale-green fix).
    inputs.file(rootProject.file(".gitignore"))
        .withPropertyName("rootGitignore")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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

tasks.register<JavaExec>("gatewayRun") {
    group = "application"
    description = "CYP-638 S0 — run the isolated Gateway process (same-origin front-door; default-deny allowlist reverse-proxy to the hub)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tneff.cyppieagents.gateway.GatewayServerKt")
    // CYP-638 S7 — the gateway launch HARDENING args ride from the SINGLE-SOURCE argfile so this dev-run and any
    // packaged gateway launcher (jpackage --java-options / systemd) carry the SAME flags (no drift; the CYP-623 lesson
    // that two hand-copied arg lists diverge). PO-Assistant flag: `-XX:-HeapDumpOnOutOfMemoryError` +
    // `-XX:-CreateCoredumpOnCrash` so the A2 cleartext heap (tokens + PTY bytes) never spills to disk. Pinned by
    // GatewayLaunchHardeningTest (which reads the same file).
    jvmArgs(
        rootProject.file("deploy/gateway/gateway.jvmargs").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") },
    )
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
    // CYP-634: pin the packaging JDK to 21 via a Gradle TOOLCHAIN (deterministic bundled JRE), NOT the Gradle JVM's
    // java.home — a build on JDK 25 would otherwise emit a 25 runtime (the README §6 follow-up, folded in with the deb
    // branch since the deb is the first second-OS packaging where determinism bites). jlink + jpackage both ship in 21.
    val jdkHome = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.asFile.absolutePath
    val installerName = "CyppieHub"
    val hubMainClass = "com.tneff.cyppieagents.ApplicationKt"
    // CYP-625 finding: `jdk.unsupported` is REQUIRED (pty4j → JNA → sun.misc.Unsafe). `jdk.crypto.ec` for the JDK EC
    // provider (Ed25519/X25519), `jdk.jfr` for Netty's JFR class refs (disabled via the arg below, module kept for
    // safety). `java.se` is the generous-but-proven aggregate (all java.* incl. sql/naming/xml/management); a later
    // pass can minimize once each target OS's full-boot module needs are profiled.
    // CYP-687 (M1.1): `jdk.jcmd` bundles `jcmd` into the runtime so the BYOA acceptance can read the RUNNING hub JVM's
    // effective flags (`jcmd <pid> VM.flags`) — the authoritative source for whether the -Xmx run-override actually
    // gripped (the hard heap gate in deploy/linux/byoa-m1-acceptance.sh). Small; no new attack surface (a diagnostic CLI).
    val jlinkModules = "java.se,jdk.unsupported,jdk.crypto.ec,jdk.jfr,jdk.jcmd"
    // CYP-623 §1: BOTH mandatory :server JVM args MUST ride the generated launcher (a custom `java -jar`/service
    // command that drops `-Dio.netty.jfr.enabled=false` hits NoClassDefFoundError FreeChunkEvent on a stripped JDK).
    // CYP-678: single-sourced from deploy/hub/hub.jvmargs — the SAME `hubJvmArgs` list `applicationDefaultJvmArgs`
    // uses, no hand-copied second set. Consolidating also gives the `.deb` the CYP-670 D2 hardening
    // (HeapDump/CoreDump-off), which is correct: the hub holds the master key in RAM on Linux too (D2 = platform-independent).
    val launcherArgs = hubJvmArgs

    // Absolute paths + flags captured at CONFIG time (Strings/Booleans → configuration-cache-safe; the task actions
    // touch NO Project/script objects — only java.io.File — so the tasks serialize cleanly under the config cache).
    val runtimePath = layout.buildDirectory.dir("hub-runtime").get().asFile.absolutePath
    val installerPath = layout.buildDirectory.dir("hub-installer").get().asFile.absolutePath
    val distLibPath = layout.buildDirectory.dir("install/server/lib").get().asFile.absolutePath
    val versionStr = project.version.toString()
    val mainJar = "server-$versionStr.jar"
    // CYP-628: a second app-image launcher (CyppieHubProvision) for the install-time provisioning entrypoint. Shares
    // the --main-jar; only overrides the main class (see the properties file). The wizard invokes it once on the host.
    val provisionLauncherProps = rootProject.file("deploy/windows/provision-launcher.properties").absolutePath
    // CYP-687 (M1.1): a third app-image launcher (CyppieHubAcceptance) — the claude-free BYOA acceptance wire-client
    // (ByoaAcceptanceMain) that proves a remote agent connects + works both ways over /ws/hub. po2 drives it on the
    // Ubuntu-26 box via deploy/linux/byoa-m1-acceptance.sh (the gap-4 target acceptance).
    val acceptanceLauncherProps = rootProject.file("deploy/linux/acceptance-launcher.properties").absolutePath
    // CYP-635: the Linux .deb maintainer-script overrides (postinst/prerm/postrm) — jpackage picks them up from
    // --resource-dir and substitutes its empty skeleton. They do the user + provision + hub.env + unit install/enable
    // (postinst), stop/disable (prerm), and remove-preserve / purge-wipe (postrm).
    val debResourceDir = rootProject.file("deploy/linux/deb-resources").absolutePath
    // CYP-636: SINGLE-SOURCE the systemd unit — bundle the reviewable master into the .deb payload (jpackage
    // --app-content → /opt/cyppiehub/cyppiehub.service) so the postinst `cp`s it (no heredoc duplication → drift
    // structurally impossible).
    val unitFile = rootProject.file("deploy/linux/cyppiehub.service").absolutePath
    // CYP-637: TEST-SCOPED twins of the .deb resources — same shape, every name/path/port `-test`-scoped so the
    // hubInstallerTest .deb (cyppiehub-test) provisions an ISOLATED hub that shares NOTHING with the live install.
    val debResourceDirTest = rootProject.file("deploy/linux/deb-resources-test").absolutePath
    val unitFileTest = rootProject.file("deploy/linux/cyppiehub-test.service").absolutePath
    val installerPathTest = layout.buildDirectory.dir("hub-installer-test").get().asFile.absolutePath
    val os = org.gradle.internal.os.OperatingSystem.current()
    // CYP-634: Linux → `.deb` (jpackage needs dpkg/fakeroot on the host). Same installDist→jlink→jpackage chain.
    val installerType = when { os.isWindows -> "msi"; os.isMacOsX -> "dmg"; else -> "deb" }
    val isWindows = os.isWindows
    val isLinux = os.isLinux

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
        description = "CYP-626/634: jpackage the hub into an OS-native installer ($installerType: msi on Windows, dmg on macOS, deb on Linux)."
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
        // CYP-628: the provisioning launcher (CyppieHubProvision) — the install wizard runs it to mint the master key
        // + tokens + default config on the host (the .msi ships no secret).
        args += listOf("--add-launcher", "CyppieHubProvision=$provisionLauncherProps")
        args += listOf("--add-launcher", "CyppieHubAcceptance=$acceptanceLauncherProps") // CYP-687 M1.1 acceptance wire-client
        // Windows: a console app (the hub logs to stdout; the CYP-627 service wrapper captures it) + install chooser.
        if (isWindows) args += listOf("--win-console", "--win-dir-chooser", "--win-menu", "--win-shortcut")
        // CYP-634: Linux `.deb` — install to /opt (→ /opt/cyppiehub/bin/CyppieHub, referenced by the systemd unit).
        // The systemd unit + maintainer scripts (install/enable/provision, CYP-635) ride via --resource-dir deploy/linux.
        if (isLinux) args += listOf(
            "--linux-package-name", "cyppiehub", "--install-dir", "/opt",
            "--resource-dir", debResourceDir, // CYP-635: postinst/prerm/postrm (service install + provision + preserve/purge)
            "--app-content", unitFile, // CYP-636: ship the systemd unit into the payload → postinst cp's it (single-source)
            // CYP-687 (M1.1) — `git` is a RUNTIME prereq (the hub clones/pulls the repo; agents work in worktrees). It is
            // NOT a shared-lib dep so dpkg-shlibdeps never auto-detects it → declare it so `apt install ./cyppiehub.deb`
            // pulls it on a fresh Ubuntu box. Appended to (not replacing) the auto shlib Depends. `claude` is a separate
            // non-apt prereq (documented; not declarable here). NB: the ~10 X11/audio libs the auto-Depends pull are
            // LEGITIMATE — java.desktop (ImageIO + Thumbnailator, CYP-215 avatars) links them; apt resolves them on Ubuntu.
            "--linux-package-deps", "git",
        )
        commandLine(args)
    }

    // CYP-637: the TEST-SCOPED .deb (Linux only). Identical build chain to hubInstaller, but --linux-package-name
    // cyppiehub-test → install-dir /opt/cyppiehub-test, and the -test maintainer scripts + unit whose EVERY destructive
    // op references ONLY the -test names. Produces build/hub-installer-test/cyppiehub-test_<ver>_amd64.deb — the artifact
    // the Auftraggeber installs (with sudo) to exercise the lifecycle without ever touching the live hub.
    if (isLinux) tasks.register<Exec>("hubInstallerTest") {
        group = "distribution"
        description = "CYP-637: jpackage the TEST-SCOPED cyppiehub-test .deb (isolated names/paths/ports) for the lifecycle acceptance."
        dependsOn("installDist", hubJlink)
        doFirst { File(installerPathTest).apply { deleteRecursively(); mkdirs() } }
        val args = mutableListOf(
            "$jdkHome/bin/jpackage",
            "--type", "deb",
            "--name", installerName, // launcher stays CyppieHub (→ /opt/cyppiehub-test/bin/CyppieHub)
            "--app-version", versionStr,
            "--input", distLibPath,
            "--main-jar", mainJar,
            "--main-class", hubMainClass,
            "--runtime-image", runtimePath,
            "--dest", installerPathTest,
        )
        launcherArgs.forEach { args += listOf("--java-options", it) }
        args += listOf("--add-launcher", "CyppieHubProvision=$provisionLauncherProps")
        args += listOf("--add-launcher", "CyppieHubAcceptance=$acceptanceLauncherProps") // CYP-687 M1.1 acceptance wire-client
        args += listOf(
            "--linux-package-name", "cyppiehub-test", "--install-dir", "/opt",
            "--resource-dir", debResourceDirTest, // CYP-637: -test postinst/prerm/postrm (isolated provision + preserve/purge)
            "--app-content", unitFileTest, // CYP-637: -test systemd unit → postinst cp's it (single-source)
        )
        commandLine(args)
    }
}