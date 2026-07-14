# Remote User-Verification (UV) Flow — testTag-Vertrag (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 · Status: **FROZEN** (Dev/Tester-Kontrakt).
> Begleit-Specs: `remote-uv-flow-ux-spec.md`, `remote-uv-flow-keys.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): prefixless `<area>[.<scopeId>].<element>[.<qualifier>]`, camelCase,
> **keine Punkte im Wert** (Punkt = Separator), charset `[A-Za-z0-9-]+`. **Geteilte API mit QA (CYP-7) — über den PO
> koordinieren.**
>
> **Anti-Duplikat / Grounding (READ-ONLY @ `eb705236`):** Die App-PIN- + Platform-Authenticator-UV ist bereits in
> **CYP-460** (`desktop-remote-operator-*`, FROZEN) spezifiziert **und teil-gebaut** (`OperatorAuthDialog` +
> `OperatorAuthTags` = Area `remote.authStep.*`, Copy `remote_pop_*`). CYP-542 fügt **keine zweite UV-Fläche** hinzu — es
> **erweitert die bestehende `OperatorAuthTags`-Area** um genau die vier Nähte, die die reale UV in Prod braucht und die
> heute fehlen. **Kein neuer Namespace, kein neues Object.**

## Warum überhaupt net-new (die vier As-built-Lücken vs. der frozen CYP-460-Spec)
1. **Enroll = zweifache Eingabe fehlt.** `OperatorAuthDialog` `Enroll`-Step rendert heute nur Disclosure-Text
   (`remote_pop_enroll_pin` + Session-only), **kein** „PIN wählen + bestätigen"-Feldpaar (CYP-460 §5.2 fordert es).
2. **Dialog nicht gemountet.** `RemoteConnectingView(popPrompt = null)` (`HubConnectSelection.kt:258/291-294`) → im
   `AUTHENTICATING`-Zustand läuft heute der neutrale INERT-Spinner statt des PoP-Prompts. (Mount-Wiring = ux-spec §4.)
3. **1-UV-für-N ist stumm.** Mechanik existiert (`CachingUserVerification`/`OPERATOR_UV_REUSE_WINDOW_MS`), aber **kein**
   UI-Hinweis, dass **eine** Bestätigung N Tunnel autorisiert. Stumme Coverage = Ehrlichkeits-Lücke.
4. **Biometrie-Enhancement-Angebot fehlt.** Der Biometrie-*Prompt* ist gebaut (`BIOMETRIC_PROMPT`), aber **kein**
   Opt-in-*Angebot* („Touch-ID einrichten?" — PIN bleibt Rückfall).

---

## Net-new Tags (erweitern `OperatorAuthTags`, Area `remote.authStep.*`)
| Konstante | Wert | Present ⇔ / Semantik |
|---|---|---|
| `ENROLL_PIN_CONFIRM` | `remote.authStep.enrollPinConfirm` | das **Bestätigungs-Feld** im Enroll (Twin zum gebauten `ENROLL_PIN_SET`). Present ⇔ Enroll-Step gerendert. |
| `enrollError(cause)` | `remote.authStep.enrollError.<cause>` | **lokale Enroll-Validierung**, `<cause>` ∈ `mismatch` / `tooShort`. Present ⇔ genau diese Validierung fehlschlägt. **Retryable**, Fehler-Ton (nie `errorContainer`), Feld bleibt aktiv. **≠** `error(<cause>)` (das ist Auth-Zeit, nicht Setup). |
| `UV_COVERAGE` | `remote.authStep.uvCoverage` | der **1-UV-für-N-Hinweis** („eine Bestätigung autorisiert die Hubs, die du jetzt öffnest"). Present ⇔ die Wiederverwendungs-Fensterung greift real (N>1 bzw. cachingUv aktiv). **Neutral/advisory**, kein Erfolgs-Grün. |
| `BIOMETRIC_OFFER` | `remote.authStep.biometricOffer` | das **Opt-in-Angebot** eines Platform-Authenticators (Enhancement). Present ⇔ Platform-Authenticator verfügbar **und** noch nicht aktiviert. **≠** `BIOMETRIC_PROMPT` (die tatsächliche Biometrie-Aufforderung). |

## Reuse — bestehende, FROZEN CYP-460-Tags (NICHT neu anlegen; verifiziert @ `eb705236`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `PATH_HINT` = `remote.authStep.pathHint` | CYP-460 | benennt den echten Pfad (PIN vs Biometrie, H1) — auch im Enroll-Header. |
| `PIN_FIELD` / `PIN_REVEAL` = `remote.authStep.pinField` / `.pinReveal` | CYP-460 | das gebaute maskierte Feld (`AuthPasswordField`), reused fürs **Setz-Feld** im Enroll. |
| `ENROLL` = `remote.authStep.enroll` | CYP-429→CYP-460 | Enroll-Container-Zeile. |
| `ENROLL_PIN_SET` = `remote.authStep.enrollPinSet` | CYP-460 | das **Setz-Feld** (Feld 1 der zweifachen Eingabe). |
| `BIOMETRIC_PROMPT` = `remote.authStep.biometricPrompt` | CYP-460 | die reale Biometrie-Aufforderung (nachdem `BIOMETRIC_OFFER` angenommen). |
| `ATTEMPTS` = `remote.authStep.attempts` | CYP-460 | Fehlversuch-Zähler (Threat-Model-parametrisiert). |
| `LOCKED_OUT` = `remote.authStep.lockedOut` | CYP-460 | temporäre Sperre + Cooldown (Rate-Limit, ≠ Hub-Reject). |
| `error(cause)` = `remote.authStep.error.<cause>` | CYP-460 | Auth-Zeit-Taxonomie `pinWrong`/`biometricFailed`/`keystoreUnavailable`/`authRejected`(terminal)/`needsEnroll`/`cancelled`. |
| `RemoteConnectTags.AUTHENTICATING` = `remote.connect.authenticating` | CYP-429 | der `AUTHENTICATING`-Zustand, in dessen `popPrompt`-Slot der Dialog mountet (ux-spec §4). |

## Fail-closed- / Ehrlichkeits-Anker (für §-QA)
- **Enroll-Commit ist gated:** kein PIN wird gesetzt, solange `enrollPinConfirm` ≠ `enrollPinSet` **oder** Länge < Min →
  `enrollError.mismatch` / `enrollError.tooShort` (retryable, Fehler-Ton, Feld aktiv). **Nie stiller Commit** einer
  unbestätigten Eingabe.
- **`uvCoverage` ist ehrlich, nicht still, nicht überzeichnet:** present ⇔ die Fensterung greift; die Copy sagt
  „die Hubs, die du **jetzt** öffnest" — **nicht** „diese Sitzung für immer" (das Fenster ist begrenzt; nach Ablauf
  **neuer** Prompt, nie stille Re-Auth, nie Fake-Coverage).
- **`biometricOffer` maskiert die PIN nie:** Annehmen des Angebots **entfernt die App-PIN nicht** (bleibt Rückfall,
  Copy-disclosed). `pathHint` nennt immer den echten Pfad — nie Biometrie-Optik ohne Biometrie. Biometrie-Fehl →
  sichtbarer `PIN_FIELD`-Fallback (gebaut, H1).
- **Kein Enumeration:** un-enrolled → Enroll-Step (`error(needsEnroll)`), **nie** „falsche PIN" für ein Gerät ohne PIN;
  `pinWrong`/`lockedOut` verraten nur Zähler/Cooldown, **nie** ob ein Hub/Konto „existiert" (PIN trägt keinen
  Benutzernamen → inhärent enumeration-frei).
- **Terminal nur bei Hub-Reject (Reuse H2):** `error(authRejected)` = terminal (errorContainer, neu anmelden); **jede**
  lokale UV-Störung (`pinWrong`/`lockedOut`/`enrollError.*`/`biometricFailed`/`cancelled`/`keystoreUnavailable`) ist
  retryable, Fehler-Ton, **nie** terminal.
- **Farbe nie alleiniger Träger (WCAG 1.4.1):** WARN-Downgrades (Session-only) tragen `▲` als **separaten** Node +
  `severityColor(WARN)` — nie Fehler-Rot, nie `tertiary`-Grün; affirmative Fakten (`uvCoverage`) neutral, kein Erfolgs-Grün.

## Self-Validation
- **Net-new: 3 Const** (`ENROLL_PIN_CONFIRM`, `UV_COVERAGE`, `BIOMETRIC_OFFER`) **+ 1 Fn** (`enrollError(cause)`,
  `<cause>` ∈ `mismatch`/`tooShort`) — alle in der **bestehenden** Area `remote.authStep.*`, im **bestehenden**
  `OperatorAuthTags`-Object (kein neues Object/Namespace).
- **0 Kollision @ `eb705236`:** `enrollPinConfirm` / `authStep.uvCoverage` / `authStep.biometricOffer` /
  `authStep.enrollError` existieren nicht im CYP-460/CYP-429-Satz (grep-verifiziert).
- **Charset ✓** camelCase, `[A-Za-z0-9-]+`, keine Punkte im Wert.
- **Reuse verifiziert:** die 9 reused Werte stammen 1:1 aus `OperatorAuthTags.kt` / `RemoteConnectTags.kt` @ `eb705236`
  (Code = Source of Truth) — Dev legt sie **nicht** neu an, wired nur.
- **Geteilte CYP-7-API:** die 4 net-new Werte mit Tester/DS über den PO abstimmen (Frozen-Contract).
- Jeder net-new Tag ist in `remote-uv-flow-ux-spec.md` verankert und trägt (wo Text) einen Copy-Key aus `-keys.md`.
