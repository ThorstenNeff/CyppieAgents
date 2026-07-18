plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application // CYP-142 (S4.3): a deployable artifact (runnable JAR / installDist) for user infra.
}

group = "com.tneff.cyppieagents"
version = "1.0.0"

// CYP-142 / E2.6 (S4) — the Remote Bridge/Runtime: the deployable that runs in USER infra, wraps the
// user's Claude-Code stream-json session, and relays it to the hub over the Hub-Wire-Protocol (/ws/hub).
// INSIDE = provider-specific (CC stdio, REUSED from :connector-core incl. the CYP-170 inversion); OUTSIDE
// = uniform wire (:core DTOs). It carries NO server secrets — only the agent's S3 token + HUB_URL.
application {
    mainClass = "com.tneff.cyppieagents.remote.BridgeMainKt"
}

// CYP-142: PIN the compile toolchain to JDK 21 (UIUX build-finding). Without this, :remote-runtime pinned no
// jvmTarget/toolchain, so the emitted bytecode level was whatever JDK ran the build (the reference build happened
// to run on 21 → class 65). For a DEPLOYABLE bundled with a jlink JRE 21, the class-file version cannot be a build-
// host accident — pin it so the Bridge jar is deterministically class 65, matched to the bundled runtime.
kotlin {
    jvmToolchain(21)
}

dependencies {
    api(projects.core)
    api(projects.connectorCore) // ClaudeCodeSession (seamed), ResumingSession (CYP-170), MediationGate, ProcessBuilderSpawner
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.logback)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientWebsockets)
    implementation(libs.ktor.serializationKotlinxJson)
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlin.testJunit)
    // CYP-142 (S4.3): an in-process WS server to round-trip the KtorWireLink transport (no real network).
    testImplementation(libs.ktor.serverCore)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverWebsockets)
}

// ── CYP-142 — self-contained Bridge distribution (jlink runtime + jpackage app-image) ─────────────────────────────
// The Bridge is a SEPARATE user-space deployable (E2.6): it runs in USER infra, wraps the user's Claude-Code stream-
// json session, and relays it to the hub over /ws/hub. Unlike the Hub `.deb` (a systemd SYSTEM service under /opt,
// installed with sudo), the Bridge is N ordinary user processes run as ONE login user — so the artifact is a jpackage
// APP-IMAGE (a self-contained directory: no sudo, no systemd, no install step), delivered as a tarball. Its generated
// launcher execs the BUNDLED jlink runtime by ABSOLUTE path — never a bare `java` on PATH. That is the CYP-685 cold-
// boot rule applied to the Bridge: a BYOA box may carry no JRE-21, a module-stripped one, or one unreadable to the run
// user (all three were MEASURED on Team-2's box). A Bridge that assumed a host JRE would not be a deployable — so, like
// the Hub, it ships its own. jlink + jpackage both ship in JDK 21.
//
// Wrapped in an immediately-invoked local function: keeps the vals LOCAL so the task-action lambdas capture locals,
// NOT the build-script object (the configuration cache cannot serialize script-object references). NB: a bare `run { }`
// wrapper is ambiguous here — it binds to the application plugin's lazy `run` TASK accessor and silently skips the
// block (the tasks never register); an explicit local fn cannot collide with any generated accessor.
(fun() {
    val bridgeJdkHome = javaToolchains.launcherFor {
        // CYP-634 determinism: pin the packaging JDK to 21 via the Gradle TOOLCHAIN (a build on JDK 25 would otherwise
        // emit a 25 runtime), NOT the Gradle JVM's java.home.
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.asFile.absolutePath
    val bridgeImageName = "CyppieBridge"
    val bridgeMainClass = "com.tneff.cyppieagents.remote.BridgeMainKt"
    // The Bridge's OWN module set — NOT the Hub's java.se aggregate (~27 resolved modules). Derived by
    // `jdeps --print-module-deps` over the FULL installDist runtime classpath (all 30 jars):
    //   java.base, java.instrument, java.logging, java.management, java.naming, java.xml, jdk.unsupported
    // (jdk.unsupported is required: kotlinx-coroutines' debug internals reference sun.misc.Unsafe — jdeps sees it).
    // PLUS one reflective add jdeps STRUCTURALLY CANNOT see, because it is loaded via java.security.Provider:
    //   jdk.crypto.ec — Ktor's own TLS (ktor-network-tls) performs the wss:// handshake to the Caddy-fronted hub via
    //   JCA (KeyAgreement "XDH"/"ECDH", KeyFactory "EC"), which resolve to the SunEC provider. Omit it → NoSuchAlgorithm
    //   at the TLS-1.3 X25519 / ECDHE handshake. The Hub bundles it for the identical reason — a certainty, not a guess.
    // (jlink also auto-pulls java.security.sasl as a transitive of java.naming/management — expected.)
    // Deliberately EXCLUDED: java.desktop / java.sql. They appear only in the RAW jdeps trace via logback's optional
    // AWT/DB appenders (dead code on the Bridge's boot→spawn→wss→serialize path); the minimal --print-module-deps set
    // omits them, and the boot-smoke under this runtime exercises that exact path without touching either.
    val bridgeJlinkModules = "java.base,java.instrument,java.logging,java.management,java.naming,java.xml,jdk.unsupported,jdk.crypto.ec"

    val bridgeRuntimePath = layout.buildDirectory.dir("bridge-runtime").get().asFile.absolutePath
    val bridgeImagePath = layout.buildDirectory.dir("bridge-image").get().asFile.absolutePath
    val bridgeDistLibPath = layout.buildDirectory.dir("install/remote-runtime/lib").get().asFile.absolutePath
    val bridgeVersionStr = project.version.toString()
    val bridgeMainJar = "remote-runtime-$bridgeVersionStr.jar"

    val bridgeJlink = tasks.register<Exec>("bridgeJlink") {
        group = "distribution"
        description = "CYP-142: jlink a minimized JRE 21 for the Bridge app-image (Bridge-own module set, not the Hub's)."
        outputs.dir(bridgeRuntimePath)
        doFirst { File(bridgeRuntimePath).deleteRecursively() } // jlink refuses to write into an existing dir
        commandLine(
            "$bridgeJdkHome/bin/jlink", "--add-modules", bridgeJlinkModules,
            "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", bridgeRuntimePath,
        )
    }

    tasks.register<Exec>("bridgeImage") {
        group = "distribution"
        description = "CYP-142: jpackage the Bridge into a self-contained app-image (bundled jlink JRE; no sudo, no systemd)."
        dependsOn("installDist", bridgeJlink) // installDist → build/install/remote-runtime/lib/*.jar (all runtime jars)
        doFirst { File(bridgeImagePath).apply { deleteRecursively(); mkdirs() } }
        commandLine(
            "$bridgeJdkHome/bin/jpackage",
            "--type", "app-image", // a directory, not an OS installer — 6 user-space starts, no root, cross-user moot
            "--name", bridgeImageName,
            "--app-version", bridgeVersionStr,
            "--input", bridgeDistLibPath,
            "--main-jar", bridgeMainJar,
            "--main-class", bridgeMainClass,
            "--runtime-image", bridgeRuntimePath, // launcher execs THIS bundled JRE by absolute path (cold-boot-safe)
            "--dest", bridgeImagePath,
        )
    }
})() // end CYP-142 Bridge-distribution scope
