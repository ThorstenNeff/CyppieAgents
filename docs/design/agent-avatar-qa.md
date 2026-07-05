# UX-QA-Abnahme-Checkliste — Per-Agent-Avatar (CYP-214 · Gate für CYP-216)

> Owner: UIUX-Designer · Story **CYP-214** (Design/QA-Prep; Gate für Dev **CYP-216**) · Stand: 2026-07-05 · Status: **QA-Prep**
> Begleitdateien: `agent-avatar-spec.md` (§8 = die 9 Invarianten; §5.1 = Titelbar-Blend), `-keys.md`, `-tags.md`, `-tokens.json`.
> **Grounded gg. develop `22ce97c`:** CYP-214-Spec + **CYP-215-Backend gemergt** — `AgentAvatar = Preset(style, seed) | Upload(ref)`
> (`core/model/AgentAvatarModel.kt`); `POST /api/agents/{id}/avatar` (multipart, **operator-gated**), `GET /api/agents/{id}/avatar`
> (**participant-gated**, fehlend → **404** → Client-Default); Reject → uniform `avatar_rejected` (Magic-Bytes, **kein SVG/kein WebP**,
> Dim-Cap-vor-Decode, center-crop-**256** + Re-Encode strippt EXIF/Payload); **write = Preset-only** (Upload-`ref` strukturell
> unfälschbar); 5-Style-Allow-List; restart-durable + project-cascade-purge.
> **Zweck:** damit der UX-QA-Pass **scharf ist, sobald Devs CYP-216-Build steht** — je Invariante: Prüf-Aktion · Erwartet · Anker.

---

## Wie zu lesen / auszuführen

**Verifikations-Vehikel (Anker-Legende):**
- **[UI]** — Compose-UI-Test (`:app:shared:jvmTest`) / Maestro über `testTag` (die Anker unten).
- **[Code]** — statische Review der **Client**-Logik (AgentAvatar-Resolver, Farb-Ableitung `deriveScheme`).
- **[Int]** — Integration gegen die realen CYP-215-Routes (**server-autoritativ**, `22ce97c`).
- **[Vis]** — Screenshot-Sichtprüfung (Kontrast/Layout) — nur wo Pixel nötig sind.

**GO/No-Go-Rubrik:** **alle 9 Invarianten + Titelbar-Blend (QA-8b) + Format-Reject (QA-2) grün = 🟢 GO.** Jede rot/ungeprüft = 🔴
No-Go mit **Finding (Severity + konkreter Fix)** an den Koordinator; Re-Verify nach Dev-Fold. Prinzip wie bei CYP-189/CYP-211:
**statisch code-verifiziert + test-verankert**, nicht „kompiliert = ok".

---

## Die Checkliste (QA-1 … QA-9)

### QA-1 — Bild = Dekoration, nie Identität/Trust (§8.1)
- **Aktion:** (a) Zwei Agenten auf **dasselbe** Bild (Preset {gleicher style,seed} oder gleicher Upload) setzen → prüfe, ob sie
  weiterhin durch **Farb-Ring + Name + `id`** unterscheidbar sind. (b) [Int] Versuch, per Edit einen fremden Upload-`ref`
  zuzuweisen (`AgentEdit(avatar = Upload("<fremd>"))`).
- **Erwartet:** (a) Ring (`borderColor`, in Titelbar `content`) auf **jeder** Stufe präsent; identisches Bild ≠ identische Identität.
  (b) **abgelehnt/ignoriert** — **write = Preset-only**, Upload nur über den gated Multipart-Pfad → `ref` strukturell unfälschbar.
- **Anker:** `agentSettings.avatar.current` (Ring präsent) [UI] · Ring-auf-jeder-Stufe [Code] · Upload-ref-unfälschbar [Int].
- **Verdikt:** Ring immer sichtbar; ein Custom-Upload kann keine andere Agent-Identität vortäuschen.

### QA-2 — Raster-only, png/jpg, **kein SVG/kein WebP**; ehrliche Ablehnung (§8.2)
- **Aktion:** Upload je einer Datei: (a) `.svg`, (b) `.webp`, (c) übergroße `.png`, (d) valide `.png`/`.jpg`.
- **Erwartet:** (a/b) `agentSettings.avatar.uploadError` = **„Nur PNG oder JPG – kein SVG"** (ERROR), **kein** gespeicherter Avatar;
  (c) `agent_avatar_upload_size_error` mit Backend-Limit (`%1$s`); (d) Erfolg. **Client-Vorprüfung** nennt den **konkreten** Grund;
  **Server-Backstop** (auch bei umgangener Vorprüfung): `POST …/avatar` → uniform `avatar_rejected` (Magic-Bytes, nicht Extension),
  fail-closed. Die UI **verspricht kein** Format, das der Server ablehnt (kein WebP im Text).
- **Anker:** `agentSettings.avatar.uploadError` (Text je Fall) [UI] · `agent_avatar_upload_type_error`/`_size_error` [UI] ·
  `POST /api/agents/{id}/avatar` → `avatar_rejected` [Int].
- **Verdikt:** ehrliche, fail-closed Ablehnung; SVG = security-verboten, WebP = nicht unterstützt — beide gespiegelt, kein stilles Ja.

### QA-3 — Fallback nie leer/kaputt (§8.4)
- **Aktion:** Durchlaufe die Kette + Fehlerfall: (1) Upload gesetzt & ladbar; (2) Upload weg, Preset gesetzt & ladbar; (3) beide
  weg, Name gesetzt → Initialen; (4) Name leer → Farb-Kreis „?". Dann **Bild-Ladefehler erzwingen** (Preset-/Upload-URL nicht
  erreichbar / `GET …/avatar` → 404).
- **Erwartet:** effektive Stufe je Zustand korrekt; bei Ladefehler **automatischer Fall auf die nächste Stufe**; **nie** Broken-Image,
  **nie** leer. (`GET …/avatar` 404 → Client-Default = Stufe 3/4.)
- **Anker:** `agentSettings.avatar.current` **+ effektive-Stufe-Semantik** (→ **Test-Hook**, s. u.) [UI] · Fallback-Logik [Code] ·
  404-Serve [Int] · sonst [Vis].
- **Verdikt:** alle 4 Stufen + Fehlerfall zeigen ein identitäts-tragendes Element; nie leer/kaputt.

### QA-4 — Ehrlicher Crop + Limit-Anzeige (§8.4→ Backend center-crop-256)
- **Aktion:** Upload eines **nicht-quadratischen** Bildes → prüfe `agentSettings.avatar.preview` und den Crop-Hinweis.
- **Erwartet:** Vorschau = **effektives** center-crop-square-Ergebnis (Server macht center-crop-**256** + Re-Encode, autoritativ) —
  **nicht** das rohe Bild; `agent_avatar_crop_hint` „Wird mittig quadratisch zugeschnitten" (INFO); Größen-Limit **ehrlich angezeigt**
  (Backend-Wert). Re-Encode **strippt EXIF/Payload** (Privacy — kein verstecktes Metadaten-Leak im gespeicherten Bild).
- **Anker:** `agentSettings.avatar.preview` (quadratisch) [UI] · Crop-Hinweis-Text [UI] · Server-Zuschnitt/EXIF-Strip [Int].
- **Verdikt:** Vorschau spiegelt das echte Ergebnis; Limit ehrlich; kein rohes Bild-Versprechen.

### QA-5 — Self-hosted = kein Egress (§8.5)
- **Aktion:** Netzwerk-Trace beim Preset-Preview-Laden **und** beim Upload/Serve.
- **Erwartet:** **0 Dritt-Egress** — alle Avatar-Bild-Requests gehen an den **self-hosted Host** (Backend-Config) bzw.
  `/api/agents/{id}/avatar`; Seed/Upload verlassen die Plattform **nicht**. Serve ist **participant-gated** (kein öffentliches Bild).
- **Anker:** **[Int]/Netzwerk** (kein `testTag`) · URL-Schema `<selfHostedHost>/…` [Code].
- **Verdikt:** keine Requests an dicebear.com o. ä.; Bilder nur an Berechtigte serviert.

### QA-6 — Determinismus (§8.6)
- **Aktion:** `{style, seed}` setzen → mehrfach rendern / neu laden. `agentSettings.avatar.shuffle` betätigen → Seed-Reroll.
- **Erwartet:** gleicher `{style, seed}` → **gleicher** Avatar (kein Runtime-Drift); Shuffle liefert einen **anderen, aber stabilen**
  Avatar desselben Styles; gespeicherter Seed reproduziert exakt.
- **Anker:** `agentSettings.avatar.presetStyle.<style>` (Auswahl stabil) [UI] · `agentSettings.avatar.shuffle` [UI] · Seed→URL
  deterministisch [Code] · gleicher-Seed-gleiches-Bild [Vis].
- **Verdikt:** reproduzierbar, kein Drift.

### QA-7 — Ehrliche Attribution / Credits (§8.7)
- **Aktion:** `agentSettings.avatar.credits` öffnen → Inhalt prüfen.
- **Erwartet:** **Credits-Fläche präsent** (weil PNG den `<metadata>`-Attribution-Block strippt). **Alle 5** Styles gelistet; die **3
  CC-BY** (`adventurer`/`big-smile`/`fun-emoji`) mit **Artist + „CC BY 4.0" + Link + „(bearbeitet)"** (`agent_avatar_credit_line` +
  `agent_avatar_credit_modified`); `bottts`/`avataaars` als Courtesy-Credit (Artist + „free for personal & commercial").
- **Anker:** `agentSettings.avatar.credits` (präsent) [UI] · Credit-Zeilen-Text je Style (Artist/„CC BY 4.0"/„bearbeitet"/Link)
  [UI] — **adressierbare Zeilen nötig** (→ Test-Hook) · `styles[]`-Daten [Code].
- **Verdikt:** alle 5 genannt; 3 CC-BY vollständig attribuiert + Link + Änderungshinweis; kein stiller Lizenz-Bruch.

### QA-8 — Ein Resolver / Anti-Divergenz (§8.8) **+ Titelbar-invertierte-Scheibe (§5.1)**
- **QA-8a Aktion:** einen Avatar (Preset/Upload) setzen → an **Titelbar, Roster, Comm, Event-Log** vergleichen.
  - **Erwartet:** identische Stufe + Ring an allen Sites (**ein** `AgentAvatar`; kein Per-Site-One-off).
  - **Anker:** `window.<id>.titlebar` (+ optional `agentAvatar.<agentId>`), Comm-/Event-Row-Tags [UI] · alle Sites rufen `AgentAvatar`
    [Code].
- **QA-8b Aktion (Titelbar-Blend):** Agent-getönte Titelbar (fokussiert **und** unfokussiert) — Avatar ansehen.
  - **Erwartet:** Avatar **hebt sich ab** — Scheibe = `it.content` (**nicht** `avatarFill`), Glyph in `it.background`, Ring =
    `it.content`; **Kontrast Glyph↔Scheibe ≥ 4.5:1 in BEIDEN Fokus-Zuständen** (garantiert durch `deriveScheme`: content/background
    ist die ≥4.5:1-Paarung). 20.dp / 1.5.dp Ring. **Nicht** der agent-getönte Fill (der würde verschwimmen).
  - **Anker:** Kontrast ≥4.5:1 [Code] (disc=`it.content`, glyph=`it.background` aus `deriveScheme`) + [Vis] Sichtprüfung auf getönter
    Leiste · neutrale Sites unberührt (Fill=`avatarFill`, Ring=`borderColor`) [Code].
- **Verdikt:** kein Per-Site-Divergenz; Titelbar-Avatar legibel (≥4.5:1) auf getönter Leiste; Farbe bleibt Identität (als Figur).

### QA-9 — Operator-Gate + Farbe-nie-alleiniger-Träger (§8.9)
- **Aktion:** (a) als **Nicht-Operator (MEMBER)** das Panel öffnen; (b) einen Preset-Style auswählen.
- **Erwartet:** (a) Avatar-Edits **disabled** + `agentSettings.gateHint` (GATED, reuse CYP-209); **server-seitig** ebenfalls
  operator-gated (`POST/DELETE …/avatar` → 403 für Nicht-Operator, `22ce97c`) → fail-closed doppelt. (b) Auswahl-Indikator **nicht
  Farbe-allein** — aktiver `agentSettings.avatar.presetStyle.<style>` trägt `selected`-Semantik + a11y (`a11y_agent_avatar_preset`
  „Avatar-Vorlage %1$s" = Friendly-Label).
- **Anker:** `agentSettings.gateHint` (präsent bei Nicht-Operator) [UI] · `POST …/avatar` 403 non-operator [Int] ·
  `presetStyle.<style>` `assertIsSelected` + a11y [UI].
- **Verdikt:** Edits operator-gated (Client **und** Server); Auswahl semantisch, nicht farb-allein.

---

## Benötigte Test-Hooks (Bitte an Dev/CYP-216 — mit dem Slice landen)

Damit QA-1/QA-3/QA-8 die **effektive Fallback-Stufe** deterministisch (nicht per Pixel) prüfen kann, und QA-7 die Credit-Zeilen
adressieren kann — bitte diese Anker mit dem CYP-216-Impl-Slice exponieren (Test-Contract v0.5, punktfrei; koordiniert über PO/CYP-7):

1. **Effektive-Stufe-Semantik** auf `agentSettings.avatar.current` (und den Render-Site-Avataren): ein **stateDescription** oder
   Tag-Qualifier `…current.stage-<image|preset|initials|color>` → QA assertet die aktive Stufe statt Broken-Image-Peeking. **(löst
   QA-3 sauber; stützt QA-1/QA-8a.)**
2. **Adressierbare Credit-Zeilen** in `agentSettings.avatar.credits`: Container-Text-Assertion genügt, oder optional per-Style-Row-
   Tag `…credits.entry-<style>` → QA prüft die 3 CC-BY-Zeilen (Artist/„CC BY 4.0"/„bearbeitet"/Link) gezielt. **(QA-7.)**
3. **Optional `agentAvatar.<agentId>`** (schon als Forward-prep in `-tags.md §3` vorgesehen): falls QA den Avatar site-übergreifend
   adressieren will (QA-8a). Nicht zwingend, wenn die bestehenden Site-Tags reichen.

> Diese Hooks sind **Test-Vertrag** (nicht still umbenennen) und **Shared-Tag-Drift** → timen **mit** dem CYP-216-Slice.

---

## Backend-Vertrag ↔ Invariante (Anker gg. `22ce97c` / CYP-215)

| Invariante | Server-autoritative Stütze (CYP-215) |
|---|---|
| QA-1 (keine Identitäts-Fälschung) | **write = Preset-only**; `Upload(ref)` nur über gated Multipart → `ref` strukturell unfälschbar |
| QA-2 (kein SVG/WebP, ehrliche Ablehnung) | Magic-Bytes (nicht Extension), no-SVG/no-webp, uniform `avatar_rejected`, Dim-Cap-vor-Decode |
| QA-4 (ehrlicher Crop, Privacy) | center-crop-**256** + Re-Encode strippt EXIF/Payload; traversal-safe Storage |
| QA-5 (kein Egress, participant-only) | DiceBear **self-hosted** Resolver (5-Style-Allow-List); `GET …/avatar` **participant-gated** |
| QA-9 (Operator-Gate) | `POST/DELETE …/avatar` **operator-gated** (Client-Gate ist Komfort, Server ist die Wahrheit) |
| (Durabilität) | `AgentOverride.avatar` restart-durable + **project-cascade-purge** (kein Orphan) |

*QA-Prep, kein Impl. Landet als CYP-214-Docs-Addendum; der Pass läuft, sobald Devs CYP-216-Build steht. Findings → Koordinator
(Severity + konkreter Fix), Re-Verify nach Fold.*
