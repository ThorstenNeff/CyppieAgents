# Remote User-Verification (UV) Flow — Design-Pass (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 (rev. mit Reviewer-Crypto-Lens, PO
> `1526516045…`) · Status: **Design-Pass approved** (U1–U4, PO synthetisiert mit Devs Mechanismus-Skizze). Begleit-Specs:
> `remote-uv-flow-tags.md` (FROZEN Tag-Kontrakt), `remote-uv-flow-keys.md` (Copy-AC). **Kein Bau, keine develop-Berührung.**
> Gegroundet READ-ONLY gg. develop `eb705236`.

## 0. Kern-Ehrlichkeit (die tragende Grounding-Korrektur)
Remote braucht eine **reale** User-Verification, um in Prod zu laufen — heute ist sie ein **fail-closed-Stub**
(`RemoteHubMode.jvm.kt:184` `deferredUserVerification { UvOutcome.Unavailable }` ⇒ PoP wird nie signiert ⇒ Prod inert).
**Die UX dieser UV existiert aber schon:** CYP-460 (`desktop-remote-operator-*`, FROZEN) spezifiziert den Flow, und
`OperatorAuthDialog` + `OperatorAuthTags` (`remote.authStep.*`) + `remote_pop_*` sind **gebaut und eingefroren**.
**CYP-542 erfindet daher keine zweite UV-Fläche** — es schließt die **vier As-built-Lücken** als dünnen Delta.

| # | Naht | As-built heute | CYP-542 schließt |
|---|---|---|---|
| **U1** | **Enroll = wählen + bestätigen (+ Strength + starker Diceware-Default)** | `OperatorAuthStep.Enroll` rendert nur Disclosure-Text — **kein** Feldpaar, **kein** Strength-Meter, **kein** generierter One-Click-Diceware-Default (CYP-460 §5.2 + HG2). | §4.1: Setz- + Bestätigungs-Feld + **capability-conditional Credential** (Passphrase/PIN) + **generierter Diceware-One-Click-Default (HG2)** + Strength-Meter (type-your-own) + Gate. |
| **U2** | **Prompt gemountet** | `RemoteConnectingView(popPrompt = null)` (`HubConnectSelection.kt:258/291-294`) → `AUTHENTICATING` zeigt den INERT-Spinner. | §4.2: `popPrompt` mountet den gebauten Dialog; `DeviceNotEnrolled` routet in den Enroll-Step. |
| **U3** | **1-UV-für-N ehrlich** | Mechanik da (`CachingUserVerification`, `OPERATOR_UV_REUSE_WINDOW_MS`), UI **stumm**. | §4.3: neutraler Coverage-Hinweis, ehrlich begrenzt. |
| **U4** | **Biometrie-Angebot** | Biometrie-*Prompt* gebaut; **kein** Opt-in-*Angebot*. | §4.4: „Touch-ID einrichten?"-Angebot, Credential bleibt Rückfall. |

## 0.1 ★ Crypto-Lens-Angleichung (PO/Reviewer) — capability-conditional Credential
Ein **kurzer 4–6-Digit-PIN ist offline brute-forcebar**, selbst mit starker Krypto — er ist **nur** sicher, wenn ein
hardware-backed Enclave den Angriff rate-limitet. Daraus die **verbindliche Credential-Staffelung** (das ist eine
Disclosure-Honesty-Regel, keine Kosmetik):

| Umgebung | Baseline-Credential | Warum |
|---|---|---|
| **no-hardware** (kein rate-limitender Enclave, z. B. Linux) | **App-Passphrase ≥64 bit** (6-Wort-diceware / 12+ Zeichen) **+ Strength-Meter** | ein kurzer PIN wäre offline knackbar. |
| **hardware-backed** (Touch-ID / Windows Hello präsent) | **Kurz-PIN** erlaubt (Enclave rate-limitet) — **oder** direkt der Platform-Authenticator (§4.4). | Hardware schützt das kurze Secret. |

**Regel (HG):** die UI **bietet nie** einen kurzen, offline-brute-forcebaren PIN an, wo keine Hardware ihn schützt — kein
falsches Sicherheitsgefühl. Die Bau-Copy heißt **„App-Passphrase"** (no-hardware) vs **„App-PIN"** (hardware-backed);
`pathHint` benennt immer den echten aktiven Pfad.

**Regel (HG2, Devs Slice-3-Flag):** auf dem Passphrase-Pfad ist der **generierte 6-Wort-Diceware-Default** der prominente,
empfohlene **One-Click-Default** (by-construction stark) — nicht bloß ein Hinweis. type-your-own bleibt möglich, ist aber
**sekundär** (Strength-Meter + ≥64-bit-Floor-Gate), weil ein struktureller Meter allein eine schwache Common-Phrase
durchlassen kann. Der generierte Credential wird **angezeigt + notierbar** (nie masked-at-generation). zxcvbn-Meter-Härtung
fürs type-your-own = **separater Follow-up (PO-owned)**.

---

## 1. Scope
**In:** die vier Nähte als Erweiterung des frozen CYP-460-Dialogs; die capability-conditional Credential-Staffelung (§0.1);
Security-sichtbare Fehlversuch-/Sperr-/Fail-closed-Zustände; maritim + M3; frozen Tag-Delta + Copy-Delta (de+en) + a11y.
**Out (bewusst):** die Auth-/Enroll-*Mechanik* selbst und die Capability-Detektion (Dev/Backend — PO synthetisiert);
Threat-Model-*Zahlen* (Passphrase-Entropie-Schwelle, PIN-Min-Länge, N, Backoff — die UX zeichnet die **Zustände**, die
Zahlen kommen vom Threat-Model, CYP-460 §5.4); Recovery/Re-Enroll (Q6 eskaliert, unverändert); TOFU (§6 CYP-460,
unverändert); Retext frozener „PIN"-Zähler-Copy für den Passphrase-Pfad (Follow-up, s. keys.md-Hinweis).

## 2. Reuse/Grounding-Karte (gegen echten Code @ `eb705236`)
- **Dialog-Content:** `OperatorAuthDialog(step, error, pin, …)` — Pin/Biometric/Enroll-Steps, H2-Terminal-vs-lokal-Split,
  `WrongPin.attemptsLeft`-Zähler, Session-only-WARN-amber. **Wiederverwenden, nicht neu bauen.**
- **Feld:** `AuthPasswordField` (maskiert, Text-Label-Reveal, kein a11y-Leak) via `PinField` — **credential-agnostisch**
  (hält PIN *oder* Passphrase).
- **Zustandsmodell:** `RemoteConnState.AUTHENTICATING` (der Slot), `OperatorAuthError` (Taxonomie), `UvOutcome`
  (`Verified`/`Denied(reason)`/`Unavailable`), `CachingUserVerification` (1-UV-für-N), `OperatorAuthStep.Enroll(sessionOnly)`.
- **Farb-/Ton-Helfer:** `severityColor(Severity.WARN)` + `Severity.glyph()` (`▲`/`ⓘ`) aus `EventVisuals.kt`.
- **Tags/Copy:** frozen `OperatorAuthTags` + `remote_pop_*` (CYP-460). Delta in den Begleit-Specs.

---

## 3. Credential-Staffelung (capability-conditional)
Drei Stufen, **eine Capability-Signal** wählt (dasselbe Signal, das auch U4 gated — Platform-Authenticator vorhanden?):
1. **no-hardware:** App-**Passphrase** ≥64 bit (Strength-Meter + Diceware-Vorschlag) — der cross-platform Boden.
2. **hardware-backed:** Kurz-**PIN** (Enclave rate-limitet) als schneller Default.
3. **Enhancement (wo präsent):** **Platform-Authenticator** (Biometrie, §4.4) über dem Credential — nie als stiller Ersatz.
`pathHint` (`remote_pop_path_hint`) benennt **immer den echten aktiven Pfad** — nie Biometrie-Optik ohne Biometrie, nie
„PIN" wo eine Passphrase nötig ist (gebaute H1 + HG).

## 4. Die vier Nähte

### 4.1 U1 — Enroll: Credential wählen **+ bestätigen** (+ Strength, capability-conditional)
Der `Enroll`-Step wird um das **Feldpaar** ergänzt (heute nur Disclosure-Text); der Credential-Typ ist capability-conditional:
- **no-hardware (Passphrase) — HG2: generierter Diceware-Default ist der Common-Path (Devs Slice-3-Flag):**
  1. **Empfohlener One-Click-Default (prominent):** `ENROLL_SUGGESTED` zeigt eine **generierte 6-Wort-Diceware-Passphrase**
     mit Label `remote_pop_enroll_suggested` („Empfohlen — 6 zufällige Wörter"). Akzeptanz per Klick
     (`remote_pop_enroll_suggest_use` „Diese Passphrase verwenden") = **by-construction stark** (~kein Bit-Literal in der
     Copy, Wortlisten-abhängig). **Regenerate** `ENROLL_SUGGEST` + `remote_pop_enroll_suggest` („Andere vorschlagen").
  2. **Notierbarkeit (Ehrlichkeit):** die Wörter sind **angezeigt + notierbar** (`remote_pop_enroll_suggested_save` „Notiere
     sie sicher — du brauchst sie bei jeder Anmeldung.") — **nie** ein masked-at-generation-Secret, das der Operator nicht
     sichern kann.
  3. **type-your-own (sekundär):** `ENROLL_TYPE_OWN` + `remote_pop_enroll_type_own` schaltet auf das **Setz-Feld** (reuse
     `PIN_FIELD` + `remote_pop_enroll_passphrase`) mit **Strength-Meter** `ENROLL_STRENGTH` (+ `remote_pop_enroll_strength_hint`,
     Level-Labels `remote_pop_strength_{weak,fair,strong}` — Text-Label, Farbe nie alleiniger Träger, WCAG 1.4.1).
     **Warum sekundär:** ein struktureller Meter allein kann ein schwaches type-your-own (Common-Phrase „correcthorse…")
     durchlassen — der generierte Default macht den Common-Path stark; type-your-own bleibt möglich, ist aber die Ausnahme.
  4. **Bestätigungs-Feld** — `ENROLL_PIN_CONFIRM` + `remote_pop_enroll_passphrase_confirm` (bei type-your-own; der
     One-Click-Default ist bereits bestätigt-durch-Anzeige).
  5. **Gate:** kein Credential gesetzt bis (Default akzeptiert) **oder** (type-your-own: Bestätigung == Setz **und**
     `meets() = Entropie ≥ ≥64-bit-Floor ∧ !blocklist`) → sonst `enrollError.mismatch` / `.tooWeak`
     (`remote_pop_enroll_too_weak`) / **`.blocklisted`** (`remote_pop_enroll_blocklisted`, PO-Ruling G1 — **distinkter** Cause
     der ehrlich das *warum* sagt: „zu verbreitet / auf Blocklist"; das Blocklist-Signal liefert B1 **jetzt** [`meets()`],
     unabhängig von CYP-544/zxcvbn). Enroll-Copy darf **spezifisch-ehrlich** sein (kein Enumeration-Oracle — der Nutzer setzt
     sein eigenes Secret; CYP-543-Neutralität betrifft nur den **Auth**-Pfad).
- **hardware-backed (Kurz-PIN):**
  1. **Setz-Feld** — reuse `remote_pop_enroll_pin` („App-PIN festlegen"), Tag `ENROLL_PIN_SET`.
  2. **Bestätigungs-Feld** — `ENROLL_PIN_CONFIRM` + `remote_pop_enroll_confirm` („App-PIN bestätigen").
  3. **Gate:** Bestätigung == Setz **und** Länge ≥ Min → sonst `enrollError.mismatch` / `enrollError.tooShort`.
- **Gemeinsam:** Gate-Fehler sind **retryable, Fehler-Ton (`colorScheme.error`, nicht `errorContainer`)**, Felder bleiben
  aktiv (analog gebautem lokalem `WrongPin`). **Nie stiller Commit** einer unbestätigten/schwachen Eingabe. Session-only-
  Disclosure unverändert (WARN-amber `remote_pop_enroll_session_only`, Q5/GE6) — kein Über-Versprechen eines Hardware-
  Schlüsselbunds.

### 4.2 U2 — Mount: der Prompt in `AUTHENTICATING` (+ Enroll-Routing)
- **Mount:** `HubConnectFlow` reicht `RemoteConnectingView` einen echten `popPrompt`-Slot (heute `null`) → im
  `AUTHENTICATING`-Zustand rendert der **gebaute `OperatorAuthDialog`** statt des INERT-Spinners
  (`HubConnectSelection.kt:291-294`). Der Slot ist **schon vorgesehen** (Signatur `:258`) — reine Verdrahtung.
- **Enroll-Routing:** `DeviceNotEnrolled` / `OperatorAuthError.NeedsEnroll` mountet den **Enroll-Step (4.1)** inline statt
  in der WARN-Retry-Sackgasse. `remote_connect_device_not_enrolled` bleibt der ehrliche Einstieg (Enumeration-Guard:
  „nicht eingerichtet", **nie** „falsche PIN").
- **Ergebnis-Taxonomie** unverändert (CYP-460 §5.3): `verifying`→neutraler Spinner; `granted`→weiter zur Hub-Liste
  **ohne Erfolgs-Grün**; lokale Fehler retryable; `authRejected` terminal.
- **Enroll→Auth-Weiterlauf (PO-Ruling G2, Kriterium „kein Credential gesetzt, Nutzer sitzt"):** nach Enroll-Erfolg treibt
  der Flow **selbsttätig** weiter — kein toter Zwischenzustand. **Kohärenz-Bonus (Dev/Backend, S-UV1):** die eben
  eingegebene Enroll-Passphrase ist **in-hand** (hat gerade den Vault gesealt) und dient direkt als **erste UV** — sie
  nutzt das `DecryptedKeyHold`-≤120s-Window, **kein sofortiger zweiter Prompt**. Sequenz: **Enroll-Erfolg → seal →
  AUTHENTICATING mit derselben in-hand-Passphrase → CONNECTED**. Das ist zugleich der Startpunkt des 1-UV-für-N-Fensters
  (§4.3) — ehrlich: **eine** Ceremony deckt Enroll **und** die jetzt geöffneten Tunnel. (`DecryptedKeyHold` = B1-Mechanik,
  noch nicht im Code @ `eb705236` — Dev/Backend-owned; die UX zeichnet nur die Zustände.)

### 4.3 U3 — 1-UV-für-N: eine Bestätigung, N Tunnel (ehrlich)
`CachingUserVerification` sorgt dafür, dass **eine** Bestätigung die **N** Tunnel autorisiert, die der Operator jetzt öffnet
(bounded window `OPERATOR_UV_REUSE_WINDOW_MS`). Sichtbar + ehrlich gemacht:
- **Hinweis** `UV_COVERAGE` + `remote_pop_uv_coverage` — **neutral/advisory** (`onSurfaceVariant`, optional `ⓘ`-Node, **kein**
  Erfolgs-Grün, es ist ein Fakt kein „Erfolg").
- **Present ⇔** die Fensterung greift real (N>1 bzw. cachingUv aktiv) — sonst absent (kein Phantom-Versprechen bei Einzel-Hub).
- **Ehrlich begrenzt:** „die Hubs, die du **jetzt** öffnest" — **nicht** „diese Sitzung für immer". Das Fenster ist begrenzt;
  **nach Ablauf ein neuer Prompt** (nie stille Re-Auth, nie Fake-Coverage). H4-konsistent.

### 4.4 U4 — Platform-Authenticator als Enhancement (Opt-in)
Wo verfügbar (macOS Touch-ID / Windows Hello): **Enhancement über dem Credential**, nie stiller Ersatz:
- **Angebot** `BIOMETRIC_OFFER` + `remote_pop_biometric_offer` (`%1$s` = Authenticator-Name). Present ⇔ verfügbar **und**
  noch nicht aktiviert. Enable/Überspringen = Dialog-Chrome (keine Button-Keys frozen, CYP-460-Konvention).
- **Ehrlichkeit** `remote_pop_biometric_offer_fallback`: „Deine App-PIN/Passphrase bleibt als Rückfall." — Annehmen
  entfernt das Credential **nie**.
- **Nach Annahme:** reale Aufforderung = **gebauter** `BIOMETRIC_PROMPT` + `remote_pop_biometric_title`; **Biometrie-Fehl →
  sichtbarer Credential-Fallback** (`remote_pop_biometric_failed`, gebaute H1).

## 5. Security-sichtbare Zustände (rate-limit-aware, keine Enumeration, fail-closed)
- **Credential-Stärke (HG):** siehe §0.1/§3 — no-hardware ⇒ Passphrase + Strength-Meter; Kurz-PIN nur hardware-backed.
- **Fehlversuch:** `remote_pop_wrong_pin` + `ATTEMPTS` — nur der **Rest-Zähler**, nie ob ein Hub/Konto „existiert".
- **Sperre (Rate-Limit):** `remote_pop_locked` + `LOCKED_OUT` — temporäre Sperre + **ehrlicher Cooldown** (`%1$s`),
  **≠** Hub-Reject. Backoff/N = Threat-Model.
- **Keine Enumeration:** Credential trägt keinen Benutzernamen → inhärent enumeration-frei; un-enrolled routet in **Enroll**
  (nie „falsche PIN" für ein Gerät ohne Credential); Feedback verrät nur Count/Cooldown.
- **Fail-closed, kein Fake-Erfolg:** `UvOutcome.Unavailable` (keine UV-Mechanik / Keystore weg) → **blockt Connect** mit
  ehrlichem Zustand (`remote_pop_keystore_unavailable`), **nie** stille Gewährung. `granted` zeigt **kein** Erfolgs-Grün.
- **Terminal nur bei Hub-Reject (H2):** `remote_pop_rejected` + `error(authRejected)` = errorContainer, neu anmelden,
  kein stiller Retry; **jede** lokale Störung retryable.
- **EINE Retry-Fläche (PO-Ruling G4):** der **Inline-Dialog** besitzt den Retry-/Lockout-Lifecycle
  (`OperatorAuthError.WrongPin` + `ATTEMPTS` + `LOCKED_OUT`); er bubbelt zum connect-level `OperatorUvFailed`→`LOST` **nur**
  bei **Nutzer-Abbruch** oder **erschöpftem Lockout**. Nie zwei konkurrierende Retry-Flächen (kein Doppel-Ehrlichkeits-
  Risiko). Wird §-QA-Kriterium; Dev verdrahtet es so.

## 6. Maritim + M3
- **Neutrale Fakten** (Pfad-Hinweis, Coverage, Setz/Bestätigen-Felder): `onSurfaceVariant`/`onSurface`, kein Statusfarbton.
- **Strength-Meter:** Level trägt **Text-Label** (`_weak`/`_fair`/`_strong`) — Farbe nie alleiniger Träger (WCAG 1.4.1).
- **WARN-Downgrade** (Session-only): `severityColor(Severity.WARN)` + separater `▲`-Node — nie Fehler-Rot, nie
  `tertiary`-Grün.
- **Lokaler Fehler** (mismatch/tooShort/tooWeak/wrongPin/lockout): `colorScheme.error`-Ton, **nicht** `errorContainer`
  (retryable, nicht „broken").
- **Terminal** (Hub-Reject): `errorContainer` (die einzige „broken"-Fläche).
- **Affirmativ** (granted): neutral weiter, **kein** Erfolgs-Grün. Farbe nie alleiniger Träger (Label + Glyph + Tag).

## 7. Acceptance-Teeth (für spätere §-QA)
1. **Credential-Staffelung (HG):** no-hardware ⇒ Passphrase + `tooWeak`-Gate; **kein** kurzer PIN angeboten ohne Hardware.
   hardware-backed ⇒ Kurz-PIN erlaubt. `pathHint` benennt den echten Pfad.
1b. **Starker Diceware-Default (HG2):** der Passphrase-Enroll zeigt die generierte 6-Wort-Diceware **prominent als
   One-Click-Default** (`enrollSuggested` present, `suggest_use` akzeptiert, `enrollSuggest` regeneriert); type-your-own ist
   **sekundär** (`enrollTypeOwn`, Meter + Floor-Gate); der generierte Credential ist **angezeigt + notierbar**
   (`_suggested_save`), nie masked-at-generation; **keine Bit-Zahl** im Copy-Literal.
2. **Enroll gated:** kein Credential gesetzt bei Mismatch / zu-kurz / zu-schwach / **blocklisted**; `enrollError.*` render
   Fehler-Ton + eigener Tag (G1: `blocklisted` **distinkt** von `tooWeak`, ehrliches *warum*), Felder aktiv, **kein**
   stiller Commit.
2c. **Enroll→Auth-Weiterlauf (G2):** nach Enroll-Erfolg treibt der Flow selbsttätig in AUTHENTICATING→CONNECTED (kein „gesetzt,
   Nutzer sitzt"); die in-hand-Passphrase dient als erste UV (`DecryptedKeyHold`-Window) — **kein** sofortiger zweiter Prompt.
2d. **EINE Retry-Fläche (G4):** Inline-Dialog besitzt Retry/Lockout; bubbelt zu `OperatorUvFailed`→LOST nur bei Abbruch /
   Lockout-erschöpft. Nie zwei konkurrierende Retry-Flächen.
3. **Mount:** `AUTHENTICATING` rendert den gebauten `OperatorAuthDialog` (nicht den INERT-Spinner); `DeviceNotEnrolled`
   routet in den Enroll-Step (kein WARN-Dead-End).
4. **1-UV-für-N:** genau **ein** Prompt für N jetzt geöffnete Tunnel; `uvCoverage` present ⇔ Fensterung greift, Copy sagt
   „jetzt geöffnete Hubs" (nicht „ganze Sitzung"); nach Ablauf **neuer** Prompt (nie still).
5. **Biometrie-Enhancement:** `biometricOffer` present ⇔ verfügbar ∧ nicht aktiviert; Annehmen entfernt das Credential nie;
   Biometrie-Fehl → sichtbarer Credential-Fallback.
6. **Fail-closed:** `Unavailable`/`keystoreUnavailable` blockt Connect mit ehrlichem Zustand, **nie** Gewährung; `granted`
   ohne Erfolgs-Grün.
7. **Keine Enumeration:** un-enrolled → Enroll (nie „falsche PIN"); Fehlversuch/Sperre verraten nur Count/Cooldown.
8. **Terminal nur Hub-Reject (H2):** `authRejected` terminal (errorContainer); jede lokale UV-Störung retryable.
9. **Tag present-iff real signal** (frozen Delta); Strength-Level trägt Text-Label; Farbe nie alleiniger Träger; WARN-amber
   für Downgrades, nie Fehler-Rot/`tertiary`-Grün.

## 8. Nahtstellen (über den PO — konvergieren mit Devs Mechanismus-Skizze)
- **S-UV1 (Backend/Dev):** die **echte** `UserVerification`-Impl ersetzt den `deferredUserVerification`-Stub
  (`RemoteHubMode.jvm.kt`) — Passphrase/PIN gegen OS-Keystore, Platform-Authenticator via Fido2. **Sie liefert auch das
  Capability-Signal** (hardware-backed?), das §3/§4.1/§4.4 gated, sowie die Threat-Model-Zahlen (Entropie-Schwelle,
  PIN-Min, N, Backoff). Die UX zeichnet die Zustände.
- **S-UV2 (Dev):** Mount-Wiring `popPrompt` (§4.2) + capability-conditionales Enroll-Feldpaar + Strength-Meter/Diceware
  (§4.1) + Coverage-Zeile (§4.3) + Biometrie-Angebot (§4.4) in `OperatorAuthDialog`/`HubConnectFlow`.
- **S-UV3 (Tester/DS, CYP-7):** die net-new Tags (`remote-uv-flow-tags.md`) im Frozen-Contract abstimmen.
- **CYP-460-Konvergenz:** diese Delta **erweitert** den frozen CYP-460-Contract, ersetzt ihn nicht — bei Konflikt gilt
  CYP-460 für die gebauten Flächen, CYP-542 für die vier Nähte.
- **G3 (PO-Ruling) — DeviceNotEnrolled-Loop = harte AC der Enroll+Wiring-Slice:** meine §4.2-Route (DeviceNotEnrolled→
  Enroll-Step) ist die Lösung; der PO macht sie zur **harten Akzeptanzbedingung** der Slice, die Dev jetzt baut — **kein**
  DeviceNotEnrolled→Retry-Sackgasse darf nach der Slice übrig bleiben. Relay an Dev läuft (PO).
- **Follow-up 1 = CYP-543 (PO-owned):** `remote_pop_wrong_pin`/`_locked` frozen „PIN"-Copy am **Auth**-Pfad → credential-
  neutrale Zähler-Copy. Betrifft **nicht** Enroll (dort ist spezifisch-ehrliche Copy korrekt, kein Enumeration-Oracle).
- **Follow-up 2 = CYP-544 (PO-owned):** **zxcvbn-Meter-Härtung** fürs type-your-own (graduelle Stärke, dictionary/pattern-
  aware). **Kein** Blocker für G1: das Blocklist-Boolean (`meets()`) existiert in B1 **jetzt** → `enrollError.blocklisted`
  ist unabhängig lieferbar.

## 9. Self-Validation
- **Deliverable-Konsistenz:** 4 Dateien (`-ux-spec`/`-tags`/`-keys`/`-qa-checklist`); Tag-Count (**7 Const + 1 Fn**, Fn-Causes
  `mismatch`/`tooShort`/`tooWeak`/`blocklisted`) und Key-Count (**21 Realkeys + 1 a11y**) über alle identisch referenziert.
  PO-Rulings G1–G5 (`1526532076…`/`1526532077…`) eingefaltet.
- **Anti-Duplikat:** kein `remote_pop_*`/`OperatorAuthTags`-Wert wird umgeschrieben; nur additive Nähte.
- **Grounded @ `eb705236`:** jede Reuse-Behauptung gegen echten Code verifiziert (Code = Source of Truth); 0-Kollision
  grep-belegt.
- **Kein Bau, keine develop-Berührung;** rein Doku. Handover über den PO.
