# ACL Operator-Selbst-Erblindung — Disclosure-Advisory (Spec)

> Owner: UIUX-Designer · **kein Ticket** (④ aus `residual-exposure-disclosure-audit.md`) · Stand 2026-07-17 ·
> **B1 vom Auftraggeber ENTSCHIEDEN (2026-07-17) — umsetzungsreif für Dev; ich baue nichts (Spec + Zähne).**
> Gegroundet READ-ONLY gg. develop `1da14371` (`acl/AclViewModel.kt`, `acl/AclPanel.kt`, `acl/AclMatrixTags.kt`,
> `strings.xml`). Companion: `-keys.md` (Copy), `-tags.md` (testTags).

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

## 5. ★ B1 — ENTSCHIEDEN (Auftraggeber, 2026-07-17)

Der Auftraggeber hat **B1** ratifiziert — diesmal mit **vollständiger** Beschreibung der Fläche (inkl. der
Tatsache, dass READ **heute** einen Halte-Dialog trägt, was ihm beim ersten Entscheid fehlte). Mit vollständiger
Info gilt **„advisory, not a block" auch für READ.**

**⟹ `selfBlind` kommt GANZ aus dem Dialog-Zweig — READ und WRITE beide post-commit Inline-Advisory.** Eine
Mechanik, eine Doktrin, kein erzwungenes Confirm. Der **PO-Lockout-Dialog bleibt** (Guard-Hälfte, 409-gedeckt) —
nur der **selfBlind-Zweig** in den Dialog fällt weg, nicht der Dialog selbst.

Konkret (`AclViewModel.kt:198`): `if (poLockout || selfBlind)` → **`if (poLockout)`**; `selfBlind` wird in
`applyToggle` (post-commit) zum Marker, beide Dimensionen.

### 5.1 ★ Retire ist PO-KOORDINIERT, nicht einseitig (geteilter QA-Contract, CYP-7/po2)

`acl_self_blind_warning` + Tag `SELF_BLIND_WARNING` hängen an CYP-7 und werden von Team-2s QA mitgelesen. **Ich
retire sie NICHT einseitig.** Ich baue die neue Fläche und benenne **exakt** die Ablösung; der **PO** fährt sie
mit po2. Die exakte Map:

| Fällt weg (retire) | Ersatz | Ort |
|---|---|---|
| **Key** `acl_self_blind_warning` (Dialog-Copy, „_warning"/READ-zentrisch) | `acl_self_blind_read` **+** `acl_self_blind_write` (dimensions-spezifisch, INFO) | `strings.xml` DE+EN |
| **Tag** `SELF_BLIND_WARNING` = `aclMatrix.selfBlindWarning` (Dialog-Node) | `CellQualifier.SELF_BLIND` → `aclMatrix.cell.<ch>.<ag>.selfBlind` (Zell-Marker) | `AclMatrixTags` |
| *(neu, kein Retire)* | `a11y_acl_self_blind_read` + `_write` | `strings.xml` DE+EN |

**Bleibt unverändert (NICHT retiren):** `LOCKOUT_DIALOG` / `.confirm` / `.cancel` (der Dialog lebt für den
PO-Lockout weiter) und dessen Copy `acl_po_lockout_warning` / `acl_po_protected`. **Nur** der selfBlind-Ast ins
Dialog-Konstrukt verschwindet. Der QA-Assertion-Umzug: vom Dialog-Node `aclMatrix.selfBlindWarning` auf den
Zell-Qualifier `aclMatrix.cell.<ch>.<ag>.selfBlind` — **das ist der geteilte Contract-Change, den der PO mit po2
fährt.**

### 5.2 ★ Acceptance-Zähne — OUTCOME, nicht Modell (PO-Vorgabe)

Der Zahn pinnt das **Ergebnis**, nicht die Berechnung — sonst bliebe er grün, während der Dialog weiter hält
(CYP-657-Klasse: Modell-grün, Fläche-falsch). Zwei Zähne, beide über die **echte** Fläche:

- **Zahn A — der Dialog erscheint NICHT MEHR, die Aktion committed durch, die Advisory wird gerendert.** Beim
  Toggle des **eigenen** READ **oder** WRITE auf aus:
  1. **kein** `aclMatrix.selfBlindWarning`/`LOCKOUT_DIALOG`-Node erscheint (der Halte-Dialog ist weg),
  2. die Zelle **committed** (optimistisch → `pending` → `enforced`; der Grant-Wert kippt tatsächlich),
  3. der **`.selfBlind`-Marker + die dimensions-richtige Copy** wird gerendert.
  **★ Mutation:** hängt man `selfBlind` wieder an den `if (poLockout || selfBlind)`-Dialog-Zweig **⇒ MUSS röten**
  (der Dialog erscheint / die Aktion hält / der Marker fehlt). Ein Modell-Zahn („`selfBlind == true` rechnet zur
  Advisory") bliebe **grün**, während der Dialog hält — **verboten.** Der Zahn assertet den Node-Zustand, nicht die
  Zwischenvariable.
- **Zahn B — der Rückweg ist ECHT (pin den Undo, nicht nur den Text).** Eine Advisory, die „du hast dir READ
  entzogen" sagt, ohne dass „wieder einschalten" **funktioniert**, ist **schlimmer** als der Dialog — sie meldet
  ehrlich und lässt den Nutzer allein. Nach Selbst-Erblindung einer Zelle → **denselben Schalter wieder an**:
  1. der Grant ist **tatsächlich wiederhergestellt** (`canRead`/`canWrite` == true, committed),
  2. der **`.selfBlind`-Marker verschwindet** (Zustand nicht mehr „aus").
  **★ Mutation:** ein Re-Grant, der den Grant **nicht** wiederherstellt, **oder** ein Marker, der **nach** dem
  Re-Grant **bleibt** ⇒ **MUSS röten.** Der Round-Trip aus↔an ist gepinnt, nicht nur die Copy.

> Diese zwei Zähne sind der Kern der Umsetzung — sie unterscheiden „B1 gebaut" von „B1 behauptet". Tester (CYP-7)
> assertet sie über die realen Nodes/Grant-Werte, nicht über die Advisory-Berechnung.

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

- **Dev:** (1) `if (poLockout || selfBlind)` → **`if (poLockout)`** (selfBlind ganz aus dem Dialog-Zweig); (2)
  post-commit Selbst-Marker **beide** Dimensionen in `applyToggle` (§6, empf. abgeleitet-aus-State); (3) Copy/Tags
  aus `-keys.md`/`-tags.md`; (4) **Zahn A + Zahn B (§5.2) — outcome-basiert**, nicht Modell.
- **QA (CYP-7) — PO-koordiniert (nicht einseitig):** Assertion-Umzug `aclMatrix.selfBlindWarning`(Dialog-Node) →
  `aclMatrix.cell.<ch>.<ag>.selfBlind`(Zell-Qualifier); Retire-Map in §5.1. **Der PO fährt die Ablösung mit po2;
  ich benenne nur exakt, was weg-/dazukommt.** `LOCKOUT_DIALOG*` (PO-Lockout) bleibt.
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
- **B1 entschieden (Auftraggeber 2026-07-17), nicht stumm angenommen (§5):** die Fläche-ändernde READ-Reconcile war
  bis zur Ratifikation als Frage offengelegt; jetzt umsetzungsreif. Der Retire ist **PO-koordiniert** (§5.1), nicht
  einseitig — ein Tag, der unter Team-2 verschwindet, wäre genau die Drift, die der Merge-Owner verhindert.
- **Zähne sind OUTCOME, nicht Modell (§5.2):** Zahn A pinnt „Dialog weg + committed + Marker da" mit der Mutation
  „Dialog-Zweig zurück ⇒ rot"; Zahn B pinnt den **echten Rückweg** (Re-Grant stellt wieder her + Marker weg). Kein
  Modell-Zahn, der grün bliebe, während der Dialog hält (CYP-657-Klasse).
- **Reuse-first:** `cellQualifier`-Bahn + `acl_po_protected`-Präzedenz + Zell-`stateDescription` + `acl_read`/
  `acl_write`-Vokabular; 4 Keys, 1 `CellQualifier`-Member, 0 neue Top-Level-Tags, 0 neue Farb-Token.
- **Kein Bau:** docs-only auf `feature/acl-self-blind-advisory-spec`, off develop `1da14371`. B1 umsetzungsreif für Dev.
