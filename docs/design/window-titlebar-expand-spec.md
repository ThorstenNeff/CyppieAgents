# Titelleisten-Doppelklick: Expand+Center / Restore-Toggle — UX/UI-Design-Spec (CYP-241)

> Owner: UIUX-Designer · Story **CYP-241** (Medium, Low–Med Prio; UX-Verbesserung am Fenster-Manager S2 + CYP-26/CYP-95) · Stand: 2026-07-05
> Begleitdateien: `window-titlebar-expand-keys.md`, `-tags.md`, `-tokens.json`.
> **Grounded gg. develop `328e7ed`:** `window/WindowManagerState.kt` (`WindowState`, `WindowReducer`, `clampToBounds`/`clampSizeToBounds`/`tile`, `focus`/`moveBy`/`resizeBy`/`fit`, Konstanten), `window/WindowManager.kt` (`FloatingWindow`, Titelleisten-`Box` + `detectDragGestures`, `graphicsLayer`-Position, Keyboard-Move/Resize), `window/WindowTestTags.kt`.
> **Rein `composeApp/commonMain` Fenster-Manager (Offset/Size-State, `pointerInput`) — keine Server-/Protokoll-Änderung.** Auslöser: Auftraggeber-Feature — Doppelklick auf die Titelleiste soll ein Fenster auf eine komfortable Größe bringen und zentrieren, ein zweiter Doppelklick zurück (Toggle).

---

## 0. Verhalten (aus dem Ticket) & Leitidee

- **1. Doppelklick auf die Titelleiste:** Fenster auf **natürliche Größe** (siehe §1 — **nicht** Vollbild/fill-max) + **Screen-zentriert**; vorherige Position+Größe **gemerkt**.
- **2. Doppelklick:** **zurück** an alte Position+Größe — **nur solange seither nicht manuell verschoben/skaliert** wurde; sonst → **frischer Expand+Center** (Empfehlung §2).

**Leitidee:** Ein **Toggle über einen transienten Restore-Anker** — kein Float-Vergleich der Geometrie, sondern *Anker vorhanden ⇒ Restore, Anker weg ⇒ (Re-)Expand*. Jede echte Nutzer-Bewegung/Skalierung löscht den Anker; damit ist die Invalidierung §2 **automatisch und robust** (keine Toleranz-/Schwellwert-Heuristik nötig).

**Ehrlichkeits-Kern (mein Lane):** „natürliche/wrap-content Größe" **kann für den scrollenden Event-Stream-Renderer nicht wörtlich** „alles sichtbar" bedeuten (Inhalt ist unbegrenzt). Das Feature vergrößert auf eine **Vorzugsgröße, viewport-gecappt** — und die Zustands-/a11y-Copy sagt **„vergrößert & zentriert"**, **nicht** „volle Größe"/„alle Inhalte sichtbar". Kein überversprochener Zustand.

---

## 1. „Natürliche Größe" konkret (Design-Entscheidung 1)

Ein Agenten-Fenster rendert einen **scrollenden Event-Stream** → **echte** Content-Höhe ist unbegrenzt, gemessenes wrap-content also kein sinnvoller Zielwert. Entscheidung:

> **Expand-Ziel = eine definierte Vorzugsgröße je Fenstertyp, komponentenweise auf den nutzbaren Viewport gecappt, mindestens auf die Typ-Mindestgröße gefloort.** Nie Vollbild (immer Rand-Margin), nie Überlauf (CYP-95-konsistent).

Berechnung (alle Werte dp, Tokens in `-tokens.json`, **abstimmbar**):

```
usableW = hostWidth  − 2·EXPAND_MARGIN
usableH = hostHeight − HOST_AFFORDANCE_BAND − 2·EXPAND_MARGIN      // Top-Band (56f) reserviert wie im tile()
prefW   = if (id ∈ contentWindowIds) EXPAND_PREFERRED_CONTENT_W else EXPAND_PREFERRED_DEFAULT_W
prefH   = if (id ∈ contentWindowIds) EXPAND_PREFERRED_CONTENT_H else EXPAND_PREFERRED_DEFAULT_H
typeMin = if (id ∈ contentWindowIds) TILED_CONTENT_WINDOW_MIN_WIDTH (320f) else MIN_WINDOW_WIDTH (160f)

width  = min(prefW, usableW).coerceAtLeast(typeMin)
height = min(prefH, usableH).coerceAtLeast(MIN_WINDOW_HEIGHT (120f))
```

- **Nie fill-max:** `EXPAND_MARGIN` (24f) hält beidseitig Luft → liest sich als „großes zentriertes Fenster", nicht als Vollbild.
- **Nie Überlauf:** die `min(pref, usable)`-Cappe + der abschließende `clampSizeToBounds`/`clampToBounds` (Reuse, §5) sind **dieselbe** Viewport-Disziplin wie CYP-95.
- **Content-Fenster (Agent/Comm)** bekommen die größere Vorzugsgröße (konsistent mit dem breiteren `TILED_CONTENT_WINDOW_MIN_WIDTH`-Floor im `tile()`).

**Zentrierung (Design-Entscheidung: Fenster-Mittelpunkt = nutzbarer Screen-Mittelpunkt):**
```
x = (hostWidth − width) / 2f
y = HOST_AFFORDANCE_BAND + (hostHeight − HOST_AFFORDANCE_BAND − height) / 2f
→ danach WindowReducer.clampToBounds(…, keepVisible = MIN_VISIBLE_WINDOW)   // defensiv, CYP-95
```
Das Top-Band (Fit-Toolbar-Affordance, `HOST_AFFORDANCE_BAND = 56f`) bleibt frei — die Zentrierung rechnet im **nutzbaren** Bereich unterhalb des Bands, exakt wie `tile()`.

---

## 2. Restore-Invalidierung — exakte Regel (Design-Entscheidung 2)

**Mechanismus: ein transienter Anker `expandAnchors: Map<String, WindowState>` in `WindowManagerState`.** (Transient = **nicht** persistiert — siehe §7.)

Eine **einzige** Reducer-Aktion `toggleExpand(id, hostWidth, hostHeight, isRtl)`:

- **Anker für `id` fehlt** → *Expand*: aktuelle `WindowState` als Anker speichern, Geometrie auf Expand-Ziel (§1) setzen, `focus(id)`.
- **Anker für `id` vorhanden** → *Restore*: Geometrie = Anker (defensiv `clampToBounds`/`clampSizeToBounds`), Anker **entfernen**, `focus(id)`.

**Invalidierung (automatisch):** jede **echte Nutzer-Manipulation** löscht den Anker für dieses Fenster:
- `moveBy(id, …)` (Titelleisten-Drag **und** Pfeiltasten-Move) → Anker `id` löschen.
- `resizeBy(id, …)` (Resize-Griff **und** Pfeiltasten-Resize) → Anker `id` löschen.
- `fit(…)` (globales Re-Tile, CYP-26) → **alle** Anker löschen (§4).

> Der Expand/Restore in `toggleExpand` setzt die Geometrie **direkt** (nicht über `moveBy`/`resizeBy`) und verwaltet den Anker explizit — er löscht sich also nicht selbst.

**Was macht der 2. Doppelklick, wenn zwischenzeitlich verändert wurde? → EMPFEHLUNG: frischer Expand+Center** (kein No-op). Da eine Nutzer-Bewegung den Anker gelöscht hat, sieht `toggleExpand` „kein Anker" und **re-ankert** die aktuelle Geometrie + expandiert erneut. Begründung: der Doppelklick tut **immer** etwas Vorhersagbares (Toggle-zurück wenn unberührt, sonst neu vergrößern+zentrieren) — **kein toter Doppelklick**, keine „warum passiert nichts?"-Verwirrung.

Damit ist „verändert" **präzise = mindestens ein `moveBy`/`resizeBy` seit dem Expand** (jede Achse, jede Quelle) — keine Toleranz-Schwelle, kein Float-Equality-Risiko.

---

## 3. Doppelklick-vs-Drag-Disambiguierung (Design-Entscheidung 3)

Die Titelleiste (`Box`, Tag `WindowTestTags.titleBar(id)`) trägt heute **einen** `pointerInput`-Block mit `detectDragGestures`. Ergänzung: **ein zweiter, separater** `pointerInput`-Block mit `detectTapGestures(onDoubleTap = …)`:

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth().background(barBg)
        .testTag(WindowTestTags.titleBar(window.id))
        .semantics { /* §7: contentDescription bleibt; stateDescription = Expand-Zustand */ }
        .pointerInput(window.id) {                       // NEU — neben, nicht statt
            detectTapGestures(onDoubleTap = { onToggleExpand() })
        }
        .pointerInput(window.id) {                       // bestehend
            detectDragGestures(onDragStart = { onFocus() }, onDrag = { c, d -> c.consume(); onMove(...) })
        },
)
```

- **Koexistenz:** ein **Drag** startet erst nach Bewegung > Touch-Slop → `detectDragGestures` gewinnt bei Bewegung; ein **stationärer Doppeltipp** löst `onDoubleTap` aus. Bewegt der Nutzer während der Tap-Erwartung, verwirft `detectTapGestures` den Tap und der Drag läuft. Die zwei Gesten teilen sich **nicht** denselben Endzustand → kein Konflikt.
- **`onToggleExpand`** ruft intern `state.focus(id)` (über den Reducer) → **kein** zusätzliches `onFocus` im Tap-Handler nötig (idempotent, aber sauber im State gekapselt).
- **Kein neuer sichtbarer Control** — die Affordance ist der Doppelklick auf die **bestehende** Titelleiste. (Discoverability: §7 stateDescription + optionale Hilfe; kein Overlay-Button, konsistent mit dem schlanken Fenster-Chrome.)

**Neue Verdrahtung (minimal, gleiche Architektur):** `FloatingWindow(… onToggleExpand: () -> Unit)` → Aufrufsite (`WindowManager.kt` ~L165) `onToggleExpand = { state.toggleExpand(window.id, hostWidth, hostHeight, isRtl) }`, analog zu den bestehenden `onFocus`/`onMove`/`onResize`.

---

## 4. Zusammenspiel mit Re-Tile / Responsive (CYP-26 / CYP-95) (Design-Entscheidung 4)

- **Expand+Center ist ein Pro-Fenster-Override, kein strukturelles Re-Layout:** `toggleExpand` ruft **nie** `fit()`/`tile()`; es ändert nur die Geometrie **eines** Fensters. Das Tiling liest den stabilen `windowOrder`, nicht die Geometrie → kein Konflikt.
- **Globales Re-Tile invalidiert den Restore-Anker:** `fit()` (die „Fenster anordnen"-Affordance, `WindowTestTags.FIT`) weist allen Fenstern frische Geometrie zu → **alle** `expandAnchors` löschen (die „alte Position" existiert nicht mehr; ein Restore darauf wäre eine Lüge). Nach einem Re-Tile startet ein Doppelklick einen **neuen** Expand-Zyklus.
- **Viewport-Konsistenz:** Expand nutzt dieselben Grenzen wie Tiling — Top-Band reserviert, `clampToBounds`/`clampSizeToBounds` als Cappe. Ein Re-Layout (z. B. Host-Resize → `fit()`) überschreibt den Expand ohnehin sauber.

---

## 5. Fokus/Z-Order & Reuse (Design-Entscheidung 5)

- **Expand und Restore bringen das Fenster nach vorn:** `toggleExpand` ruft `focus(id)` → `WindowReducer.bringToFront` → Fenster wird `focusedId` (letztes in `windows`, oberste Z-Ebene). Ein vergrößertes, zentriertes Fenster ist das primäre Arbeitsfenster → Fokus ist erwartet und konsistent mit dem bestehenden Fokus-Modell.
- **Reuse (keine divergente Geometrie-Logik):** `toggleExpand` nutzt die **bestehenden** `WindowReducer.clampToBounds`/`clampSizeToBounds` und die **bestehenden** Konstanten (`MIN_VISIBLE_WINDOW`, `HOST_AFFORDANCE_BAND`, `TILED_CONTENT_WINDOW_MIN_WIDTH`, `MIN_WINDOW_WIDTH`, `MIN_WINDOW_HEIGHT`). Neu sind nur: der `expandAnchors`-Map, die `toggleExpand`-Aktion, drei Layout-Tokens (§1) und die Anker-Löschung in `moveBy`/`resizeBy`/`fit`.

---

## 6. Animation (Design-Entscheidung 6)

**Empfehlung: instant (kein Tween) — konsistent mit der bestehenden Fenster-Motion.** Der Drag bewegt heute 1:1/direkt (`graphicsLayer`-Translation, keine Feder/kein Tween im Code); ein plötzlich animiertes Expand fiele aus dem Muster. Zusätzlich hält *instant* den **Restore-Zustand sauber**: die „unberührt seit Expand"-Prüfung (§2) hängt an der Anker-Präsenz, nicht an Zwischen-Frames — eine Animation würde keine Logik brechen (Anker-Modell ist geometrie-unabhängig), aber *instant* ist die einfachere, test-stabilere Wahl.

> **Optional/deferred (nicht MVP):** ein sanftes ~180–220 ms Ease ließe sich **rein visuell** ergänzen (animierte `graphicsLayer`-Translation/Size über der sofort gesetzten Float-Geometrie), ohne das State-Modell oder die Anker-Logik anzufassen. Erst wenn der Fenster-Manager generell Motion bekommt.

---

## 7. testTags / Semantik / a11y (Design-Entscheidung 7)

**Kein neuer Tag** — die Geste sitzt auf der bestehenden Titelleiste:
- **Gesten-Ziel (QA):** `WindowTestTags.titleBar(id)` — der Test doppeltippt diesen Knoten.
- **Zustands-Exposition (QA + a11y):** `stateDescription` am Fenster-/Titelleisten-Knoten spiegelt die **Anker-Präsenz** (= Expand-mit-Restore-verfügbar):
  - Anker vorhanden → `window_state_expanded` — DE „Vergrößert und zentriert" / EN „Enlarged and centered".
  - kein Anker → `window_state_normal` — DE „Normalgröße" / EN „Normal size".

  **Ehrlichkeits-Anker:** die Copy sagt **„vergrößert und zentriert"**, **nicht** „volle Größe" / „alle Inhalte sichtbar" — der scrollende Renderer zeigt weiterhin nur einen Ausschnitt (§0/§1). `stateDescription` = **Anker-Präsenz**, nicht „ist groß": zieht der Nutzer ein vergrößertes Fenster weg (Anker gelöscht), fällt der Zustand ehrlich auf „Normalgröße" zurück (Restore nicht mehr verfügbar), obwohl es visuell noch groß ist. So verspricht der Zustand nie ein Restore, das es nicht mehr gibt.

- **QA-Verifikation** (3 Verhalten, alle über `stateDescription` + Geometrie-Assertions am State-Reducer):
  1. nach 1. Doppelklick → `window_state_expanded`; Geometrie = Expand-Ziel, zentriert, ≤ Viewport.
  2. nach 2. Doppelklick (unberührt) → `window_state_normal`; Geometrie == Original.
  3. nach Drag/Resize + 2. Doppelklick → `window_state_expanded` (frischer Re-Expand; Anker war gelöscht) — **kein toter Doppelklick**.
  4. nach `fit()` (Re-Tile) → `window_state_normal` an allen Fenstern (Anker gelöscht).

- **a11y-Empfehlung (Parität):** der Doppelklick ist eine **Zeiger**-Geste. Das Fenster trägt bereits eine **Tastatur-Steuerung** (Pfeiltasten-Move/Resize, `KEYBOARD_MOVE_STEP`). Für Parität ein **Tastatur-Äquivalent** für Expand/Restore empfehlen (z. B. eine dokumentierte Taste auf der fokussierten Titelleiste, die `onToggleExpand()` auslöst). **Nicht blockierend** (das Fenster bleibt ohne das Feature voll bedienbar), aber ehrlich zu ergänzen — sonst ist eine Komfortfunktion zeiger-exklusiv. Empfehlung: klein mitnehmen oder als Forward-Item CYP-241a führen.

---

## 8. Modell / Verhalten (Delta, minimal)

**Neu (alles `commonMain` Fenster-Manager):**
- `WindowManagerState.expandAnchors: Map<String, WindowState>` — **transient** (nicht serialisiert; siehe unten), Session-lokal.
- `WindowManagerState.toggleExpand(id, hostWidth, hostHeight, isRtl)` (+ reine `WindowReducer`-Hilfsfunktion für Expand-Ziel & Zentrierung, testbar außerhalb Compose wie der Rest).
- Anker-Löschung in `moveBy`/`resizeBy` (pro `id`) und `fit` (alle).
- `FloatingWindow(… onToggleExpand)` + `detectTapGestures(onDoubleTap)` auf der Titelleiste.
- `stateDescription` (2 Keys) am Fenster-Knoten.
- 3 Layout-Tokens (`-tokens.json`).

**Unverändert:** `WindowState`-Felder, `tile()`/`fit()`-Tiling-Logik, `clampToBounds`/`clampSizeToBounds`, Drag/Resize/Keyboard-Pfade (bekommen nur die Anker-Löschung), Server/Protokoll — nichts.

> **Persistenz-Ehrlichkeit (§7 vertieft):** CYP-204 persistiert Fenster-Geometrie. Der Restore-Anker ist **bewusst transient** — nach einem Reload/Neustart gibt es **kein** stale „Restore auf die Position von letzter Sitzung". Der Anker lebt nur, solange das Fenster seit dem Expand unberührt in **dieser** Sitzung ist. Das ist die ehrliche Semantik von „zurück, solange nicht verändert".

---

## 9. Disclosure-Ehrlichkeit — Invarianten (Abnahme-Checkliste UX-QA)

1. **„Natürliche Größe" ehrlich:** Expand-Ziel = Vorzugsgröße **viewport-gecappt**, **nie** Vollbild (Margin), **nie** Überlauf (`clampSizeToBounds`), gefloort auf Typ-Min. Copy/`stateDescription` = „vergrößert & zentriert", **nicht** „alles sichtbar".
2. **Zentrierung:** Fenster-Mittelpunkt = **nutzbarer** Screen-Mittelpunkt (Top-Band `HOST_AFFORDANCE_BAND` reserviert), defensiv geclampt.
3. **Restore nur wenn unberührt:** Anker bei Expand gesetzt; **jede** user-`moveBy`/`resizeBy` (Drag, Resize-Griff, Pfeiltasten) löscht ihn; 2. Doppelklick restauriert nur bei Anker, sonst **frischer Expand+Center** (re-anker) — **kein toter Doppelklick**.
4. **Doppelklick ≠ Drag:** separater `detectTapGestures(onDoubleTap)` neben `detectDragGestures`; stationärer Doppeltipp und bewegungs-basierter Drag kollidieren nicht.
5. **Re-Tile-Konsistenz:** globales `fit()` (CYP-26) löscht **alle** Anker; Expand triggert **nie** ein Re-Tile; Expand respektiert die CYP-95-Viewport-Grenzen.
6. **Fokus/Z-Order:** Expand **und** Restore rufen `focus(id)` → Fenster nach vorn.
7. **Anker transient:** `expandAnchors` **nicht** persistiert (kein stale Restore nach Reload/CYP-204); Session-lokal.
8. **a11y + Parität:** `stateDescription` (expanded/normal) lesbar & ehrlich; **DE+EN 1:1**; Tastatur-Äquivalent empfohlen (Parität zur bestehenden Pfeiltasten-Steuerung).
9. **Instant/konsistent:** kein Tween (konsistent mit der 1:1-Drag-Motion); smooth = deferred optional.

---

## 10. Counts / Reuse (Selbst-Validierung → Begleitdateien)

- **Neue i18n-Keys:** **2** (`window_state_expanded`, `window_state_normal`), DE+EN-Parität — siehe `window-titlebar-expand-keys.md`.
- **Neue Tags:** **0** — Geste auf bestehendem `WindowTestTags.titleBar(id)`; Zustand via `stateDescription` (Semantik, kein Tag) — siehe `window-titlebar-expand-tags.md`.
- **Neue Tokens:** **3** Layout-Konstanten (`EXPAND_PREFERRED_CONTENT_W/H`, `EXPAND_PREFERRED_DEFAULT_W/H` [4 Werte], `EXPAND_MARGIN`), **0 Farben** — siehe `window-titlebar-expand-tokens.json`.
- **Größe: S–M** — reiner Fenster-Manager (State-Reducer + ein Gesten-Handler + Semantik), Reuse der Clamp/Tile-Infrastruktur, keine Model-/Server-Änderung.

*Nur Design/Spec/Verifikation, keine Implementierung. Key/Tag-Landing mit dem CYP-241-Impl-Slice timen (CYP-7). Sequenzierung: Impl nach CYP-240-Live-Bugs (Ticket); Design-Pass ist ungated. UX-QA nach Dev-Impl durch UIUX (Invarianten §9).*
