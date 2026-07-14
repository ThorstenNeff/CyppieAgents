# CYP-542 B1 — Passphrase-Enroll-EINGABE: UX/a11y-Audit-Checkliste

> Owner: UIUX-Designer · Story **CYP-542** (M2-A Phase A / B1: Platform-UV-UI) · Epic CYP-427 · Stand 2026-07-14
> Status: **PRESTAGED + RECONCILED** an Team-1-UIUX' ratifizierte Visual-Spec — läuft, wenn Devs die B1-Enroll-Eingabe-UI landen.
> **Die QA IST die B1-Akzeptanz-Evidenz/DoD** (PO1). **CYP-542-Transition beim RUN gegen die gebaute UI** (nicht bei dieser Prestage).
>
> **Meine Naht = die `→UIUX2`-Markierungen** in Team-1-UIUX' Visual-Spec `docs/design/remote-uv-flow-visual-spec.md`
> (Branch `feature/CYP-542-uv-ui-spec` @ `0dfc8005`, Z. 11/13 Naht-Def + 37/48/57/103/120). **Ich ownt:** Eingabe-Interaktion
> + a11y (Fokus/IME/Reveal · SR-Ansagen Feld+Meter · Fokus-Reihenfolge · Selektion/Copy+SR · Zustandswechsel-Ansage).
> **Team-1-UIUX ownt:** Layout/Typo/visuelle Meter-Sprache + WCAG-Farbe+Glyph+Label-Paarung. **NICHT meins:** Copy-Wortlaut,
> Farb-Werte, Enumeration, Flow-Sequenz. Echte Überschneidung → **an PO → PO1-Reconcile** (nie still entscheiden).
>
> **Reales UI (4 Elemente, aus der Visual-Spec):** (1) Diceware-Default prominent (6 Wörter, One-Click-Verwenden + Regenerate)
> · (2) type-your-own Feldpaar (Setz+Confirm, **reuse `AuthPasswordField`**) mit Strength-Meter (3 Verdicts) · (3) Blocklist/
> too_weak/mismatch als retryable Feld-Fehler · (4) Auto-Weiterlauf Enroll→AUTHENTICATING (kein Erfolgs-Grün).
> **testTag-Anker (real, `OperatorAuthTags` area `remote.authStep.*` @ `0dfc8005`):** `enrollSuggested`/`enrollSuggest`/
> `enrollTypeOwn`/`enrollPinSet`/`enrollPinConfirm`/`enrollStrength`/`enrollError.<mismatch|tooShort|tooWeak|blocklisted>` +
> `RemoteConnectTags.authenticating`. **Mess-Disziplin:** wo möglich headless `runComposeUiTest` (Semantics/testTag, gemessen); Rest Objekt-Read.

---

## A0 — Anti-Duplikat (VOR allen Achsen)
Baut die type-your-own-Eingabe auf **`AuthPasswordField`** (maskiert, Text-Label-Reveal, nie a11y-Leak — Visual-Spec §1b, CYP-176)
oder ist es ein **divergentes One-off**? *Diskriminiert:* eine Eigenbau-Eingabe ohne die gehärteten Masking/a11y-Eigenschaften.
*Mess:* Objekt-Read der Composable-Quelle + testTag-Herkunft (`enrollPinSet`/`enrollPinConfirm`).

## 1 — Diceware-Default: Selektion / Copy-Interaktion + SR (`→UIUX2` Z. 37, Tag `enrollSuggested`)
- **A1.1 — Wörter selektierbar + kopierbar** (der Nutzer muss sie in einen Passwort-Manager sichern können). *Diskriminiert:*
  ein nicht-selektierbarer Text-Block, der Speichern erzwingt = Abtippen. *Mess:* Selektion/Copy-Aktion am `enrollSuggested`-Node.
- **A1.2 — Copy wird SR-angesagt** („Passphrase kopiert") — nicht stumm. *Diskriminiert:* stiller Copy, den ein SR-Nutzer nicht
  bestätigt bekommt. *Mess:* Announce/Live-Region nach Copy.
- **A1.3 — Clipboard-Egress: disclosed + ehrlich + kein silent Copy (PO1-Reconcile 2026-07-14, meine a11y-Achse).** Der Diceware-
  Copy ist ein echter Egress (Crown-Jewel → Zwischenablage); Copy **erlaubt** (Passwort-Manager-Save real+wichtig), aber:
  **(a) kein silent Copy** — der SR sagt die **Clipboard-Disclosure** an (dass die Passphrase in die Zwischenablage geht);
  **(b) honest-wording-Zahn (load-bearing, meine Lane):** kein Announce/Text impliziert je „sicher"/„gelöscht", wenn die Plattform
  es **nicht garantiert** — bei best-effort-Auto-Clear ehrlich „best-effort/nach X" statt „gelöscht". *Diskriminiert:* ein stiller
  Copy ODER ein „sicher gelöscht", wo nur best-effort-Clear läuft. *Mess:* Announce-Präsenz nach Copy + Wortlaut-Register-Read.
  *(Team-1 ownt die Disclosure-**Copy** — kommt von PO1; Dev baut die Clear-**Mechanik** [bounded Timeout, wo Plattform stützt] gegen meine Spec.)*
- **A1.4 — Die generierten Wörter erscheinen NICHT in `contentDescription`/Log als separater Leak-Vektor** über die sichtbare
  Darstellung hinaus (sie sind sichtbar by-design, aber kein Doppel-Announce/Log). *Mess:* kein zweiter Node/Log trägt sie.

## 2 — Fokus-Reihenfolge (`→UIUX2` Z. 120: **Default → Accept → Regenerate → type-own**)
- **A2.1 — Tab-Order = Default-Anzeige → „Diese verwenden" (`enrollSuggested`-Accept) → „Andere vorschlagen"
  (`enrollSuggest`) → „Eigene eingeben" (`enrollTypeOwn`).** *Diskriminiert:* type-own vor Accept fokussiert (kehrt die
  Ehrlichkeits-Hierarchie um — der leise Ausnahme-Pfad dürfte nicht zuerst kommen). *Mess:* Fokus-Order-Assertion.
- **A2.2 — Regenerate hält den Fokus + sagt die NEUE Passphrase an** (nicht Fokus verlieren/ins Leere springen). *Diskriminiert:*
  Regenerate, das den Fokus an den Anfang wirft oder die neue Diceware nicht ansagt (SR-Nutzer weiß nicht, dass sich was änderte).
- **A2.3 — type-own expandiert das Feldpaar + verschiebt Fokus sinnvoll dorthin** (nicht Fokus verloren beim Aufklappen).
- **A2.4 — Keyboard-only vollständig:** alle 4 Affordanzen per Tab/Enter erreichbar; kein Maus-Zwang; kein Fokus-Trap.

## 3 — type-your-own Feldpaar: Affordances + Leak-Freiheit + Reveal (`→UIUX2` Z. 48, Tags `enrollPinSet`/`enrollPinConfirm`)
- **A3.1 — Default maskiert** (`PasswordVisualTransformation`); kein Klartext-Default. *Mess:* Feld-Text nicht als Klartext-Node.
- **A3.2 — Kein Klartext-Echo, NIE a11y-Leak** (schärfster Security-Zahn): weder Setz- noch Confirm-Feld trägt den Wert je in
  `contentDescription`/Announce/Log. *Diskriminiert:* `contentDescription`, die den eingegebenen Wert einbettet (SR-Leak).
  *Mess:* assert, kein Semantics-Node trägt den eingegebenen Secret.
- **A3.3 — Paste NICHT blockiert** (Passwort-Manager-Pflicht). *Diskriminiert:* das „Sicherheits"-Anti-Pattern Paste-disable
  (bricht Manager, senkt reale Sicherheit). *Mess:* Paste füllt das Feld; kein `onPaste`-Suppress. + Autofill-Semantik wo Plattform sie trägt.
- **A3.4 — Reveal = Text-Label-Toggle** (Visual-Spec: kein Emoji), Reveal nur der **aktuellen** Eingabe, kein Persist/Echo; Toggle-
  Zustand SR-angesagt (`a11y_auth_password_show`/`_hide`). *Diskriminiert:* icon-only-Reveal ohne Label (1.4.1 + SR-stumm).
- **A3.5 — Kein Autocorrect/Autocapitalize/Suggestion** auf dem Secret.

## 4 — Strength-Meter: Live-SR-Ansage (`→UIUX2` Z. 57, Tag `enrollStrength`)
> **Nur type-your-own** (der Diceware-Default ist by-construction stark → **kein Meter**; A4 NICHT gegen den Default fordern).
- **A4.1 — Meter-Level ist LIVE-REGION `Polite`** — Stärke-Änderung wird angesagt, **ohne Fokus zu stehlen** (nicht bei jedem
  Tastendruck den Fokus reißen). *Diskriminiert:* rein visuelle Leiste, SR-stumm; ODER assertiver Announce, der Fokus reißt. *Mess:*
  liveRegion==Polite am `enrollStrength`-Node.
- **A4.2 — Level trägt Text-Label** (`remote_pop_strength_{weak,fair,strong}`) — Farbe/Fill nie alleiniger Träger; das SR hört das
  Wort, nicht nur die Farbe. *(WCAG-Farbwert selbst = Team-1.)*
- **A4.3 — Honesty-Parität am Announce (meine Lane, an Team-1s BLOCKLISTED-Fill-Ehrlichkeit gekoppelt):** bei `blocklisted` darf der
  SR-Announce **NICHT „stark"** sagen (auch wenn strukturell lang) — er folgt dem strengeren Verdict („zu verbreitet"), genau wie
  der Fill optisch gedämpft wird. *Diskriminiert:* ein Meter, der visuell dämpft, aber der SR-Announce weiter „stark" sagt (Konflation).
  *Mess:* Announce-Text bei blocklist-Fall ≠ „strong".
- **A4.4 — Stärke = advisory, NIE Garantie** (Register, nicht Wortlaut): kein „sicher/geschützt"-Announce; einzige Offline-Barriere.

## 5 — Retryable Enroll-Fehler: Fokus + Ansage (Tags `enrollError.<mismatch|tooShort|tooWeak|blocklisted>`)
- **A5.1 — Fehler wird SR-angesagt (assertive) + fokus-geführt**, Feld bleibt **aktiv/retryable** (Visual-Spec §3: kein
  Sackgassen-State). *Diskriminiert:* stiller Fehler (nur farbige Outline), den ein SR-Nutzer nie bemerkt. *Mess:* liveRegion==Assertive
  am `enrollError`-Node + Fokus-Ziel.
- **A5.2 — mismatch (Setz≠Confirm) wird angesagt** — nicht nur farbig; Fokus führt zur Korrektur. *Diskriminiert:* mismatch nur visuell.
- **A5.3 — SR sagt die SPEZIFISCHE Ursache an (PO1-Reconcile 2026-07-14, meine a11y-Achse — kein Copy-Konflikt).** `blocklisted` ≠
  `tooWeak` haben bereits **distinkte ratifizierte Strings** (`remote_pop_enroll_blocklisted` vs tooWeak); meine Achse = die
  Live-Region **so verdrahten, dass der SR die spezifische Ursache hört** (blocklisted / tooWeak / mismatch / tooShort), **nie zu
  einem generischen „ungültig" kollabiert**. *Diskriminiert:* eine Error-Live-Region, die alle 4 Ursachen auf einen generischen
  Announce mappt (SR-Nutzer erfährt nicht *warum*). *Mess:* Announce bei jeder der 4 `enrollError.<cause>` trägt die distinkte Ursache. *(Wortlaut = Team-1, existiert schon.)*
- **A5.4 — Feld bei Fehler NICHT still geleert** (oder Leerung angesagt). *Diskriminiert:* stilles Clear.

## 6 — Auto-Weiterlauf: Zustandswechsel-Ansage + Fokus-Übergabe (`→UIUX2` Z. 103, Tag `RemoteConnectTags.authenticating`)
- **A6.1 — Enroll→AUTHENTICATING wird SR-angesagt** (der Zustand ist *verifying*, nicht *granted*) — kein toter/stiller Moment.
  *Diskriminiert:* stiller Sprung, den ein SR-Nutzer nicht mitbekommt. *Mess:* Announce beim Übergang.
- **A6.2 — Fokus-Übergabe an AUTHENTICATING-Fortschritt** sinnvoll (nicht ins Leere / nicht auf der verschwundenen Enroll-Karte).
- **A6.3 — granted → Hub-Liste: Fokus-Übergabe + SR-Ansage, KEIN Erfolgs-Grün** (Honesty-Parität: die Optik ist neutral, der
  Announce sagt nicht „Erfolg/sicher"). *Diskriminiert:* ein Success-Screen/-Grün, das mehr Sicherheit suggeriert als der neutrale Zustand.

---

## Overlap-Governance (nicht in Team-1s Lane rutschen)
- **Meins:** Fokus/IME/Reveal-Mechanik · SR-Ansagen (Feld · Meter-Live · Zustandswechsel · Copy) · Fokus-Reihenfolge · Selektion/
  Copy-Interaktion · a11y-Leak-Freiheit · advisory-vs-Garantie- + BLOCKLISTED-**Register** am Announce.
- **Team-1-UIUX:** Layout/Typo/visuelle Meter-Sprache · WCAG-Farb-/Glyph-/Label-**Werte** · Copy-Wortlaut DE/EN · Ton-Trennung · Flow-Sequenz.
- **Grauzonen — PO1-RECONCILE 2026-07-14 (beide RESOLVED):** ① Clipboard-Egress (A1.3) → Copy erlaubt, aber disclosed + ehrlich +
  kein-silent; meine Achse = SR-Disclosure-Announce + honest-wording + no-silent (Team-1 Copy von PO1, Dev Clear-Mechanik). ②
  Ursachen-Register (A5.3) → distinkte Strings existieren; meine Achse = Live-Region sagt spezifische Ursache (nicht generisch). **Kein
  offener Copy-Konflikt mehr.** Rest-Grauzone bei Bedarf (Pfad-Benennung) weiter an PO, nicht still.

## Unlock-Pfad (separater Slice — hier NICHT gefordert)
Der Follow-Usage-**Unlock** (`pinWrong`/`lockedOut`/`biometricFailed`, `desktop-remote-operator-ux-spec.md` §5.3) ist eine **andere**
Fläche als dieser CYP-542-Enroll. Wenn/sobald die Unlock-Eingabe-UI landet: eigener a11y-Pass (assertive pinWrong-Announce +
Versuchszähler + Lockout-ehrlich≠Hub-Reject + biometricFailed→PIN-Fallback). **Nicht Teil dieses RUNs**, damit ich nicht gegen ein
nicht-gebautes UI auditiere. An PO, falls der Slice zusammengelegt wird.

## Self-Validation
- Alle 5 `→UIUX2`-Nähte abgedeckt (Z. 37 Copy/Selektion · 48 Reveal · 57 Meter-Live · 103 Zustandswechsel · 120 Fokus-Reihenfolge) + A0-Anti-Duplikat.
- Jeder Zahn **diskriminierend** (welche falsche Impl) + an einen **realen testTag** verankert (`remote.authStep.enroll*` / `remote.connect.authenticating`).
- **Reconciled an die reale 4-Element-Enroll-UI** (nicht die ursprünglich angenommene Plain-PIN); Unlock-Pfad explizit ausgeklammert.
- Scoped auf Interaktion/a11y; Farbe/Copy/Flow = Team-1; Grauzonen als Overlap-an-PO markiert (nie still).
- Grounded @ `0dfc8005` (Visual-Spec + tags) + `AuthPasswordField`/`AnnouncingHint` (CYP-176). **CYP-542-Transition beim RUN.**

---

## RUN-RESULTS — voller a11y-Audit @ `f71598980` (batch/CYP-542-b1, 2026-07-14, gemessen: Objekt-Read `RemoteOperatorAuthSteps.kt` + grep)
> **Re-target (PO1): gegen Batch-Tip `f71598980` verifiziert** (nicht `c3c03a9d`). Am Objekt bestätigt: `RemoteOperatorAuthSteps.kt`
> **byte-identisch** c3c03a9d↔f71598980 (0 Diff) + `liveRegion`-grep weiter 0 → **F1/F2/F3 halten verbatim** am wahren Merge-Baum.
> **Verdikt: a11y NO-GO — 3 Findings zu fixen, bevor CYP-542 auf der a11y-Achse abschließt.** (Kein Selbst-Transition; PO1 zieht die
> Findings in den B1-Batch.) Mess-Methode: Compose-a11y ist im Code deklariert → Objekt-Read + `liveRegion`-grep sind die Messung.

**🔴 F1 (PRIMÄR, blockierend) — KEINE Live-Region im gesamten Enroll/Operator-UI → dynamische SR-Ansagen fehlen komplett.**
`grep liveRegion|LiveRegionMode` über `connect/**` + `net/hub/operator/**` @ `c3c03a9d` = **0 Treffer**. Folge (WCAG 4.1.3 Status
Messages, AA): Strength-Verdict-Wechsel (weak→strong) nicht angesagt (mein A4.1 Polite), Enroll-Fehler `too_weak`/`blocklisted`
(`EnrollOutcomeLine`/`StrengthMeter`) + `mismatch` nicht angesagt (A5.1 Assertive), Regenerate-neue-Passphrase nicht angesagt (A2.2),
Enrolling-Zustand nicht angesagt (A6.1). **Die distinkten Ursachen-DATEN sind korrekt (Pre-Gate PASS), aber der SR HÖRT sie nie.**
*Fix:* Strength-Verdict-Zeile in `liveRegion=Polite`, Fehler/Mismatch-Zeilen in `liveRegion=Assertive` (der Announce trägt den schon-
distinkten String → spezifische Ursache, erfüllt ②).

**🟠 F2 (A0-Forward-Flag + ①-Honesty, verlinkt) — `DicewareReveal` REUSED `RecoveryCodesReveal` NICHT + „kopiert"-Notiz ohne Copy-Aktion.**
`RecoveryCodesReveal` (der gehärtete Pfad) hat einen echten Copy-Button (`LocalClipboardManager.setText`) + Ack-Gate. `DicewareReveal`
ist ein **One-off**, das nur `SelectionContainer` „spiegelt" — **kein Copy-Button, kein Clipboard-Write**. TROTZDEM sagt die Notiz
`remote_pop_enroll_clipboard_notice` = „In die Zwischenablage kopiert / Copied to clipboard" → **behauptet einen Copy, der nie
passiert** (①-honest-wording-Verstoß: eine Aussage ohne korrespondierende Aktion). *Fix (A):* `RecoveryCodesReveal` wirklich reusen
(Copy-Button) → Notiz wird ehrlich. *Fix (B):* wenn nur Manual-Select gewollt, Notiz umformulieren (nicht „kopiert" behaupten).
**Positiv am Register:** die Clear-Guidance „Zwischenablage nach dem Speichern leeren" ist **ehrlich** (kein falsches „auto-gelöscht/sicher").

**🟡 F3 (Spec-vs-Build-Divergenz, reconcile) — Reveal-Toggle fehlt auf dem Feldpaar.** `PassphraseInput` übergibt **kein** `revealTag`
an `AuthPasswordField` → kein Text-Label-Reveal, obwohl Visual-Spec §1b (→UIUX2 Z.48) ihn spezifiziert. Spannung: die Security-Rider
(rider 4/5, Crown-Jewel-Surface minimieren) sprechen gegen Reveal; das Doppel-Eingabe-Confirm-Feld deckt Tippfehler-Verifikation ab.
*→ PO/Team-1-Reconcile:* Reveal bewusst gedroppt (dann Visual-Spec §1b nachziehen) ODER wiren.

**✅ PASS (gemessen):** a11y-Leak-frei (A3.2 — `contentDesc` = statisches Label-Resource, nie `value`; rider 5); Honesty-Parität (A4.3 —
BLOCKLISTED-Fill `outline`-gedämpft, kein Glyph, OK=`●`+neutral kein-Grün, TOO_WEAK=`▲`-WARN-amber, alle Farbe+Glyph/Copy+Label 1.4.1);
Fokus-Reihenfolge Default→Accept→Regenerate→type-own (Layout-Reihenfolge); no-silent-Copy (keine app-initiierte Clipboard-Schreibung);
Feld reused `AuthPasswordField` (A0-Input); CharArray-Hygiene/Zeroize (`DisposableEffect`).

**Ehrliche Scope-Notizen:** `tooShort` nicht in dieser Passphrase-UI gerendert (PIN-Pfad, separat) → N/A hier. A2.2-Autofill bewusst
**disabled** per Security-Rider (Crown-Jewel; Passwort-Manager-Pfad = Diceware-Reveal statt Feld-Autofill) → resolved-by-design, kein
Finding. `granted`→Hub-Liste-Übergang außerhalb dieser Datei (VM-State). Logik-Tests (Dev-Self-Gate) nicht re-gefahren — Fokus = a11y-Layer.

**CYP-542-a11y-Readiness: NO-GO bis F1 (+F2) gefixt; F3 reconcile.** Findings → PO → PO1-Batch. Re-Verify wenn Dev fixt.
