# Per-Agent-Avatar-Picker — UX/UI-Design-Spec (CYP-214)

> Owner: UIUX-Designer · Story **CYP-214** (Avatar-Design; Feature **CYP-212** Avatar-Picker; Epic **CYP-208** Per-Agent-
> Customization — **gemergt**) · Stand: 2026-07-05 · Status: **final** (CYP-212 gestartet; §-Asks + Lizenz-Entscheid resolvet)
> Begleitdateien: `agent-avatar-keys.md`, `agent-avatar-tags.md`, `agent-avatar-tokens.json`
> **Baut auf** `agent-customization-spec.md` (CYP-209): erweitert dessen Settings-Panel (`agentSettings`) um eine **Avatar-Sektion**.
> **Grounded gg. develop `c4f2d53`** (CYP-208/CYP-209/CYP-210/CYP-211 **gemergt**): `comm/SenderPalette.kt`
> (`SenderColor.avatarFill` **+ `borderColor`**, `initialsOf`), `:core` `ColorDerivation` (WCAG `deriveScheme`), Titelbar-Theming
> (CYP-211), Render-Sites `comm/CommPanel.kt` + `eventlog/EventRowUi.kt`. DiceBear self-hosted HTTP-API + Style-Lizenzen
> verifiziert via Context7/Doku (`/dicebear/dicebear`).
> **Auftraggeber-Wahl (bestätigt):** DiceBear **self-hosted**, Styles: `bottts` · `avataaars` · `adventurer` · `big-smile` ·
> `fun-emoji`; **Custom-Upload** (any-size → **server**-downsize + center-crop-square). Raster-only (png/jpg/webp), **kein SVG**.
> **Konsumenten:** Backend **CYP-215** (self-hosted Style-Satz + Upload-Verarbeitung), Dev **CYP-216** (`AgentAvatar`-Resolver + UI).

---

## 0. Umfang & Leitidee

Pro Agent ein **Bild**, zwei Wege: **(1) Preset-Library** (DiceBear self-hosted, 5 kuratierte Styles) und **(2) Custom-Upload**
(Raster, server-verarbeitet). Erweitert den **CYP-209-Settings-Screen** um eine **Avatar-Sektion**; definiert, wie der Avatar in
**Titelbar / Roster / Comm / Event-Log** erscheint, die **Fallback-Kette** und die **Lizenz-/Credits-Fläche**.

**Ehrlichkeits-Kern (mein Lane — verbindlich, §8):**
- **Avatar-Bild = Dekoration, NIE Identitäts-/Trust-Signal.** Die **deterministische Farbe** (CYP-14/CYP-209) + **`id`** bleiben
  die echten Identitäts-Anker; ein hochgeladenes Bild kann keine andere Agent-Identität „vortäuschen" (der **Farb-Ring** + Name +
  `id` disambiguieren weiter). Konsistent mit „Farbe nie alleiniger Träger; Avatar+Name+Farbe" (CYP-14).
- **Raster-only, kein SVG** (SVG = Script-/XSS-Vektor): Client-Hinweis + **server-autoritative** Validierung (Magic-Bytes, nicht
  Extension), fail-closed.
- **Self-hosted = kein Egress:** der Seed (Agent-`id`/Name) geht an **keinen** Dritt-Dienst (Privacy).
- **Ehrliche Attribution:** die Credits-Fläche (§3.4) ist Pflicht, weil der PNG-Render die eingebettete DiceBear-`<metadata>`-
  Attribution abstreift — die 3 CC-BY-4.0-Styles werden **sichtbar** gecreditet (§7).

---

## 1. Reuse-Bestand (gg. `c4f2d53` verifiziert)

| Baustein | Datei | Reuse in CYP-214 |
|---|---|---|
| `initialsOf(name)` (Initialen-Avatar) | `comm/SenderPalette.kt` | **Fallback-Stufe 3** der Kette (§4) |
| `SenderColor.avatarFill` / `borderColor` (CYP-211 gemergt) | `comm/SenderPalette.kt` | Farb-Kreis (Stufe 4) + **Farb-Ring** um jeden Avatar |
| `:core` `deriveScheme(base).border` (CYP-211) | `core/model/ColorDerivation.kt` | Ring-Farbe (≥3:1) — **kein** neuer Farbwert |
| Render-Sites `CommPanel`, `EventRowUi`, Titelbar (CYP-209/211) | dito | ein **gemeinsamer** Avatar-Resolver bedient alle (§5, keine Per-Site-Divergenz) |
| CYP-209 Settings-Panel `agentSettings` | `agent-customization-spec.md` | die Avatar-Sektion reiht sich ein (§3) |
| `LabeledField`/`TonedHint`/`SectionHeading` | CYP-209/Code | Upload-Fehler (ERROR), Crop-Hinweis (INFO), Sektions-Kopf, Credits |
| `agent_save`/`agent_cancel`/`workspace_operator_only` | strings.xml | Panel-Aktionen + Operator-Gate (reuse) |

**Anti-Divergenz:** genau **ein** Avatar-Resolver/-Composable (`AgentAvatar`, → Dev CYP-216) für Titelbar, Roster, Comm und
Event-Log — kein weiterer One-off je Site. Die Fallback-Kette lebt an **einer** Stelle.

---

## 2. DiceBear self-hosted (verifiziert via Context7/Doku)

- **HTTP-API-Schema:** `GET <selfHostedHost>/<version>/<style>/<format>?seed=<seed>&size=<n>` — `format` = **`png`** (Raster;
  **nicht** `svg` — Compose rendert Raster plattform-neutral, SVG-Rendering vermeiden). `seed` = **deterministisch** (gleicher
  Seed → gleicher Avatar). `<version>`-Root liefert die verfügbaren Styles als JSON.
- **Kuratierte Styles (bestätigt):** `bottts`, `avataaars`, `adventurer`, `big-smile`, `fun-emoji` (lowercase, Hyphen für
  mehrteilige — **punktfrei**, taugen als Tag-Segment).
- **Self-hosted:** die Previews kommen vom **eigenen** Host (Backend-Deployment, CYP-215) → **kein** Dritt-Egress; der Seed (Agent-
  Identität) verlässt die Plattform nicht. Backend besitzt Host/Version-Konfig.
- **Lizenzen:** DiceBears **Code** ist MIT, aber **jeder Style trägt die Lizenz seines Artists** — maßgeblich ist die
  **Artwork-Lizenz je Style**. Freigabe-Liste + Attribution-Pflichten: **§7** (autoritativ für den self-hosted Style-Satz CYP-215).

---

## 3. Avatar-Sektion im Settings-Panel (erweitert CYP-209 §4)

`SectionHeading` `agent_avatar_section` „Avatar". Reiht sich in `agentSettings` (nach Identität, vor/bei Farbe). Bereiche:

### 3.1 Aktueller Avatar + Entfernen
- **Aktueller Avatar** (Tag `agentSettings.avatar.current`): zeigt die **effektive** Stufe der Fallback-Kette (§4) mit **Farb-Ring**
  (der `borderColor` aus CYP-211) — der Ring bleibt **immer** (Identitäts-Anker), auch über einem Custom-Bild.
- **Entfernen** (`agent_avatar_remove`, Tag `agentSettings.avatar.remove`): löscht Upload **und** Preset → zurück auf
  Initialen/Farbe (Stufe 3/4). Neutral (kein Fehler).

### 3.2 Preset-Library (DiceBear)
- **Style-Grid** (`agent_avatar_preset_label` „Vorlagen", Tag `agentSettings.avatar.preset`): je kuratiertem Style **eine
  Vorschau**, gerendert mit dem **Seed des Agenten** (Default-Seed = Agent-`id` → deterministisch, stabil — **§-Ask 1 bestätigt**).
  Tag je Style `agentSettings.avatar.presetStyle.<style>` (`<style>` = DiceBear-Name, punktfrei). Jede Vorschau trägt ein
  **Friendly-Label** (§6): `Roboter` · `Charaktere` · `Abenteurer` · `Frohe Gesichter` · `Emojis` — der rohe DiceBear-Bezeichner
  (`bottts`…) bleibt intern (Style-Key/Seed), wird **nie** angezeigt. Auswahl-Indikator **nicht Farbe-allein** (Ring/Häkchen +
  `selected`-Semantik), a11y `a11y_agent_avatar_preset` „Avatar-Vorlage %1$s" (%1$s = Friendly-Label).
- **Variante/Shuffle** (`agent_avatar_shuffle`, Tag `agentSettings.avatar.shuffle` — **MVP, §-Ask 1 bestätigt**): re-rollt den
  **Seed** innerhalb des gewählten Styles → ein anderer Avatar desselben Styles. Deterministisch pro Seed; der gespeicherte
  `{style, seed}` ist stabil.
- **Vorschau-Bilder:** Raster-**PNG** vom self-hosted Host, async geladen; während Laden/bei Fehler greift die Fallback-Kette
  (§4) — kein leerer/kaputter Zustand (fail-closed auf die nächste Stufe).

### 3.3 Custom-Upload
- **Upload-Button** (`agent_avatar_upload` „Bild hochladen", Tag `agentSettings.avatar.upload`), a11y `a11y_agent_avatar_upload`
  „Bild hochladen (PNG, JPG oder WebP)".
- **Flow:** Datei wählen → **Client-Vorprüfung** (Typ png/jpg/webp; grobe Größen-Obergrenze) → Upload → **Server** downsized +
  **center-crop-square** (autoritativ — **§-Ask 3 bestätigt: MVP = Server-Center-Crop**) → gespeicherter Avatar (Ref) → Stufe 1.
- **Crop/Preview** (`agent_avatar_crop_hint` „Wird mittig quadratisch zugeschnitten", INFO; Tag `agentSettings.avatar.preview`):
  eine **quadratische** Vorschau, die den **center-crop** ehrlich zeigt (der eigentliche Zuschnitt ist server-seitig; die Vorschau
  spiegelt das Ergebnis). Client-seitiger verschiebbarer Reposition-Crop = **Zukunft**, nicht MVP (**§-Ask 3 bestätigt**).
- **Limits (Backend-autoritativ, §-Ask 4 bestätigt):** max Dateigröße/Dimension setzt **Backend** (CYP-215); die UI **zeigt** die
  Grenze (`%1$s` in `agent_avatar_upload_size_error`) und spiegelt die ehrliche Server-Ablehnung.
- **Security-UX (§7/§8):** Fehler klar und ehrlich —
  - falscher Typ (inkl. **SVG**) → `TonedHint(agent_avatar_upload_type_error, ERROR)` „Nur PNG, JPG oder WebP – kein SVG",
    Tag `agentSettings.avatar.uploadError`.
  - zu groß → `agent_avatar_upload_size_error` „Bild zu groß (max %1$s)".
  - sonstiger Fehler → `agent_avatar_upload_generic_error` „Upload fehlgeschlagen – erneut versuchen".
  - **Server ist die Wahrheit:** die Client-Vorprüfung ist ein erster Hinweis; **der Server validiert autoritativ** (Magic-Bytes,
    nicht Extension; kein SVG; Größen-/Dimensions-Limit) und lehnt fail-closed ab → die UI spiegelt die **ehrliche Server-Ablehnung**.

### 3.4 Credits / Attribution (PO-Entscheid 2026-07-05: alle 5 behalten + Credits-Fläche)
- **Credits-Affordance** (`agent_avatar_credits` „Bild-Vorlagen · Credits", Tag `agentSettings.avatar.credits`): eine kleine,
  ruhige Fläche (Zeile/Link unter dem Preset-Grid, öffnet eine statische Credits-Liste), a11y `a11y_agent_avatar_credits`.
  Deckt sich mit der bestehenden Third-Party-License-Hygiene (Exposé §10.3).
- **Inhalt — alle 5 Styles gelistet** (Reuse `TonedHint`/Panel, kein neues Fenster-System):
  - Zeile je Style `agent_avatar_credit_line` „%1$s von %2$s — %3$s" (%1$s = Friendly-Label, %2$s = Artist, %3$s = **Lizenzname**,
    verlinkt die Lizenz-URL).
  - **CC-BY-Styles** (`adventurer`/`big-smile`/`fun-emoji`): Lizenzname = „CC BY 4.0" + Zusatz `agent_avatar_credit_modified`
    „(bearbeitet)" — erfüllt CC-BYs **Änderungs-Hinweis** (DiceBear-Avatare sind Remixe/Derivate). **Pflicht.**
  - **Permissive-Styles** (`bottts`/`avataaars`): Lizenzname = „Free for personal & commercial" (Pablo Stanley) — **kein**
    Attribution-Zwang, aber **ehrlich mitgenannt** (Courtesy-Credit).
- **Warum Pflicht:** wir rendern **PNG** → DiceBears eingebetteter `<metadata>`-Attribution-Block wird abgestreift; die Attribution
  muss deshalb **in-App** stehen, sonst sind die CC-BY-Styles nicht compliant (§7). Bild + Credits = Dekoration/Compliance; der
  Farb-Ring/`id`-Anker bleibt der echte Trust-Träger.

### 3.5 Operator-Gate
Wie CYP-209 §4.5: Edits `enabled = isOperatorAccess`; nicht-editable → `TonedHint(workspace_operator_only, GATED)` + read-only.

---

## 4. Fallback-Kette (deterministisch, fail-closed)

**custom-upload → preset → initials → color** — jede Stufe fällt sauber auf die nächste:

1. **Custom-Upload** vorhanden & ladbar → Bild (quadratisch, Farb-Ring).
2. sonst **Preset** (`{style, seed}`) gesetzt & ladbar → DiceBear-PNG (Farb-Ring).
3. sonst **Initialen** (`initialsOf(name)` auf `avatarFill`) — bestehendes CYP-14-Verhalten.
4. sonst **Farbe** (reiner `avatarFill`-Kreis; `initialsOf` liefert `"?"` bei leerem Namen → Stufe 3 deckt das ab).

- **Lade-/Fehlerzustand:** lädt ein Bild (Stufe 1/2) nicht (Netz/Server) → **automatischer Fall auf die nächste Stufe** (nie ein
  kaputtes Bild-Icon; nie leer). Ehrlich: der Avatar zeigt **immer** etwas Identitäts-Tragendes.
- **Farb-Ring auf allen Stufen** — die Farbe (CYP-211-`borderColor`) bleibt der Identitäts-Anker, unabhängig von der Bild-Stufe.

---

## 5. Render-Sites — ein gemeinsamer `AgentAvatar` (kein Per-Site-One-off)

Heute rendern **CommPanel** + **EventRowUi** (+ Titelbar CYP-209/211) den Initialen-Avatar direkt. CYP-216 führt **einen**
gemeinsamen `AgentAvatar(agent, size)`-Composable ein, der die Fallback-Kette (§4) + den Farb-Ring kapselt; alle Sites rufen
**nur** ihn. So sind Avatar-Verhalten, Fallback und Ring **überall identisch** (Reuse-Mandat; kein dritter divergenter Avatar).

- **Titelbar (CYP-209/211):** kleiner Avatar links vom Titel (themed; Ring = `scheme.border`).
- **Roster / Comm / Event-Log:** bestehende Avatar-Stellen rufen `AgentAvatar` statt der Inline-Initialen.
- **Bild-Laden (KMP):** async Raster-Loader (Impl-Detail Dev — Coil/Kamel o. ä.); Design fordert nur: Raster-PNG vom self-hosted
  Host, async, mit Fallback-Kette bei Laden/Fehler.

---

## 6. Friendly Style-Labels (§-Ask 5 bestätigt — DE-Default + EN)

Sichtbar **und** a11y = Friendly-Label (nicht der rohe DiceBear-Bezeichner). Ehrliche Deskriptoren (was der Avatar zeigt) → taugen
als a11y-Name; ruhige M3-Stimme, keine Hype-Wörter. Keys: `agent_avatar_style_<name>` (§ `agent-avatar-keys.md`).

| Style-Key (intern) | Friendly-Label DE | Friendly-Label EN |
|---|---|---|
| `bottts` | Roboter | Robots |
| `avataaars` | Charaktere | Characters |
| `adventurer` | Abenteurer | Adventurers |
| `big-smile` | Frohe Gesichter | Happy Faces |
| `fun-emoji` | Emojis | Emojis |

---

## 7. Lizenz-/Attribution-Freigabe (autoritativ für CYP-215) — **PO-Entscheid: alle 5 + Credits**

DiceBear-Code = MIT; **maßgeblich ist die Artwork-Lizenz je Style**. Verifiziert via DiceBear-Style-Seiten/License-Overview
(Context7 `/dicebear/dicebear`). **Strukturierte Freigabe-/Third-Party-License-Daten:** `agent-avatar-tokens.json → styles[]`.

| Style | Artist | Lizenz | Attribution | Freigabe |
|---|---|---|---|---|
| `bottts` | Pablo Stanley | „Free for personal and commercial use" ([bottts.com](https://bottts.com/)) | **keine** | ✅ **frei** |
| `avataaars` | Pablo Stanley | „Free for personal and commercial use" ([avataaars.com](https://avataaars.com/)) | **keine** | ✅ **frei** |
| `adventurer` | Lisa Wischofsky | **CC BY 4.0** | **Pflicht** | ✅ **mit Credits-Notice (§3.4)** |
| `big-smile` | Ashley Seo | **CC BY 4.0** | **Pflicht** | ✅ **mit Credits-Notice (§3.4)** |
| `fun-emoji` | Davis Uche | **CC BY 4.0** | **Pflicht** | ✅ **mit Credits-Notice (§3.4)** |

- **PO-Entscheid (2026-07-05, konventionell/reversibel):** **alle 5 behalten + In-App-Credits-Fläche** (§3.4). Shipping *mit*
  Attribution ist voll compliant — **kein** Lizenz-Risiko in diesem Pfad. Styles später droppbar (reversibel).
- **Nuance (ehrlich):** `bottts`/`avataaars` sind **nicht literal CC0/MIT**, sondern Pablo Stanleys eigener „free for personal &
  commercial"-Grant — **wirkungsgleich permissiv** (kein Attribution-Zwang), aber kein SPDX-Standard.
- **PNG-Attribution-Catch (Kern-Disclosure):** PNG trägt **keine** `<metadata>`-Attribution → für die 3 CC-BY-Styles ist die
  **In-App-Credits-Fläche (§3.4) Pflicht**, sonst nicht compliant shippbar. Deshalb ist die Fläche kein „nice-to-have".

---

## 8. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste UX-QA)

1. **Bild = Dekoration, nie Identität/Trust:** der **Farb-Ring** (CYP-211-`borderColor`) + `id` + Name bleiben die Identitäts-
   Anker über **jeder** Bild-Stufe; ein Custom-Upload kann keine andere Agent-Identität vortäuschen (Farbe/`id` disambiguieren).
2. **Raster-only, kein SVG:** Client lehnt SVG/falschen Typ mit klarer Meldung ab; **Server validiert autoritativ** (Magic-Bytes,
   fail-closed) — die UI spiegelt die ehrliche Server-Ablehnung, keine stille Annahme.
3. **Fallback nie leer/kaputt:** Lade-/Server-Fehler → nächste Stufe (custom→preset→initials→color); nie ein Broken-Image, nie
   „kein Avatar".
4. **Ehrlicher Crop:** die Vorschau zeigt das **effektive** (center-crop-square) Ergebnis; der Server ist die autoritative
   Zuschnitt-/Downsize-Quelle; die Größen-Grenze wird **ehrlich angezeigt** (Backend-autoritativ).
5. **Self-hosted = kein Egress:** Seeds/Uploads gehen an keinen Dritt-Dienst (Privacy-Ehrlichkeit).
6. **Determinismus:** `{style, seed}` → stabiler Avatar (kein Runtime-Drift).
7. **Ehrliche Attribution:** alle 5 Styles im Credits-Block genannt; die 3 CC-BY sichtbar gecreditet (Artist + „CC BY 4.0" + Link +
   „bearbeitet"). Die Credits-Fläche ist präsent, weil der PNG-Render die eingebettete Attribution abstreift — kein stiller
   Lizenz-Bruch.
8. **Ein Resolver (Anti-Divergenz):** Titelbar/Roster/Comm/Event-Log nutzen **einen** `AgentAvatar` — gleiche Fallback-Kette + Ring
   überall.
9. **Operator-Gate + Farbe-nie-Träger:** Avatar-Edits operator-gated (reuse); Preset-Auswahl nicht Farbe-allein (Ring/Häkchen +
   a11y-Label).

---

## 9. §-Asks — **alle 5 vom PO/Backend resolvet (2026-07-05)**

1. **Style-Set & Default-Seed:** kuratierte 5 **bestätigt**; Default-Seed = Agent-`id` **bestätigt**; **Shuffle = MVP** (nicht mehr
   optional).
2. **Avatar-Shape (Backend):** diskriminierte Union `AgentAvatar{UPLOAD|PRESET}|null` **bestätigt** — **Backend-owned** (CYP-215),
   finale Shape liegt bei Backend (wie CYP-210 color-base-hex); meine §10-Empfehlung = Referenz.
3. **Client-Reposition-Crop:** **MVP = Server-Center-Crop** + quadratische Vorschau **bestätigt**; verschiebbarer Crop = Zukunft.
4. **Upload-Limits:** **Backend-autoritativ** **bestätigt** — UI zeigt die Grenze (`%1$s`).
5. **Style-Labels:** **Friendly deutsche Labels** **bestätigt** (§6); a11y nutzt das Friendly-Label.
- **Lizenz-Entscheid:** **alle 5 behalten + Credits-Fläche** (PO 2026-07-05); Freigabe-Liste an Backend relayed.

---

## 10. Modell / Persistenz (Referenz für Backend CYP-215)

**Empfehlung (Backend besitzt die finale Shape):** `Agent.avatar: AgentAvatar?` als **diskriminierte** Form —
`{ kind: UPLOAD, ref: <stored-image-id> }` | `{ kind: PRESET, style: String, seed: String }` | `null`.
`null` ⇒ Fallback auf Initialen/Farbe (backward-compatible). Upload vs. Preset sind **strukturell verschieden** (Blob-Ref vs.
style+seed) → eine diskriminierte Union ist die ehrliche minimale Shape (anders als die color-base-hex-Entscheidung CYP-210, wo
ein Skalar reichte).
- **Determinismus:** `{style, seed}` → derselbe Avatar überall/immer (wie `colorSlot`/`derive`).
- **Upload-Storage:** server-seitig (downsized/cropped Blob); die UI hält nur die Ref, nie rohe Bytes im DTO.

---

## 11. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** siehe `agent-avatar-keys.md` (Save/Cancel/Gate reused; +5 Friendly-Style-Labels; +4 Credits).
- **Neue Tags:** `agentSettings.avatar.*` (Erweiterung der CYP-209-`agentSettings`-Area; +`credits`) — siehe `agent-avatar-tags.md`.
- **Neue Tokens:** Avatar-/Grid-Maße + Upload-Constraints + Fallback-Kette + **`styles[]`-Lizenz-/Third-Party-License-Daten**;
  **0 neue Farben** (Ring = CYP-211-`borderColor`, Kreis = `avatarFill`) — siehe `agent-avatar-tokens.json`.
- **Kein neues Fenster-System; ein gemeinsamer `AgentAvatar`; Upload server-verarbeitet (kein Client-Bildbearbeiten im MVP).**

*Finale Spec (CYP-212 gestartet). Nur Design/Spec/Verifikation, keine Implementierung. Key/Tag-Landing mit dem CYP-216-Impl-Slice
timen (Shared-Key/Tag-Drift — CYP-7, PO koordiniert). Lizenz-Freigabe (§7) hatte Priorität für Backend CYP-215 — relayed.*
