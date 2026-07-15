# Login-Flow — Visual/UX-Completeness-Critic: Fehler- & Edge-Sicht­zustände (CYP-576 / CYP-575)

> Owner: UIUX-Designer · Stand 2026-07-15 · **Review, kein Code-Change.** Komplementär zur Happy-Path-QA
> (`CYP-576-login-happypath-uxqa.md`). **Leitfrage:** „Welchen Sicht-Zustand würde der Mensch morgen als **Bug fehllesen**
> oder **übersehen** — ein Error, der nicht als Error liest; ein Loading-State ununterscheidbar von einem Hang; eine
> mehrdeutige Transition?" Gegroundet READ-ONLY gg. develop `d06001a6` (file:symbol).

## Urteil: **5 von 6 legibel bestätigt · 1 echte Lücke (Corrupt-Vault, Med)**
Die visuelle Ebene trägt die Triage — **außer** beim Corrupt-Vault-Dead-End, der als „kaputt" statt „by-design + Recovery"
liest. Details unten; die Lücke → Doc/UI-Note an PO (kein Selbst-Fix).

---

## Pro Sicht-Zustand (gegroundet)

### ① Browser-Return-Error / OIDC-Error — ✅ **legibel**
- **Ist:** `onGithubReturn` → `SessionState.None` → `GithubUiState.Error` (`AuthViewModel.kt`); rendert
  `AnnouncingHint(auth_github_error, HintTone.ERROR, GITHUB_ERROR, Assertive)` = **„Die GitHub-Anmeldung wurde nicht
  abgeschlossen. Bitte erneut versuchen oder oben mit E-Mail anmelden."** + Retry-Button (`GITHUB_RETRY`).
- **Legibilität:** **kein** stummer Rücksprung — der Mensch ist zwar zurück auf dem Login-Screen, aber **mit sichtbarem
  error-Ton-Hinweis (Assertive announced) + Grund + Retry + E-Mail-Ausweg**. Zieht die richtige Triage-Zeile. **Kein Gap.**

### ② Leere Hub-Liste (falscher GH-Account, GAP 3) — ✅ **legibel**
- **Ist:** `hubs.isEmpty()` → **eigener** Render (`HubConnectSelection.kt:91-104`): `hubconnect_hubs_empty` „Noch keine
  Hubs — sobald dein Hub online ist, erscheint er hier." (`bodyMedium`, `onSurfaceVariant`, Tag `HUBS_EMPTY`) **+ Refresh**
  (`HUBS_REFRESH`). Der Loading-State (`LoadingHubs`) rendert dagegen `PrepareView()` mit `CircularProgressIndicator`.
- **Legibilität:** **erkennbar-leer** (eigene Copy + Refresh) und **distinct vom Laden** (Spinner ≠ Text+Refresh). Die
  Copy ist ehrlich („erscheint hier, sobald online") — kein Fehl-Signal „lädt noch". **Kein Gap.**

### ③ Unlock (`PassphrasePrompt`) vs. „Passphrase setzen" (`SetPassphrase`, GAP 2) — ✅ **legibel** (1 optionale Stärkung)
- **Ist:** `PassphrasePromptStep` (Unlock) = Titel **„App-Passphrase eingeben"** + **ein** maskiertes Feld + Coverage-Zeile.
  `SetPassphraseStep` (Enroll) = Titel **„App-Passphrase festlegen"** + **Diceware-Default-Block** (lesbare Wörter + „Diese
  Passphrase verwenden") + type-your-own + **Strength-Meter**.
- **Legibilität:** **strukturell stark distinct** — der Diceware-Block + Meter erscheinen **nur** beim Setzen; distinkte
  Verben (*eingeben*=entsperren vs *festlegen*=neu). Der Mensch verwechselt sie nicht. **Kein Gap.**
- **Optionale Stärkung (nicht-blockierend):** beide teilen das „App-Passphrase"-Präfix; eine 1-Zeilen-Subline
  („Erstmalig — leg deine Passphrase fest" vs „Entsperre mit deiner Passphrase") würde die Erst-vs-Wiederkehr-Lesbarkeit
  weiter härten. Nice-to-have, kein Gap.

### ④ Corrupt-Vault → OOB-Recovery-Dead-End (E-F1, by-design fail-closed) — ⚠ **GAP [Med]**
- **Ist:** `VaultOpen.Corrupt` → `UvOutcome.Unavailable` (`PassphraseUserVerification.kt:43`) → rendert
  `OperatorAuthError.KeystoreUnavailable` → **„Schlüsselbund nicht verfügbar."** (`OperatorAuthCopy.kt:23`), error-Ton,
  terminal (fail-closed, **nie** auto-re-enroll — CYP-525-H1b-Schutz).
- **Das Legibilitäts-Problem (zwei):**
  1. **Liest als „kaputt", nicht als „by-design + Recovery":** „Schlüsselbund nicht verfügbar." ist eine **generische
     technische Fehlmeldung**. Sie **signposted den OOB-Recovery-Weg nicht** (die `remote_recovery_codes_*`-Codes) und
     vermittelt nicht, dass der fail-closed-Block **beabsichtigt** ist (Vault korrupt/manipuliert → **kein** stilles
     Re-Enroll). Der Mensch liest „kaputt/Fehlstart" und zieht die **falsche** Triage-Zeile.
  2. **Corrupt ≠ Missing, aber gleich gerendert:** `VaultOpen.Corrupt` **und** `VaultOpen.Missing`
     (`PassphraseUserVerification.kt:43-44`) mappen **beide** auf `UvOutcome.Unavailable` → **dieselbe** bland Copy. Der
     **sicherheitsrelevante** Corrupt-Fall (braucht Recovery-Guidance, ist ein Tamper-Signal) ist damit **ununterscheidbar**
     vom transienten Missing-Fall (raced deletion). Der Code trennt die Wahrheiten (`OperatorSecretVault.kt:15-17`), die
     **UI kollabiert sie**.
- **Doc/UI-Note (→ PO, Fix-Vorschlag, kein Selbst-Fix):** für den Corrupt-Pfad eine **eigene, ehrliche Copy** die (a) den
  fail-closed-Zustand als **beabsichtigt** benennt („Aus Sicherheitsgründen gesperrt — der Geräte-Schlüsselbund ist
  beschädigt oder wurde verändert.") und (b) den **Recovery-Weg signposted** („Stelle den Zugang mit deinen
  Wiederherstellungs-Codes wieder her." → verlinkt/führt zur `remote_recovery_*`-Fläche). Missing bleibt separat (neutraler,
  ggf. „neu verbinden"). Neuer Key + Recovery-Affordanz — **kann ich als Copy/Keys/Tags-Delta liefern**, wenn priorisiert.

### ⑤ Loading/Pending-Zustände (Loopback-Wait · Verify-Pending · Connecting) — ✅ **legibel**
- **Connecting/RemoteConnecting:** `CircularProgressIndicator` + Fortschritts-Copy (relayDialing→handshake→trust→
  `remote_connect_authenticating` „Operator wird bestätigt…"), `HubConnectFlow.kt:124/186`, `RemoteOperatorAuthSteps.kt:234`.
  **Distinct von Hang** (Spinner + benannter Schritt). ✅
- **Verify-Pending:** `VerifyPendingScreen` mit `auth_verify_pending_body` (beschreibend „falls ein Konto existiert…") — ein
  **benannter Warte-Zustand**, kein Hang. ✅
- **Loopback-Wait (`BrowserHandoff`):** **kein** Spinner in den ersten ~30 s, **aber** beschreibende Copy (Weiter im
  Browser… + sichtbare Fallback-URL) **und** der **Timeout-Escape** (`TimedOut` nach X≈30 s, INFO + Retry). Der Timeout ist
  der Hang-Diskriminator → **kein** ewiges „Continuing". ✅
  - **Optionale Stärkung (nicht-blockierend):** ein dezenter Progress/Spinner im Handoff-Fenster würde „arbeitet" schon
    **vor** dem Timeout verstärken. Nice-to-have; der Timeout+URL deckt die Legibilität bereits.

### ⑥ Port-Bind-Error (Loopback, GAP 4) — ✅ **legibel (schon adressiert)**
- **Ist:** `main.kt:55-73` (CYP-576 P1 / BUG-A / **CYP-578**): ein Bind-/Start-Fehler wird **nicht** verschluckt →
  `onReturn(null,null,null)` → die VM **rejectet** (State-Mismatch) → **retry-baren `GithubUiState.Error`**.
- **Legibilität:** der stille Fehlstart ist **bewusst behoben** — der Port-Bind-Fehler landet im **sichtbaren, retry-baren
  Error-State** (①). **Kein Gap** (Kommentar zitiert genau das Ziel: „SURFACE a bind failure instead of swallowing it into
  an eternal 'Continuing…' hang").

---

## Gap-Liste (Output an PO)
| # | Sicht-Zustand | Legibel? | Note |
|---|---|---|---|
| ① | Browser-Return-/OIDC-Error | ✅ | error-Ton + Grund + Retry + E-Mail-Ausweg (Assertive) |
| ② | Leere Hub-Liste | ✅ | eigene „noch keine Hubs"-Copy + Refresh, distinct vom Spinner |
| ③ | Unlock vs Set-Passphrase | ✅ | strukturell distinct (Diceware nur bei Set); optionale Subline-Stärkung |
| **④** | **Corrupt-Vault → Unavailable** | **⚠ GAP [Med]** | **liest als „kaputt"; kein Recovery-Signpost; Corrupt≡Missing kollabiert. Eigene Copy + Recovery-Affordanz nötig.** |
| ⑤ | Loading/Pending | ✅ | Spinner+Schritt / Timeout-Escape — kein Hang; optionaler Handoff-Progress |
| ⑥ | Port-Bind-Error | ✅ | CYP-578 surfaced → retry-barer Error |

## Self-Validation
- **Jeder** der 6 Zustände gg. **gebautem** Code @ `d06001a6` geprüft (file:symbol), nicht gg. Annahme.
- **1 echte Lücke** (④ Corrupt-Vault) mit **Severity + konkretem Fix-Vorschlag**; 5 legibel **bestätigt** (klar gesagt, nicht vage).
- **Kein Bau, kein Selbst-Fix** — die ④-Copy/Keys/Tags liefere ich als Delta auf PO-Priorisierung. docs-only auf `feature/CYP-576-auth-oidc-legibility-spec`.
