# testTag-Schema — Per-Agent-Customization (CYP-209)

> Owner: UIUX-Designer · Story **CYP-209** · Stand: 2026-07-04 · Status: Vorschlag
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte
> `[A-Za-z0-9-]+` (**keine Punkte** — Trenner). Verifiziert gg. `window/WindowTestTags.kt`, `agentmgmt/AgentMgmtTags.kt`,
> `settings/SettingsTags.kt` @ develop `56567a1`.
> **Vertrag zwischen Dev und QA (CYP-7):** diese Tags sind eine **API**, nicht still umbenennen; koordiniert über den PO.

Zwei Bereiche: (1) der **Titelbar-Button** erweitert die bestehende **Window-Area**; (2) das **Overlay-Panel** ist eine neue,
**single-instance** Area `agentSettings` (modal, ein Panel zur Zeit — Muster wie `settings`/`agentMgmt`).

---

## 1. Titelbar-Button — Erweiterung `WindowTestTags` (Window-Area)

| Element | testTag | Zweck |
|---|---|---|
| Settings-⋮-Button | `window.<id>.settings` | Öffnet das per-Window Settings-Panel; sitzt in der Titelbar-Row (nach dem Badge) |

> **Vorschlag `WindowTestTags`-Ergänzung:** `fun settings(id: String) = "window.$id.settings"` — analog zu `titleBar`/`content`.
> Die Titelbar selbst (`window.<id>.titlebar`) + das Theming brauchen **keinen** neuen Tag (QA prüft Theming via Button/Panel +
> die `:core`-Derivation-Unit-Tests, s. §4).

---

## 2. Settings-Panel — neue Area `agentSettings` (single-instance)

| Element | testTag | Zweck |
|---|---|---|
| Panel-Container | `agentSettings.panel` | modales Overlay |
| Anzeigename-Feld | `agentSettings.name.input` | editierbarer Name (§4.1) |
| `id` read-only | `agentSettings.idReadonly` | stabile Identität (nicht editierbar) |
| Farb-Sektion | `agentSettings.color` | Sektions-Container Farbe |
| Palette-Swatch | `agentSettings.swatch.<index>` | 1 je Slot, `index` = 0–7 (punktfrei; **nicht** `sender.0` — Punkt verboten) |
| custom-hex-Feld | `agentSettings.customHex.input` | eigener Farbwert |
| Kontrast/Status-Advisory | `agentSettings.contrastAdvisory` | „für Lesbarkeit angepasst" / „ähnelt Status-Farbe" (INFO) |
| custom-hex-Fehler | `agentSettings.customHex.error` | invalid-hex (ERROR) |
| Farb-Vorschau | `agentSettings.preview` | Beispiel-Titelbar mit dem Derived-Scheme (bg/text/border) |
| Persona-Feld | `agentSettings.persona.input` | CLAUDE.md-View/Edit (§4.4) |
| Restart-Hinweis | `agentSettings.effectHint` | „gespeichert≠aktiv – neu starten" (EFFECT_DEFERRED), **nur** bei Persona-Änderung |
| Operator-Gate-Hinweis | `agentSettings.gateHint` | GATED, wenn nicht editable |
| Speichern | `agentSettings.save` | schreibt Name/Farbe (CYP-210) + Persona (bestehender Pfad) |
| Abbrechen | `agentSettings.cancel` | schließt ohne Speichern |

> **Selektor-Hinweis Swatch:** `agentSettings.swatch.<index>` mit `index ∈ 0..7` (der Palette-Reihenfolge). Der ausgewählte
> Swatch trägt zusätzlich die `selected`-Semantik (nicht Farbe-allein). Kein separater `.selected`-Tag nötig (State via
> Semantik/`assertIsSelected`).

---

## 3. Test-relevante Disclosure-Anker (für QA/CYP-7)

Diese Tags existieren **gerade**, damit die Ehrlichkeits-Eigenschaften (§7) testbar sind:

- **`id` read-only = Identität:** `agentSettings.idReadonly` ist präsent + **nicht** editierbar (kein TextField/`assertIsNotEnabled`
  bzw. reiner Text) neben `agentSettings.name.input` → Umbenennen ≠ Identitätswechsel.
- **Live vs. deferred:** `agentSettings.effectHint` erscheint **nur** nach Persona-Änderung, **nicht** bei Name-/Farb-Änderung →
  QA prüft die „sofort vs. restart-deferred"-Grenze über An-/Abwesenheit des Hinweises.
- **Kontrast-Ehrlichkeit:** custom-hex, das angepasst wurde → `agentSettings.contrastAdvisory` präsent (die Vorschau
  `agentSettings.preview` zeigt das Effektiv-Ergebnis, nicht die Roh-Eingabe); invalid-hex → `agentSettings.customHex.error`
  (ERROR), **kein** Speichern eines unlesbaren Werts.
- **Operator-Gate:** nicht-editable → `agentSettings.gateHint` (GATED) + Eingaben disabled; Persona-Sicht read-only (§-Ask 3).
- **Farbe ≠ Status:** Custom-Hue nahe einer CYP-12-Status-Hue → `agentSettings.contrastAdvisory` trägt den Status-Advisory-Text
  (optional, §-Ask 4).
- **Swatch-Auswahl nicht-Farbe-allein:** der aktive `agentSettings.swatch.<index>` trägt `selected` + a11y `Farbe %1$s`.

---

## 4. Farb-Derivation — `:core`-Unit-Tests statt UI-Tags

Die `derive(base)`-Utility (§5) lebt in `:core` (compose-frei) → sie wird über **Unit-Tests** verankert (nicht über testTags):
- `onColor` = kontraststärkere von WHITE/BLACK; `contrastRatio(onColor, background) ≥ 4.5`; `border`-Kontrast `≥ 3:1`;
  `derive()` **deterministisch** (gleiche Base → gleiches Scheme); `adjusted`-Flag korrekt bei Mitteltönen. Server+client-shared
  (dasselbe `:core`-Modul wie `colorSlot`).

---

## 5. Was NICHT neu ist (Reuse / bewusste Auslassung)

- **Titelbar/Fenster-Tags** (`window.<id>.titlebar`/`.content`/`.resizeHandle`) — unverändert; nur `+settings(id)`.
- **`agentMgmt`-Tags** — der Config-Dialog (Rolle/Launch/Worktree/Connector/Add/Remove) bleibt; das neue Panel ist die
  Appearance+Persona-Fläche, **kein** Ersatz. Persona schreibt über denselben Pfad (Anti-Divergenz, §7.7).
- **Kein Theming-Qualifier-Tag** — Focus/Unfocus-Theming wird über Button/Panel-Präsenz + `:core`-Derivation-Tests geprüft.

---

## 6. Hand-off-Hinweis (Dev + QA)

- `window.<id>.settings` in `WindowTestTags`; neues `AgentSettingsTags`-Objekt (`:app:shared`, Area `agentSettings`) — **mit dem
  Tester (CYP-7) geteilt**, Änderungen koordiniert über den PO.
- **Shared-Tag-Drift:** mit dem CYP-211-Slice timen.

---

## 7. Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Tags gesamt:** 1 Window-Erweiterung (`window.<id>.settings`) + **13** `agentSettings`-Area-Tags (davon
  `swatch.<index>` = 1 index-scoped Funktion für die 8 Swatches).
- **0 Kollision** gg. `WindowTestTags`/`AgentMgmtTags`/`SettingsTags` @ `56567a1` (kein `agentSettings`-Area vorhanden, kein
  `window.<id>.settings`).
- **Punktfrei-Invariante:** Swatch-Index 0–7 statt `sender.0` (Punkt verboten); `id` = Agent-ID (punktfrei wie in `window.<id>`).
- **Reuse-gegen-Code verifiziert:** `WindowTestTags.titleBar/content` + die single-instance-Area-Konvention (`settings`/`agentMgmt`)
  existieren real; `agentSettings` folgt demselben Muster.
