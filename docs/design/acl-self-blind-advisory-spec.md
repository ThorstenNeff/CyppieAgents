# ACL Operator-Selbst-Erblindung — Disclosure-Advisory (Spec)

> Owner: UIUX-Designer · **kein Ticket** (④ aus `residual-exposure-disclosure-audit.md`) · Stand 2026-07-17 ·
> **Design/Spec-Pass, kein Code.** Gegroundet READ-ONLY gg. develop `1da14371`
> (`acl/AclViewModel.kt`, `acl/AclPanel.kt`, `acl/AclMatrixTags.kt`, `strings.xml`).
> Companion: `-keys.md` (Copy), `-tags.md` (testTags).

## 0. Auftraggeber-Entscheid & die Doktrin-Zeile (beidseitig erfüllt)

Der Auftraggeber hat zu ④ entschieden: **„add an advisory (not a block)."** Damit ist die eine Regel aus dem
Audit **beidseitig** erfüllt — und **von ihm**, nicht von uns:

> **Garantie-brechend → Guard. Selbst-betreffend & recoverable → offenlegen, nicht blocken.**
> - **Guard-Hälfte:** PO-Aussperrung bricht Hub-and-Spoke fürs Team → harter Guard (`acl_po_protected`, 409).
>   **Kein Guard auf Operator-ACL** (CYP-663, sein Entscheid) — **unangetastet.**
> - **Disclosure-Hälfte:** Operator-Selbst-Erblindung ist reversibel, bricht keine fremde Garantie → **Advisory**
>   (sein Entscheid, jetzt). Diese Spec liefert sie.

Diese Spec **revidiert CYP-663 nicht** und fügt **keinen** Block hinzu. Sie schließt die **zweite Achse** —
Disclosure — die vorher niemand gestellt hatte und die als Nebeneffekt von „kein Guard" stumm durchgelaufen wäre.

## 1. Mechanik: der Code spiegelt die Doktrin

Heute laufen **beide** Fälle durch **denselben** Halte-Dialog (`AclViewModel.kt:198-206`):
```
val poLockout = AclReducer.wouldLockoutPo(channelId, agent, newValue, s.channels)
val selfBlind = agentId == OPERATOR_ID && dimension == AclDimension.READ && !newValue   // READ-only!
if (poLockout || selfBlind) { … lockoutPrompt = LockoutPrompt(…) ; return }             // hält die Aktion
applyToggle(…)
```
Das ist (a) **READ-gated** (WRITE fällt stumm durch) und (b) ein **Halte-Dialog** für den Selbst-Fall — genau die
„erzwungene Reibung / Bestätigungs-Dialog", die der Auftraggeber-Entscheid **nicht** will.

**Ziel-Mechanik — trennt die zwei Achsen sauber, sodass der Code die Doktrin *ist*:**
```
if (poLockout) { … lockoutPrompt = LockoutPrompt(…) ; return }   // Guarantee-brechend → Dialog/Guard (BLEIBT)
applyToggle(…)                                                    // committed sofort, wie jede andere Zelle
// … in applyToggle, nach dem optimistischen Upsert:
if (agentId == OPERATOR_ID && !newValue) markSelfBlind(key, dimension)  // Selbst-betreffend → post-commit Advisory
```
- **PO-Lockout** bleibt der pre-commit **Dialog** (Guard, 409-gedeckt).
- **Selbst-Erblindung** wird ein **post-commit INFO-Marker** auf der Zelle — **beide** Dimensionen (READ **und**
  WRITE), über die **bestehende** `cellNotice`→`StateMarker`-Bahn (`AclPanel.kt:310,366`, wie `acl_po_protected`).
  **Kein Dialog, kein Halten, kein Confirm.** Die Aktion committed wie jede andere.

## 2. Die vier Auftraggeber-Constraints — und wie die Spec sie hält

| # | Constraint | Erfüllt durch |
|---|---|---|
| ① | **WRITE-Symmetrie** (READ hatte Advisory, WRITE keine) | Marker feuert für `!newValue` in **beiden** Dimensionen; dimensions-spezifische Copy `acl_self_blind_read`/`_write` |
| ② | **Handlung statt Symptom** | Copy = „Du hast dir das X-Recht für %1$s **entzogen** … **Wieder einschalten**, um es zurückzuholen" (Handlung + Rückweg), **nicht** „dieser Kanal ist leer" |
| ③ | **Ton `INFO`, kein Alarm** | neutraler `StateMarker` (INFO), **nicht** `state.notice`/errorContainer, kein Rot, kein `EFFECT_DEFERRED` (nichts latent — sofort wirksam) |
| ④ | **kein Scare / kein Dialog / kein Zwang; committed wie bisher** | post-commit Marker über `applyToggle`; `selfBlind` **raus** aus dem `lockoutPrompt`-Zweig; Copy im Sach-, nicht Warn-Duktus |

**Grenze, die ich selbst gezogen habe und die gilt:** wenn die Copy anfängt, wie eine Warnung vor etwas
Gefährlichem zu klingen, ist sie falsch. Sie beschreibt einen **reversiblen Zustand, den der Nutzer selbst
herstellte** — Sach-Ton, kein Alarm, kein Confirm-Gate.

## 3. WRITE-Symmetrie + der „self-blind ist READ-zentrisch"-Fund

Eigenes **Lesen** aus → *erblinden* (nicht mehr sehen). Eigenes **Antworten** aus → *verstummen* (nicht mehr
senden, **sieht weiter**). „Erblinden" ist für WRITE **falsch** — und dieses READ-zentrische Modell ist vermutlich
**der Grund**, warum der Guard `dimension == READ`-gated gebaut wurde und WRITE stumm blieb. Deshalb **zwei**
Copys mit der je **realen** Konsequenz (Details `-keys.md`), nicht eine verwaschene parametrisierte.

## 4. Copy (Verweis)

`acl_self_blind_read` / `acl_self_blind_write` (+ `a11y_*`), INFO, Handlung→Konsequenz→Rückweg — voll in
`acl-self-blind-advisory-keys.md`. `%1$s` = Kanalname.

## 5. ★ Flag: READ-Reconcile ist ein Behavior-Change (PO-Entscheid)

Constraint ④ sagt „kein Bestätigungs-Dialog". Der **heutige READ-Pfad IST einer** (`LockoutDialog`,
`AclViewModel.kt:198-206` hält die Aktion bis `confirmLockout`). Zwei ehrliche Optionen:

- **B1 (empfohlen):** `selfBlind` **ganz** aus dem Dialog-Zweig nehmen → **READ und WRITE** beide post-commit
  Inline-Advisory. **Volle Symmetrie, erfüllt ④ vollständig** (der Selbst-Fall hat gar keinen Dialog mehr).
  **Retire** `acl_self_blind_warning` + Tag `SELF_BLIND_WARNING`. **Ändert eine ausgelieferte Fläche** (READ-Dialog
  → Inline).
- **B2:** READ-Dialog **belassen**, nur WRITE bekommt die post-commit Advisory. **Asymmetrisch** (READ blockt-dann-
  committed, WRITE committed-dann-meldet) — und **lässt genau die Confirm-Reibung stehen, die ④ ausschließt.**

**Empfehlung: B1.** B2 hält die Reibung, die der Auftraggeber zweimal nicht wollte, und macht zwei Mechaniken für
**eine** Doktrin-Klasse. **Aber B1 ändert eine ausgelieferte READ-Fläche — das ist dein/Auftraggeber-Ratifikat,
nicht meine stille Annahme.** Ich baue nichts; ich lege die Wahl offen. (Der **PO-Lockout**-Dialog bleibt in
beiden Optionen — der ist die Guard-Hälfte, korrekt.)

## 6. Lifecycle / Persistenz (→ Dev-Seam)

Der Marker beschreibt einen **stehenden** Zustand („du hast dir das Recht hier entzogen"), nicht einen Blitz:
- **Setzen:** in `applyToggle`, wenn `agentId == OPERATOR_ID && !newValue`, für die getoggelte Dimension.
- **Halten:** solange der Selbst-Aus-Zustand gilt. ⚠ **Carve-out nötig:** der `EntryChanged`-Echo räumt heute
  `cellNotice - key` (`AclViewModel.kt:146-152`) — der bestätigende Echo würde den frisch gesetzten Selbst-Marker
  **sofort wegräumen** (Flackern). Der Selbst-Marker darf von **seinem eigenen bestätigenden Echo nicht** gelöscht
  werden. Dev-Wahl der Impl: (a) ein eigenes Feld `selfBlind: Set<key>` (persistiert über Echo, geräumt beim
  Re-Grant), **oder** (b) rein **abgeleitet** aus dem Zustand (`cell.agentId == operator && grant == off`) — dann
  gibt es nichts zu räumen; das „gerade getan"-Announce (§7) hängt am Toggle-Event, der stehende Marker am
  abgeleiteten Zustand. **(b) ist die sauberste** (kein Lösch-Timing, Marker = Wahrheit über den Zustand).
- **Räumen:** wenn der Operator das Recht **wieder einschaltet** (Re-Grant → Zustand nicht mehr aus → Marker weg).
  Der Undo **ist** derselbe Schalter; kein separater Dismiss nötig (optional dismissbar, Dev/UIUX2-Detail).

## 7. Accessibility

- **Announce = Polite, einmal** beim Erscheinen (`a11y_acl_self_blind_read`/`_write`). **Nicht** Assertive — nichts
  Dringendes; eine reversible Selbst-Aktion unterbricht den SR nicht. (Kontrast zu `AccessRevoked`, das terminal
  **Assertive** ist — dort wird dir *von außen* der Zugang genommen; hier tust *du* es selbst, reversibel.)
- **stateDescription:** solange der Zustand hält, trägt die Zelle die Klausel als Teil ihrer `a11y_acl_cell`-
  Beschreibung — der SR hört sie auch beim späteren Anfokussieren, nicht nur im Moment (Idiom: bestehende
  Zell-`stateDescription`).
- **Farbe nie alleiniger Träger** (WCAG 1.4.1): der Marker trägt **Text** (die Copy) + Qualifier-Tag; INFO-Ton ist
  Verstärkung, nicht der einzige Kanal.

## 8. Ton / Tokens

- **INFO / neutral.** Reuse der `acl_po_protected`-`StateMarker`-Optik (neutraler Inline-Marker), **nicht** der
  errorContainer-Banner (`state.notice`, `AclPanel.kt:133-134`). Kein WARN, kein Rot, kein Grün.
- **Fläche = garantiert-Headroom** (Matrix-Zelle auf Standard-M3-Surface, **nicht** agent-eingefärbt) → ein
  Sekundär-/INFO-Ton ist hier **sicher** (`onSurfaceVariant` vs surface = 8.69/9.80:1, `docs/COLOR-CODING.md §8`).
  Die Null-Headroom-Invariante gilt **nicht** hier — das ist keine Titelleiste. Kein neues Farb-Token nötig.
- **Glyph (falls die StateMarker-Konvention einen trägt):** neutraler INFO-Marker, kein Alarm-Glyph.

## 9. Seams (→ Coordinator-Routing)

- **Dev:** (1) `selfBlind` aus dem `lockoutPrompt`-Zweig lösen; (2) post-commit Selbst-Marker beide Dimensionen
  (§6, empf. abgeleitet); (3) Copy/Tags aus `-keys.md`/`-tags.md`; (4) READ-Reconcile B1/B2 nach PO-Entscheid (§5).
- **QA (CYP-7):** Contract-Change `SELF_BLIND_WARNING`(Dialog) → `.selfBlind`(Zell-Qualifier) — geteilt,
  PO-koordiniert (`-tags.md`).
- **UIUX2 (falls im Roster, via PO):** Announce-Politeness/optional-Dismiss-Interaktion — meine Copy/Ton/Symmetrie
  stehen; die Interaktions-Feinheit ist teilbar.

## 10. Self-Validation

- **Constraints ①–④ zeilenweise abgehakt (§2)** und gg. den echten Guard-Code geprüft (`AclViewModel.kt:197-206`),
  nicht token-plausibel: der Dialog-Zweig ist real, `selfBlind` ist real READ-gated, `cellNotice`→`StateMarker` ist
  der reale neutrale Inline-Pfad.
- **Kein Guard, kein Re-Litigate von CYP-663:** die Spec fügt **nur** Disclosure hinzu; der PO-Lockout-Guard bleibt
  unangetastet; die Code-Trennung macht die Doktrin-Zeile explizit.
- **INFO ehrlich begründet:** kein ERROR (nichts kaputt), kein EFFECT_DEFERRED (nichts latent — sofort wirksam),
  kein Scare (reversibler Selbst-Zustand). Alarm-Rot wäre Guard-durch-die-Hintertür — explizit vermieden.
- **Behavior-Change offengelegt, nicht angenommen (§5):** B1 (empfohlen) ändert eine ausgelieferte READ-Fläche →
  als PO/Auftraggeber-Ratifikat markiert, nicht stumm gezogen.
- **Reuse-first:** `cellQualifier`-Bahn + `acl_po_protected`-Präzedenz + Zell-`stateDescription` + `acl_read`/
  `acl_write`-Vokabular; 4 Keys, 1 `CellQualifier`-Member, 0 neue Top-Level-Tags, 0 neue Farb-Token.
- **Kein Bau:** docs-only auf `feature/acl-self-blind-advisory-spec`, off develop `1da14371`.
