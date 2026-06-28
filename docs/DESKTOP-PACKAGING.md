# Desktop Packaging & Start (CYP-72 / S9)

Native desktop distribution of the Compose Multiplatform app (`:app:desktopApp`) via the Compose
Gradle plugin. Desktop-only — Android/iOS are out of scope here.

> **Requirements:** JDK 17+ (JetBrains Runtime recommended) to *build*. The packaged app **bundles its
> own JRE**, so an end user running the distributable/installer needs no JDK.

## Fresh clone → one command → runs

```bash
./gradlew :app:desktopApp:run
```

Launches the desktop window directly from source. For live data it talks to the Ktor hub on
`localhost:8787` (CYP-24 default in `ShellConfig`); start it in another shell with `./gradlew :server:run`.
Without the server the window still opens; the comm/agent panels show their disconnected state.

## Self-contained distributable (bundled JRE, no installer)

```bash
./gradlew :app:desktopApp:createDistributable     # build the app-image
./gradlew :app:desktopApp:runDistributable        # run it
```

Output: `app/desktopApp/build/compose/binaries/main/app/CyppieAgents/` — a self-contained directory
with the launcher at `bin/CyppieAgents` (Linux/macOS) / `CyppieAgents.exe` (Windows) and a bundled
runtime under `lib/runtime/`. Copy the `CyppieAgents/` folder to any machine of the same OS and run the
launcher — no JDK required.

## Native installer (per OS)

```bash
./gradlew :app:desktopApp:packageDistributionForCurrentOS
```

Produces the OS-native installer under `app/desktopApp/build/compose/binaries/main/<format>/`:

| OS | Format | Artifact |
|----|--------|----------|
| Linux | `.deb` | `deb/cyppieagents_<version>_amd64.deb` |
| macOS | `.dmg` | `dmg/CyppieAgents-<version>.dmg` |
| Windows | `.msi` | `msi/CyppieAgents-<version>.msi` |

> `packageDistributionForCurrentOS` only builds the format for the host OS (cross-build is not
> supported by jpackage). On Linux the `.deb` build needs `dpkg`/`fakeroot` available on the host.

Packaging is configured in `app/desktopApp/build.gradle.kts` (`compose.desktop.application.nativeDistributions`):
`packageName = "CyppieAgents"`, `packageVersion`, vendor/description metadata, lowercase Linux package
name, macOS bundle id. An app icon is an optional follow-up (none bundled yet).

## No secrets in the artifact

The distributable bundles **only** app code + the JRE. All runtime configuration — hub URL, agent/operator
tokens, the Anthropic API key — is resolved at runtime from the environment via `ShellConfig`, never baked
into the build. `.env` is `.gitignore`d and is not part of any artifact. (Verified: the built app-image
contains no `*.env` / `*secret*` / `*token*` files.)
