# Remote User-Verification (UV) Flow — testTag-Vertrag (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 (rev. mit Reviewer-Crypto-Lens, PO
> `1526516045…`) · Status: **FROZEN** (Dev/Tester-Kontrakt).
> Begleit-Specs: `remote-uv-flow-ux-spec.md`, `remote-uv-flow-keys.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): prefixless `<area>[.<scopeId>].<element>[.<qualifier>]`, camelCase,
> **keine Punkte im Wert** (Punkt = Separator), charset `[A-Za-z0-9-]+`. **Geteilte API mit QA (CYP-7) — über den PO
> koordinieren.**
>
> **Anti-Duplikat / Grounding (READ-ONLY @ `eb705236`):** Die Credential-+Platform-Authenticator-UV ist bereits in
> **CYP-460** (`desktop-remote-operator-*`, FROZEN) spezifiziert **und teil-gebaut** (`OperatorAuthDialog` +
> `OperatorAuthTags` = Area `remote.authStep.*`, Copy `remote_pop_*`). CYP-542 fügt **keine zweite UV-Fläche** hinzu — es
> **erweitert die bestehende `OperatorAuthTags`-Area** um genau die Nähte, die die reale UV in Prod braucht und die heute
> fehlen. **Kein neuer Namespace, kein neues Object.**

## Crypto-Lens-Angleichung (PO/Reviewer, wichtig für die Tag-Semantik)
Das **Setz-/Bestätigungs-Feld ist Credential-agnostisch** (dasselbe maskierte `AuthPasswordField`), aber **was** es
aufnimmt, ist **capability-conditional**:
- **no-hardware** (kein rate-limitender Enclave, z. B. Linux) ⇒ **App-Passphrase ≥64 bit** (6-Wort-diceware / 12+ Zeichen)
  **mit Strength-Meter** — ein kurzer PIN wäre offline brute-forcebar.
- **hardware-backed** (Platform-Authenticator präsent, Enclave rate-limitet) ⇒ **Kurz-PIN** erlaubt.
Die Tag-Werte behalten der Konsistenz halber die `enrollPin…`-Namen (Twin zum FROZEN `enrollPinSet`); die **Copy**
unterscheidet PIN vs Passphrase (`remote-uv-flow-keys.md`).

## Warum überhaupt net-new (die As-built-Lücken vs. der frozen CYP-460-Spec)
1. **Enroll = zweifache Eingabe + Strength fehlt.** `OperatorAuthStep.Enroll` rendert heute nur Disclosure-Text, **kein**
   Feldpaar und **keinen** Strength-Meter (CYP-460 §5.2 fordert „zweifache Eingabe, Stärke-Hinweis").
2. **Dialog nicht gemountet.** `RemoteConnectingView(popPrompt = null)` (`HubConnectSelection.kt:258/291-294`).
3. **1-UV-für-N ist stumm.** Mechanik existiert (`CachingUserVerification`), UI-Hinweis fehlt.
4. **Biometrie-Enhancement-Angebot fehlt.** `BIOMETRIC_PROMPT` gebaut, aber **kein** Opt-in-*Angebot*.

---

## Net-new Tags (erweitern `OperatorAuthTags`, Area `remote.authStep.*`)
| Konstante | Wert | Present ⇔ / Semantik |
|---|---|---|
| `ENROLL_PIN_CONFIRM` | `remote.authStep.enrollPinConfirm` | das **Bestätigungs-Feld** im Enroll (Twin zum gebauten `ENROLL_PIN_SET`). **Credential-agnostisch** (hält PIN *oder* Passphrase). Present ⇔ Enroll-Step gerendert. |
| `ENROLL_STRENGTH` | `remote.authStep.enrollStrength` | der **Strength-Meter** (no-hardware Passphrase-Pfad). Present ⇔ Passphrase-Enroll aktiv. **Trägt ein Text-Level-Label** (schwach/mittel/stark) — Farbe **nie** alleiniger Träger (WCAG 1.4.1). |
| `ENROLL_SUGGEST` | `remote.authStep.enrollSuggest` | die **Diceware-Vorschlag**-Affordanz („Passphrase vorschlagen"). Present ⇔ Passphrase-Enroll aktiv. |
| `enrollError(cause)` | `remote.authStep.enrollError.<cause>` | **lokale Enroll-Validierung**, `<cause>` ∈ `mismatch` / `tooShort` (Kurz-PIN Min-Länge) / `tooWeak` (Passphrase-Entropie). Present ⇔ genau diese Validierung fehlschlägt. **Retryable**, Fehler-Ton (nie `errorContainer`), Feld bleibt aktiv. **≠** `error(<cause>)` (Auth-Zeit, nicht Setup). |
| `UV_COVERAGE` | `remote.authStep.uvCoverage` | der **1-UV-für-N-Hinweis**. Present ⇔ die Wiederverwendungs-Fensterung greift real (N>1 bzw. cachingUv aktiv). **Neutral/advisory**, kein Erfolgs-Grün. |
| `BIOMETRIC_OFFER` | `remote.authStep.biometricOffer` | das **Opt-in-Angebot** eines Platform-Authenticators (Enhancement). Present ⇔ Platform-Authenticator verfügbar **und** noch nicht aktiviert. **≠** `BIOMETRIC_PROMPT`. |

## Reuse — bestehende, FROZEN CYP-460-Tags (NICHT neu anlegen; verifiziert @ `eb705236`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `PATH_HINT` = `remote.authStep.pathHint` | CYP-460 | benennt den echten Pfad (Passphrase / PIN / Biometrie, H1) — auch im Enroll-Header. |
| `PIN_FIELD` / `PIN_REVEAL` = `remote.authStep.pinField` / `.pinReveal` | CYP-460 | das maskierte Feld (`AuthPasswordField`), reused fürs **Setz-Feld** (hält PIN *oder* Passphrase). |
| `ENROLL` = `remote.authStep.enroll` | CYP-429→CYP-460 | Enroll-Container-Zeile. |
| `ENROLL_PIN_SET` = `remote.authStep.enrollPinSet` | CYP-460 | das **Setz-Feld** (Feld 1 der zweifachen Eingabe). |
| `BIOMETRIC_PROMPT` = `remote.authStep.biometricPrompt` | CYP-460 | die reale Biometrie-Aufforderung (nach Annahme von `BIOMETRIC_OFFER`). |
| `ATTEMPTS` = `remote.authStep.attempts` | CYP-460 | Fehlversuch-Zähler (Threat-Model-parametrisiert). |
| `LOCKED_OUT` = `remote.authStep.lockedOut` | CYP-460 | temporäre Sperre + Cooldown (Rate-Limit, ≠ Hub-Reject). |
| `error(cause)` = `remote.authStep.error.<cause>` | CYP-460 | Auth-Zeit-Taxonomie `pinWrong`/`biometricFailed`/`keystoreUnavailable`/`authRejected`(terminal)/`needsEnroll`/`cancelled`. |
| `RemoteConnectTags.AUTHENTICATING` = `remote.connect.authenticating` | CYP-429 | der `AUTHENTICATING`-Zustand, in dessen `popPrompt`-Slot der Dialog mountet (ux-spec §4.2). |

## Fail-closed- / Ehrlichkeits-Anker (für §-QA)
- **HG — Credential-Stärke matcht die reale Absicherung:** no-hardware ⇒ `ENROLL_STRENGTH` + Passphrase-Copy + `tooWeak`-Gate;
  **Kurz-PIN nur** wenn hardware-backed (Enclave rate-limitet). Die UI **bietet nie** einen kurzen, offline-brute-forcebaren
  PIN an, wo keine Hardware ihn schützt (kein falsches Sicherheitsgefühl). `pathHint` benennt ehrlich, was aktiv ist.
- **Enroll-Commit ist gated:** kein Credential gesetzt, solange `enrollPinConfirm` ≠ `enrollPinSet` **oder** die
  Stärke-/Längen-Schwelle des Pfads nicht erreicht → `enrollError.mismatch` / `.tooShort` / `.tooWeak` (retryable). **Nie
  stiller Commit** einer unbestätigten/schwachen Eingabe.
- **`uvCoverage` ist ehrlich, nicht still, nicht überzeichnet:** present ⇔ Fensterung greift; Copy sagt „die Hubs, die du
  **jetzt** öffnest" — nicht „diese Sitzung für immer"; nach Ablauf **neuer** Prompt, nie stille Re-Auth.
- **`biometricOffer` maskiert das Credential nie:** Annehmen entfernt PIN/Passphrase nicht (bleibt Rückfall,
  Copy-disclosed). `pathHint` nennt immer den echten Pfad; Biometrie-Fehl → sichtbarer `PIN_FIELD`-Fallback (gebaut, H1).
- **Kein Enumeration:** un-enrolled → Enroll (`error(needsEnroll)`), nie „falsche PIN"; `pinWrong`/`lockedOut` verraten nur
  Zähler/Cooldown, nie ob ein Hub/Konto „existiert" (Credential trägt keinen Benutzernamen → inhärent enumeration-frei).
- **Terminal nur bei Hub-Reject (Reuse H2):** `error(authRejected)` terminal; **jede** lokale UV-Störung retryable.
- **Farbe nie alleiniger Träger (WCAG 1.4.1):** `ENROLL_STRENGTH` trägt ein Text-Level-Label; WARN-Downgrades tragen `▲`
  + `severityColor(WARN)` (nie Fehler-Rot, nie `tertiary`-Grün); affirmative Fakten (`uvCoverage`) neutral, kein Grün.

## Self-Validation
- **Net-new: 5 Const** (`ENROLL_PIN_CONFIRM`, `ENROLL_STRENGTH`, `ENROLL_SUGGEST`, `UV_COVERAGE`, `BIOMETRIC_OFFER`)
  **+ 1 Fn** (`enrollError(cause)`, `<cause>` ∈ `mismatch`/`tooShort`/`tooWeak`) — alle in der **bestehenden** Area
  `remote.authStep.*`, im **bestehenden** `OperatorAuthTags`-Object (kein neues Object/Namespace).
- **0 Kollision @ `eb705236`:** `enrollPinConfirm` / `enrollStrength` / `enrollSuggest` / `uvCoverage` / `biometricOffer` /
  `enrollError` existieren nicht im CYP-460/CYP-429-Satz (grep-verifiziert).
- **Charset ✓** camelCase, `[A-Za-z0-9-]+`, keine Punkte im Wert.
- **Reuse verifiziert:** die 9 reused Werte stammen 1:1 aus `OperatorAuthTags.kt` / `RemoteConnectTags.kt` @ `eb705236`
  (Code = Source of Truth) — Dev legt sie **nicht** neu an, wired nur.
- **Geteilte CYP-7-API:** die net-new Werte mit Tester/DS über den PO abstimmen (Frozen-Contract).
- Jeder net-new Tag ist in `remote-uv-flow-ux-spec.md` verankert und trägt (wo Text) einen Copy-Key aus `-keys.md`.
