# Remote User-Verification (UV) Flow — Design-Pass (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 · Status: **Design-Pass** (PO synthetisiert
> mit Devs Mechanismus-Skizze). Begleit-Specs: `remote-uv-flow-tags.md` (FROZEN Tag-Kontrakt), `remote-uv-flow-keys.md`
> (Copy-AC). **Kein Bau, keine develop-Berührung.** Gegroundet READ-ONLY gg. develop `eb705236`.

## 0. Kern-Ehrlichkeit (die tragende Grounding-Korrektur)
Remote braucht eine **reale** User-Verification, um in Prod zu laufen — heute ist sie ein **fail-closed-Stub**
(`RemoteHubMode.jvm.kt:184` `deferredUserVerification { UvOutcome.Unavailable }` ⇒ PoP wird nie signiert ⇒ Prod inert).
**Die UX dieser UV existiert aber schon:** CYP-460 (`desktop-remote-operator-*`, FROZEN) spezifiziert den App-PIN- +
Platform-Authenticator-Flow, und `OperatorAuthDialog` + `OperatorAuthTags` (`remote.authStep.*`) + `remote_pop_*` sind
**gebaut und eingefroren**. **CYP-542 erfindet daher keine zweite UV-Fläche** — es schließt die **vier As-built-Lücken**,
die die reale UV in Prod braucht und die heute fehlen. Das ist bewusst ein **dünner Delta**, kein Parallel-Spec.

Die vier Lücken (alle READ-ONLY @ `eb705236` verifiziert):

| # | Naht | As-built heute | CYP-542 schließt |
|---|---|---|---|
| **U1** | **Enroll = wählen + bestätigen** | `OperatorAuthStep.Enroll` rendert nur Disclosure-Text — **kein** Feldpaar (CYP-460 §5.2 fordert „zweifache Eingabe"). | §4.1: Setz-Feld + Bestätigungs-Feld + Mismatch/Too-short-Gate. |
| **U2** | **Prompt gemountet** | `RemoteConnectingView(popPrompt = null)` (`HubConnectSelection.kt:258/291-294`) → `AUTHENTICATING` zeigt den INERT-Spinner. | §4.2: `popPrompt` mountet den gebauten Dialog; `DeviceNotEnrolled` routet in den Enroll-Step. |
| **U3** | **1-UV-für-N ehrlich** | Mechanik da (`CachingUserVerification`, `OPERATOR_UV_REUSE_WINDOW_MS`), UI **stumm**. | §4.3: neutraler Coverage-Hinweis, ehrlich begrenzt. |
| **U4** | **Biometrie-Angebot** | Biometrie-*Prompt* gebaut; **kein** Opt-in-*Angebot*. | §4.4: „Touch-ID einrichten?"-Angebot, PIN bleibt Rückfall. |

---

## 1. Scope
**In:** die vier Nähte oben, als Erweiterung des frozen CYP-460-Dialogs; Security-sichtbare Fehlversuch-/Sperr-/
Fail-closed-Zustände; maritim + M3; frozen Tag-Delta + Copy-Delta (de+en) + a11y.
**Out (bewusst):** die Auth-/Enroll-*Mechanik* selbst (Dev/Backend — PO synthetisiert); Threat-Model-*Zahlen*
(Mindestlänge, N, Backoff — die UX zeichnet die **Zustände**, die Zahlen kommen vom Threat-Model, CYP-460 §5.4);
Recovery/Re-Enroll (Q6 eskaliert, unverändert); TOFU (§6 CYP-460, unverändert); der optionale Passphrase-Opt-up
(reused `PIN_FIELD`, keine neue Fläche).

## 2. Reuse/Grounding-Karte (gegen echten Code @ `eb705236`)
- **Dialog-Content:** `OperatorAuthDialog(step, error, pin, …)` — Pin/Biometric/Enroll-Steps, H2-Terminal-vs-lokal-Split,
  `WrongPin.attemptsLeft`-Zähler, Session-only-WARN-amber. **Wiederverwenden, nicht neu bauen.**
- **Feld:** `AuthPasswordField` (maskiert, Text-Label-Reveal, kein a11y-Leak) via `PinField`.
- **Zustandsmodell:** `RemoteConnState.AUTHENTICATING` (der Slot), `OperatorAuthError` (Taxonomie), `UvOutcome`
  (`Verified`/`Denied(reason)`/`Unavailable`), `CachingUserVerification` (1-UV-für-N), `OperatorAuthStep.Enroll(sessionOnly)`.
- **Farb-/Ton-Helfer:** `severityColor(Severity.WARN)` + `Severity.glyph()` (`▲`/`ⓘ`) aus `EventVisuals.kt`.
- **Tags/Copy:** frozen `OperatorAuthTags` + `remote_pop_*` (CYP-460). Delta in den Begleit-Specs.

---

## 3. App-PIN als Software-Baseline (der cross-platform Boden)
Die **App-PIN** ist die **immer verfügbare** Baseline (Linux = immer, sonst Fallback) — der Platform-Authenticator (§4.4)
ist ein **Enhancement darüber**, nie ein Ersatz. `pathHint` (`remote_pop_path_hint`) benennt **immer den echten aktiven
Pfad** — nie Biometrie-Optik ohne Biometrie (gebaute H1).

## 4. Die vier Nähte

### 4.1 U1 — Enroll: PIN wählen **+ bestätigen** (Setup)
Der `Enroll`-Step wird um das **Feldpaar** ergänzt (heute nur Disclosure-Text):
1. **Setz-Feld** — reuse `PIN_FIELD` + `remote_pop_enroll_pin` („App-PIN festlegen"). Tag: `ENROLL_PIN_SET` (gebaut).
2. **Bestätigungs-Feld** — `ENROLL_PIN_CONFIRM` (net-new) + `remote_pop_enroll_confirm` („App-PIN bestätigen").
3. **Gate:** ein PIN wird **erst gesetzt, wenn** Bestätigung == Setz **und** Länge ≥ Threat-Model-Min. Sonst:
   - `enrollError.mismatch` + `remote_pop_enroll_mismatch` (PINs ungleich),
   - `enrollError.tooShort` + `remote_pop_enroll_too_short` (`%1$s` = Min).
   Beide **retryable, Fehler-Ton (`colorScheme.error`, nicht `errorContainer`)**, Felder bleiben aktiv — analog zum
   gebauten lokalen `WrongPin`-Pfad. **Nie stiller Commit** einer unbestätigten Eingabe.
4. **Session-only-Disclosure** unverändert (gebaut): solange `DEVICE_SECURE` named-not-built ist, WARN-amber
   `remote_pop_enroll_session_only` (`▲` + `severityColor(WARN)`, Q5/GE6). **Kein Über-Versprechen** eines
   Hardware-Schlüsselbunds.

### 4.2 U2 — Mount: der Prompt in `AUTHENTICATING` (+ Enroll-Routing)
- **Mount:** `HubConnectFlow` reicht `RemoteConnectingView` einen echten `popPrompt`-Slot (heute `null`) → im
  `AUTHENTICATING`-Zustand rendert der **gebaute `OperatorAuthDialog`** statt des INERT-Spinners
  (`HubConnectSelection.kt:291-294`). Der Slot ist **schon vorgesehen** (Signatur `:258`) — reine Verdrahtung, kein
  Umbau.
- **Enroll-Routing:** `DeviceNotEnrolled` / `OperatorAuthError.NeedsEnroll` mountet den **Enroll-Step (4.1)** inline,
  statt in der WARN-Retry-Sackgasse zu enden. `remote_connect_device_not_enrolled` bleibt der ehrliche Einstieg
  (Enumeration-Guard: „nicht eingerichtet", **nie** „falsche PIN").
- **Ergebnis-Taxonomie** unverändert (CYP-460 §5.3): `verifying`→neutraler Spinner; `granted`→weiter zur Hub-Liste
  **ohne Erfolgs-Grün**; lokale Fehler retryable; `authRejected` terminal.

### 4.3 U3 — 1-UV-für-N: eine Bestätigung, N Tunnel (ehrlich)
`CachingUserVerification` sorgt dafür, dass **eine** PIN-Bestätigung die **N** Tunnel autorisiert, die der Operator jetzt
öffnet (bounded window `OPERATOR_UV_REUSE_WINDOW_MS`). Diese Coverage wird **sichtbar und ehrlich gemacht**:
- **Hinweis** `UV_COVERAGE` + `remote_pop_uv_coverage` — **neutral/advisory** (`onSurfaceVariant`, optional `ⓘ`-Node,
  **kein** Erfolgs-Grün, es ist kein „Erfolg" sondern ein Fakt).
- **Present ⇔** die Fensterung greift real (N>1 bzw. cachingUv aktiv) — sonst absent (kein Phantom-Versprechen bei
  Einzel-Hub).
- **Ehrlich begrenzt:** die Copy sagt „die Hubs, die du **jetzt** öffnest" — **nicht** „diese Sitzung für immer". Das
  Fenster ist begrenzt; **nach Ablauf ein neuer Prompt** (nie stille Re-Auth, nie Fake-Coverage). H4-konsistent: der
  Deckungs-Anspruch ist nie optimistischer als die reale Fensterung.

### 4.4 U4 — Platform-Authenticator als Enhancement (Opt-in)
Wo ein Platform-Authenticator verfügbar ist (macOS Touch-ID / Windows Hello), wird er als **Enhancement über der
PIN-Baseline** angeboten — nie als stiller Ersatz:
- **Angebot** `BIOMETRIC_OFFER` + `remote_pop_biometric_offer` (`%1$s` = Authenticator-Name). Present ⇔ verfügbar **und**
  noch nicht aktiviert. Enable/Überspringen = Dialog-Chrome (keine Button-Keys frozen, CYP-460-Konvention).
- **Ehrlichkeit** `remote_pop_biometric_offer_fallback`: „Deine App-PIN bleibt als Rückfall." — Annehmen entfernt die
  PIN **nie**.
- **Nach Annahme:** die reale Biometrie-Aufforderung ist der **gebaute** `BIOMETRIC_PROMPT` + `remote_pop_biometric_title`;
  **Biometrie-Fehl → sichtbarer PIN-Fallback** (`remote_pop_biometric_failed`, gebaute H1).
- `pathHint` nennt nach Annahme den **Biometrie**-Pfad, davor den **PIN**-Pfad — immer der echte.

## 5. Security-sichtbare Zustände (rate-limit-aware, keine Enumeration, fail-closed)
Alle **gebaut** (CYP-460) — CYP-542 verankert sie als §-QA-Zähne, ergänzt nichts außer der Enumeration-Klarstellung:
- **Fehlversuch:** `remote_pop_wrong_pin` + `ATTEMPTS` — zeigt **nur den Rest-Zähler**, nie ob ein Hub/Konto „existiert".
- **Sperre (Rate-Limit):** `remote_pop_locked` + `LOCKED_OUT` — temporäre Sperre + **ehrlicher Cooldown** (`%1$s`),
  **≠** Hub-Reject. Backoff/N = Threat-Model.
- **Keine Enumeration:** PIN trägt keinen Benutzernamen → inhärent enumeration-frei; un-enrolled routet in **Enroll**
  (nie „falsche PIN" für ein Gerät ohne PIN); Feedback verrät nur Count/Cooldown.
- **Fail-closed, kein Fake-Erfolg:** `UvOutcome.Unavailable` (keine UV-Mechanik / Keystore weg) → **blockt Connect** mit
  ehrlichem Zustand (`remote_pop_keystore_unavailable`), **nie** stille Gewährung. `granted` zeigt **kein** Erfolgs-Grün.
- **Terminal nur bei Hub-Reject (H2):** `remote_pop_rejected` + `error(authRejected)` = errorContainer, neu anmelden,
  kein stiller Retry; **jede** lokale Störung retryable.

## 6. Maritim + M3
- **Neutrale Fakten** (Pfad-Hinweis, Coverage, Setz/Bestätigen-Felder): `onSurfaceVariant`/`onSurface`, kein Statusfarbton.
- **WARN-Downgrade** (Session-only): `severityColor(Severity.WARN)` + separater `▲`-Node (WCAG 1.4.1) — nie Fehler-Rot,
  nie `tertiary`-Grün.
- **Lokaler Fehler** (mismatch/tooShort/wrongPin/lockout): `colorScheme.error`-Ton, **nicht** `errorContainer` (retryable,
  nicht „broken").
- **Terminal** (Hub-Reject): `errorContainer` (die einzige „broken"-Fläche).
- **Affirmativ** (granted): neutral weiter, **kein** Erfolgs-Grün. Farbe nie alleiniger Träger (Label + Glyph + Tag).

## 7. Acceptance-Teeth (für spätere §-QA)
1. **Enroll gated:** kein PIN gesetzt, solange Bestätigung ≠ Setz **oder** Länge < Min; `enrollError.mismatch/tooShort`
   render Fehler-Ton + eigener Tag, Felder bleiben aktiv, **kein** stiller Commit.
2. **Mount:** `AUTHENTICATING` rendert den gebauten `OperatorAuthDialog` (nicht den INERT-Spinner); `DeviceNotEnrolled`
   routet in den Enroll-Step (kein WARN-Dead-End).
3. **1-UV-für-N:** genau **ein** PIN-Prompt für N jetzt geöffnete Tunnel; `uvCoverage` present ⇔ Fensterung greift, Copy
   sagt „jetzt geöffnete Hubs" (nicht „ganze Sitzung"); nach Fenster-Ablauf **neuer** Prompt (nie still).
4. **Biometrie-Enhancement:** `biometricOffer` present ⇔ verfügbar ∧ nicht aktiviert; Annehmen entfernt die PIN nie;
   `pathHint` nennt den echten Pfad; Biometrie-Fehl → sichtbarer PIN-Fallback.
5. **Fail-closed:** `Unavailable`/`keystoreUnavailable` blockt Connect mit ehrlichem Zustand, **nie** Gewährung;
   `granted` ohne Erfolgs-Grün.
6. **Keine Enumeration:** un-enrolled → Enroll (nie „falsche PIN"); Fehlversuch/Sperre verraten nur Count/Cooldown.
7. **Terminal nur Hub-Reject (H2):** `authRejected` terminal (errorContainer); jede lokale UV-Störung retryable.
8. **Tag present-iff real signal** (frozen Delta); Farbe nie alleiniger Träger (Glyph/Label/Tag), WARN-amber für
   Downgrades, nie Fehler-Rot/`tertiary`-Grün.

## 8. Nahtstellen (über den PO — konvergieren mit Devs Mechanismus-Skizze)
- **S-UV1 (Backend/Dev):** die **echte** `UserVerification`-Impl ersetzt den `deferredUserVerification`-Stub
  (`RemoteHubMode.jvm.kt`) — App-PIN gegen OS-Keystore, Platform-Authenticator via Fido2. Die UX zeichnet die Zustände;
  die Mechanik + Threat-Model-Zahlen (Min-Länge, N, Backoff) liefert die Skizze.
- **S-UV2 (Dev):** Mount-Wiring `popPrompt` (§4.2) + Enroll-Feldpaar (§4.1) + Coverage-Zeile (§4.3) + Biometrie-Angebot
  (§4.4) in `OperatorAuthDialog`/`HubConnectFlow`.
- **S-UV3 (Tester/DS, CYP-7):** die 4 net-new Tags (`remote-uv-flow-tags.md`) im Frozen-Contract abstimmen.
- **CYP-460-Konvergenz:** diese Delta **erweitert** den frozen CYP-460-Contract, ersetzt ihn nicht — bei Konflikt gilt
  CYP-460 für die gebauten Flächen, CYP-542 für die vier Nähte.

## 9. Self-Validation
- **Deliverable-Konsistenz:** 3 Dateien (`-ux-spec`/`-tags`/`-keys`), Tag-Count (3 Const + 1 Fn) und Key-Count
  (6 Realkeys + 1 a11y) über alle drei identisch referenziert.
- **Anti-Duplikat:** kein `remote_pop_*`/`OperatorAuthTags`-Wert wird umgeschrieben; nur additive Nähte.
- **Grounded @ `eb705236`:** jede Reuse-Behauptung gegen echten Code verifiziert (Code = Source of Truth); 0-Kollision
  grep-belegt.
- **Kein Bau, keine develop-Berührung;** rein Doku. Handover über den PO.
