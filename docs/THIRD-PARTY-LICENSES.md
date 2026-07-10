# Third-Party Licenses

Runtime/bundled third-party dependencies whose licensing warrants an explicit record. (Not exhaustive of the
transitive graph — this documents deliberate license *elections* and copyleft-adjacent components.)

## JediTerm (Desktop terminal widget) — CYP-334

- **Artifacts:** `org.jetbrains.jediterm:jediterm-core`, `org.jetbrains.jediterm:jediterm-ui` (version `3.73`).
- **Scope:** `:app:shared` **jvmMain only** — the Desktop `TerminalView` actual (JediTerm in a `SwingPanel`,
  CYP-331 Option D). Not shipped on Web/Wasm/Android/iOS (stub actuals). Used as an unmodified binary dependency.
- **Distribution:** JetBrains **intellij-dependencies** repo (`https://cache-redirector.jetbrains.com/intellij-dependencies`),
  not Maven Central — see `settings.gradle.kts` (group-scoped to `org.jetbrains.jediterm`).
- **License:** JediTerm is **dual-licensed — LGPLv3 *or* Apache-2.0, at the user's option.**
  > "JediTerm is dual-licensed under both the LGPLv3 (found in the `LICENSE-LGPLv3.txt` file …) and Apache 2.0
  > License (found in the `LICENSE-APACHE-2.0.txt` file …). You may select, at your option, one of the above-listed
  > licenses." — JediTerm `README.md` (github.com/JetBrains/jediterm)
  >
  > (Note: the Maven POM for `3.73` lists only `LGPL 3.0`; the authoritative project LICENSE/README offers the dual
  > election above. Verified against the source repo, not the POM alone.)
- **Our election:** **Apache License 2.0.** We take JediTerm under Apache-2.0 (permissive) — so there is **no
  LGPL copyleft obligation** from bundling it in the Desktop distribution. Compatible with an Apache/MPL/BSL
  release of this project. Reproduce the Apache-2.0 notice for JediTerm in the app's distributed license file.
