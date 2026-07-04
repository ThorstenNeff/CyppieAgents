# Per-Agent-Avatar-Picker — UX/UI-Design-Spec (CYP-212, DESIGN-AHEAD)

> Owner: UIUX-Designer · Story **CYP-212** (Avatar-Picker; Folge zu Epic **CYP-208** Per-Agent-Customization) · Stand: 2026-07-04
> Status: **Vorschlag / DESIGN-AHEAD** — **shipt NICHT vor CYP-208**; speist später Backend + Dev (CYP-212)
> Begleitdateien: `agent-avatar-keys.md`, `agent-avatar-tags.md`, `agent-avatar-tokens.json`
> **Baut auf** `agent-customization-spec.md` (CYP-209): erweitert dessen Settings-Panel (`agentSettings`) um eine **Avatar-Sektion**.
> **Grounded gg. develop `1988a15`** (CYP-210 backend color-Shape bereits gemergt: `HubState.editAgent` trägt name+color):
> `comm/SenderPalette.kt` (`SenderColor`/`initialsOf`), Render-Sites `comm/CommPanel.kt` + `eventlog/EventRowUi.kt` (+ Titelbar
> CYP-209). DiceBear self-hosted HTTP-API verifiziert via Context7 (`/dicebear/dicebear`).
> **Auftraggeber-Wahl:** DiceBear **self-hosted** (Styles: bottts/avataaars/adventurer/big-smile/fun-emoji) + **Custom-Upload**
> (any-size → **server**-downsize + center-crop-square). Raster-only (png/jpg/webp), **kein SVG**.

---

## 0. Umfang & Leitidee

Pro Agent ein **Bild**, zwei Wege: **(1) Preset-Library** (DiceBear self-hosted, kuratierte Styles) und **(2) Custom-Upload**
(Raster, server-verarbeitet). Erweitert den **CYP-209-Settings-Screen** um eine **Avatar-Sektion**; definiert, wie der Avatar in
**Titelbar / Roster / Comm** erscheint, und die **Fallback-Kette**.

**Ehrlichkeits-Kern (mein Lane — verbindlich, §7):**
- **Avatar-Bild = Dekoration, NIE Identitäts-/Trust-Signal.** Die **deterministische Farbe** (CYP-14/CYP-209) + **`id`** bleiben
  die echten Identitäts-Anker; ein hochgeladenes Bild kann keine andere Agent-Identität „vortäuschen" (der **Farb-Ring** + Name +
  `id` disambiguieren weiter). Konsistent mit „Farbe nie alleiniger Träger; Avatar+Name+Farbe" (CYP-14).
- **Raster-only, kein SVG** (SVG = Script-/XSS-Vektor): Client-Hinweis + **server-autoritative** Validierung (Magic-Bytes, nicht
  Extension), fail-closed.
- **Self-hosted = kein Egress:** der Seed (Agent-`id`/Name) geht an **keinen** Dritt-Dienst (Privacy).

---

## 1. Reuse-Bestand (gg. `1988a15` verifiziert)

| Baustein | Datei | Reuse in CYP-212 |
|---|---|---|
| `initialsOf(name)` (Initialen-Avatar) | `comm/SenderPalette.kt` | **Fallback-Stufe 3** der Kette (§4) |
| `SenderColor.avatarFill` / `+borderColor` (CYP-209) | `comm/SenderPalette.kt` | Farb-Kreis (Stufe 4) + **Farb-Ring** um jeden Avatar |
| Render-Sites `CommPanel`, `EventRowUi`, Titelbar (CYP-209) | dito | ein **gemeinsamer** Avatar-Resolver bedient alle (§5, keine Per-Site-Divergenz) |
| CYP-209 Settings-Panel `agentSettings` | `agent-customization-spec.md` | die Avatar-Sektion reiht sich ein (§3) |
| `LabeledField`/`TonedHint`/`SectionHeading` | CYP-209/Code | Upload-Fehler (ERROR), Crop-Hinweis (INFO), Sektions-Kopf |
| `agent_save`/`agent_cancel`/`workspace_operator_only` | strings.xml | Panel-Aktionen + Operator-Gate (reuse) |

**Anti-Divergenz:** genau **ein** Avatar-Resolver/-Composable (`AgentAvatar`) für Titelbar, Roster und Comm — kein dritter
One-off je Site. Die Fallback-Kette lebt an **einer** Stelle.

---

## 2. DiceBear self-hosted (verifiziert via Context7)

- **HTTP-API-Schema:** `GET <selfHostedHost>/<version>/<style>/<format>?seed=<seed>&size=<n>` — `format` = **`png`** (Raster;
  **nicht** `svg` — Compose rendert Raster plattform-neutral, SVG-Rendering vermeiden). `seed` = **deterministisch** (gleicher
  Seed → gleicher Avatar). `<version>`-Root liefert die verfügbaren Styles als JSON.
- **Kuratierte Styles (Auftraggeber):** `bottts`, `avataaars`, `adventurer`, `big-smile`, `fun-emoji` (Style-Namen lowercase,
  Hyphen für mehrteilige — **punktfrei**, taugen als Tag-Segment).
- **Self-hosted:** die Previews kommen vom **eigenen** Host (Backend-Deployment, CYP-212) → **kein** Dritt-Egress; der Seed (Agent-
  Identität) verlässt die Plattform nicht. Backend besitzt Host/Version-Konfig.

---

## 3. Avatar-Sektion im Settings-Panel (erweitert CYP-209 §4)

`SectionHeading` `agent_avatar_section` „Avatar". Reiht sich in `agentSettings` (nach Identität, vor/bei Farbe). Drei Bereiche:

### 3.1 Aktueller Avatar + Entfernen
- **Aktueller Avatar** (Tag `agentSettings.avatar.current`): zeigt die **effektive** Stufe der Fallback-Kette (§4) mit **Farb-Ring**
  (der `borderColor` aus CYP-209) — der Ring bleibt **immer** (Identitäts-Anker), auch über einem Custom-Bild.
- **Entfernen** (`agent_avatar_remove`, Tag `agentSettings.avatar.remove`): löscht Upload **und** Preset → zurück auf
  Initialen/Farbe (Stufe 3/4). Neutral (kein Fehler).

### 3.2 Preset-Library (DiceBear)
- **Style-Grid** (`agent_avatar_preset_label` „Vorlagen", Tag `agentSettings.avatar.preset`): je kuratiertem Style **eine
  Vorschau**, gerendert mit dem **Seed des Agenten** (Default-Seed = Agent-`id` → deterministisch, stabil). Tag je Style
  `agentSettings.avatar.presetStyle.<style>` (`<style>` = DiceBear-Name, punktfrei). Auswahl-Indikator **nicht Farbe-allein**
  (Ring/Häkchen + `selected`-Semantik), a11y `a11y_agent_avatar_preset` „Avatar-Vorlage %1$s".
- **Variante/Shuffle** (optional, `agent_avatar_shuffle`, Tag `agentSettings.avatar.shuffle`): re-rollt den **Seed** innerhalb des
  gewählten Styles → ein anderer Avatar desselben Styles. Deterministisch pro Seed; der gespeicherte `{style, seed}` ist stabil.
- **Vorschau-Bilder:** Raster-**PNG** vom self-hosted Host, async geladen; während Laden/bei Fehler greift die Fallback-Kette
  (§4) — kein leerer/kaputter Zustand (fail-closed auf die nächste Stufe).

### 3.3 Custom-Upload
- **Upload-Button** (`agent_avatar_upload` „Bild hochladen", Tag `agentSettings.avatar.upload`), a11y `a11y_agent_avatar_upload`
  „Bild hochladen (PNG, JPG oder WebP)".
- **Flow:** Datei wählen → **Client-Vorprüfung** (Typ png/jpg/webp; grobe Größen-Obergrenze) → Upload → **Server** downsized +
  **center-crop-square** (autoritativ) → gespeicherter Avatar (Ref) → wird Stufe 1 der Kette.
- **Crop/Preview** (`agent_avatar_crop_hint` „Wird mittig quadratisch zugeschnitten", INFO; Tag `agentSettings.avatar.preview`):
  eine **quadratische** Vorschau, die den **center-crop** ehrlich zeigt (der eigentliche Zuschnitt ist server-seitig; die Vorschau
  spiegelt das Ergebnis). Optionaler client-seitiger Reposition-Crop = **Zukunft**, nicht MVP (§-Ask 3).
- **Security-UX (§7):** Fehler klar und ehrlich —
  - falscher Typ (inkl. **SVG**) → `TonedHint(agent_avatar_upload_type_error, ERROR)` „Nur PNG, JPG oder WebP – kein SVG",
    Tag `agentSettings.avatar.uploadError`.
  - zu groß → `agent_avatar_upload_size_error` „Bild zu groß (max %1$s)".
  - sonstiger Fehler → `agent_avatar_upload_generic_error` „Upload fehlgeschlagen – erneut versuchen".
  - **Server ist die Wahrheit:** die Client-Vorprüfung ist ein erster Hinweis; **der Server validiert autoritativ** (Magic-Bytes,
    nicht Extension; kein SVG; Größen-/Dimensions-Limit) und lehnt fail-closed ab → die UI spiegelt die **ehrliche Server-Ablehnung**.

### 3.4 Operator-Gate
Wie CYP-209 §4.5: Edits `enabled = isOperatorAccess`; nicht-editable → `TonedHint(workspace_operator_only, GATED)` + read-only.
(Reconcile mit CYP-80 / CYP-209-§-Ask 3.)

---

## 4. Fallback-Kette (deterministisch, fail-closed)

**custom-upload → preset → initials → color** — jede Stufe fällt sauber auf die nächste:

1. **Custom-Upload** vorhanden & ladbar → Bild (quadratisch, Farb-Ring).
2. sonst **Preset** (`{style, seed}`) gesetzt & ladbar → DiceBear-PNG (Farb-Ring).
3. sonst **Initialen** (`initialsOf(name)` auf `avatarFill`) — bestehendes CYP-14-Verhalten.
4. sonst **Farbe** (reiner `avatarFill`-Kreis; `initialsOf` liefert `"?"` bei leerem Namen → Stufe 3 deckt das ab).

- **Lade-/Fehlerzustand:** lädt ein Bild (Stufe 1/2) nicht (Netz/Server) → **automatischer Fall auf die nächste Stufe** (nie ein
  kaputtes Bild-Icon; nie leer). Ehrlich: der Avatar zeigt **immer** etwas Identitäts-Tragendes.
- **Farb-Ring auf allen Stufen** — die Farbe (CYP-209-`borderColor`) bleibt der Identitäts-Anker, unabhängig von der Bild-Stufe.

---

## 5. Render-Sites — ein gemeinsamer `AgentAvatar` (kein Per-Site-One-off)

Heute rendern **CommPanel** + **EventRowUi** (+ Titelbar CYP-209) den Initialen-Avatar direkt. CYP-212 führt **einen**
gemeinsamen `AgentAvatar(agent, size)`-Composable ein, der die Fallback-Kette (§4) + den Farb-Ring kapselt; alle Sites rufen
**nur** ihn. So sind Avatar-Verhalten, Fallback und Ring **überall identisch** (Reuse-Mandat; kein dritter divergenter Avatar).

- **Titelbar (CYP-209):** kleiner Avatar links vom Titel (themed; Ring = `scheme.border`).
- **Roster / Comm / Event-Log:** bestehende Avatar-Stellen rufen `AgentAvatar` statt der Inline-Initialen.
- **Bild-Laden (KMP):** async Raster-Loader (Impl-Detail Dev — Coil/Kamel o. ä.); Design fordert nur: Raster-PNG vom self-hosted
  Host, async, mit Fallback-Kette bei Laden/Fehler.

---

## 6. Modell / Persistenz (feeds Backend+Dev CYP-212)

**Empfehlung (nicht-blockierend, §-Ask 2):** `Agent.avatar: AgentAvatar?` als **diskriminierte** Form —
`{ kind: UPLOAD, ref: <stored-image-id> }` | `{ kind: PRESET, style: String, seed: String }` | `null`.
`null` ⇒ Fallback auf Initialen/Farbe (backward-compatible). Upload vs. Preset sind **strukturell verschieden** (Blob-Ref vs.
style+seed) → eine diskriminierte Union ist die ehrliche minimale Shape (anders als die color-base-hex-Entscheidung CYP-210, wo
ein Skalar reichte). **Backend besitzt die finale Shape** (wie CYP-210); meine Spec = Referenz.
- **Determinismus:** `{style, seed}` → derselbe Avatar überall/immer (wie `colorSlot`/`derive`).
- **Upload-Storage:** server-seitig (downsized/cropped Blob); die UI hält nur die Ref, nie rohe Bytes im DTO.

---

## 7. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste UX-QA)

1. **Bild = Dekoration, nie Identität/Trust:** der **Farb-Ring** (CYP-209-`borderColor`) + `id` + Name bleiben die Identitäts-
   Anker über **jeder** Bild-Stufe; ein Custom-Upload kann keine andere Agent-Identität vortäuschen (Farbe/`id` disambiguieren).
2. **Raster-only, kein SVG:** Client lehnt SVG/falschen Typ mit klarer Meldung ab; **Server validiert autoritativ** (Magic-Bytes,
   fail-closed) — die UI spiegelt die ehrliche Server-Ablehnung, keine stille Annahme.
3. **Fallback nie leer/kaputt:** Lade-/Server-Fehler → nächste Stufe (custom→preset→initials→color); nie ein Broken-Image, nie
   „kein Avatar".
4. **Ehrlicher Crop:** die Vorschau zeigt das **effektive** (center-crop-square) Ergebnis; der Server ist die autoritative
   Zuschnitt-/Downsize-Quelle.
5. **Self-hosted = kein Egress:** Seeds/Uploads gehen an keinen Dritt-Dienst (Privacy-Ehrlichkeit).
6. **Determinismus:** `{style, seed}` → stabiler Avatar (kein Runtime-Drift).
7. **Ein Resolver (Anti-Divergenz):** Titelbar/Roster/Comm/Event-Log nutzen **einen** `AgentAvatar` — gleiche Fallback-Kette + Ring
   überall.
8. **Operator-Gate + Farbe-nie-Träger:** Avatar-Edits operator-gated (reuse); Preset-Auswahl nicht Farbe-allein (Ring/Häkchen +
   a11y).

---

## 8. §-Asks (an PO/Backend — nicht-blockierend; DESIGN-AHEAD, shipt nicht vor CYP-208)

1. **Style-Set & Default-Seed:** kuratierte 5 (bottts/avataaars/adventurer/big-smile/fun-emoji) bestätigt? Default-Seed =
   Agent-`id` (deterministisch) ok? „Variante/Shuffle" (Seed-Reroll) im MVP oder Zukunft?
2. **Avatar-Shape (Backend):** Empfehlung diskriminiert `AgentAvatar{UPLOAD|PRESET}` (§6); Backend entscheidet die finale Shape
   (wie CYP-210). Upload-Storage-Ref-Form (id/URL)?
3. **Client-Reposition-Crop:** MVP = server center-crop + quadratische Vorschau (empfohlen); client-seitiger verschiebbarer Crop =
   Zukunft — bestätigen.
4. **Upload-Limits:** max Dateigröße/Dimension (für `agent_avatar_upload_size_error %1$s`) — Backend legt die autoritativen
   Grenzen fest; die UI zeigt sie.
5. **Style-Labels:** Previews sind visuell; a11y nennt den Style-Namen (`bottts`…). Friendly-Labels (z. B. „Roboter") gewünscht
   oder Style-Namen belassen?

---

## 9. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** siehe `agent-avatar-keys.md` (Save/Cancel/Gate reused).
- **Neue Tags:** `agentSettings.avatar.*` (Erweiterung der CYP-209-`agentSettings`-Area) — siehe `agent-avatar-tags.md`.
- **Neue Tokens:** Avatar-/Grid-Maße + Upload-Constraints (Formate, max-size, center-crop) + Fallback-Kette; **0 neue Farben**
  (Ring = CYP-209-`borderColor`, Kreis = `avatarFill`) — siehe `agent-avatar-tokens.json`.
- **Kein neues Fenster-System; ein gemeinsamer `AgentAvatar`; Upload server-verarbeitet (kein Client-Bildbearbeiten im MVP).**

*DESIGN-AHEAD — shipt nicht vor CYP-208. Nur Design/Spec/Verifikation, keine Implementierung. Key/Tag-Landing mit dem späteren
CYP-212-Impl-Slice timen (CYP-7, PO koordiniert).*
