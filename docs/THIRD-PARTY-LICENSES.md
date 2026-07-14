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

## EFF Large Wordlist (operator-passphrase diceware) — CYP-542 / B1

- **Asset:** `app/shared/src/jvmMain/resources/diceware/eff_large_wordlist.txt` — the 7776-word (6⁵) EFF Large
  Wordlist, bundled **verbatim** (the raw `<5-dice-digits>\t<word>` file; the loader parses the word column).
- **Scope:** `:app:shared` **jvmMain only** — the CYP-542/B1 diceware passphrase default (uniform-random,
  ≥6 words ⇒ ≥77 bit). Desktop-first; iOS/web = the ② follow-on (CYP-545 = localized lists, non-MVP).
- **Provenance (auditable, verify-at-source — CYP-212 hygiene):**
  - **Source (EFF origin, not a mirror):** `https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt`
  - **Retrieved:** 2026-07-14
  - **SHA-256 of the committed file:** `addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e`
    (matches the canonical EFF file — reproducibly verifiable at the B1 gate: exact-7776-unique ∧ SHA-256 match).
- **License:** **Creative Commons Attribution 3.0 United States (CC BY 3.0 US)**, © Electronic Frontier
  Foundation. Attribution: *"EFF Large Wordlist" by the Electronic Frontier Foundation (eff.org), CC BY 3.0 US
  (https://creativecommons.org/licenses/by/3.0/us/).* Used unmodified (raw file bundled). Reproduce this
  attribution in the app's distributed license file.

## BouncyCastle provider (Argon2id KDF) — CYP-542 / B1

- **Artifact:** `org.bouncycastle:bcprov-jdk18on` (version `1.78.1`, pinned exact ≥1.78 — RR5 supply-chain; ≤1.77
  carry CVE-2024-30172 / CVE-2023-33201).
- **Scope:** `:app:shared` **jvmMain only** — the `Argon2BytesGenerator` for the operator-passphrase KEK. Pure-Java,
  used as an unmodified binary dependency (no `BouncyCastleProvider` registration; JDK SunEC serves Ed25519, not BC).
- **License:** the **BouncyCastle License** (an adaptation of the MIT X11 license) — permissive, no copyleft.
