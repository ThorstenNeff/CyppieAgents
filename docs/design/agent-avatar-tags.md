# testTag-Schema — Per-Agent-Avatar-Picker (CYP-212, DESIGN-AHEAD)

> Owner: UIUX-Designer · Story **CYP-212** · Stand: 2026-07-04 · Status: Vorschlag / DESIGN-AHEAD
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte
> `[A-Za-z0-9-]+` (**keine Punkte** — Trenner; **Hyphen erlaubt** → DiceBear-Style-Namen wie `big-smile`/`fun-emoji` sind gültig).
> Erweitert die CYP-209-Area **`agentSettings`** (single-instance) — verifiziert gg. `agent-customization-tags.md`.
> **Vertrag Dev/QA (CYP-7):** API, nicht still umbenennen; koordiniert über den PO.

Die Avatar-Sektion ist Teil des CYP-209-Settings-Panels → **Sub-Tags unter `agentSettings.avatar`**.

---

## 1. Neue Tags — `agentSettings.avatar.*`

| Element | testTag | Zweck |
|---|---|---|
| Avatar-Sektion | `agentSettings.avatar` | Sektions-Container |
| Aktueller Avatar | `agentSettings.avatar.current` | effektive Fallback-Stufe + Farb-Ring (§3.1) |
| Entfernen | `agentSettings.avatar.remove` | Upload+Preset löschen → Initialen/Farbe |
| Preset-Grid | `agentSettings.avatar.preset` | DiceBear-Style-Grid |
| Style-Vorschau | `agentSettings.avatar.presetStyle.<style>` | 1 je Style; `<style>` = DiceBear-Name (bottts/avataaars/adventurer/big-smile/fun-emoji) |
| Variante/Shuffle | `agentSettings.avatar.shuffle` | Seed-Reroll (optional, §-Ask 1) |
| Upload-Button | `agentSettings.avatar.upload` | Custom-Upload starten |
| Upload-Vorschau (Crop) | `agentSettings.avatar.preview` | quadratische center-crop-Vorschau |
| Upload-Fehler | `agentSettings.avatar.uploadError` | Typ/Größe/SVG-Ablehnung (ERROR) |

> **Style-Selektor:** `agentSettings.avatar.presetStyle.big-smile` etc. — Hyphen ist gültig, **kein Punkt**. Der aktive Style trägt
> zusätzlich `selected`-Semantik (nicht Farbe-allein) → `assertIsSelected`, kein separater `.selected`-Tag nötig.

---

## 2. Test-relevante Disclosure-Anker (für QA/CYP-7)

- **Fallback nie leer/kaputt:** `agentSettings.avatar.current` (und die Render-Site-Avatare) sind **immer** präsent mit einer
  Fallback-Stufe; bei Bild-Ladefehler **kein** Broken-Image → QA prüft die Kette custom→preset→initials→color über die effektive
  Stufe (nicht über ein leeres/kaputtes Node).
- **SVG/Typ-Ablehnung ehrlich:** invalider Upload → `agentSettings.avatar.uploadError` (ERROR) + **kein** gespeicherter Avatar;
  Server ist die autoritative Ablehnung (Magic-Bytes).
- **Preset-Auswahl nicht Farbe-allein:** aktiver `agentSettings.avatar.presetStyle.<style>` trägt `selected` + a11y.
- **Farb-Ring = Identität:** der Ring (CYP-209-`borderColor`) ist auf **jeder** Avatar-Stufe präsent → Bild ist Dekoration, nicht
  Identität (§7.1). (Kein eigener Ring-Tag; visuell/`:core`-derivation-verankert.)
- **Operator-Gate:** nicht-editable → Avatar-Edits disabled + `agentSettings.gateHint` (aus CYP-209, reuse).

---

## 3. Render-Sites — ein gemeinsamer `AgentAvatar`

Der Avatar in Titelbar/Roster/Comm/Event-Log ist **ein** `AgentAvatar`-Composable (§5 Spec). Er braucht **keinen** neuen
per-Site-Tag — die Sites behalten ihre bestehenden Anker (`window.<id>.titlebar`, Comm-/Event-Row-Tags). Optionaler Test-Tag
`agentAvatar.<agentId>` nur falls QA den Avatar site-übergreifend adressieren will (Forward-prep, nicht MVP).

---

## 4. Hand-off + Zähl-/Validierungs-Block

- Sub-Tags in das CYP-209-`AgentSettingsTags`-Objekt (`:app:shared`), mit dem Tester (CYP-7) geteilt; Änderungen über den PO.
- **Neue Tags gesamt: 9** unter `agentSettings.avatar.*` (davon `presetStyle.<style>` = 1 style-scoped Funktion für die 5 Styles).
- **0 Kollision** gg. `agent-customization-tags.md` (CYP-209) + `AgentMgmtTags`/`WindowTestTags`/`SettingsTags` @ `1988a15`
  (kein `agentSettings.avatar.*` vorhanden).
- **Punktfrei-Invariante:** Style-Namen sind hyphen-basiert, **punktfrei** (`big-smile`, `fun-emoji`) → gültige Selektor-Segmente.
- **Reuse-gegen-Doc verifiziert:** Area `agentSettings` + `agentSettings.gateHint` stammen aus CYP-209 (`agent-customization-tags.md`);
  Avatar-Tags erweitern dieselbe Area.
- **⚠ Shared-Tag-Drift:** mit dem CYP-212-Impl-Slice timen (DESIGN-AHEAD).
