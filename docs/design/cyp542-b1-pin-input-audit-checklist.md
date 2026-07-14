# CYP-542 B1 — Passphrase/PIN-EINGABE: UX/a11y-Audit-Checkliste

> Owner: UIUX-Designer · Story **CYP-542** (M2-A Phase A / B1: Platform-UV-UI, App-PIN + platform-authenticator) · Stand 2026-07-14
> Status: **PRESTAGED** — läuft, wenn Devs die B1-Eingabe-UI landen; **die QA IST die B1-Akzeptanz-Evidenz/DoD** (PO1: kein separates Ticket).
> **CYP-542-Transition erfolgt beim RUN gegen die gebaute UI** (nicht beim Landen dieser Prestage); Ergebnis + Findings → PO → PO1.
> **Scope (PO1, ENG, NON-OVERLAPPING mit Team-1-UIUX):** NUR die **Passphrase/PIN-EINGABE-Interaktion** — die security-kritischste
> Eingabe im Produkt (**einzige Offline-Barriere**). **NICHT der Flow** (Flow-Kohärenz / Töne / WCAG-Kontrast-Werte / Enumeration-Safety /
> DE-EN-Copy-Wortlaut = Team-1-UIUX). Ich liefere an PO → PO1 (reconcilet mit Team-1-UIUX bei Überschneidung).
> **Grounding (real @ develop):** `desktop-remote-operator-ux-spec.md` §5 (DevicePoP-Enroll/Unlock, §5.3 Zustands-Taxonomie, §5.4
> „Reuse `AuthPasswordField`"); Reuse-Anker `AuthComponents.kt` `AuthPasswordField`/`AnnouncingHint` (CYP-176).
> **Mess-Disziplin:** wo möglich headless `runComposeUiTest` (testTag/Semantics-Assertion, gemessen); Rest expliziter Objekt-Read. Nie Pixel-Claim ohne Beleg.

---

## 0. Vorab-Zahn (Anti-Duplikat, VOR allen Achsen)
**A0 — Reuse statt Neu-Bau.** Baut die B1-Eingabe auf `AuthPasswordField` (maskiert, Text-Label-Reveal, nie a11y-Leak — §5.4) oder
ist es ein **divergentes One-off**? Ein zweites Passwort-Feld-Muster ist das Anti-Pattern. *Diskriminiert:* fängt eine Eigenbau-Eingabe,
die die gehärteten a11y/Masking-Eigenschaften von `AuthPasswordField` NICHT erbt. *Mess:* Objekt-Read der Composable-Quelle + testTag-Herkunft.

---

## 1. Eingabe-Affordances (die security-kritische Eingabe)
- **A1.1 — Default maskiert.** Feld startet `PasswordVisualTransformation()` (nie Klartext-Default). *Diskriminiert:* ein Feld, das den
  Secret im Klartext rendert. *Mess:* Semantics — der Feld-Text ist nicht als Klartext-Node lesbar.
- **A1.2 — Kein Klartext-Echo, NIE a11y-Leak.** Der eingegebene Secret erscheint **nirgends** als Content: nicht in `contentDescription`,
  nicht in einem Announce, nicht in Logs. *Diskriminiert (schärfster Sicherheits-Zahn):* eine `contentDescription`, die den PIN-Wert
  einbettet (der klassische Screen-Reader-Leak). *Mess:* assert, dass kein Semantics-Node den eingegebenen Wert trägt.
- **A1.3 — Kein Autocorrect/Autocapitalize/Suggestions** auf dem Secret (KeyboardType.Password/Number; keine Vorschlagsleiste, die
  Zeichen preisgibt/verändert). *Diskriminiert:* ein Text-Feld mit aktiver Autokorrektur auf dem Passwort.
- **A1.4 — PIN vs Passphrase korrekt.** PIN = numerisch (Richtwert ≥6, §5.4) → Ziffern-Tastatur ABER Paste bleibt erlaubt (A2);
  Passphrase = Text (Höher-Sicherheit-Opt-up). *Diskriminiert:* PIN-Feld, das alphabetische Eingabe/keine Ziffern-IME hat.

## 2. Paste-Support (Passwort-Manager!) — **Pflicht, nicht optional**
- **A2.1 — Paste NICHT blockiert.** Einfügen aus einem Passwort-Manager funktioniert. *Diskriminiert:* das verbreitete
  „Sicherheits"-Anti-Pattern, Paste zu deaktivieren (bricht Passwort-Manager, senkt reale Sicherheit → schwächere Secrets). *Mess:*
  Paste-Aktion füllt das Feld; kein `onPaste`-Suppress.
- **A2.2 — Autofill/Passwort-Manager-Semantik** (wo Plattform sie trägt): das Feld ist als Passwort-Autofill markiert, damit Manager
  speichern/füllen können. *Diskriminiert:* Feld ohne Autofill-Hint, das Manager nicht erkennen. *Mess:* Objekt-Read der Autofill-Semantik.
- **A2.3 — Kein Zeichen-Split/Boxen, der Paste bricht.** Wenn PIN als Einzel-Zeichen-Boxen gerendert wird (häufiges PIN-UI), muss ein
  Paste des ganzen Codes trotzdem alle Boxen füllen. *Diskriminiert:* segmentierte PIN-UI, in die man nur tippen, nicht einfügen kann.

## 3. Show/Hide-Toggle
- **A3.1 — Toggle vorhanden, Reveal nur der AKTUELLEN Eingabe**, nie Persist/Echo (§5.4). *Mess:* Reveal-Klick → Feld zeigt Klartext;
  erneut → maskiert; kein Klartext außerhalb des Felds.
- **A3.2 — Toggle-Zustand wird angesagt** (contentDescription `a11y_auth_password_show`/`_hide`, Reuse) — Screen-Reader hört „anzeigen"/
  „verbergen". *Diskriminiert:* icon-only-Toggle ohne Label (Farbe/Icon allein = 1.4.1-Verstoß + SR-stumm). *Mess:* Semantics-contentDescription.
- **A3.3 — Reveal-State lokal, kein Leak über Reveal.** Reveal darf den Wert nicht in einen Announce/Log spülen (nur visuell im Feld).

## 4. Keyboard / Tab-Order
- **A4.1 — Keyboard-only vollständig bedienbar:** Feld → Reveal-Toggle → Submit in **logischer** Tab-Reihenfolge; Enter/IME-Action
  submittet. *Diskriminiert:* Reveal-Toggle nicht fokussierbar, oder Tab springt am Submit vorbei. *Mess:* Fokus-Order-Assertion.
- **A4.2 — Kein Fokus-Trap** im Feld/Toggle; Fokus-Ring **präsent/erreichbar** (den WCAG-Kontrast-WERT des Rings prüft Team-1; ich prüfe
  nur Präsenz + Keyboard-Erreichbarkeit). *Diskriminiert:* Toggle nur per Maus erreichbar.
- **A4.3 — Ziffern-IME bricht Keyboard-Nav nicht** (PIN numerisch, aber Tab/Enter/Paste weiter möglich).

## 5. Screen-Reader-Announce der Stärke-Anzeige (Enroll/Set-Pfad)
> Gilt für den **Set/Enroll**-Pfad (§5.2: PIN/Passphrase setzen, zweifache Eingabe + Stärke-Hinweis). Beim reinen **Unlock** gibt es
> keinen Stärke-Meter.
- **A5.1 — Stärke-Hinweis ist eine LIVE-REGION** (`AnnouncingHint`, `LiveRegionMode.Polite`) — Änderungen werden angesagt, **ohne Fokus
  zu stehlen**. *Diskriminiert:* eine rein visuelle Stärke-Leiste, die der Screen-Reader nie hört; ODER ein assertiver Announce, der bei
  jedem Tastendruck den Fokus reißt. *Mess:* Semantics-liveRegion == Polite auf dem Hint-Node.
- **A5.2 — Stärke = ADVISORY-Heuristik, NIE Garantie** (meine Honesty-Lane). Wortlaut/Announce sagt nicht „sicher"/„geschützt", sondern
  eine Einschätzung; die Passphrase ist die **einzige Offline-Barriere** — die UI darf keinen Schutz suggerieren, der über die Heuristik
  hinausgeht. *Diskriminiert:* ein grünes „Stark ✓ — dein Konto ist sicher". *Mess:* Register-Read (advisory), Ton nicht success-grün als
  Garantie (Anti-Hype). *(Copy-DE/EN-Wortlaut selbst = Team-1; ich prüfe nur das advisory-vs-Garantie-REGISTER am Announce → Grauzone, an PO.)*
- **A5.3 — Match/Mismatch der Doppel-Eingabe** wird angesagt (nicht nur farbig) — „stimmt überein" / „stimmt nicht überein" als Text/Announce.

## 6. Fehler-State-Fokus (Unlock-Pfad, §5.3)
- **A6.1 — `pinWrong` wird angesagt + fokus-geführt.** Bei lokal falscher PIN: Fehler als **assertive** Live-Region (SR hört ihn sofort);
  Fokus geht sinnvoll (auf Fehler ODER zurück ins Feld), nicht ins Leere. *Diskriminiert:* stiller Fehler (nur rote Umrandung), den ein
  SR-Nutzer nie bemerkt. *Mess:* liveRegion==Assertive auf Error-Node + Fokus-Ziel-Assertion.
- **A6.2 — Versuchszähler angesagt** („noch N Versuche", §5.3 `pinWrong`) — nicht nur visuell. *Diskriminiert:* Zähler nur als Farbe/Zahl
  ohne Announce.
- **A6.3 — Feld wird bei Fehler NICHT still geleert.** Wird es geleert (üblich), ist das angesagt; sonst bleibt der Wert, damit der Nutzer
  korrigieren kann. *Diskriminiert:* stilles Clear, das den SR-Nutzer im Unklaren lässt, warum das Feld leer ist.
- **A6.4 — `lockedOut` ehrlich + angesagt:** temporäre Sperre mit **ehrlicher Wartezeit** (§5.3), klar **≠** Hub-Reject; als Live-Region
  angesagt. *Diskriminiert:* Lockout, der wie ein Server-/Auth-Reject aussieht (falsche Ursache), oder ohne Wartezeit-Offenlegung.
- **A6.5 — `biometricFailed` → Fallback-auf-PIN ehrlich** (§5.3): der Fokus/Announce führt sauber zur PIN-Eingabe, nie eine
  Biometrie-Optik vortäuschen, wo nur PIN existiert (§5.1-Ehrlichkeit; Linux = immer PIN). *(Pfad-Benennung selbst grenzt an Team-1-Flow —
  ich prüfe nur den Eingabe-Fokus-Übergang, flagge Overlap an PO.)*

---

## Overlap-Governance (damit ich nicht in Team-1s Lane rutsche)
- **Meins (Eingabe-Interaktion):** Affordances, Paste, Show/Hide-Mechanik, Keyboard/Fokus, Live-Region-*Mechanik* der Announces,
  a11y-Leak-Freiheit, advisory-vs-Garantie-*Register* am Stärke-Announce.
- **Team-1-UIUX (Flow):** Flow-Kohärenz, Ton-/Farb-WCAG-Werte, Enumeration-Safety, DE/EN-Copy-Wortlaut, Zustands-Sequenz.
- **Grauzone → an PO flaggen, nicht still entscheiden:** Pfad-Benennung (A6.5), Stärke-Copy-Register (A5.2). Ich prüfe die
  a11y/Interaktions-Facette, PO1 reconcilet mit Team-1.

## Self-Validation (dieser Checkliste)
- 6 PO-Achsen alle abgedeckt (1 Affordances · 2 Paste · 3 Show/Hide · 4 Keyboard · 5 Stärke-Announce · 6 Fehler-Fokus) + A0-Anti-Duplikat.
- Jeder Zahn ist **diskriminierend** formuliert (welche falsche Impl fängt er) — nicht „ist X da?".
- Scoped auf die **Eingabe**, Flow explizit ausgeklammert; Grauzonen als Overlap-an-PO markiert.
- Grounded: §5.3/§5.4 Zustände + `AuthPasswordField`/`AnnouncingHint`-Reuse (real @ develop).
- **CYP-542-Transition beim RUN** gegen die gebaute B1-Eingabe-UI (nicht bei dieser Prestage); Findings → PO → PO1.
