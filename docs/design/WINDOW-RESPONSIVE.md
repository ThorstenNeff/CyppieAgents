# Responsive Window-Tiling — Breakpoints & Composer-Robustheit (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-26** (Prio niedrig/Backlog) · Status: **Entwurf — Backlog-Design** · Stand: 2026-06-26
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/design/WINDOW-RESPONSIVE.md`.
> Bezug: Fenster-Manager (CYP-10/CYP-16), Shell (CYP-15), Renderer (CYP-6). Implementierung später (iOS-Dev), gebündelt mit **F10** (RTL-Kachelung) / **F12** (Overlap) im `tile()`-Adaptiv-Refactor.

Spezifiziert das adaptive Verhalten der Fenster-Kachelung auf schmalen Hosts. Keine Implementierungsvorgabe.

---

## 0. Befund (iOS-Sim-Smoke, CYP-15 auf iPhone-Portrait)

`WindowReducer.tile()` wählt bei 3 Agenten `columns = ceil(sqrt(3)) = 2`. Auf ~390dp Portrait-Host:
`usableWidth ≈ 390`, `cellWidth = (390 − gap·3)/2 ≈ 171dp` → Agentenfenster ~171dp breit → der CYP-6-Composer fällt unter die nutzbare Breite, der Placeholder („Nachricht an den Agenten…") **bricht zeichenweise um**. Auf Desktop/Tablet unkritisch.

---

## 1. Zwei-Ebenen-Fix

Die zwei Ebenen sind **bewusst getrennt**: die Breakpoint-Logik verhindert den Normalfall, die Komponenten-Robustheit fängt jeden Restfall sauber ab.

### 1.1 Layout — Host-Breite-Breakpoint (Material 3 Window Size Classes)

Breakpoint an der **Host-Breite** in dp (beim Panel ist das die Fenster-/Host-Breite, nicht die physische Bildschirmbreite):

| Size Class | Host-Breite | Spalten-Cap | Verhalten |
|---|---|---|---|
| **Compact** | < 600 dp | **1** | ⚠ **ERSETZT durch S10 (CYP-54, `docs/PHONE-PAGER.md`):** Compact/Phone = **HorizontalPager** (eine Seite je Fenster), **nicht** gestapelt. Diese Zeile gilt nur noch historisch. |
| **Medium** | 600–839 dp | **2** | bis zu 2 Spalten |
| **Expanded** | ≥ 840 dp | — | bestehende `sqrt(count)`-Logik (Desktop/Tablet-Landscape) |

Umsetzung in `tile()`: `columns = min(sqrtColumns, sizeClassCap)` (Expanded = kein Cap). Reihen folgen aus `ceil(count / columns)`.

### 1.2 Komponenten-Robustheit (sofort, breakpoint-unabhängig)

Greift auch, falls je ein schmales Fenster durchrutscht (manuelles Resize, ungewöhnliche Hosts):
- **Composer-Placeholder einzeilig:** `maxLines = 1`, `softWrap = false`, `TextOverflow.Ellipsis` → degradiert per Ellipsis statt zeichenweisem Umbruch.
- **`COMPOSER_MIN_WIDTH ≈ 280 dp`** als Inhaltsgarantie für das Eingabefeld.
- **Min-Breite für gekachelte Agentenfenster auf ≥ ~300 dp** anheben (statt generisch `MIN_WINDOW_WIDTH = 160 dp`), damit der Composer nie unter die nutzbare Breite fällt. (160 dp bleibt ggf. der harte Floor für andere Fenstertypen; gekachelte Agentenfenster brauchen mehr.)

---

## 2. Randfälle

- **RTL:** Der 1-Spalten-Stack respektiert start/end automatisch (kein Sonderfall). Der Mehrspalten-Fall sollte mit dem **F10**-Fix (Kachelung an Start-Kante spiegeln) zusammen gelöst werden.
- **Höhe in Compact:** gestapelte Fenster ggf. nahezu voll-höhe; bei vielen Agenten die Stapelung vertikal scrollbar (statt zu quetschen — adressiert auch **F12**).
- **Host-Resize/Rotation:** greift auf den bestehenden Reflow (`updateHostSize` → `clampSizeToBounds`/`clampToBounds`, CYP-16 F6) auf; der Breakpoint wird beim Re-Tile neu ausgewertet.

---

## 3. Offene Punkte

1. Exakte `COMPOSER_MIN_WIDTH`/Min-Fensterbreite final mit Dev kalibrieren (280/300 dp sind begründete Startwerte).
2. ~~Ob in Compact echtes „Stapeln mit Scroll" oder „ein Fenster maximiert + Switcher"~~ → **ENTSCHIEDEN durch S10 (CYP-54): „ein Fenster + Switcher" als `HorizontalPager` mit Snap.** Außerdem deckt S10 die Compact-Erkennung über **Compose Window Size Classes** (Pager sobald **eine** Dimension Compact, width ODER height) ab — CYP-26 bleibt für **Medium/Expanded** (Canvas-Tiling-Caps) + Komponenten-Robustheit zuständig.
3. Bündelung mit F10/F12 im `tile()`-Adaptiv-Refactor (PO/iOS-Dev).
