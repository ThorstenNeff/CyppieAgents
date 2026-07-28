# CYP-893 — Vertical-Nav-Rail (Desktop/Tablet) — Visual-Spec (Kanonische Cross-Team-Referenz)

> Owner: UIUX (Team-1) · **Visual-Spec-Pass** (Optik + Achsen-/Honesty-Regeln; Dev baut die Struktur dagegen,
> mein Gate am Bau). **Rahmen-Owner: PL** (die *geteilte* Spec — Desktop=Team-1 / Browser=Team-2 **identisch**, kein
> Client-Drift). Story-Cut liegt beim PL zur Ratifizierung; dieser Visual-Pass läuft parallel. Stand develop `883328d3`.
> **Auftraggeber-Direktive via PL.** **Kein Bau hier.**
>
> **★ Status: KANONISCHE Cross-Team-Referenz (PL-aufgewertet).** Team-2s Browser-uiux **adaptiert diese Spec an
> Web-Idiome** — baut keine divergente. Deshalb sind hier **konvergenz-freundliche Primitive** gewählt: solche, die
> Desktop-Compose **und** Web tragen (maritim/M3 trägt beide).
>
> **Parität-Prinzip (wie CYP-847/856):** geteilt ist die **Semantik/Struktur/das Modell**, nicht das Markup. Compose
> nennt Rollen/Tokens/States; die exakte Glyph-/Widget-Wahl je Surface ist per-Surface, solange die Semantik matcht.
>
> **Konvergenz-Primitive auf einen Blick (was beide Clients teilen):** (1) M3-`NavigationRail`-**Muster** (Rail links
> 80 dp / Pane rechts; Web = das deckungsgleiche Nav-Rail-Pattern) · (2) Responsive-Gate **Landscape + Kurzkante
> ≥600 dp** (Material-Medium, surface-agnostisch) · (3) **Semantik-Parität statt Glyph-Parität** bei den Ziel-Icons
> (Default: House-Glyphen + Agent-Avatare, **kein** neuer Vektor-Icon-Dep — §5/§11.1) · (4) M3-Farbrollen
> `secondaryContainer`-Aktiv-Pille / `onSurface`↔`onSurfaceVariant` (kein `tertiary`/Grün) · (5) nicht-farblicher
> Aktiv-Träger (Form + `selected`/`aria-current`, WCAG 1.4.1) · (6) **lokaler** optimistischer Nav-State (≠
> non-optimistischer Server-Switch) · (7) die Empty-Honesty-Triade (Empty ≠ Load-Error ≠ Not-Loaded). Alles unten
> im Detail; Cross-Team-Design-Fragen sammeln sich in §11.

---

## 0. Was das ist (ein Satz)
Eine **persistente vertikale Navigations-Rail links** + **Content-Pane rechts**; die Rail führt zu **vier Zielen**
(Canvas · PO-Agent · Worker-Agenten (dyn.) · Einstellungen), **genau ein Ziel aktiv** — nur **Landscape + Kurzkante
≥ ~600 dp (7"+ / Tablet+)**. Reine **Navigation** (Ansicht wechseln), kein Status-, Health- oder Trust-Signal.

---

## 1. Wann die Rail erscheint (Responsive-Gate — Reuse, kein neuer Breakpoint)
- **Bedingung:** `orientation == Landscape` **UND** **Kurzkante (`min(width,height)`) ≥ 600 dp**. Darunter/Portrait →
  die Rail erscheint **nicht** (bestehende Phone-Pager-Fläche bleibt, **Non-Goal: kein Portrait**).
- **Grounding:** Reuse der bestehenden Größen-Idiomatik — `material3-window-size-class` (`WindowWidthSizeClass`,
  schon in `window/WindowManager.kt`) + `window/LayoutBreakpoints.kt` `PANE_COLLAPSE_WIDTH = 600.dp` (der bereits
  etablierte Material-Medium-Schwellwert). **Kein neuer Breakpoint erfinden** — die 600-dp-Kurzkante ist genau die
  vorhandene Medium-Grenze, nur auf die *kurze* Seite gemessen (= „Tablet-quer", die M3-NavigationRail-Bedingung).
- **Ehrliche Grenze:** die Rail ist eine **additive** Chrome-Schicht für Tablet+/Desktop-quer; sie **ersetzt** den
  Phone-Pfad nicht und erfindet keinen dritten Zustand.

---

## 2. Anatomie (Layout)
```
┌────┬─────────────────────────────┐
│ ▦  │                             │   Rail (links, persistent, 80 dp):
│    │                             │     Canvas · PO · Worker… · Einstellungen
│ ◆  │      Content-Pane           │
│    │      (das aktive Ziel)      │   Content-Pane (rechts, weight(1f)):
│ ◇  │                             │     rendert GENAU das aktive Ziel
│ ◇  │                             │
│    │                             │   Trenner: 1 dp vertikal `outlineVariant`
│ ⚙  │                             │     (nicht-farbliche Separation)
└────┴─────────────────────────────┘
```
- **Struktur:** `Row { NavigationRail(…) ; VerticalDivider(outlineVariant) ; Box(weight(1f)) { activeDestination } }`.
- **Ort im Shell-Stack:** die Rail sitzt **innerhalb** der Workspace-Chrome, **unter** den Top-Bars
  (`ProjectSwitcherBar` = *was*, `HubSwitcherBar` = *wo*) und **umschließt** die Content-Fläche, in der heute der
  `WindowHost` lebt. Die Top-Bars bleiben **über** der Rail (globaler Kontext ändert sich nicht beim Ziel-Wechsel).
  Konkret: der heutige `BoxWithConstraints { WindowHost }`-Slot (AgentShell) wird zum **`Row { Rail ; Pane }`**; die
  Bars darüber unverändert.
- **Rail-Container:** `surface`, flach/subtil (Idiomatik wie die `Surface` der Switcher-Bars), voll-hoch
  (`fillMaxHeight`), **scrollbar** wenn die Worker-Liste die Höhe übersteigt (die 4-fixen Ziele bleiben erreichbar —
  siehe §4).

---

## 3. Die vier Ziele + Ordering
Reihenfolge oben→unten (PL-vorgegeben, mein Feinschliff bestätigt sie):

| # | Ziel | Herkunft | Content-Pane (Empfehlung, Reuse — PL/Dev bestätigen §11) |
|---|---|---|---|
| 1 | **Canvas** | fix | der bestehende **`WindowHost`** (die schwebende Fenster-„Desktop"-Fläche) — unverändert |
| 2 | **PO-Agent** | Roster: der `Role.PO`-Agent | die bestehende **`AgentWindow`**-Render des PO, pane-füllend (kein neuer Renderer) |
| 3 | **Worker-Agenten** (dyn. Liste) | Roster: alle `Role.WORKER` | je Worker die bestehende `AgentWindow`-Render, pane-füllend |
| 4 | **Einstellungen** | fix | das bestehende **`SettingsPanel`** |

- **Struktur-Ordering:** die **fixen strukturellen** Ziele klammern die **identitäts-basierten**: `Canvas` (oben, der
  Default-Einstieg / „alle Fenster") · dann die **Agenten** (PO zuerst — er ist die Nabe — dann Worker) · `Einstellungen`
  **unten** (M3-Konvention: sekundär/utility-Ziel am Fuß der Rail, via `Spacer(weight(1f))` vom Rest abgesetzt).
- **Grounding Rolle:** `com.tneff.cyppieagents.model.Role { PO, WORKER }` existiert; PO/Worker-Trennung ist real
  modelliert (`AclReducer`/`AgentManagementViewModel.poCount`) — die Rail liest den Roster, erfindet keine Zuordnung.
- **PO-Sonderfall (ehrlich):** die PO-Zeile ist **present-iff** ein `Role.PO`-Agent existiert. Kein PO im Roster →
  **keine tote PO-Zeile** (weglassen, nicht ausgrauen). Mehrere PO (soll in Hub-and-Spoke nicht vorkommen) → **alle**
  zeigen (Realität nicht verstecken), nicht zu einer kollabieren.

---

## 4. Rail-Breite + Item-Metriken (M3-kanonisch)
- **Rail-Breite: `80.dp`** (M3-NavigationRail-Container-Standard). Nicht schmaler (Touch-/Glyph-Legibilität Tablet),
  nicht breiter (die Rail ist Navigation, kein Panel).
- **Item:** `NavigationRailItem` (M3) — Ziel-Glyph/Avatar **oben**, kurzes **Label darunter** (`alwaysShowLabel = true`;
  bei 4 Zielen + kurzer Worker-Liste ist Platz — das Label trägt die Identität mit, nicht der Glyph allein → WCAG 1.4.1).
  Touch-Ziel ≥ 48 dp.
- **Struktur vs. Liste:** die **fixen** Ziele (Canvas oben; Einstellungen unten) bleiben **immer sichtbar**; der
  **Worker-Block** in der Mitte ist der **scrollbare** Teil (`weight(1f)` + vertikaler Scroll), damit N Worker die
  Fuß-Ziele nie verdrängen. PO sitzt als erster Agent oben im Agent-Block (mit-scrollend oder fix — Dev-Ermessen am
  Code; **Canvas + Einstellungen fix ist die harte Regel**).
- **Label-Kürzung:** Worker-Namen `TextOverflow.Ellipsis`, max 1–2 Zeilen (Idiomatik wie die Namens-Kürzung der
  Switcher-Bars); der volle Name lebt in der a11y-Description.

---

## 5. Ziel-Glyphen / „Icons" (⚠ House-Idiom — KEIN Vektor-Icon-Set)
**Grounding-Befund (load-bearing):** die App hat **kein** Material-Vektor-Icon-Set (`Icons.*` = 0 Fundstellen; keine
`material-icons`-Dependency im Version-Katalog). Alle Marker sind **Text-Glyphen** (`●`/`○`/`▲`/`◉` in Switcher-Bars,
Mode-Marker, Trust-Badge). **Ein Vektor-Icon-Set wäre ein divergenter One-off + eine neue Dependency** → nicht ohne
geteilten Beschluss (§11, Frage 1).

**Meine Wahl (im Ermessen, house-konsistent), mit Semantik:**

| Ziel | Optik | Semantik |
|---|---|---|
| **Canvas** | Glyph **`▦`** (Gitter = die Fenster-Fläche), `onSurfaceVariant` inaktiv | „alle Fenster / der Desktop" |
| **PO-Agent** | **Reuse `AgentAvatarView`** des PO (Identität), Fallback-Glyph **`◆`** (gefüllte Raute = die Nabe) | die konkrete PO-Identität, nicht ein generisches Symbol |
| **Worker** | **Reuse `AgentAvatarView`** je Worker, Fallback-Glyph **`◇`** (hohle Raute = Speiche, ≠ Nabe) | die konkrete Worker-Identität |
| **Einstellungen** | Glyph **`⚙`** (Zahnrad, universell), `onSurfaceVariant` inaktiv | Utility/Config |

- **Reuse-Kern:** **Agenten-Ziele tragen die vorhandene `AgentAvatarView`** (schon im Shell: `ui/AgentAvatarView`) —
  identitäts-ehrlich (man navigiert zu *diesem* Agenten, nicht zu „einem Worker") und kein neuer Bildweg. Nur die
  **strukturellen** Ziele (Canvas, Einstellungen) sind Glyphen. `◆`/`◇` sind der **Fallback**, wenn ein Avatar fehlt.
- **`◆`/`◇`-Paar** ist bewusst: gefüllt=Nabe/PO, hohl=Speiche/Worker — dieselbe Fill/Hollow-Ehrlichkeit wie
  `●`(online)/`○`(offline) im Hub-Switcher. **Nie farb-kodiert** (Glyph trägt, nicht Farbe).

---

## 6. Aktiv/Inaktiv-States (M3 `NavigationRailItem`, nicht-farblich getragen)
- **Aktiv-Indikator:** die M3-Pillen-Form (`indicatorColor = secondaryContainer`), Glyph `onSecondaryContainer`,
  Label `onSurface`. **Nicht-farblicher Träger = die Pillen-FORM hinter dem aktiven Item + `selected = true`-Semantik**
  (WCAG 1.4.1 — Farbe nie alleiniger Träger; die Auswahl ist an Form **und** Semantik erkennbar, auch ohne Farbsicht).
- **Inaktiv:** Glyph + Label `onSurfaceVariant`, keine Pille.
- **⚠ Grün-De-Overload (CYP-300/E1):** der Aktiv-Indikator ist **`secondaryContainer`** — **NIE `tertiary`** (kippt
  nachts grün) und **kein affirmatives Grün**. `secondaryContainer` ist die M3-Default-Wahl und trägt maritim/M3 in
  Day+Night ohne Status-Konnotation.
- **Genau ein aktives Ziel** (`selected` an genau einem Item) — die Rail ist ein Single-Select-Nav, kein Multi-Toggle.
- **Hover/Focus (Desktop):** M3-Default-State-Layer (`onSurface`-Overlay) — kein Sonder-Styling.

---

## 7. Auswahl-Verhalten (⚠ LOKALER View-State — bewusst NICHT non-optimistisch)
**Der load-bearing Unterschied zu HubSwitcher/ProjectSwitcher:** ein Ziel-Wechsel ist ein **reiner Client-View-Wechsel**
(kein Server-Roundtrip, keine Mutation). Deshalb:
- **Auswahl greift sofort/optimistisch** — der aktive Marker folgt dem **Klick** (lokaler Nav-State), **nicht** einem
  Server-`confirm`. Das ist hier **ehrlich**, weil nichts über den Server *behauptet* wird (im Gegensatz zum
  Hub-Switch, wo Optimismus einen unbestätigten Server-Zustand vortäuschen würde).
- **Dev-Zahn / Anti-Falle:** die Rail **nicht** an eine `activeHubId`-artige Server-Bestätigung gaten (das wäre eine
  falsche Analogie zum Hub-Switcher). Nav-Selection = `rememberSaveable`-freier lokaler UI-State (Non-Goal: **keine
  persistierte Config** — der aktive Bereich überlebt den Prozess-Neustart **nicht**, und soll es nicht).
- **Der Inhalt** hinter jedem Ziel behält seine **eigene** Ehrlichkeit: wechselt man zu einem Agenten, rendert dessen
  bestehende `AgentWindow` mit ihren realen (nicht-optimistischen) Status-/Trust-/Revoke-Signalen. Die Rail addiert
  **keine** Status-Ebene (siehe §9).

---

## 8. Empty-/Ausnahme-States (Honesty-Triade — Reuse HubSwitcher-Muster)
Dieselbe **drei-Ausgänge-Ehrlichkeit** wie der Hub-Switcher (Empty ≠ Load-Error ≠ Not-Loaded), damit die Rail nie ein
irreführendes „leer" bei einem Fehler zeigt:

| Roster-Zustand | Rail-Verhalten |
|---|---|
| **not-loaded** (Roster noch nicht da) | **strukturelle** Ziele rendern (Canvas oben, Einstellungen unten); **Agenten-Ziele absent** — kein confident-empty, keine Platzhalter-Zeilen |
| **loaded, 0 Worker** (ehrlich leer) | Canvas · PO (falls vorhanden) · **kein** Worker-Item · Einstellungen. Optionale **nicht-interaktive** Caption im Worker-Block: „Keine Worker" (`onSurfaceVariant`, `labelSmall`) — **kein** Ziel/Glyph, nur Disambiguierung „geladen & leer" ≠ „lädt noch" |
| **load-error** | strukturelle Ziele bleiben (die Nav darf nie ganz verschwinden); im Agenten-Block eine **kleine** Retry-Affordanz (Idiomatik `LoadErrorRetry`) — **nie** ein erfundener Agent, nie ein stiller Leer-Zustand |

- **Kern-Regel:** die **fixen** Ziele (Canvas, Einstellungen) rendern **immer** (die Navigation ist Chrome und darf bei
  einem Daten-Fehler nicht ausfallen); nur die **identitäts-basierten** Ziele hängen am Roster.
- **Aktives-Ziel-verschwindet-Kante:** war ein Worker aktiv und fällt aus dem Roster (entfernt/Reload) → **Fallback auf
  Canvas** (das immer-präsente Default-Ziel), nie ein toter aktiver Marker auf einem nicht mehr existenten Ziel.

---

## 9. Was die Rail NICHT ist (Honesty-Zähne — mein Kern-Flag)
- **Kein Status-/Health-/Trust-Signal.** Die Rail zeigt **keine** Agent-Lifecycle- (RUNNING/ERROR), Busy-, Token-,
  Revoke- oder Trust-Badges. Aktiv/Inaktiv = **Navigations-Auswahl**, nicht Agent-Gesundheit. Ein Status-Badge auf einem
  Nav-Ziel würde (a) Navigation mit Health konflatieren und (b) riskieren, eine Garantie/einen Zustand zu implizieren,
  den die Rail nicht führt. **Status lebt dort, wo er heute ehrlich lebt** — in der `AgentWindow`-Titlebar/den Bannern
  des jeweiligen Content-Panes.
- **⭐ Geteilte Frage an PL (§11, Frage 2):** *ob* die Rail je einen Ziel-Badge tragen soll (z. B. ein ERROR-Punkt an
  einem Agenten-Ziel), ist ein **geteilter** Cross-Surface-Beschluss und **honesty-gated** — falls je gebaut: ein
  Modell für beide Clients, WARN/ERROR-Töne nach `A11Y`/Severity-Doktrin, **nie** affirmatives Grün, Status nie
  vorgetäuscht. Im **sauberen 80 %** ist die Rail **nur Navigation**.
- **Kein Rail-Reorder, keine persistierte Config, kein Portrait** (Non-Goals — bestätigt).

---

## 10. a11y
- **Rail-Container:** Nav-Semantik + `contentDescription` „Navigation / Zwischen Bereichen wechseln" (Parität web
  `nav aria-label`).
- **Item:** `NavigationRailItem` trägt `selected`-Semantik nativ (= `aria-current`-Parität); Label ist der a11y-Name
  (Worker: **voller** Name, nicht der ellipsierte). Aktiv = „<Ziel>, ausgewählt".
- **Genau-ein-selected** maschinen-lesbar (die Nav ist Single-Select).
- **Glyph nie allein:** jedes Ziel hat ein **Text-Label** (§4) — der Glyph/Avatar ist dekorativ (`role` none /
  Label trägt), nie der einzige Name.
- **Keine Live-Region:** ein Ziel-Wechsel ist eine vom Nutzer ausgelöste Navigation (er schaut hin) → **kein**
  `liveRegion`-Announce (konsistent mit der A11Y-Doktrin: Ansage nur bei Ergebnis-abgeschickt/unaufgefordert-kritisch;
  eine selbst-ausgelöste Nav ist keins von beidem). Der Fokus wandert in den neuen Content-Pane (Standard-Nav-Fokus).

---

## 11. Geteilte Cross-Surface-Fragen (an PO → PL; ein Modell für beide Clients)
Diese berühren **auch Team-2s Browser-Rail** → nicht einseitig entscheiden:
1. **Icon-System:** meine Empfehlung = **House-Glyphen (`▦`/`⚙`, `◆`/`◇`-Fallback) + `AgentAvatarView` für Agenten**
   (kein neuer Vektor-Icon-Dep). Falls **Vektor-Icon-Parität** über beide Clients gewünscht ist, ist das ein geteilter
   Beschluss **+ eine neue Dependency** — dann bräuchte web ein deckungsgleiches Set. **Default (Empfehlung): Glyphen +
   Avatare, semantische Parität statt Glyph-Parität.**
2. **Status-auf-Rail:** im 80 % **absent** (§9). Ob je Ziel-Badges dazukommen = geteilt + honesty-gated.
3. **Content-Pane-Mapping (Reuse-Bestätigung):** meine Empfehlung = Canvas → bestehender `WindowHost`; Agenten-Ziele →
   bestehende `AgentWindow`-Render (pane-füllend); Einstellungen → `SettingsPanel` (§3). Das ist **Reuse vor Neu** und
   die ehrlichste Zuordnung — **PL/Dev bestätigen**, ob das Pane-Modell (Canvas-als-Fenster-Fläche vs. Einzel-Agent-Fokus)
   so das geteilte Modell ist. (Der Pane-**Inhalt** ist Story-Cut/PL; ich spec die **Rail-Optik**.)

---

## 12. testTags / i18n-Keys (Skizze — final am Bau, DE+EN zusammen)
- **Tags** (Area `navRail`): `navRail.rail` (Container) · `navRail.dest.canvas` · `navRail.dest.po.<agentId>` ·
  `navRail.dest.worker.<agentId>` · `navRail.dest.settings` · `navRail.workersEmpty` (loaded-empty-Caption) ·
  `navRail.rosterError` (+ Retry). Aktiv über die native `selected`-Semantik (kein Farb-only-Tag).
- **i18n** (snake_case, `values` DE-Default + `values-en`): `nav_rail_dest_canvas` DE „Canvas"/EN „Canvas" ·
  `nav_rail_dest_po` DE „PO-Agent"/EN „PO agent" · `nav_rail_dest_settings` DE „Einstellungen"/EN „Settings" ·
  `nav_rail_workers_empty` DE „Keine Worker"/EN „No workers" · `a11y_nav_rail` DE „Zwischen Bereichen wechseln"/EN
  „Switch between areas" · `a11y_nav_rail_selected` DE „%1$s, ausgewählt"/EN „%1$s, selected". **Worker-Labels =
  Agent-Name** (dynamisch, kein statischer Key). **Kein grün, keine Status-Copy** (die Rail führt keinen Status).

---

## Self-Validation
- **Am Code geerdet (develop `883328d3`):** Shell-Stack (`AgentShell.kt` `Column{ ProjectSwitcherBar ; MultiHubShell ;
  BoxWithConstraints{ WindowHost } }`), das Bar-Idiom (`ProjectSwitcherBar`/`HubSwitcherBar`: `fillMaxWidth`, Padding
  `12/6dp`, `●`-Marker `onSurfaceVariant`, `<Area>Tags.BAR`), der Responsive-Anker (`WindowWidthSizeClass` +
  `LayoutBreakpoints.PANE_COLLAPSE_WIDTH=600.dp`), das Rollen-Modell (`model.Role{PO,WORKER}`, `poCount`) und der
  **Icon-Befund** (`Icons.*`=0, kein `material-icons`-Dep → App ist glyph-basiert) real gelesen — file-belegt.
- **Reuse vor Neu:** M3 `NavigationRail`/`NavigationRailItem` (kanonisch), `AgentAvatarView` für Agenten-Ziele,
  bestehender `WindowHost`/`AgentWindow`/`SettingsPanel` als Pane-Inhalt, die HubSwitcher-Honesty-Triade
  (Empty≠Error≠Not-Loaded), das `●`/`○`-Fill-Ehrlichkeits-Idiom. Kein divergenter One-off; **explizit** kein neuer
  Icon-Dep ohne geteilten Beschluss.
- **Honesty-Zähne benannt:** (1) die Rail ist **Navigation, kein Status/Health/Trust** — die load-bearing Trennung, mit
  dem Status-auf-Rail als geteilter, honesty-gated Frage geparkt; (2) Auswahl ist **lokaler View-State, bewusst
  optimistisch** (≠ HubSwitcher non-optimistisch) — die Anti-Falle explizit für Dev; (3) `secondaryContainer` statt
  `tertiary` (Grün-De-Overload); (4) nicht-farblicher Aktiv-Träger (Pillen-Form + `selected`, WCAG 1.4.1).
- **Cross-Surface sauber:** Semantik/Modell geteilt, Glyph/Widget per-Surface; drei geteilte Fragen (Icon-System,
  Status-auf-Rail, Pane-Mapping) **an PO→PL** geroutet, nicht einseitig entschieden — kein Client-Drift-Risiko.
- **Non-Goals respektiert:** kein Rail-Reorder, keine persistierte Config, kein Portrait — „sauberes 80 %".
- **Kein Bau** — Optik-/Regel-Vorlage; Dev baut die Struktur (Rail + Pane-Wiring), zieht die Optik dagegen; mein Gate
  am Bau (Aktiv-State-Töne, Empty-Triade, Glyph/Avatar-Reuse, die Status-Absenz als Zahn).
