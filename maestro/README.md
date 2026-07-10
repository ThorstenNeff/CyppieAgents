# Maestro flows (CYP-11)

UI-gating for UI slices runs on **Maestro against the native builds (Android/iOS)**.
This directory holds one `*.yaml` per flow.

> ## ⚠ Es gibt keine Web-Flows mehr — und es kann keine geben (CYP-352)
>
> Die fünf `*-web.yaml`-Flows wurden entfernt. **Maestro kann auf dem Compose-Wasm-Canvas nichts adressieren:**
> es treibt Chromium über Selenium und liest den DOM, während Compose in ein `<canvas>` malt und keinen
> DOM-Knoten je Composable anlegt. Gemessen — derselbe Maestro sieht eine reine HTML-Seite (`exit 0`) und von
> unserer App **nichts**, weder testTag noch sichtbaren Text, mit 30 s Geduld.
>
> Der Satz „the Wasm mechanism is still to be confirmed … **not** wired yet", der früher weiter unten in dieser
> README stand, war die ganze Zeit richtig. Der Kopf von `smoke-web.yaml` behauptete gleichzeitig, der
> Mechanismus sei „verified in the tester's spike". **Er war es nie.**
>
> **Was stattdessen prüft:**
> * Oberfläche und Sicherheitsaussagen → `runComposeUiTest` unter `./gradlew :app:shared:wasmJsBrowserTest`
>   (echtes Headless-Chrome, echte Compose-Semantik). Beispiel: `EventLogPresenceWasmTest`.
> * Das **ausgelieferte Artefakt** → `scripts/web-boot-smoke.sh <URL> <erwartetes-Bundle>`.
>
> Vollständige Begründung, Verlustliste und Restposten: `docs/QA-WEB-FLOW-ROLLBACK-CYP-352.md`.

## Conventions (from Test-Contract v0.5 §4)
- **Web flow:** `appId`-free in spirit (placeholder `web`); the target is a URL opened via `openLink`.
- **Selectors reference testTags only** (no localizable text selectors). Maestro `id:` is a **regex**,
  so tag selectors are **escaped (`\.`) and anchored (`^…$`)** — e.g. `^agent\.backend\.input$`.
- **Tags (bare, no `@`):** `smoke`, `gating`, `security`, plus slice tags.

## Running (tester environment)
```bash
# Native flows only (Android/iOS). Web-Flows gibt es nicht mehr, siehe oben.
maestro test maestro/eventlog-browse-android.yaml
# filter by tag:
maestro test maestro/ --include-tags smoke
```

## Web statt Maestro
```bash
./gradlew :app:shared:wasmJsBrowserTest                       # Oberflaeche + Sicherheitsaussagen
./gradlew :app:webAppDemo:wasmJsBrowserDistribution           # Artefakt bauen
scripts/web-boot-smoke.sh http://localhost:8080 webAppDemo.js # Artefakt, Assets, Boot, "es wurde gemalt"
```

## Status / ownership
- **Authored by Dev (CYP-11).** The Compose UI-test infrastructure is verified runnable on JVM
  (`./gradlew :app:shared:jvmTest` → `UiTestInfraTest`, proving testTag addressing + off-screen
  `performScrollToNode`).
- **Runtime verification of this Maestro flow is the tester's (CYP-7) environment** — Dev's sandbox
  has no browser/Maestro runner. The selectors + structure are authored to the contract; the tester
  owns the QA verdict.
