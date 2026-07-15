# CYP-584 — Enroll-UX-Legibilität: Dead-Wait & Fehler-Recovery (Design-Spec)

> Owner: UIUX2-Designer (Team-2) · Ticket **CYP-584** (verlinkt CYP-525) · Pairing: Team-1-UIUX (über PO/PO1) · Stand 2026-07-15
> Status: **DESIGN-FIRST — RATIFY vor Bau.** Post-Deadline-Politur (NICHT Noon-kritisch). **Design/Copy/Keys/Tags/QA, kein Code.**
> **Grounding-Anker (READ-ONLY, file:line):** develop `6ee3fa94`. Alle Objekt-Zitate an diesem Stand.
> **Scope = Enroll (`SetPassphraseStep`/`HubConnectViewModel`/`OperatorEnrollController`) + 1 connect-flow-Fold** (§3b F3: `DeviceCustodyCorrupt`-Recovery-Affordanz in `HubConnectSelection`, PO1-Call 2026-07-15 — gleiche lead-to-recovery-Semantik + geteilter Dev-Check mit F2). Die OIDC-Fläche ist Team-1s (gebaut: `5a721953`, Spec `56024536`); **die OIDC-Block-a11y (Team-1 §7 → UIUX2) ist ERLEDIGT in `5a721953`** (PO1-bestätigt 2026-07-15, Option a — Team-1 hat AnnouncingHint/INFO/Live-Region/Fallback-URL-Clipboard selbst verdrahtet) → **kein separater OIDC-a11y-Beitrag hier.**
> **Werkzeug-Grenze:** kein JS-Browser → Runtime = guided-human; Verhaltens-Invarianten headless via `runComposeUiTest`; Kontrast by-measurement.

---

## 0. Honesty-Invarianten (Prüfmaßstab — load-bearing)
1. **Kein toter Warte-Zustand:** jeder Pending-State hat einen Ausweg (Cancel) **oder** Timeout+Retry. Nie ein Spinner, aus dem man nicht herauskommt.
2. **Kein falscher Fortschritt:** ein Spinner behauptet „arbeitet gerade". Hängt der Vorgang, **lügt** der ewige Spinner. Ein advisory-Timeout macht den Hänger ehrlich.
3. **Spezifisch vor generisch:** ein Fehler nennt die Ursache **und** den zu ihr passenden nächsten Schritt.
4. **Ton folgt der Wahrheit** (Team-1s OIDC-Doktrin, jetzt geteilt): advisory/langsam = **INFO/neutral** · echter Fehler = **ERROR** · **nie** Timeout als Fehler-rot; Farbe nie alleiniger Träger.
5. **a11y-Parität:** Progress/advisory = **Polite** · Fehler/Interrupt = **Assertive** (`AnnouncingHint`/`liveRegion`).

---

## 1. Reuse-Grounding — kein neues Vokabular
- **Team-1s OIDC-Legibilität ist die geteilte Sprache** (`56024536` + Build `5a721953`): `HintTone.INFO` (advisory/progress/timeout) · `HintTone.ERROR` (echter Fehler) · `AnnouncingHint(text,tone,tag,mode)` · das `GithubUiState.TimedOut(url)`+Retry-Muster (`AuthViewModel:325 delay(handoffTimeoutMs)`). **Ich spiegle das für Enroll — kein divergenter One-off.**
- **Enroll ist schon ~an Parität** (meine CYP-542-liveRegions @ `6ee3fa94`): `ENROLLING`=Polite announced (`RemoteOperatorAuthSteps:227-236`) · Fehler/Mismatch=Assertive+error-Ton (`:245-249`, `EnrollOutcomeLine:349-364`) · damped-Strength=Polite (`:308-313`) · Non-Colour-Carrier (Glyph/self-describing). **Der CYP-584-Delta ist klein und gezielt** (F1+F2 unten), keine Neuerfindung.
- **Ton-Entscheidung (Punkt B) — PO1-BESTÄTIGT (2026-07-15):** **KEIN neuer `HintTone.PROGRESS`** — OIDC+Enroll auf `INFO` (advisory/progress) + `ERROR` (echter Fehler) alignen. Meine frühere PROGRESS-Empfehlung war vor Team-1s Build; die Evidenz (Team-1 baute OIDC-Progress mit INFO + verwarf einen neuen Ton bewusst) kippte sie, PO1s frühere PROGRESS-Approval wurde entsprechend revidiert. Enroll nutzt INFO/ERROR heute schon → **kein Token-Bau in CYP-584.**

---

## 2. F1 — Dead-Wait auf `ENROLLING` (der schärfste Fund)

**Objekt (`RemoteOperatorAuthSteps.kt:227-259`):** während `ENROLLING` rendert der Step **nur** einen `CircularProgressIndicator` (Polite announced). Der Submit-Button **und** der Cancel-`OutlinedButton` liegen im **`else`-Zweig** → **während `ENROLLING` gibt es KEINEN Cancel, KEINEN Retry, KEINEN Ausweg.** Dazu (`HubConnectViewModel.kt:321-337`): `setEnrollPassphrase` startet `runScope.launch { controller.enroll(passphrase) }` **ohne** `withTimeout`/Watchdog; `EnrollPhase{ENTERING,ENROLLING,ERROR}` hat **kein TIMEDOUT** (anders als OIDC).

**Der Trap:** hängt `controller.enroll()` (oder der Auto-Reconnect-on-`Enrolled` `connectRemoteInternal`, ein Netz-Hop), **spinnt der Indicator für immer** — und liest als „arbeitet" (Invariante 2 verletzt), ohne dass der Nutzer entkommt (Invariante 1 verletzt).

> **⚠ Ehrliche Scope-Grenze (nicht asserted):** ob `controller.enroll()` **wirklich** hängen kann, ist ein **Dev/Behavior-Check** (lokaler Seal = bounded/gering; I/O/Migration/Anchor-Check/Auto-Reconnect = Netz-Hop = kann hängen). Ich behaupte **keinen** bewiesenen Infinite-Hang. **ABER der UX-Trap „kein Ausweg während ENROLLING" ist real, unabhängig von der Hang-Frage** — und ist der Design-Gap.

**Design (mirror OIDC `TimedOut`):**
1. **Cancel während `ENROLLING` (Minimum, immer):** der Cancel-`OutlinedButton` bleibt **auch im `if(enrolling)`-Zweig** sichtbar (nur der Submit wird durch den Spinner ersetzt). Reuse `cancelEnroll` (`:337`). → Der Nutzer kommt immer raus.
2. **Timeout→advisory (falls Dev/Behavior-Check „kann hängen" bestätigt):** neuer `EnrollPhase.TIMED_OUT` (oder Flag) via `delay(enrollTimeoutMs)`-Watchdog ab `ENROLLING`, X≈**20 s** (tunbar; Enroll-Seal ist schneller als ein Browser-Login → kürzer als OIDC-30s). Bei Timeout:
   - Advisory-Zeile `remote_pop_enroll_slow` „Das dauert länger als erwartet." — **INFO/neutral** `onSurfaceVariant`, **Polite** announced. **NICHT** error-rot (nichts ist kaputt, es ist nur langsam — Invariante 4).
   - **Explizites Retry** `remote_pop_enroll_retry` „Erneut versuchen" (Reuse `setEnrollPassphrase` mit derselben Passphrase) **+ Cancel bleibt.**
   - Der Spinner darf bleiben (der Vorgang läuft evtl. noch) — aber nicht mehr **allein**; die advisory+Ausweg-Affordances entkleiden ihn der Optimismus-Lüge.
3. **Seam (→ Dev/VM):** (a) Cancel im `enrolling`-Zweig rendern; (b) optional Watchdog + `TIMED_OUT`; (c) Retry = `setEnrollPassphrase` re-invoke. **(a) ist der ehrliche Minimal-Fix ohne Behavior-Beweis; (b)/(c) folgen, wenn der Hang bestätigt ist.**

---

## 3. F2 — Fehler-Recovery-Spezifität (per-Outcome-Affordanz)

**Objekt:** `EnrollOutcome{Enrolled,TooWeak,Blocklisted,MigrationFailed,AlreadyEnrolled}` (`OperatorEnrollController.kt:31-38`) = 5 typisierte Ursachen (Split-Error **schon da**). Bei Fehler → `SetPassphrase(hub, ERROR, outcome)` (`HubConnectViewModel:334`); der Render zeigt `EnrollOutcomeLine(outcome)` (Assertive) + das **re-enterable Feld + Submit + Cancel** (`else`-Zweig). ⟹ **die Recovery ist heute für ALLE 5 dieselbe: „tippe neu + submit".**

**Das ist für 2 der 5 der falsche mentale Weg:**

| Outcome | Klasse | Ehrlicher nächster Schritt | Recovery heute | Delta |
|---|---|---|---|---|
| `TooWeak` | passphrase-quality | stärkere Passphrase tippen | implizites Re-Entry ✓ | **behalten** — KEIN „Retry"-Button (retry-womit? die Eingabe MUSS sich ändern) |
| `Blocklisted` | passphrase-quality | andere Passphrase tippen | implizites Re-Entry ✓ | **behalten** (wie oben) |
| `MigrationFailed` | strukturell/transient | **erneut versuchen** (gleiche Eingabe kann klappen) | nur Re-Entry (verwirrend — „warum neu tippen?") | **expliziter Retry** `remote_pop_enroll_retry` (Reuse F1-Retry), Copy nennt „vorübergehend" |
| `AlreadyEnrolled` | strukturell/terminal | **zum Entsperren/Verbinden**, NICHT neu-enrollen | Re-Entry lädt zu falscher Handlung ein | **Route-to-Unlock-Affordanz** (kein Passphrase-Feld-Fokus); Copy „Dieses Gerät ist schon eingerichtet — entsperren statt einrichten." |

**Copy (`EnrollOutcomeLine`, je Outcome spezifisch, ERROR-Ton außer wo neutral):** die vorhandene typisierte Zeile bleibt; **ergänzt wird die passende Affordanz je Klasse** — nicht ein blanket „Retry" über alle. Honesty: die Copy unterstellt keine falsche Ursache und leitet zum **richtigen** nächsten Schritt.

> **Seam (→ Dev/VM):** `AlreadyEnrolled` braucht eine Route-to-Unlock-Aktion (existiert der Unlock-Pfad im VM? `connectRemote`/`backToHubList`+Unlock — Dev bestätigt die Verdrahtung). `MigrationFailed`-Retry = `setEnrollPassphrase` re-invoke.

---

## 3b. F3 — DeviceCustodyCorrupt-Recovery-Affordanz (connect-flow, PO1-Fold aus CYP-583)

> **Fold (PO1-Call 2026-07-15):** die guided Recovery-Affordanz zum CYP-583-`DeviceCustodyCorrupt`-Fehler fließt in CYP-584 ein — **andere Fläche** (connect-flow, nicht enroll), aber **dieselbe „lead-to-recovery"-Semantik + derselbe Dev-Check** wie F2s `AlreadyEnrolled`→Route-to-Unlock. CYP-583 selbst sagt es an: *„The full dedicated recovery-ack flow is the follow-on (CYP-584); this is the honest distinct signal."*

**Objekt (Branch `feature/CYP-583-devicekey-fail-closed`, `HubConnectSelection.kt:400-415`):** `RemoteFailure.DeviceCustodyCorrupt` rendert heute die a11y + Copy `remote_connect_device_custody_corrupt` (ERROR-Ton, eigener Node `error("deviceCustodyCorrupt")`, **nicht** WARN-amber, **nicht** generic-LOST — CYP-583s ehrliches distinktes Signal). **Der Ausgang fehlt:** eine `connectRemote`-Retry (`RemoteConnectTags.RETRY`, im Fläche vorhanden) **repariert einen korrupten lokalen Schlüssel NICHT** → wäre der falsche/irreführende nächste Schritt (dieselbe „blanket-Retry-ist-falsch"-Klasse wie F2). Der **richtige** Weg = Recovery-Code-Eingang.

**Design (lead-to-recovery, reuse):**
- **Copy bleibt** CYP-583s `remote_connect_device_custody_corrupt` **mit meinem ratifizierten Terminologie-Swap** („Wiederherstellungs-Code", nicht „Backup-Code"; DE „…richte das Gerät mit einem Wiederherstellungs-Code neu ein."). ERROR-Ton (schon korrekt).
- **★ Affordanz (der Gap):** ein **Lead-to-Recovery-Button** „Mit Wiederherstellungs-Code neu einrichten" → routet zum **bestehenden** `RecoveryInputContent` (`RemoteRecoveryTags.START`, `remote_recovery_start_title/_body/_code_label`). **Reuse** des `remote_recovery_*`-Vokabulars + des vorhandenen Recovery-Screens — **kein** neuer Recovery-Flow. **KEIN** irreführender „Verbindung erneut versuchen" für diesen Fehler.
- **Honesty:** korrupt = ERROR (real, terminal-bis-Recovery); nenne die Ursache **und** führe zum **echten** Fix (Recovery-Eingang), nicht zu einer Retry, die nichts repariert. Deckungsgleich mit F1/F2-Doktrin.

> **Seam (→ Dev/VM, GETEILT mit F2):** existiert im connect-VM ein Pfad `DeviceCustodyCorrupt → RecoveryInputContent` (Start-Recovery)? **Derselbe „existiert der Recovery-Pfad im VM?"-Dev-Check wie F2** `AlreadyEnrolled`→Unlock. Existiert er → Button verdrahten; wenn nicht → Dev baut die VM-Transition (z. B. `startRecovery()`).

---

## 4. a11y — Audit (schon da) + die 2 neuen Nodes
- **Schon korrekt (verifizieren, nicht neu bauen):** `ENROLLING`=Polite (`:232`) · `mismatch`=Assertive (`:249`) · `EnrollOutcomeLine`=Assertive (`:353/364`) · Strength=Polite (`:313`) · Non-Colour (Glyph/self-describing).
- **Neu (F1/F2):** die **advisory-Timeout-Zeile** = **Polite** (Status, kein Interrupt) · der **Retry/Route-Button** = fokussierbar mit Label (Farbe nie alleiniger Träger) · **Fokus-Management:** bei `ENROLLING→TIMED_OUT`/`ERROR` Fokus auf die Advisory/Recovery-Affordanz führen (der Nutzer landet am Ausweg, nicht im Nirwana).
- **F3 (connect-flow):** der `DeviceCustodyCorrupt`-Fehler ist **Assertive** (schon so in CYP-583) · der **Lead-to-Recovery-Button** fokussierbar mit Label · Fokus landet nach dem Fehler auf der Recovery-Affordanz (nicht auf der irreführenden connect-Retry).

## 5. Keys/Tags-Delta — **shared → Sync-Flag, landen mit der Impl**
Namespace `remote_pop_enroll_*` (Keys) / `remote.*`/`OperatorAuthTags` (Tags), spiegelt Bestand + Team-1s `auth.github.*`-Muster:
```
Keys (DE + EN-Parität):
  remote_pop_enroll_slow   = "Das dauert länger als erwartet."   / "This is taking longer than expected."
  remote_pop_enroll_retry  = "Erneut versuchen"                  / "Try again"
  remote_pop_enroll_already = "Dieses Gerät ist schon eingerichtet — entsperren statt einrichten." / "This device is already set up — unlock instead of enrolling."
  (MigrationFailed-Copy: bestehende typisierte Zeile + „vorübergehend"-Nuance)
  # F3 (connect-flow): KEINE neue Copy — Reuse `remote_connect_device_custody_corrupt` (CYP-583, mit Terminologie-Swap
  #   „Wiederherstellungs-Code") + `remote_recovery_start_title/_body/_code_label` (Bestand). Nur 1 Button-Label:
  remote_connect_to_recovery = "Mit Wiederherstellungs-Code neu einrichten" / "Set up again with a recovery code"
Tags (OperatorAuthTags, camelCase, kein Rename):
  remote.enroll.slow        # advisory-Timeout (Polite)
  remote.enroll.retry       # Retry-Button (Timeout + MigrationFailed geteilt)
  remote.enroll.toUnlock    # AlreadyEnrolled Route-to-Unlock
  remote.connect.toRecovery # F3 Lead-to-Recovery-Button (DeviceCustodyCorrupt → RecoveryInputContent). Reuse-Ziel: RemoteRecoveryTags.START
```
> **⚠ Shared mit QA/CYP-7 — vor Rename koordinieren (via PO). Keys/Tags landen MIT Team-1-Devs/Dev5s Impl-Slice, nicht vorab** (sonst bricht ein Shared-Check). Timing mit dem bauenden Ticket.

## 6. Self-Validation — diskriminierende Zähne (headless via `runComposeUiTest`)
- **T1 (F1-Ausweg):** im `ENROLLING`-State existiert ein **Cancel** (`onNodeWithTag(cancel).assertExists()`); **diskriminiert** den heutigen no-escape-Spinner.
- **T2 (F1-Timeout, falls gebaut):** X s in ENROLLING (virt. Zeit) → advisory-Zeile (Polite, **nicht** error-Rolle) + Retry erscheinen, Spinner nicht mehr allein; **diskriminiert** den ewigen Optimismus-Spinner.
- **T3 (F2-Spezifität):** `AlreadyEnrolled` → **Route-to-Unlock**-Affordanz, **kein** Passphrase-Retry; `MigrationFailed` → **Retry**; `TooWeak`/`Blocklisted` → **kein** Retry-Button (nur Re-Entry). **diskriminiert** ein blanket-„Retry" über alle Outcomes.
- **T4 (Ton):** advisory = INFO/neutral, Fehler = ERROR-Rolle, **nie** `tertiary`/Grün; **diskriminiert** einen error-roten Timeout.
- **T5 (a11y):** advisory=Polite, Fehler=Assertive; Fokus landet nach State-Wechsel auf der Recovery-Affordanz.
- **T6 (F3-lead-to-recovery):** `DeviceCustodyCorrupt` → ein **Lead-to-Recovery**-Button (`remote.connect.toRecovery`) existiert und führt zu `RemoteRecoveryTags.START`; **kein** irreführender connect-Retry als einziger/primärer Ausweg. **diskriminiert** ein Corrupt-Surface, das nur eine Retry anbietet (die nichts repariert).

## 7. Scope-Grenze
- **Enroll (F1/F2) + der 1 connect-flow-Fold (F3 DeviceCustodyCorrupt-Recovery-Affordanz, PO1-Call).** OIDC-Fläche = Team-1 (done); **OIDC-Block-a11y = erledigt in `5a721953` (PO1 Option a), kein Beitrag hier.**
- **Punkt-B-Ton = PO1-bestätigt: INFO/ERROR, kein PROGRESS-Token.**
- **F1-Hang + F3-Recovery-Pfad = derselbe geteilte Dev/Behavior-Check** (kann `enroll()` hängen? / existiert der Recovery-Pfad im VM — F2+F3 teilen ihn); der **Cancel-während-ENROLLING** (F1) ist der Behavior-unabhängige Minimal-Fix. **F3 reuse-t den bestehenden `RecoveryInputContent`/`remote_recovery_*` — kein neuer Recovery-Flow.**
- **Design-first, kein Bau, keine develop-Berührung.** Keys/Tags landen mit der Impl (Sync-Flag §5). Runtime = guided-human. Gefundene Unehrlichkeit im Bau → Bug via PO → PO1.
