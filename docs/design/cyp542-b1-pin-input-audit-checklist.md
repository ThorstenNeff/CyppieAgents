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
- **A1.3 — Copy-Honesty (meine Lane):** Copy legt das Secret in die **Zwischenablage** (verlässt die App-Grenze) — der
  Save-Hinweis (`remote_pop_enroll_suggested_save`) ist SR-erreichbar + neutral (kein WARN, Guidance). Falls die Fläche einen
  Clipboard-Clear/Timeout andeutet, muss er ehrlich sein (nicht suggerieren, was nicht passiert). *(Clipboard-Egress-Wortlaut =
  Grauzone zu Team-1-Copy → an PO.)*
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
- **A5.3 — `blocklisted` ≠ `tooWeak` bleibt distinkt hörbar:** die selbstbeschreibende Copy trägt die Ursache (die Copy-Wahl ist
  Team-1; ich prüfe, dass die **Ursachen-Distinktion** SR-erreichbar ist, nicht zu einem generischen „ungültig" kollabiert). *Mess:*
  Announce/Text unterscheidet blocklisted vs tooWeak.
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
- **Grauzone → an PO flaggen, nicht still:** Clipboard-Egress-Wortlaut (A1.3), Stärke-/Ursachen-Copy-Register (A4.4/A5.3), Pfad-Benennung.

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
