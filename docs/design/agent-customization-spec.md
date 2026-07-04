# Per-Agent-Customization — UX/UI-Design-Spec (CYP-209)

> Owner: UIUX-Designer · Story **CYP-209** (Design-Lane von Epic **CYP-208** Per-Agent-Customization) · Stand: 2026-07-04
> Status: **Vorschlag → Referenz** · speist **Backend CYP-210** (color-Shape) + **Dev CYP-211** (Impl)
> Begleitdateien: `agent-customization-keys.md`, `agent-customization-tags.md`, `agent-customization-tokens.json`
> **Grounded gg. develop `56567a1`** (echter Code gelesen): `window/WindowManager.kt`, `window/WindowTestTags.kt`,
> `comm/SenderPalette.kt` (`SenderColor`/`SenderPalette`), `core/model/ColorSlot.kt`, `core/model/CommModel.kt` (`Agent`),
> `core/model/AgentMgmtModel.kt` (`AgentEdit`/`AgentDetail`/`NewAgentSpec`), `agentmgmt/AgentManagementPanel.kt`
> (`LabeledField`/`RolePicker`/Dialoge/`agent_edit_effect_hint`), `settings/SettingsPanel.kt` (`SectionHeading`), `ui/TonedHint.kt`.
> **Stil-Vorgabe (Auftraggeber):** **current-interim look + M3** — **KEIN** maritime-Redesign (bleibt separat/später).
> **Auftraggeber-Wahl:** **Palette-Swatches (8 `SenderPalette`-Slots) + custom-hex**, interim-Style.

---

## 0. Umfang & Leitidee

Agenten unterscheidbarer machen: **editierbarer Anzeigename + Farbe + ein per-Window-Settings-Screen**, plus die
**Farb-Derivations-Regeln** (Base-Color → background/text/border, WCAG-sicher) und **Titelbar-Theming**.

**Vier Deliverables:**
1. **Drei-Punkte-Settings-Button** in der Window-Titelbar (§3).
2. **Settings-Screen** (Overlay-Panel, konsistent zu den bestehenden Agent-Dialogen): **Name** (editierbar; `id` read-only =
   stabile Identität) · **Farbe** (Palette-Swatches + custom-hex mit Kontrast-Guard) · **CLAUDE.md** (Persona-View/Edit + der
   bestehende „gespeichert≠aktiv – neu starten"-Hinweis) (§4).
3. **⭐ Farb-Derivations-Spec** — deterministische, compose-freie **`:core`-Utility** (server+client-shared wie `colorSlot`):
   Base → {background, text/onColor, border}, WCAG-kontrast-sicher; erweitert `SenderColor` um `borderColor` (§5).
4. **Titelbar-Theming** — wie die Agenten-Farbe die Titelbar färbt (bg/text/border, Focus/Unfocus), ersetzt das hardcoded
   `primary`/`surfaceVariant` (§6).

**Drei Ehrlichkeits-Anker (mein Kern — verbindlich, §7):**
- **`id` read-only = stabile Identität; Umbenennen ist kosmetisch** (ändert **nie** Identität/Rolle/ACL/Capabilities).
- **Farbe = Identität/Dekoration, NIE Garantie/Berechtigung/Status** (CYP-14-Prinzip trägt weiter; Status-Hues aus CYP-12
  reserviert).
- **Live vs. deferred ehrlich:** Name + Farbe wirken **sofort** (Anzeige); **CLAUDE.md-Persona = restart-deferred**
  (`agent_edit_effect_hint`, EFFECT_DEFERRED) — nie „aktiv" vorspielen, bevor der Agent neu startet.

---

## 1. Reuse-Bestand (gg. `56567a1` verifiziert)

| Baustein | Datei | Reuse in CYP-209 |
|---|---|---|
| `SenderColor(avatarFill, onAvatar, nameAccent)` | `comm/SenderPalette.kt` | **+ `borderColor`** (§5.4); sonst unverändert |
| `SenderPalette` (8 Slots + PO) | `comm/SenderPalette.kt` | die 8 Swatches der Palette (§4.2) |
| `colorSlot(id)` (FNV-1a, `:core`, compose-frei) | `core/model/ColorSlot.kt` | Default-Farbe wenn kein Override; Muster für die neue Derivation |
| `Agent(id, name, role, …)` | `core/model/CommModel.kt` | **+ nullable color-Feld** (Shape = CYP-210, §5.5) |
| `LabeledField` | `agentmgmt/AgentManagementPanel.kt` | Name-Feld, custom-hex-Feld |
| `RolePicker`-Muster (Row + selektierbare Optionen) | dito | Vorbild für Swatch-Picker (§4.2) |
| `SectionHeading(text)` (titleMedium + `heading()`) | `settings/SettingsPanel.kt` | Sektions-Köpfe im Panel |
| `TonedHint(text, tone, tag)` | `ui/TonedHint.kt` | GATED (Gate), EFFECT_DEFERRED (Restart), INFO (Advisory), ERROR (invalid) |
| `agent_edit_effect_hint` (EFFECT_DEFERRED) | strings.xml | **Reuse** für CLAUDE.md-Persona (kein zweiter Restart-Mechanismus) |
| `workspace_operator_only` (GATED) | strings.xml | **Reuse** für den Operator-Gate-Hinweis |
| `WindowTestTags` | `window/WindowTestTags.kt` | **+ `settings(id)`** für den Titelbar-Button (§3) |

**Anti-Divergenz (verbindlich):** Die CLAUDE.md-/Persona-Bearbeitung existiert schon in `AgentManagementPanel` (Edit-Dialog,
`agent_edit_effect_hint`). Das per-Window-Panel darf **keinen zweiten, divergenten Persona-Editor** werden: es schreibt Persona
über **denselben** Pfad (`PUT /api/agents/{id}`, `AgentEdit.persona`) und reused den **gleichen** Restart-Hinweis. Name/Farbe
gehen über die **neue** CYP-210-Shape. Eine Quelle je Feld, kein dritter One-off (§8 Reconcile).

---

## 2. Wo es lebt

Zwei berührte Flächen — beide bestehend/erweitert, **kein** neues Fenster-System:
- **Window-Titelbar** (`WindowManager.FloatingWindow`, `window/WindowManager.kt`) — bekommt (a) den Drei-Punkte-Button und
  (b) das Farb-Theming.
- **Ein neues Overlay-Panel** `AgentSettingsPanel` (modal, single-instance, im Stil der `AgentManagement`-Dialoge) — geöffnet
  vom Titelbar-Button für **den** Agenten dieses Fensters.

---

## 3. Deliverable 1 — Drei-Punkte-Settings-Button (Titelbar)

Heute enthält die Titelbar-`Row` (`WindowManager.kt:490`) nur `Text(title)` + optional `WindowBadgeView` (Badge-Slot,
`:501`). Neu: ein **Drei-Punkte-Button (⋮)** am **Zeilen-Ende** (nach dem Badge), M3-„more"-Affordanz (interim-Style).

- **Icon/Affordanz:** ⋮ (vertikaler 3-Punkt) — Text-Glyph/`Icon`, konsistent M3. **Nicht** Farbe-allein: es ist ein Button mit
  `contentDescription` `a11y_agent_settings_open` „Einstellungen für %1$s öffnen".
- **Themed:** der Button sitzt **auf** der gefärbten Titelbar → sein Content nutzt die abgeleitete **`onColor`** (§6), damit er auf
  jeder Agenten-Farbe lesbar bleibt (≥3:1 als Icon/large).
- **Tag:** `window.<id>.settings` (Erweiterung von `WindowTestTags`).
- **Operator-Gate (§4.5):** Default = der Button ist sichtbar, aber die **Edits** im Panel sind operator-gated (disabled +
  GATED). Ob der Button für Nicht-Operatoren strukturell entfällt statt read-only zu öffnen = **§-Ask 3** (Reconcile mit
  CYP-80-MEMBER-Scope). Default in dieser Spec: **sichtbar → read-only-Panel für Nicht-Operator**.
- **Platzierung/Z-Order:** Teil der Titelbar-`Row` (kein Overlay über dem Content) → keine Fenster-Chrome-Z-Order-Probleme.

---

## 4. Deliverable 2 — Settings-Screen (Overlay-Panel)

Ein modales Overlay im Stil der `AgentManagement`-Dialoge (`AlertDialog`-konsistent): `SectionHeading` je Sektion,
`LabeledField`/Swatch-Picker als Eingaben, `TonedHint` für Gate/Restart/Advisory. Titel `agent_settings_title` „„%1$s" anpassen".

### 4.1 Sektion Identität — Name (editierbar) + `id` (read-only)

- **Anzeigename:** `LabeledField`, Label `agent_display_name_label` „Anzeigename", Tag `agentSettings.name.input`,
  a11y `a11y_agent_display_name`. Editierbar (operator-gated). Wirkt **sofort** (Anzeige) → **kein** Restart-Hinweis.
- **`id` read-only daneben:** `agent_id_stable_label` „ID (fest)" + der `id`-Wert als **nicht-editierbarer** Text
  (Tag `agentSettings.idReadonly`). **Ehrlichkeits-Anker:** der Name ist ein **Label**, `id` ist die **stabile Identität** —
  Umbenennen ändert **nicht** die Identität/ACL/Rolle. (Reused Konzept aus `agent_edit_id_locked_hint`.)
- **Avatar-Initialen** kommen aus dem **Namen** (bestehend, CYP-14) → Umbenennen ändert die Initialen; die **Farbe** bleibt
  stabil (aus `id`/Override, nicht aus dem Namen) — kein Farb-Springen beim Umbenennen.
- **Reconcile CYP-88:** `AgentEdit` ließ `name` bewusst weg (Umbenennen im Config-Dialog gesperrt). CYP-209 führt den
  editierbaren **Anzeigenamen hier** ein → §-Ask 1 (bestätigen, dass CYP-209 das ablöst; `id` bleibt die unveränderliche
  Identität — die CYP-88-Intention „Identität nicht umbenennen" bleibt über den read-only `id` gewahrt).

### 4.2 Sektion Farbe — Palette-Swatches + custom-hex

`SectionHeading` `agent_color_section` „Farbe". Zwei Eingabe-Modi, ein Ergebnis (die gewählte Base-Color):

- **Palette (`agent_color_palette_label` „Palette"):** die **8 `SenderPalette`-Slots** als Swatch-Reihe (Vorbild `RolePicker`:
  eine `Row`/`FlowRow` selektierbarer Elemente). Jeder Swatch = Farbfläche (der Slot-`avatarFill`) mit **Auswahl-Indikator, der
  NICHT Farbe-allein ist** (Ring/Häkchen + `selected`-Semantik), a11y `a11y_agent_color_swatch` „Farbe %1$s" + Selected-State.
  Tags `agentSettings.swatch.<index>` (index 0–7, punktfrei). Der aktive Slot ist markiert.
- **Eigener Farbwert (`agent_color_custom_label` „Eigener Farbwert (Hex)"):** `LabeledField`, Tag `agentSettings.customHex.input`,
  a11y `a11y_agent_color_custom`. **Kontrast-Guard (§5.6):**
  - **Format-Validierung:** `#RRGGBB` → sonst `isError=true` + `TonedHint(agent_color_custom_invalid, ERROR)` „Ungültiger
    Hex-Wert (z. B. #3B82F6)".
  - **Live-Vorschau der *abgeleiteten* (evtl. angepassten) Farbe** (§4.3), nicht der rohen Eingabe.
  - **Advisory, wenn die Base für Lesbarkeit angepasst wurde:** `TonedHint(agent_color_adjusted_hint, INFO)` „Für Lesbarkeit
    angepasst (Kontrast)" — ehrlich, dass die effektive Farbe von der Eingabe abweicht (Tag `agentSettings.contrastAdvisory`).
  - **Advisory, wenn die Base einer reservierten Status-Farbe ähnelt** (CYP-12 running/ok/error/waiting):
    `TonedHint(agent_color_status_like_hint, INFO)` „Ähnelt einer Status-Farbe – Farbe ist Identität, kein Status" (**optional**,
    §-Ask 4). Kein Hard-Block (der Operator behält die Kontrolle) — nur ehrliche Aufklärung.
- **Kein Override → Default:** ist keine Farbe gesetzt, gilt die deterministische `colorSlot(id)`-Farbe (bestehendes Verhalten,
  fail-safe backward-compatible). „Zurücksetzen" = Override entfernen.

### 4.3 Live-Vorschau (Derived-Scheme)

Eine **Beispiel-Titelbar-Chip** zeigt das **abgeleitete** Scheme (background = Base, text = onColor, border) **live**, damit der
Operator das *echte* Ergebnis (inkl. Kontrast-Anpassung) vor dem Speichern sieht. Tag `agentSettings.preview`,
a11y `a11y_agent_color_preview` „Farbvorschau: %1$s".

### 4.4 Sektion CLAUDE.md (Persona) — reuse + Restart-Ehrlichkeit

- **Persona-View/Edit:** `LabeledField(singleLine=false)`, Label **reused** `agent_add_persona_label` „Persona / CLAUDE.md",
  Tag `agentSettings.persona.input`, a11y `a11y_agent_add_persona` (reuse). Prefill aus `AgentDetail.persona`
  (`GET /api/agents/{id}`).
- **Restart-Hinweis (reuse, verbindlich):** bei geänderter Persona → `TonedHint(agent_edit_effect_hint, EFFECT_DEFERRED,
  agentSettings.effectHint)` „Gespeichert. Wirkt erst beim nächsten Start des Agenten …". **Kein zweiter Restart-Mechanismus**
  (§1 Anti-Divergenz). Schreibt über `PUT /api/agents/{id}` (`AgentEdit.persona`) — derselbe Pfad wie der Config-Dialog.
- **Ehrlichkeits-Kern:** Persona ist **restart-deferred**; Name/Farbe sind **sofort**. Das Panel zeigt den EFFECT_DEFERRED-Hinweis
  **nur** an der Persona (nicht an Name/Farbe) → die „garantiert/sofort vs. advisory/deferred"-Grenze ist sichtbar getrennt.

### 4.5 Save/Cancel + Operator-Gate

- **Save** `agent_save` (reuse), Tag `agentSettings.save`; **Cancel** `agent_cancel` (reuse), Tag `agentSettings.cancel`.
- **Operator-Gate (reuse-Muster aus `AgentManagementPanel`):** alle Edit-Affordanzen `enabled = state.editable`
  (`isOperatorAccess`), + `TonedHint(workspace_operator_only, GATED, agentSettings.gateHint)` wenn nicht editable. Nicht-Operator
  = read-only-Sicht (Name/id/Farbe/Persona sichtbar, keine Mutation). Persona-Sichtbarkeit für Nicht-Operator/MEMBER =
  **§-Ask 3** (Reconcile CYP-80; Default: read-only sichtbar, Persona = Rollen-Text, kein Secret).

---

## 5. Deliverable 3 — ⭐ Farb-Derivations-Spec (`:core`, compose-frei, shared)

**Prinzip (wie `colorSlot`, CYP-14-Split):** die **Rechen-Utility** lebt in **`:core`** und ist **compose-frei** — sie operiert
auf **ARGB-Ints** (`0xRRGGBB`), deterministisch, **server+client-shared** (Backend CYP-210 validiert/formt damit; Client
rendert damit — **eine** Formel, kein Drift). `:app:shared` wrappt die Int-Ergebnisse in Compose-`Color` und baut `SenderColor`.

### 5.1 WCAG-Grundfunktionen (in `:core`)

```
relLuminance(rgb): Double         // WCAG 2.x: sRGB linearisieren, L = 0.2126·R + 0.7152·G + 0.0722·B
contrastRatio(a, b): Double       // (Lheller + 0.05) / (Ldunkler + 0.05)  ∈ [1, 21]
```

### 5.2 Ergebnis-Typ

```
data class DerivedScheme(
  val background: Int,   // = Base (bzw. für Lesbarkeit minimal angepasst, s. 5.3)
  val onColor:    Int,   // Text/Icon auf dem Background (schwarz ODER weiß)
  val border:     Int,   // sichtbare Kante
  val adjusted:   Boolean // true = Background wurde für Kontrast nachgezogen (→ Advisory §4.2)
)
```

### 5.3 Derivations-Regeln (deterministisch)

1. **onColor** = `contrastRatio(WHITE, base) ≥ contrastRatio(BLACK, base) ? WHITE : BLACK` (die kontraststärkere der beiden).
2. **Selbstkorrektur (Kontrast-Guarantie Text ≥ 4.5:1):** ist `contrastRatio(onColor, base) < 4.5` (Base mittlerer Luminanz),
   **ziehe den Background** in fixen L-Schritten von der Mitte weg (dunkler, falls onColor=WHITE; heller, falls onColor=BLACK),
   **bis ≥ 4.5:1** erreicht ist → `adjusted = true`. Der **Hue bleibt erhalten**, nur die Lightness wird nachgezogen (die
   Eingabe wird nie verworfen, nur lesbar gemacht). Deterministische Obergrenze der Schritte; erreicht sie das Ziel nicht
   (extrem gesättigte Mitteltöne), gilt der beste erreichte Wert + `adjusted=true` (Vorschau + Advisory zeigen die Wahrheit).
3. **border** = Base um ein **festes L-Delta** in Richtung höherer Kontrast verschoben, so dass **≥ 3:1** gegen eine neutrale
   Fläche (Surface) in **beiden** Themes erreicht wird → die Fensterkante ist auf hellem **und** dunklem Hintergrund sichtbar.

**Kontrast-Ziele (aus CYP-14):** Text/onColor **≥ 4.5:1**, border/large **≥ 3:1**. Dark-Default **und** Light.

### 5.4 `SenderColor` + `borderColor` (`:app:shared`)

`SenderColor` wird um **`borderColor: Color`** erweitert (heute 3 Felder → **4**). Die 8 Palette-Slots bekommen einen
`borderColor` (aus der Derivation ihres `avatarFill`, einmalig hinterlegt oder via `derive()` berechnet). `avatarFill` bleibt der
Background, `onAvatar` = onColor, `nameAccent` unverändert (Name-Text auf App-Surface — eigene, bestehende Achse).

### 5.5 Base-Color-Herkunft & Persistenz (feeds CYP-210)

Die Base-Color je Agent kommt aus: **(a)** Palette-Slot-Pick, **(b)** custom-hex, **(c)** kein Override → `colorSlot(id)`-Default.
**Persistenz-Shape = Backend CYP-210** — meine **Empfehlung** (nicht-blockierend, §-Ask 2): ein nullable, additives Feld an
`Agent` als kleine **diskriminierte** Form
`color: AgentColor?` mit `{ kind: PALETTE|CUSTOM, ref: String }` (PALETTE-`ref` = Slot-Index → **theme-adaptiver** SenderColor;
CUSTOM-`ref` = Hex → via `derive()` kontrast-sicher). `null`/absent ⇒ `colorSlot(id)`-Default (backward-compatible, fail-safe).
Reines Hex-only-Feld wäre simpler, verlöre aber die dark/light-Adaptivität der Palette — deshalb die diskriminierte Empfehlung.
**Backend besitzt die finale Shape; meine Spec = Referenz + Derivations-Regeln.**

### 5.6 Kontrast-Guard (Zusammenfassung)

Der Guard lässt **nie** eine unlesbare Kombination durch: Format-Reject (invalid hex) + Selbstkorrektur (5.3.2, Text ≥ 4.5:1) +
**ehrliche Vorschau des Effektiv-Ergebnisses** + Advisory bei Anpassung/Status-Ähnlichkeit. Determinismus (5) garantiert:
**gleiche Base → gleiches Scheme** auf Server und Client, in beiden Themes.

---

## 6. Deliverable 4 — Titelbar-Theming (ersetzt hardcoded `primary`/`surfaceVariant`)

Heute: `background = isFocused ? primary : surfaceVariant`; `text = isFocused ? onPrimary : onSurfaceVariant`
(`WindowManager.kt:472-498`). Neu: aus dem `DerivedScheme` der Agenten-Farbe:

| Zustand | Background | Text/Icon (Titel, Badge, ⋮-Button) | Border |
|---|---|---|---|
| **Focused** | `scheme.background` (volle Agenten-Farbe) | `scheme.onColor` (≥4.5:1) | `scheme.border` (≥3:1) |
| **Unfocused** | `scheme.background` **gedimmt** (lerp Richtung Surface, fixer Dim-Faktor) | onColor gegen den **gedimmten** bg **neu abgeleitet** (Kontrast bleibt) | border gedimmt |

- **Elevation** (tonal/shadow, `:464-465`) bleibt unverändert (Focus-Signal zusätzlich zur Farbe → nicht Farbe-allein).
- **Focus/Unfocus nie nur über Farbe:** Elevation + (optional) Border-Stärke tragen den Fokus mit (WCAG 1.4.1 / 1.4.11).
- **Titel-Text** behält `maxLines=1`/Ellipsis; Badge + ⋮-Button erben `onColor` → lesbar auf jeder Agenten-Farbe.
- **Default-Agenten** (kein Override) nutzen ihr `colorSlot(id)`-Scheme → auch ohne Customization ist die Titelbar ab sofort
  agenten-farbig statt uniform `primary` (konsistent mit den Avatar-Farben im Comm-Panel — eine Identitäts-Farbe pro Agent).
- **PO/Hub:** nutzt seinen reservierten PO-Slot (ruhig-autoritativ), wie in CYP-14.

---

## 7. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste für die UX-QA)

1. **`id` read-only = stabile Identität:** der editierbare Anzeigename steht **neben** dem nicht-editierbaren `id`; Umbenennen
   ändert **nie** Identität/Rolle/ACL/Capabilities/Farb-Derivations-Key (Farbe stabil aus `id`/Override, nicht aus Name).
2. **Farbe ≠ Garantie/Berechtigung/Status:** Recolor ändert **keine** Capability/ACL; Custom-Hue, das einer Status-Farbe ähnelt,
   löst eine ehrliche Advisory (kein Status-Vortäuschen); Farbe ist Identität/Dekoration (CYP-14).
3. **Live vs. deferred getrennt:** Name + Farbe **sofort** (kein Restart-Hinweis); CLAUDE.md-Persona **restart-deferred**
   (`agent_edit_effect_hint`, EFFECT_DEFERRED) — nie „aktiv" vor Neustart.
4. **Kontrast garantiert & ehrlich:** Derived-Scheme erfüllt Text ≥4.5:1 / border ≥3:1 (beide Themes); die Vorschau zeigt das
   **effektive** (evtl. angepasste) Ergebnis; Anpassung wird per Advisory offengelegt (`adjusted → agent_color_adjusted_hint`).
5. **Determinismus/kein Drift:** Derivation `:core`, compose-frei, server+client-shared (wie `colorSlot`) → gleiche Base →
   gleiches Scheme überall; kein Runtime-Seed.
6. **Keine Secrets:** Name/Farbe/Persona sind keine Secrets (Persona = Rollen-Text, keine Credentials); der API-Key bleibt
   ausschließlich im operator-only/maskierten `SettingsPanel` (nicht berührt).
7. **Eine Quelle je Feld (Anti-Divergenz):** Persona-Edit teilt Pfad + Restart-Hinweis mit `AgentManagement`; kein zweiter
   divergenter Persona-Editor, kein dritter Farb-/Namens-One-off.
8. **Nicht-Farbe-Träger überall:** Swatch-Auswahl (Ring/Häkchen + a11y), Focus (Elevation + Border), Tone-Glyphen — Farbe nie
   alleiniger Signalträger (WCAG 1.4.1).

---

## 8. Reconcile / Hand-off

- **Backend CYP-210:** besitzt die persistierte **color-Shape** (§5.5) + kann `derive()`/Validierung server-seitig nutzen
  (shared `:core`). Meine Derivations-Regeln (§5) + die color-Shape-Empfehlung sind die Referenz.
- **Dev CYP-211:** implementiert Button (§3), Panel (§4), `SenderColor.borderColor` + `derive()`-Wrapper (§5.4), Titelbar-Theming
  (§6). **Anti-Divergenz (§1/§7.7):** Persona über den bestehenden `PUT /api/agents/{id}`-Pfad + `agent_edit_effect_hint`;
  Name/Farbe über die CYP-210-Shape. `name`-Edit an **einer** Stelle (dieses Panel), nicht zusätzlich divergent im Config-Dialog.
- **Shared-Key/-Tag-Drift (CYP-7):** neue `agent_*`/`a11y_agent_*`-Keys + `agentSettings`-Tags + `window.<id>.settings` landen
  **mit** dem konsumierenden CYP-211-Slice (PO koordiniert), nicht isoliert.

---

## 9. §-Asks (an PO/Backend — nicht-blockierend; Spec baut auf den Defaults)

1. **Editierbarer Anzeigename löst CYP-88-Name-Lock ab?** — Default: **ja** (Anzeigename editierbar hier; `id` read-only bleibt
   die unveränderliche Identität, CYP-88-Intention gewahrt). Bestätigen.
2. **color-Shape (CYP-210):** meine Empfehlung = nullable diskriminierte `AgentColor{kind:PALETTE|CUSTOM, ref}` (§5.5); Hex-only
   simpler, verlöre Palette-Theme-Adaptivität. Backend entscheidet die finale Shape.
3. **Operator-Gate/Nicht-Operator-Sicht:** Default = Button sichtbar → **read-only-Panel** für Nicht-Operator (Edits
   disabled+GATED), Persona read-only sichtbar (Rollen-Text, kein Secret). Reconcile mit CYP-80-MEMBER-Scope: strukturelle
   Button-Auslassung statt read-only? Persona-Sicht für MEMBER erlaubt?
4. **Status-Farb-Advisory** (`agent_color_status_like_hint`): optionale Ehrlichkeits-Advisory, wenn eine Custom-Farbe einer
   CYP-12-Status-Hue ähnelt. Aufnehmen (Default: ja, INFO/Advisory, kein Block) oder weglassen?
5. **Name live vs. deferred:** Default = Anzeigename wirkt **sofort** (Anzeige-Label; `Agent.name` „informational on the wire").
   Falls das Backend `name` in den Spawn/Env koppelt, würde Name **auch** restart-deferred → dann den EFFECT_DEFERRED-Hinweis
   auch an den Namen. Bestätigen, dass Name reines Display ist.

---

## 10. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** siehe `agent-customization-keys.md` (Ziel: schlank; Persona/Save/Cancel/Restart/Operator-Gate **reused**).
- **Neue Tags:** `window.<id>.settings` (1, Window-Area) + `agentSettings`-Area (Panel-intern, single-instance) — siehe
  `agent-customization-tags.md`.
- **Neue Tokens:** `SenderColor.borderColor` (1 Feld) + Derivations-Konstanten (Kontrast-Ziele 4.5/3.0, Dim-Faktor, border-Δ);
  **keine neuen Palette-Farben** (Reuse) — siehe `agent-customization-tokens.json`.
- **Kein neues Fenster-System, kein neuer Persona-Endpoint, kein zweiter Restart-Mechanismus.**

*Nur Design/Spec/Verifikation — keine Implementierung. Key/Tag-Landing mit dem CYP-211-Slice timen (CYP-7, PO koordiniert).*
