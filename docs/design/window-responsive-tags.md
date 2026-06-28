# testTag-Schema — Responsive Window-Manager (Desktop) (v0.2)

> Owner: UIUX-Designer · Ticket: **CYP-26** · Speist CYP-26-Impl · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/design/WINDOW-RESPONSIVE.md`, `window-responsive-tokens.json`, `window-responsive-keys.md`.
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte `[A-Za-z0-9-]+` (**keine Punkte**).
> **Vertrag Dev↔QA (CYP-7):** API, nicht still umbenennen. Gate: Reviewer + Desktop-`runComposeUiTest`.

Macht die drei Robustheits-Garantien (Default-in-Bounds, Composer-Min, Re-Flow) + die Fit-Aktion + (optional) Snap test-adressierbar. **Reuse:** die bestehenden `WindowTestTags` bleiben die Basis.

---

## 0. Bestehende Tags (Reuse, unverändert — Quelle `window/WindowTestTags.kt`)

| Element | testTag | Genutzt von CYP-26-QA für |
|---|---|---|
| Canvas-Host | `window.host` | Modus-Beleg (Canvas ⇔ beide ≥ Medium; XOR `phonePager.pager`) |
| Fenster (je id) | `window.<id>` | Default-in-Bounds-Assertion (Position/Größe vs. Host) |
| Titelleiste | `window.<id>.titlebar` | Drag/Fokus |
| Inhalt | `window.<id>.content` | Composer-Min-Breite |
| Resize-Griff | `window.<id>.resize` | Resize-Clamp |

> Es werden **keine** Fenster-id-Schemata neu erfunden. CYP-26 ergänzt nur Host-Affordanzen (unten).

## 1. Neue Affordanzen — Area `window`, Scope `host`

| Element | testTag | Zweck |
|---|---|---|
| Fenster einpassen | `window.host.fit` | nutzer-getriggertes einmaliges Re-Tile (`window_fit_action`) |
| Snap-Hilfslinie *(optional)* | `window.host.snapguide` | nur falls §3 übernommen; während des Drags sichtbar |
| Snap-Schalter *(optional)* | `window.host.snaptoggle` | nur falls Snap abschaltbar |

## 2. Composer-Robustheit (Reuse Renderer-Tags)

| Element | testTag | Zweck |
|---|---|---|
| Agent-Composer-Input | `agentView.<id>.input` | bestehend (`AgentViewTags`) — Min-Breite/Ellipsis prüfen |
| Comm-Composer-Input | `comm.composer.input` | bestehend (`CommTags`) — Min-Breite/Ellipsis prüfen |

> Composer-Tags bleiben in ihren Modulen (CYP-6/CYP-17); CYP-26 fügt **kein** zweites Input-Tag hinzu.

---

## 3. Test-relevante Robustheits-Anker (für QA/CYP-7)

- **Default-in-Bounds:** nach Boot/Re-Tile gilt für **jedes** `window.<id>`: `0 ≤ x` und `x + width ≤ hostWidth` (analog y/height) — **kein** off-host Fenster. (Default-Layout ist voll sichtbar, nicht 48-dp-Floor.)
- **Manuelles Verschieben ≠ Default:** ein per Drag an den Rand geschobenes Fenster bleibt mit ≥ 48 dp sichtbar — bewusst schwächer als das Default-Layout (kein Widerspruch, getrennt testen).
- **Spalten-Cap:** auf Medium-Host (z. B. 760×700) erzeugt das Default-Layout **≤ 2 Spalten**; auf Expanded (z. B. 1280×900) `sqrt(count)`. (Über Fenster-x-Positionen verifizierbar.)
- **Composer-Min:** in einem auf < `COMPOSER_MIN_WIDTH` gezogenen Inhaltsfenster bricht der Placeholder **nicht** zeichenweise (Ellipsis), und „Senden" bleibt als (Icon-)Button sichtbar.
- **Modus-Trennung:** `window.host` existiert **nur** im Canvas (beide ≥ Medium); im Compact existiert stattdessen `phonePager.pager` (S10) — nie beide.
- **Fit heilt:** nach `window.host.fit` sind alle Fenster wieder voll im Host (auch wenn vorher manuell rausgeschoben).

---

## 4. Hand-off-Hinweis (Dev + QA)

- Neue Tags gehören als Ergänzung zu `WindowTestTags` in `:app:shared` — **mit Tester (CYP-7) teilen**, Änderungen über den PO.
- Optionale Snap-Tags nur anlegen, **wenn** der PO §3 übernimmt.
- **Shared-Tag-Drift:** mit der CYP-26-Impl + Test-Modul zeitgleich nachziehen.
