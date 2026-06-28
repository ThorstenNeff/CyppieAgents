# Responsive Window-Manager — Desktop-Robustheit (Medium/Expanded) (v0.2)

> Owner: UIUX-Designer · Ticket: **CYP-26** (Story unter CYP-3) · Status: **Entwurf — Desktop-Linie aktiv** · Stand: 2026-06-28
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/design/WINDOW-RESPONSIVE.md`.
> Begleit-Artefakte (Muster CYP-17): `docs/design/window-responsive-tokens.json`, `window-responsive-keys.md`, `window-responsive-tags.md`.
> Bezug (im Code verifiziert, develop `ebdf442`): `window/WindowManager.kt` (`WindowHost`/`WindowCanvas`/`FloatingWindow`), `window/WindowManagerState.kt` (`WindowReducer.tile()`/`clampToBounds`/`clampSizeToBounds`/`updateHostSize`), `AgentShell.kt` (Montage + Initial-`tile()`), Composer in `agentview/AgentWindow.kt` + `comm/CommPanel.kt`. **Brand:** CyppieAgents (Anti-Hype).

> **PO-Rahmen (2026-06-28, verbindlich):** Der **Free-Floating-Window-Manager bleibt** (etablierte Desktop-Metapher). CYP-26 = **responsive Robustheit, kein Umbau.** **Kein forciertes Tiling.** Fokus: Desktop-Größen (**Medium/Expanded**); der **Compact**-Fall (Phone) ist über **S10/CYP-54** (`docs/PHONE-PAGER.md`, HorizontalPager) abgedeckt und hier **out of scope**.
> **PO-Abnahme (2026-06-28):** Spec **akzeptiert**. Entscheide: **Snap → Backlog** (nicht CYP-26-Scope; §3 nur noch Referenz); Re-Tile-Politik **bestätigt** (kein Auto-Re-Tile, explizite „Fenster einpassen"-Aktion); Maße = **Defaults, Dev kalibriert** gegen echten Desktop-Render; `tile()` **nach Host-Messung** (BoxWithConstraints) bestätigt; **RTL-Kachelung (F10) in CYP-26 gebündelt** — ja.

Spezifiziert die responsive Robustheit des frei beweglichen Fenster-Canvas auf Desktop-Hosts. Keine Implementierungsvorgabe — Verhalten + Leitplanken.

---

## 0. Geltungsbereich & Nicht-Ziele

| | |
|---|---|
| **In Scope** | Canvas-Modus (beide Achsen ≥ `Medium`): (a) Default-Layout passt in den Viewport (kein off-host Fenster), (b) Composer-Min-Breite + Robustheit, (c) Re-Flow/Clamp bei Host- und Fenster-Resize. **Plus RTL-Kachelung (F10)** — vom PO in CYP-26 gebündelt (§6). |
| **Nicht-Ziele** | **Kein** forciertes Tiling / Auto-Layout-Lock. **Kein** Umbau der Free-Floating-Metapher. **Kein** Compact/Phone-Layout (→ S10/CYP-54). **Snap = Backlog** (PO 2026-06-28, eigenes Ticket falls je gewollt; §3 nur Referenz). |

**Modus-Grenze (Recap, Quelle = `WindowHost`):** Pager sobald **eine** Achse `Compact` (S10); **Canvas nur, wenn beide ≥ `Medium`** (dieses Spec). Die **Width**-Size-Class steuert im Canvas den Spalten-Cap (§2.1).

---

## 1. Befund (Code-Stand develop `ebdf442`)

- **`tile()`** (`WindowManagerState.kt`) wählt `columns = ceil(sqrt(count))` — **ohne Size-Class-Cap**. Bei 6 Default-Fenstern → 3 Spalten. Auf einem kleinen **Medium**-Host (600–700 dp) ⇒ Zellbreite ~170–200 dp.
- **Composer** (`AgentWindow.kt`, `CommPanel.kt`): `OutlinedTextField` mit `Modifier.weight(1f)`, **keine Min-Breite**, **kein** `maxLines`/`Ellipsis` auf dem Placeholder ⇒ Placeholder **bricht zeichenweise um**, sobald die Zelle schmal wird. Genau der von Dev geflaggte Fall.
- **Clamp:** `updateHostSize()` feuert bei **jedem** Host-Resize (LaunchedEffect in `WindowHost`) und ruft `clampSizeToBounds` + `clampToBounds` für **alle** Fenster. `moveBy`/`resizeBy` clampen das bewegte Fenster. **Aber:** `tile()` läuft **nur einmal** beim Init (`AgentShell`), **kein** Re-Tile bei Resize.
- **`clampToBounds`** hält nur `MIN_VISIBLE_WINDOW = 48 dp` sichtbar (Fenster darf zu 90 % aus dem Host ragen). Für *manuelles* Verschieben ok; für das **Default-Layout** ist das zu schwach (s. §2.1).
- **Konstanten heute:** `MIN_WINDOW_WIDTH = 160`, `MIN_WINDOW_HEIGHT = 120`, `MIN_VISIBLE_WINDOW = 48`, `RESIZE_HIT_SLOP = 44`, `RESIZE_HANDLE_SIZE = 24`, `KEYBOARD_MOVE_STEP/RESIZE_STEP = 16`. **`COMPOSER_MIN_WIDTH` existiert nicht.**

---

## 2. Drei Robustheits-Garantien

### 2.1 Default-Layout passt in den Viewport (kein off-host Fenster)

**Ziel:** Nach Boot und nach einem Re-Tile liegt **jedes** Fenster **vollständig** im sichtbaren Host — nicht nur 48 dp.

1. **Spalten-Cap nach Width-Size-Class** (statt nacktem `sqrt`):
   - **Medium** (600–839 dp): **≤ 2 Spalten**.
   - **Expanded** (≥ 840 dp): `sqrt(count)` wie bisher (kein Cap).
   - Umsetzung: `columns = min(ceil(sqrt(count)), sizeClassWidthCap)`; Reihen folgen aus `ceil(count / columns)`. Bei mehr Reihen als in die Höhe passen → vertikale Kachelung darf die volle Höhe nutzen; Inhalt scrollt **innerhalb** des Fensters, nicht der Host.
2. **Host-Messung abwarten:** `tile()` darf **nicht** mit `hostWidth/Height == 0` final positionieren. Entweder erst nach der ersten echten Messung kacheln **oder** beim ersten gemessenen `updateHostSize` **einmalig neu kacheln**. (Heute: Init-`tile()` kann mit Fallback-Maßen rechnen → potenziell off-host. → Leitplanke.)
3. **Default-Clamp = voll sichtbar:** Für das **erzeugte** Layout gilt nicht der 48-dp-Floor, sondern „**vollständig im Host**" (`x ∈ [0, host−width]`, `y ∈ [0, host−height]`, vorher Größe auf Host clampen). Der 48-dp-Floor bleibt nur für **manuelles** Verschieben durch den Nutzer.

> **Bewusste Trennung:** *Default-Layout* = voll sichtbar (System erzeugt es). *Manuelles Verschieben* = 48-dp-Floor (Nutzer darf ein Fenster bewusst an den Rand schieben). Beides ehrlich, nicht widersprüchlich.

### 2.2 Composer-Min-Breite + Placeholder-Robustheit (komponentenweit, breakpoint-unabhängig)

Greift auch, wenn ein Fenster manuell schmal gezogen wird:
- **Placeholder einzeilig:** `maxLines = 1`, `softWrap = false`, `TextOverflow.Ellipsis` → degradiert per Ellipsis statt zeichenweisem Umbruch. (Gilt für **beide** Composer: Agent + Comm.)
- **`COMPOSER_MIN_WIDTH ≈ 280 dp`** als Inhaltsgarantie des Eingabefelds (Token).
- **Min-Breite gekachelter Inhalts-Fenster** (Agent/Comm) auf **≥ ~320 dp** statt generisch 160 dp, damit Composer + „Senden" nie unter die nutzbare Breite fallen. `MIN_WINDOW_WIDTH = 160` bleibt **harter Floor** für andere Fenstertypen (ACL/Event-Log lesen sich auch schmaler).
- Bei Fenster < `COMPOSER_MIN_WIDTH + Sendebutton`: Eingabefeld behält Min-Breite, **„Senden" wird zum Icon-Button** (kein Abschneiden des Labels) — Affordance bleibt, Ehrlichkeit bleibt.

### 2.3 Re-Flow / Clamp bei Resize

- **Host-Resize/Rotation:** bestehender Pfad `updateHostSize → clampSizeToBounds → clampToBounds` bleibt. **Ergänzung:** beim Verkleinern zuerst **Größe** auf Host clampen, dann **Position**, damit ein Fenster nicht „nach außen" geschoben wird, statt zu schrumpfen.
- **Re-Tile-Trigger (optional, nutzer-getriggert):** Da kein Auto-Tiling forciert wird, **kein** automatisches Re-Tile bei jedem Resize (das würde die Hand-Anordnung des Nutzers zerstören). Stattdessen eine **explizite Aktion „Fenster einpassen"** (`window_fit_action`, §Keys), die einmalig neu kachelt — heilt off-host/überlappte Zustände auf Nutzerwunsch.
- **Größenänderung eines Fensters** (`resizeBy`): bleibt im Host (`clampSizeToBounds`); Position unverändert. Korrekt.

---

## 3. Leichtes Snap — ⏸ BACKLOG (NICHT CYP-26-Scope)

> **PO-Entscheid 2026-06-28: Snap → Backlog**, eigenes Ticket falls je gewollt. CYP-26 liefert nur (a)(b)(c) + RTL. Diese Sektion bleibt als **Referenz** für ein späteres Snap-Ticket; Tokens/Keys/Tags dafür sind als „deferred" markiert. **Dev baut Snap in CYP-26 nicht.**

Vorschlag (Referenz) eines *weichen, jederzeit übersteuerbaren* Snaps beim Ziehen:
- **Edge-Snap:** Nähert sich eine Fensterkante einer **Host-Kante** auf < `SNAP_THRESHOLD ≈ 12 dp`, rastet sie an die Host-Kante (mit Standard-Rand). 
- **Peer-Snap:** Nähert sich eine Kante der Kante eines anderen Fensters auf < `SNAP_THRESHOLD`, rasten die Kanten bündig.
- **Visuelle Snap-Hilfslinie** während des Drags (Token `window.snap.guide`, reuse Outline-Hue) — Form/Position, nicht nur Farbe.
- **Jederzeit übersteuerbar:** schnelleres/weiteres Ziehen ignoriert den Snap (kein Lock). Snap ist **Komfort**, kein Layout-Zwang.
- **Default:** Vorschlag **an**, abschaltbar (`window_snap_toggle`, falls PO eine Einstellung will). RTL: Snap-Kanten spiegeln mit der Leserichtung.

> Diese Sektion ist **opt-in fürs Backlog** — markiert als Vorschlag. Falls der PO Snap nicht will, entfällt §3 vollständig ohne Auswirkung auf §2 (die Pflicht-Robustheit).

---

## 4. Tokens, i18n, testTags

- **Tokens:** Maße/Caps in `docs/design/window-responsive-tokens.json` (`COMPOSER_MIN_WIDTH`, `TILED_CONTENT_WINDOW_MIN_WIDTH`, Size-Class-Spalten-Caps, `SNAP_THRESHOLD`, Snap-Guide-Hue als Reuse). Überwiegend **dimensionale** Tokens, kaum neue Farben.
- **i18n:** wenige Strings (`window_fit_action` + a11y; optional `window_snap_toggle`) — `docs/design/window-responsive-keys.md`. Reuse vor Neuanlage.
- **testTags:** `docs/design/window-responsive-tags.md` — nutzt **bestehende** `WindowTestTags` (`window.host`, `window.<id>`, `…titlebar/content`, Resize-Handle) und ergänzt nur Affordanzen/QA-Anker für die Garantien (Default-in-Bounds, Composer-Min, Fit-Aktion, Snap-Guide).

---

## 5. Zustände & Disclosure

| Zustand | Verhalten | Disclosure-Regel |
|---|---|---|
| **Boot, Host noch nicht gemessen** | Layout erst nach erster echter Messung final positionieren | nie ein off-host Fenster „vorgaukeln" |
| **Default-Layout** | alle Fenster voll im Host | System-erzeugtes Layout ist immer voll sichtbar |
| **Nutzer schiebt Fenster an den Rand** | 48-dp-Floor (bleibt greifbar) | bewusste Nutzer-Aktion ≠ Default; Titelleiste bleibt erreichbar |
| **Host verkleinert** | erst Größe, dann Position clampen | Fenster schrumpft sichtbar, statt heimlich rauszuwandern |
| **Composer in schmalem Fenster** | Min-Breite + Ellipsis, Senden→Icon | nichts wird zeichenweise zerbrochen; Affordance bleibt |
| **„Fenster einpassen" geklickt** | einmaliges Re-Tile | nutzer-getriggert, kein heimliches Auto-Layout |

---

## 6. Randfälle

- **RTL (Mehrspalten-Kachelung):** Kachelung an der **Start-Kante** spiegeln — der bestehende **F10**-Punkt; mit CYP-26 zusammen lösen. Der Composer/Inhalt respektiert start/end automatisch.
- **Viele Fenster (Operator: bis 7)** auf kleinem Medium: Spalten-Cap 2 ⇒ mehr Reihen ⇒ Höhe knapp; Default-Layout bleibt voll sichtbar (Fenster werden flacher, aber nicht off-host). Inhalt scrollt im Fenster.
- **Host-Rotation/Split-Screen:** diskreter Bucket-Wechsel; bei Wechsel **nach** Compact greift S10 (Pager). `focusedId` bleibt erhalten (S10 §3).
- **Overlap (F12):** beim Re-Tile entzerrt; sonst Free-Floating-typisch erlaubt (z-Order via Fokus).

---

## 7. Abgrenzung zu S10/CYP-54 — verbindlich

| Bereich | Zuständig |
|---|---|
| **Compact (Phone)** — eine Achse Compact | **S10/CYP-54:** HorizontalPager (eine Seite je Fenster). |
| **Medium/Expanded (Desktop/Tablet)** — Canvas | **CYP-26 (dies):** Free-Floating + Robustheit + Spalten-Cap + optionales Snap. |
| **Komponenten-Robustheit (Composer)** | **CYP-26**, gilt **innerhalb** jeder Pager-Seite genauso (komplementär zu S10). |

→ S10 verweist bereits hierher; **keine divergierenden Specs.**

---

## 8. Entscheide (PO 2026-06-28) & verbleibende Dev-Kalibrierung

**Vom PO entschieden (keine offenen Asks mehr):**
1. ✅ **Maße = Defaults, Dev kalibriert** `COMPOSER_MIN_WIDTH` (280) / `TILED_CONTENT_WINDOW_MIN_WIDTH` (320) / Caps (Medium ≤2, Expanded sqrt) gegen den echten Desktop-Render.
2. ✅ **Re-Tile-Politik:** kein Auto-Re-Tile; **explizite „Fenster einpassen"-Aktion**.
3. ⏸ **Snap → Backlog** (eigenes Ticket, nicht CYP-26).
4. ✅ **Init-Timing:** `tile()` **nach Host-Messung** (BoxWithConstraints).
5. ✅ **RTL-Kachelung (F10) in CYP-26 gebündelt.**

**Gate:** Reviewer + Desktop-`runComposeUiTest` (Tags in `window-responsive-tags.md`). Verbleibend ist nur die Zahlen-Kalibrierung (#1) — Designwerte sind Defaults.
