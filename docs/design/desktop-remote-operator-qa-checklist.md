# CYP-460 §-QA-Checkliste — Desktop-Remote-Operator-Dialog (DevicePoP)

> Owner: UIUX-Designer · Story CYP-460 (Epic CYP-427) · Stand 2026-07-12 · **Vorbereitet** für den §-QA-Pass gegen Devs
> Dialog-Build (Slice 2 gegen die eingefrorenen CYP-460-Typen/Tags). Referenz: `desktop-remote-operator-ux-spec.md` +
> `-keys.md` / `-tags.md` / `-tokens.json`. **Ausführung PO-getriggert, sobald Devs Build branch-reif ist.**
> Prüf-Modus: Render/Visual + Read gegen den Build (read-only Worktree), kein Gate nötig; Befunde an PO priorisiert.

## A. testTag-Abdeckung (Area `remote`, Distanz zu `connector`)
Jeder Tag muss **vorhanden, korrekt benannt und korrekt konditional** sein:
- [ ] `remote.login.browserHandoff` (§4, während OIDC-System-Browser-Handoff) · `remote.login.browserReturn` (Loopback-Rückkehr)
- [ ] `remote.authStep.pathHint` (benennt aktiven Pfad PIN vs Biometrie) · `remote.authStep.pinField` · `remote.authStep.pinReveal`
- [ ] `remote.authStep.biometricPrompt` (Fido2) · `remote.authStep.enroll` (**Reuse CYP-429**) · `remote.authStep.enrollPinSet` (Raw-Enroll)
- [ ] `remote.authStep.attempts` (Versuchszähler) · `remote.authStep.lockedOut`
- [ ] `remote.authStep.error.<cause>` mit `<cause>` ∈ `pinWrong`/`biometricFailed`/`keystoreUnavailable`/`authRejected`/`needsEnroll`/`cancelled` — **jeder Cause als eigener Qualifier**
- [ ] **Reuse** korrekt: TOFU `remote.trust.{fingerprint,wordlist,hex,qr,pinPrompt,changedAlarm}`, `remote.connect.*`, `remote.relayDrop`, `remote.context.*` (identisch CYP-429, keine Varianten)
- [ ] **Nur** `authStep.enroll` ist reused; die 10 net-new Tags existieren distinkt; kein `connector.*`-Namespace-Leak.

## B. Fail-closed-Anker (die tragenden QA-Zähne)
- [ ] **`error.authRejected` ist TERMINAL** — kein Retry-Tag/kein stiller Re-Versuch; UI führt zu „neu anmelden" (`remote_pop_rejected`). (Grounding: `RemoteFailure.AuthRejected`, `RemoteHubSession` schließt Tunnel.)
- [ ] **Biometrie-Fehl → PIN-Fallback SICHTBAR** (`biometricFailed` → `pinField` erscheint, `remote_pop_biometric_failed`). Kein Sackgassen-Fehler.
- [ ] **`pathHint` nennt den ECHTEN Pfad** — Linux zeigt PIN, **nie** Biometrie-Optik ohne Biometrie (H1).
- [ ] **Recovery (Q6): KEIN Tag/Flow** — der Dialog zeichnet **keinen** Re-Enroll-/Recovery-Weg vor (offen/eskaliert). Absenz ist korrekt.
- [ ] `authStep.enroll`/`enrollPinSet` **nur** wenn kein Device-Key (needsEnroll), sonst absent.

## C. Honesty-Copy-Checks (Kern meiner Lane)
- [ ] **LOKAL ≠ HUB (H2, load-bearing):** `pinWrong`/`lockedOut` (lokaler Versuch VOR Assertion) sind **visuell + textuell distinkt** von `authRejected` (hub-terminal). Lokal = neutraler/Fehler-Hinweis **+ Versuchszähler** (`remote_pop_wrong_pin` „…noch %1$s Versuche"), Lockout = Wartezeit (`remote_pop_locked`); **Hub-Reject** = errorContainer-**terminal** „neu anmelden". **Nie** ein lokaler Tippfehler als „vom Hub abgelehnt" (und umgekehrt).
- [ ] **Session-only-Disclosure (H7/Q5):** solange `DEVICE_SECURE` named-not-built → `remote_pop_enroll_session_only` „nur für diese Sitzung" **präsent**; **KEINE** Hardware-/Geräte-Schlüsselbund-Zusage in der Enroll-Copy. (Wechsel auf Schlüsselbund-Copy erst wenn DEVICE_SECURE gebaut.)
- [ ] **E2E-Metadaten-Ehrlichkeit (H5/S-6):** `remote_e2e_indicator` sagt **nur** „Nutzlast E2E-verschlüsselt" — **kein** „vollständig unsichtbar/anonym" (Relay sieht Größen/Timing). Nicht über die Nutzlast hinaus versprechen.
- [ ] **TOFU (H4):** `Changed` = **harter Block** WARN-Amber (`remote_trust_changed_*`, `remote.trust.changedAlarm`), **nie still**, OOB-Re-Pin; `FirstUse` = OOB-Confirm (Wordlist primär + Hex + QR); „gepinnt"-Indikator neutral. Fingerprint **nie** als „automatisch sicher".
- [ ] **Native Login ehrlich (H3):** `remote_login_browser_handoff`/`_return` — ehrlicher System-Browser-Handoff, **kein** eingebetteter Webview; Email/Passwort nativ.
- [ ] **E2E ≠ Hub-Vertrauen:** `remote_e2e_indicator` (Transport) nie als Hub-Authentizität lesbar (getrennt von TOFU).

## D. i18n / a11y
- [ ] **14 Keys DE+EN-Parität** (`remote_pop_*` 12 + `remote_login_*` 2); `%1$s`-Arg-Counts identisch DE/EN (`path_hint`/`biometric_title`/`wrong_pin`/`locked` = je 1, Rest 0).
- [ ] **Kein Klartext-Leak:** PIN/Passphrase nie im a11y-Baum; Reveal nur der aktuellen Eingabe (`AuthPasswordField`-Muster).
- [ ] a11y-Labels für PoP-Zustände + Trust; Announce-Verhalten für Fehler ehrlich (nicht alarmistisch außer Trust-Änderung).

## E. Farb-/Ton-Disziplin (maritim + M3)
- [ ] **Nie `tertiary`/#40D6A0 als Status** (kein Erfolgs-Grün für granted/verbunden/gepinnt).
- [ ] **WARN-Amber NUR** Trust-Änderung (`EventVisuals`); **errorContainer NUR** harte Terminals (`authRejected`, Reconnect-fail); lokaler PIN-Fehler = neutraler/dezenter Fehler-Ton (kein Terminal-Rot).
- [ ] Farbe nie allein (1.4.1): jeder Zustand Form+Label+a11y; Dark/Light über `maritimeColorScheme`.

## F. Teeth-Mapping (Spec §14 → dieser Check)
1. Union ehrlich → A/C (pathHint) · 2. Fehler-Klassen getrennt → C (lokal≠hub) · 3. Native Login ehrlich → C/H3 ·
4. TOFU → C · 5. E2E nicht über-versprochen → C · 6. Relay-Drop → (via CYP-429-Reuse) in-flight ungewiss ·
7. Keystore-Disclosure → C (session-only) · 8. Reuse (kein Divergenz) → A · 9. Farbe nie allein/maritim → E.

## G. Bewusst DEFERRED (Absenz ist korrekt, NICHT als Befund werten)
- **Q2/Q3-Zahlen** (PIN-Länge/Lockout-N/Backoff) = Threat-Model-parametrisiert → der Dialog zeigt die **Zustände**
  (Zähler/Wartezeit); konkrete Zahlen prüfe ich erst, wenn das Threat-Model sie liefert. **Platzhalter-Zahl ≠ Befund.**
- **Q6 Recovery** = offen/eskaliert → **kein** Re-Enroll-Flow im Dialog erwartet; dessen Absenz ist **korrekt**.
- **`DEVICE_SECURE`-Copy** (Schlüsselbund-Zusage) erscheint erst nach dem Keystore-Bau (S-2); heute nur session-only.

---

*Vorbereitet zur sofortigen Ausführung, sobald Devs Dialog-Build (Slice 2 gg. CYP-460) branch-reif ist — PO-getriggert.
Read/Visual gegen den Build (read-only), Befunde priorisiert an den PO. Shift-left-Signal an Dev/Tester (CYP-7): baut
gegen A/B/C.*
