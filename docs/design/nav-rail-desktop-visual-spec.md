# CYP-893 — Vertical-Nav-Rail (Desktop/Tablet) — Visual-Spec (Kanonische Cross-Team-Referenz)

> Owner: UIUX (Team-1) · **Visual-Spec-Pass** (Optik + Achsen-/Honesty-Regeln; Dev baut die Struktur dagegen,
> mein Gate am Bau). **Rahmen-Owner: PL** (die *geteilte* Spec — Desktop=Team-1 / Browser=Team-2 **identisch**, kein
> Client-Drift). Story-Cut liegt beim PL zur Ratifizierung; dieser Visual-Pass läuft parallel. Stand develop `883328d3`.
> **Auftraggeber-Direktive via PL.** **Kein Bau hier.**
>
> **⟳ AMENDMENT 2026-07-28 (PL-Cross-Team, Anti-Drift — uiux2 fing's beim Web-Adaptieren):** `Role` ist **3-wertig**,
> nicht 2 — `{ PO, WORKER, PRODUCT_LEAD }` (CYP-98, `core/.../model/CommModel.kt:29`). Der Reviewer-Rolle
> `PRODUCT_LEAD` darf nicht durch eine 2-Rollen-Partition unsichtbar fallen. Amendiert (kanonisch, ein Modell beide
> Clients): **Ordering Canvas → PO → PRODUCT_LEAD → Worker (scrollbar) → Einstellungen**; PRODUCT_LEAD = **eigenes,
> distinktes** Ziel (nie in Worker gebündelt / bei PO kollabiert / gedroppt; **present-iff** wie PO). Betroffen §2/§3/
> §4/§5/§8/§10/§12 + Self-Validation. **Rein Client-UI, kein `:core`/DTO** (Enum existiert). Meine ursprüngliche
> `{PO,WORKER}`-Erdung war ein Unter-Read → am echten Enum korrigiert (verify-don't-trust).
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
Eine **persistente vertikale Navigations-Rail links** + **Content-Pane rechts**; die Rail führt zu den **Zielen**
(Canvas · PO · **PRODUCT_LEAD** · Worker-Agenten (dyn.) · Einstellungen), **genau ein Ziel aktiv** — nur **Landscape + Kurzkante
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
│    │                             │     Canvas · PO · PRODUCT_LEAD · Worker… · Einstellungen
│ ◆  │      Content-Pane           │
│ ◎  │      (das aktive Ziel)      │   Content-Pane (rechts, weight(1f)):
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
  (`fillMaxHeight`), **scrollbar** wenn die Worker-Liste die Höhe übersteigt (die fixen Ziele Canvas/Einstellungen +
  die Lead-Agenten bleiben erreichbar — siehe §4).

---

## 3. Die Ziele + Ordering (3 Rollen — kanonisch)
Reihenfolge oben→unten (PL-vorgegeben inkl. Amendment, mein Feinschliff bestätigt sie):

| # | Ziel | Herkunft | Content-Pane (Empfehlung, Reuse — §11.3 ratifiziert) |
|---|---|---|---|
| 1 | **Canvas** | fix | der bestehende **`WindowHost`** (die schwebende Fenster-„Desktop"-Fläche) — unverändert |
| 2 | **PO-Agent** | Roster: der `Role.PO`-Agent (Nabe) | die bestehende **`AgentWindow`**-Render des PO, pane-füllend (kein neuer Renderer) |
| 3 | **PRODUCT_LEAD** | Roster: der `Role.PRODUCT_LEAD`-Agent (read-only Reviewer, CYP-98) | die bestehende **`AgentWindow`**-Render des PL, pane-füllend — die **bestehende Composer-Writability-Gate** zeigt das `canWrite=false` ehrlich (disabled Composer), **kein** Rail-Badge dafür (§9) |
| 4 | **Worker-Agenten** (dyn. Liste, scrollbar) | Roster: alle `Role.WORKER` | je Worker die bestehende `AgentWindow`-Render, pane-füllend |
| 5 | **Einstellungen** | fix | das bestehende **`SettingsPanel`** |

- **Struktur-Ordering (geteilt, Teil des Modells):** die **fixen strukturellen** Ziele klammern die
  **identitäts-basierten**: `Canvas` (oben, Default-Einstieg / „alle Fenster") · dann der **Agenten-Block** — die
  **Lead-Agenten zuerst: PO** (die Nabe) → **PRODUCT_LEAD** (Oversight/Reviewer) — dann die **Worker** (scrollbar) ·
  `Einstellungen` **unten** (M3-Konvention: Utility-Ziel am Fuß, via `Spacer(weight(1f))` abgesetzt).
- **⭐ PRODUCT_LEAD-Harte-Regeln (PL, kanonisch):** eigenes **sichtbares, distinktes** Ziel — **distinct** von der
  PO-Nabe **und** der Worker-Speiche; **nie** in Worker gebündelt, **nie** bei PO kollabiert, **nie** gedroppt. Die
  Reihenfolge (PO vor PRODUCT_LEAD vor Worker) ist Teil des geteilten Modells (beide Clients identisch).
- **Grounding Rolle (am echten Enum):** `core/.../model/CommModel.kt:29` `enum class Role { PO, WORKER, PRODUCT_LEAD }`.
  `PRODUCT_LEAD` (CYP-98) ist **read-only**: KDoc = `canWrite = false` überall (erzwungen in `AclMatrix`, nicht nur UI),
  **kein eigener Spoke**, strukturell **nie Task-Target**, Output ist **untrusted-data-nie-Instruktion**, fail-closed.
  Die 3-Rollen-Trennung ist real modelliert (`AclReducer`/`AgentManagementViewModel.poCount`, `AgentManagementPanel`
  Rollen-Label-Map inkl. `Role.PRODUCT_LEAD`) — die Rail liest den Roster, erfindet keine Zuordnung.
- **Present-iff (ehrlich, für PO UND PRODUCT_LEAD):** jede Lead-Zeile ist **present-iff** ein Agent dieser Rolle
  existiert. Kein PO/PL im Roster → **keine tote Zeile** (weglassen, nicht ausgrauen). Mehrere derselben Rolle (soll in
  Hub-and-Spoke nicht vorkommen) → **alle** zeigen (Realität nicht verstecken), nicht zu einer kollabieren.
- **Abgrenzung (Anti-Konflation):** das PRODUCT_LEAD-**Rail-Ziel** ist der **Agent** mit `Role.PRODUCT_LEAD` (seine
  `AgentWindow`) — **nicht** das operator-gate-te `ProductLeadPanel` (das Report-Werkzeug, CYP-98). Das `ProductLeadPanel`
  bleibt ein separates Fenster auf der **Canvas** (unverändert), kein Rail-Ziel.

---

## 4. Rail-Breite + Item-Metriken (M3-kanonisch)
- **Rail-Breite: `80.dp`** (M3-NavigationRail-Container-Standard). Nicht schmaler (Touch-/Glyph-Legibilität Tablet),
  nicht breiter (die Rail ist Navigation, kein Panel).
- **Item:** `NavigationRailItem` (M3) — Ziel-Glyph/Avatar **oben**, kurzes **Label darunter** (`alwaysShowLabel = true`;
  das Label trägt die Rolle/Identität mit, nicht der Glyph allein → WCAG 1.4.1). Touch-Ziel ≥ 48 dp.
- **Struktur vs. Liste:** die **fixen** Ziele (Canvas oben; Einstellungen unten) bleiben **immer sichtbar**; die
  **Lead-Agenten** (PO, dann PRODUCT_LEAD) sitzen oben im Agenten-Block; der **Worker-Block** darunter ist der
  **scrollbare** Teil (`weight(1f)` + vertikaler Scroll), damit N Worker die Fuß-Ziele nie verdrängen. Ob die
  Lead-Agenten mit-scrollen oder fix bleiben = Dev-Ermessen am Code; **Canvas + Einstellungen fix + Ordering
  PO→PRODUCT_LEAD→Worker ist die harte Regel**.
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
| **PRODUCT_LEAD** | **Reuse `AgentAvatarView`** des PL (Identität), Fallback-Glyph **`◎`** (Ring-im-Ring = Observer/Oversight) | read-only Reviewer, **außerhalb** des Build-Loops — eigene Shape-Familie (rund ≠ Rauten-Loop) |
| **Worker** | **Reuse `AgentAvatarView`** je Worker, Fallback-Glyph **`◇`** (hohle Raute = Speiche, ≠ Nabe) | die konkrete Worker-Identität |
| **Einstellungen** | Glyph **`⚙`** (Zahnrad, universell), `onSurfaceVariant` inaktiv | Utility/Config |

- **Reuse-Kern:** **Agenten-Ziele tragen die vorhandene `AgentAvatarView`** (schon im Shell: `ui/AgentAvatarView`) —
  identitäts-ehrlich (man navigiert zu *diesem* Agenten, nicht zu „einem Worker") und kein neuer Bildweg. Nur die
  **strukturellen** Ziele (Canvas, Einstellungen) sind Glyphen. `◆`/`◎`/`◇` sind der **Fallback**, wenn ein Avatar fehlt.
- **Glyph-Semantik-System (bewusst, nie farb-kodiert — Glyph trägt, nicht Farbe):**
  - **`◆` (PO) / `◇` (Worker)** = **Rauten-Familie = der Hub-and-Spoke-Build-Loop**: gefüllt=Nabe, hohl=Speiche —
    dieselbe Fill/Hollow-Ehrlichkeit wie `●`/`○` im Hub-Switcher.
  - **`◎` (PRODUCT_LEAD)** = **runde Familie = ein Observer außerhalb des Build-Loops** — der Glyph selbst kodiert „nicht
    Nabe, nicht Speiche" (die eigene Shape-Familie erfüllt die PL-Regel „distinct von PO **und** Worker", nicht bloß eine
    dritte Rauten-Variante). Grund geerdet: das Enum-KDoc sagt PL ist „**NOT part of the MVP build loop (PO/WORKER)**".
    *(Kollisions-Note: der Mode-Marker `◉` lebt in der Titlebar — anderer Surface, keine In-Kontext-Kollision; falls Dev
    eine klarere Distinktheit will, ist `⊙` ein akzeptables Äquivalent. Semantik = Observer, nicht Prestige — nie ein
    Stern/Rang-Glyph.)*

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
| **not-loaded** (Roster noch nicht da) | **strukturelle** Ziele rendern (Canvas oben, Einstellungen unten); **Agenten-Ziele absent** (PO, PRODUCT_LEAD, Worker) — kein confident-empty, keine Platzhalter-Zeilen |
| **loaded, 0 Worker** (ehrlich leer) | Canvas · PO (present-iff) · PRODUCT_LEAD (present-iff) · **kein** Worker-Item · Einstellungen. Optionale **nicht-interaktive** Caption im Worker-Block: „Keine Worker" (`onSurfaceVariant`, `labelSmall`) — **kein** Ziel/Glyph, nur Disambiguierung „geladen & leer" ≠ „lädt noch" |
| **load-error** | strukturelle Ziele bleiben (die Nav darf nie ganz verschwinden); im Agenten-Block eine **kleine** Retry-Affordanz (Idiomatik `LoadErrorRetry`) — **nie** ein erfundener Agent, nie ein stiller Leer-Zustand |

- **Kern-Regel:** die **fixen** Ziele (Canvas, Einstellungen) rendern **immer** (die Navigation ist Chrome und darf bei
  einem Daten-Fehler nicht ausfallen); nur die **identitäts-basierten** Ziele (PO, PRODUCT_LEAD, Worker) hängen am Roster.
- **Present-iff gilt für beide Lead-Rollen:** kein PO **oder** kein PRODUCT_LEAD im Roster → die jeweilige Zeile fehlt
  (weglassen, nicht ausgrauen) — **unabhängig** voneinander (PL kann ohne PO existieren und umgekehrt).
- **Aktives-Ziel-verschwindet-Kante:** war ein Agent (Worker **oder** PO/PL) aktiv und fällt aus dem Roster
  (entfernt/Reload) → **Fallback auf Canvas** (das immer-präsente Default-Ziel), nie ein toter aktiver Marker auf einem
  nicht mehr existenten Ziel.

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
  (Agenten: **voller** Name, nicht der ellipsierte). Aktiv = „<Ziel>, ausgewählt".
- **Rolle im Namen (PL-Regel „Label trägt die Rolle"):** die Lead-Ziele nennen ihre **Rolle** — PO und **Product Lead**
  (reuse `agent_role_po` / `agent_role_product_lead`) — damit ein Reviewer nie als generischer Agent oder als Worker
  gelesen wird. Der Glyph/Avatar ist dekorativ; die Rolle steht im Text (WCAG 1.4.1).
- **Genau-ein-selected** maschinen-lesbar (die Nav ist Single-Select).
- **Glyph nie allein:** jedes Ziel hat ein **Text-Label** (§4) — der Glyph/Avatar ist dekorativ (`role` none /
  Label trägt), nie der einzige Name.
- **Keine Live-Region:** ein Ziel-Wechsel ist eine vom Nutzer ausgelöste Navigation (er schaut hin) → **kein**
  `liveRegion`-Announce (konsistent mit der A11Y-Doktrin: Ansage nur bei Ergebnis-abgeschickt/unaufgefordert-kritisch;
  eine selbst-ausgelöste Nav ist keins von beidem). Der Fokus wandert in den neuen Content-Pane (Standard-Nav-Fokus).

---

## 11. Geteilte Cross-Surface-Fragen (an PO → PL; ein Modell für beide Clients)
> **✅ PL-RATIFIZIERT 2026-07-28: alle 3 Defaults bestätigt, kein Amend.** (1) Glyphen + `AgentAvatarView`, kein
> Vektor-Dep · (2) Status-auf-Rail **absent** (load-bearing Honesty; CYP-807 liveness≠trust / render≠authority) · (3)
> Pane-Mapping-Reuse bestätigt. Bleiben als Rationale dokumentiert; die Nachlade-Amendment (3-Rollen) ist **separat**
> (PL, oben) und ändert an diesen 3 Rulings nichts.

Diese berühren **auch Team-2s Browser-Rail** → nicht einseitig entschieden:
1. **Icon-System:** meine Empfehlung = **House-Glyphen (`▦`/`⚙`, `◆`/`◇`-Fallback) + `AgentAvatarView` für Agenten**
   (kein neuer Vektor-Icon-Dep). Falls **Vektor-Icon-Parität** über beide Clients gewünscht ist, ist das ein geteilter
   Beschluss **+ eine neue Dependency** — dann bräuchte web ein deckungsgleiches Set. **Default (Empfehlung): Glyphen +
   Avatare, semantische Parität statt Glyph-Parität.**
2. **Status-auf-Rail:** im 80 % **absent** (§9). Ob je Ziel-Badges dazukommen = geteilt + honesty-gated.
3. **Content-Pane-Mapping (Reuse — ✅ bestätigt):** Canvas → bestehender `WindowHost`; **alle drei** Agenten-Rollen
   (PO, PRODUCT_LEAD, Worker) → bestehende `AgentWindow`-Render (pane-füllend), wobei die PRODUCT_LEAD-Window ihre
   `canWrite=false`-Realität über die **bestehende Composer-Writability-Gate** zeigt (kein Rail-Badge, §9); Einstellungen
   → `SettingsPanel`. **Reuse vor Neu**, die ehrlichste Zuordnung. (Der Pane-**Inhalt** ist Story-Cut/PL; ich spec die
   **Rail-Optik**.)

---

## 12. testTags / i18n-Keys (Skizze — final am Bau, DE+EN zusammen)
- **Tags** (Area `navRail`): `navRail.rail` (Container) · `navRail.dest.canvas` · `navRail.dest.po.<agentId>` ·
  `navRail.dest.productLead.<agentId>` · `navRail.dest.worker.<agentId>` · `navRail.dest.settings` ·
  `navRail.workersEmpty` (loaded-empty-Caption) · `navRail.rosterError` (+ Retry). Aktiv über die native
  `selected`-Semantik (kein Farb-only-Tag). **Der eigene `productLead`-Tag pinnt die Distinktheit maschinell** (ein
  Render-Zahn, der PRODUCT_LEAD unter `worker`/`po` bündelt → RED).
- **i18n** (snake_case, `values` DE-Default + `values-en`): `nav_rail_dest_canvas` DE „Canvas"/EN „Canvas" ·
  `nav_rail_dest_po` DE „PO-Agent"/EN „PO agent" · `nav_rail_dest_settings` DE „Einstellungen"/EN „Settings" ·
  `nav_rail_workers_empty` DE „Keine Worker"/EN „No workers" · `a11y_nav_rail` DE „Zwischen Bereichen wechseln"/EN
  „Switch between areas" · `a11y_nav_rail_selected` DE „%1$s, ausgewählt"/EN „%1$s, selected". **Reuse (kein neuer
  Key): das PRODUCT_LEAD-Label = bestehendes `agent_role_product_lead` („Product Lead" DE/EN, CYP-98)**; PO-Rolle
  analog `agent_role_po`. **Agenten-Labels tragen Rolle (Lead) bzw. Agent-Name (Worker, dynamisch, kein statischer
  Key).** **Kein grün, keine Status-Copy** (die Rail führt keinen Status).

---

## Self-Validation
- **Am Code geerdet (develop `883328d3`):** Shell-Stack (`AgentShell.kt` `Column{ ProjectSwitcherBar ; MultiHubShell ;
  BoxWithConstraints{ WindowHost } }`), das Bar-Idiom (`ProjectSwitcherBar`/`HubSwitcherBar`: `fillMaxWidth`, Padding
  `12/6dp`, `●`-Marker `onSurfaceVariant`, `<Area>Tags.BAR`), der Responsive-Anker (`WindowWidthSizeClass` +
  `LayoutBreakpoints.PANE_COLLAPSE_WIDTH=600.dp`) und der **Icon-Befund** (`Icons.*`=0, kein `material-icons`-Dep → App
  ist glyph-basiert) real gelesen — file-belegt.
- **⟳ Amendment verify-don't-trust (3-Rollen):** die PL-Claim „`Role` hat einen 3. Wert `PRODUCT_LEAD`" **am echten Enum
  verifiziert** — `core/.../model/CommModel.kt:29` `{ PO, WORKER, PRODUCT_LEAD }`; die KDoc-Fakten (read-only,
  `canWrite=false` überall via `AclMatrix`, kein Spoke, nie Task-Target, untrusted-Output, fail-closed) real gelesen und
  in die Distinktheit/Pane-Honesty übersetzt. Meine ursprüngliche `{PO,WORKER}`-Erdung war ein **Unter-Read** (ich las
  die MVP-Build-Loop-Rollen, nicht das volle Enum) — am Code korrigiert, nicht blind übernommen. Label-**Reuse** geprüft:
  `agent_role_product_lead` existiert bereits (DE/EN) → kein neuer Key.
- **Reuse vor Neu:** M3 `NavigationRail`/`NavigationRailItem` (kanonisch), `AgentAvatarView` für Agenten-Ziele,
  bestehender `WindowHost`/`AgentWindow`/`SettingsPanel` als Pane-Inhalt, die HubSwitcher-Honesty-Triade
  (Empty≠Error≠Not-Loaded), das `●`/`○`-Fill-Ehrlichkeits-Idiom. Kein divergenter One-off; **explizit** kein neuer
  Icon-Dep ohne geteilten Beschluss.
- **Honesty-Zähne benannt:** (1) die Rail ist **Navigation, kein Status/Health/Trust** — die load-bearing Trennung, mit
  dem Status-auf-Rail als geteilter, honesty-gated Frage geparkt; (2) Auswahl ist **lokaler View-State, bewusst
  optimistisch** (≠ HubSwitcher non-optimistisch) — die Anti-Falle explizit für Dev; (3) `secondaryContainer` statt
  `tertiary` (Grün-De-Overload); (4) nicht-farblicher Aktiv-Träger (Pillen-Form + `selected`, WCAG 1.4.1); (5)
  **PRODUCT_LEAD distinkt** — eigene Shape-Familie (`◎` rund ≠ Rauten-Loop), eigener Tag, present-iff, read-only-Realität
  über die bestehende Composer-Gate (kein Rail-Badge) — der Reviewer fällt nie unsichtbar in eine 2-Rollen-Partition.
- **Cross-Surface sauber:** Semantik/Modell geteilt, Glyph/Widget per-Surface; drei geteilte Fragen (Icon-System,
  Status-auf-Rail, Pane-Mapping) **an PO→PL** geroutet, nicht einseitig entschieden — kein Client-Drift-Risiko.
- **Non-Goals respektiert:** kein Rail-Reorder, keine persistierte Config, kein Portrait — „sauberes 80 %".
- **Kein Bau** — Optik-/Regel-Vorlage; Dev baut die Struktur (Rail + Pane-Wiring), zieht die Optik dagegen; mein Gate
  am Bau (Aktiv-State-Töne, Empty-Triade, Glyph/Avatar-Reuse, die Status-Absenz als Zahn).
