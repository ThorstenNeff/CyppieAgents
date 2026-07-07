# Design-Spec — Produktweites M3-Maritime Design-System

> **Status:** DESIGN-PASS (kein Bau, kein Merge) · Owner: UIUX-Designer · Auftraggeber-gesetzt 2026-07-03 (produktweite Design-Sprache = maritim + Material 3) · PO-Go auf Vollspec 2026-07-07.
> **Deliverables:** dieses Spec + `maritime-design-system-tokens.json` (Namen + Hex je Scheme). Docs-only auf `feature/maritime-design-system` (off develop `d89b5bd`). **Nichts wird ersetzt/gebaut vor Auftraggeber-Abnahme.**
> **Framing (Anti-Divergenz):** Dies **formalisiert + erweitert** das schon abgenommene CYP-268-Maritim-Theme zum produktweiten Fundament — **keine Neu-Erfindung**. `[[design-language-maritime-m3]]`.
> **Grounding:** `app/shared/.../ui/MaritimeTheme.kt` (`MaritimeLight`/`MaritimeDark`, 24 Rollen, develop-gemergt) · `docs/design/dev-api-docs-experience-tokens.json` v1.2 (CYP-234 Doku-Surface, teilt die Marken-Hues) · `dev-api-docs-hosted-styling-prescope.md` (Redoc-`theme`-Mapping).

---

## §0 — ZWEI AUFTRAGGEBER-ENTSCHEIDUNGEN (für den Sign-off, PO legt sie vor)

Alles andere ist auf meiner ratifizierten Empfehlung gebaut. **Diese zwei sind reine Produkt-/Geschmacks-Calls des Auftraggebers — an EINER Stelle sichtbar, kippbar, nicht-blockierend:**

| # | Entscheidung | Diese Spec baut auf | Alternative (kippbar) |
|---|---|---|---|
| **E1** | **Grün-Prominenz im Night** (§3) | Grün = **`tertiary`/Akzent**, `primary` bleibt **blau** (Marke konsistent Day+Night) | Grün prominenter = **Marken-`primary` im Night** (blau→grün als Haupt-CTA nur nachts). Konsequenz-Skizze in §3.4. |
| **E2** | **Night löst die R4-Dark ab** (§3) | Night ersetzt die abgenommene CYP-268-`MaritimeDark` (blau-auf-navy `#0A1922` → schwärzer + grün) | Night **koexistiert** als 3. Scheme / R4-Dark bleibt Default. **Formale Ablöse ist an den Sign-off gegated** — jetzt docs-only, nichts ersetzt. |

Die Auftraggeber-Setzung 2026-07-03 („Night = Dunkelblau/Schwarz/Grün") **deckt die Absicht** beider; die formale Bestätigung holt der PO mit diesem Sign-off ein.

---

## §1 — Prinzipien

1. **Marke kräftig / Lesen ruhig.** Marken-Rollen (primary, Links, aktive Nav) sind gesättigt; Lese-/Arbeitsflächen (surface, Body, Panels) bleiben ruhig. (Aus CYP-234 übernommen, jetzt produktweit.)
2. **Eine Sprache, zwei Surfaces.** EIN kanonisches Token-Set (`tokens.json`) speist die **Compose-App** (`MaterialTheme.colorScheme`) **und** die **Landing/Doku** (`--maritime-*`-CSS-Vars / Redoc-`theme`). Kein zweites, divergentes Palettenblatt.
3. **Semantik erhalten.** `error` bleibt rot; die Severity-Ampel und die 9 Sender-Identitäten sind **semantisch** und hängen **nicht** am Scheme. Farbe ist **nie** alleiniger Bedeutungsträger (WCAG 1.4.1).
4. **Theme-ready durch EINEN Seam.** Die App liest überall `MaterialTheme.colorScheme.*`; injiziert man das maritime Scheme am einzigen `App.kt`-Seam, rekoloriert es fast alles ohne Komponenten-Touch. Reine Rekolorierung — 0 strings/testTags/Layout (Ausnahmen in §8 enumeriert).
5. **Day = kanonisch, unangetastet.** Der Day-Scheme ist das schon abgenommene, AA-clean `MaritimeLight` — **1:1 adoptiert**, nicht neu erfunden.

---

## §2 — Farb-Tokens: **Day** (= CYP-268 `MaritimeLight`, KANONISCH)

Verbatim aus dem gemergten `MaritimeTheme.kt`. Blau/Weiß, ruhig. **Nicht ändern** (abgenommenes AA-clean Theme). Vollständige 24 Rollen + surfaceContainer-Leiter in `tokens.json → color.day`.

**Kern-Kontraste (Day, verifiziert):**

| Paar | Hex fg / bg | Kontrast | Ziel | ✓ |
|---|---|---|---|---|
| onSurface / surface | `#0C2635` / `#FFFFFF` | **15.9:1** | 4.5 | ✓ AAA |
| onSurfaceVariant / surface | `#3A4E5A` / `#FFFFFF` | **8.7:1** | 4.5 | ✓ |
| primary / surface (Links) | `#0A5AA0` / `#FFFFFF` | **7.0:1** | 4.5 | ✓ AAA |
| onPrimary / primary | `#FFFFFF` / `#0A5AA0` | **7.0:1** | 4.5 | ✓ |
| outline / surface (UI) | `#6E8C9E` / `#FFFFFF` | **3.1:1** | 3.0 | ✓ |
| error / surface | `#B3261E` / `#FFFFFF` | **6.3:1** | 4.5 | ✓ |

---

## §3 — Farb-Tokens: **Night** (NEU — Dunkelblau/Schwarz/Grün)

Gegroundet auf `MaritimeDark`: **primary-/secondary-/error-Familien bleiben** (Markenidentität + rote Fehler-Semantik konstant); **Surfaces werden schwärzer** (`#0A1922` navy → `#06121A` blau-schwarz); **`tertiary` wird Signal-Grün**. Vollständige Rollen in `tokens.json → color.night`.

### §3.1 — Grün: Rolle, Herkunft, Kontrast
- **Rolle:** `tertiary` = `#40D6A0`, `tertiaryContainer` = `#0C3D30`. **Nur Marke/Akzent** — Fokus-Ring, aktive Auswahl, dekorative Chrome, Landing-Akzent. **Nie** Erfolg/Status.
- **Herkunft (Marken-Story):** maritimes **Signal-Grün** (Steuerbord-/Navigationslaterne) — die Nacht-Identität, wie das Harbour-Teal die Tag-Identität ist. Der Akzent ist damit **scheme-expressiv** (Teal bei Tag, Grün bei Nacht) — eine bewusste Day/Night-Marke, **kein** semantischer Bruch (weil Advisory-Komponenten den `tertiary` gar nicht nutzen, §3.3).
- **Hue-Trennung:** Grün ~158° ist bewusst weg von primary-Blau (~205°), error-Rot (~2°), warn-Amber (~45°, Severity) und info-Grau — **kein** Sender/Status/Marken-Blau kann als Grün gelesen werden und umgekehrt.

**Night-Kern-Kontraste (verifiziert gegen `#06121A`):**

| Paar | Hex fg / bg | Kontrast | Ziel | ✓ |
|---|---|---|---|---|
| onSurface / surface | `#DCE7ED` / `#06121A` | **15.1:1** | 4.5 | ✓ AAA |
| onSurfaceVariant / surface | `#A6BECD` / `#06121A` | **9.8:1** | 4.5 | ✓ |
| onSurfaceVariant / surfaceVariant | `#A6BECD` / `#12242F` | **8.2:1** | 4.5 | ✓ |
| primary / surface | `#6FBEEA` / `#06121A` | **9.2:1** | 4.5 | ✓ AAA |
| **tertiary (grün) / surface** | `#40D6A0` / `#06121A` | **10.2:1** | 4.5 | ✓ AAA |
| onTertiary / tertiary | `#04322A` / `#40D6A0` | **7.6:1** | 4.5 | ✓ |
| onTertiaryContainer / tertiaryContainer | `#9EEAD0` / `#0C3D30` | **8.8:1** | 4.5 | ✓ |
| error / surface | `#F2B8B5` / `#06121A` | **9.6:1** | 4.5 | ✓ |
| outline / surface (UI) | `#57707F` / `#06121A` | **3.6:1** | 3.0 | ✓ |

### §3.2 — „Schwarz": die Surface-Leiter
Night geht **schwärzer** als die R4-Dark. Die tonale Elevations-Leiter (§6) hebt jede Ebene um einen Navy-Schritt an — höhere Elevation = **hellere** Fläche (M3-tonal), nicht nur Schatten:
`Lowest #040D14 → Low #0A1922 (die alte Basis, jetzt eine Ebene) → #0F2029 → High #14293A → Highest #1B3242`.

### §3.3 — ⚠️ Ehrlichkeits-Carve-out (harte §9-Invariante, mein Kern) — **KORRIGIERT gg. Code (2026-07-07)**
**Grounding gegen den echten Code korrigiert die frühere Annahme dieser Spec:** `tertiary`/`tertiaryContainer` ist heute **kein reiner Dekor-Akzent**, sondern eine **überladene Semantik-Rolle**. Tatsächliche Konsumenten:
- **WARN-Severity** — `Severity.WARN -> colorScheme.tertiary` (`report/ProductLeadPanel.kt:301`, `window/WindowBadge.kt:100`)
- **Pending/Running-Status** — `startPending`-Dot + `ToolStatus.RUNNING`-Spinner (`agentview/AgentWindow.kt:241,332,222`)
- **EFFECT_DEFERRED-Banner** — Fläche = `tertiaryContainer` (`ui/TonedHint.kt:52`, `connector/ConnectorCapabilityViews.kt:90,213`)
- **Access-denied / Generating-Hinweise** — (`report/ProductLeadPanel.kt:72,83`), ACL-Zellen-Fill (`acl/AclPanel.kt:110`), ein AgentMgmt-Label (`agentmgmt/AgentManagementPanel.kt:319`)
- **`TonedHint(INFO)` nutzt `secondary` (blau), NICHT tertiary** → kein Re-Point nötig (die frühere „INFO auf tertiaryContainer"-Annahme war **falsch**, per `ui/TonedHint.kt:89`).

**Konsequenz:** Grün einfach auf `tertiary` zu legen, würde **WARN-Warnungen nachts GRÜN** färben (schlimmster Fall — eine Warnung liest als Erfolg), ebenso Pending/Deferred/Denied. → **Harte Vorbedingung (§9-Inv.1): `tertiary` muss ZUERST ent-überladen werden** — jeder Semantik-Konsument auf seine korrekte Rolle umgehängt (**WARN → Severity-Palette** [CYP-274-Familie, amber], **EFFECT_DEFERRED → `secondaryContainer`**, **Denied → Fehler/neutral**, **Pending/Running → neutral/`secondary`**), **bevor** Grün angewandt wird. Erst dann ist `tertiary` ein echter Dekor/Marken-Akzent und Grün ehrlich. Per-Site-Aufschlüsselung = Migrations-Plan Paket **a0** (`maritime-design-system-migration-plan.md`).

**Nebenbefund (latenter Ehrlichkeits-Bug, existiert schon heute):** WARN hängt an einer **Marken-Rolle** statt an der Severity-Semantik → Severity hat **zwei inkonsistente Darstellungen** (Event-Log-Rails via CYP-274 amber vs. diese Badges via `tertiary` teal/grün). Konsolidierung auf die Severity-Palette ist der richtige Fix und schließt den Bug — schon vor Night sichtbar.

### §3.4 — Konsequenz-Skizze **E1** (falls Auftraggeber Grün prominenter kippt)
Wenn Grün **Marken-`primary` im Night** würde: aktive CTAs/Links/Nav wären nachts grün, tags blau — die **Marke wechselt die Farbe zwischen den Schemes** (Blau↔Grün). Kostet Marken-Konstanz (E1-Empfehlung hält Blau über beide). Zusätzlich müsste dann die Grün-vs-Status-Trennung noch strenger geführt werden (grüner Primary-Button neben grün-freier Erfolgs-Semantik). **Meine Empfehlung bleibt Grün=Akzent**; die Skizze ist da, damit der Auftraggeber es an einer Stelle sieht.

---

## §4 — Typo-Scale (M3)

Vollständige 15-Stufen-M3-Skala (Größe/Zeilenhöhe/Gewicht/Tracking) in `tokens.json → typography`. Gewichte: Regular 400 · Medium 500 · Bold 700. Werte sind **font-family-agnostisch** (die Skala trägt unabhängig von der Schrift).

**§-Ask (nicht-blockierend, Produkt-/Lizenz-Call):** die **Schriftfamilie**. Optionen:
- **A — Plattform-/Compose-Default** (kein Bundling, kein Lizenz-Thema; leicht plattform-uneinheitlich).
- **B — eine gebundelte neutrale Grotesk** (z. B. Inter, OFL) → konsistent über App + Web + Doku; kostet Bundling.
- **Empfehlung:** B für produktweite Konsistenz (App **und** Landing/Doku dieselbe Schrift) — aber **nicht-blockierend**; die Skala steht so oder so. Landing/Doku-Fallback-Stack: `system-ui, "Inter", -apple-system, Segoe UI, Roboto, sans-serif`.

---

## §5 — Shape-Tokens (M3)

M3-Corner-Skala (dp) + Komponenten-Defaults in `tokens.json → shape`. Ruhig/anti-hype = **M3-Defaults**, nicht über-gerundet:
`none 0 · extraSmall 4 · small 8 · medium 12 · large 16 · extraLarge 28 · full (Pille)`. Fenster-Panels = `medium`, Buttons = `full`, Chips = `small`, Dialoge = `extraLarge`.

---

## §6 — Elevation / Tonal-Tokens (M3)

dp-Leiter (Schatten) **Level 0–5** + die **surfaceContainer-Farbleiter** (die eigentliche tonale Ausdrucksebene) in `tokens.json → elevation` + `color.*.surfaceContainer*`. In **Night** ist die tonale Leiter kritisch: erhöhte Flächen werden **hellere Navy-Töne**, nicht nur Schatten (Schatten trägt auf near-black kaum). Alle Container-Ebenen tragen `onSurface` ≥ AA (verifiziert, §3-Tabelle-Prinzip; Highest `#1B3242` → onSurface 10.6:1).

---

## §7 — Reichweite-Mapping (App ↔ Landing/Doku)

Volle Zeilen-Tabelle in `tokens.json → surface_mapping`. Prinzip:

| Surface | Liest | Wie |
|---|---|---|
| **Compose-App** | `MaterialTheme.colorScheme.<Rolle>` | EIN `App.kt`-Seam injiziert Day/Night; rekoloriert fast alles |
| **Landing / Doku** | `--maritime-<var>` CSS-Custom-Properties | ersetzt die interim Graphit/Petrol-Teal-Vars 1:1 |
| **Redoc/AsyncAPI** (Doku-Renderer) | `theme.<key>` (CE-2.x) | Mapping light+dark aus CYP-234-Pre-Scope §3 (sidebar/rightPanel/method-badges/code-bg) |

**„Marke kräftig / Lesen ruhig"** bleibt: Lese-/Body-Flächen ruhig (Weiß bei Tag / near-black-navy bei Nacht), Marken-Rollen kräftig; Code-Panel ozean-dunkel in **beiden** Modi (CYP-234). Die Doku-Tokens v1.2 sind der **Teilmengen-Vorläufer** dieses Sets — hier zum Vollset (inkl. Night) formalisiert; beim Doku-Deploy (CYP-287) gegen dieses Set re-gesynct.

---

## §8 — Migrations-Hinweise für Dev (kein Bau jetzt)

**Rein rekoloriert durch den Seam (0 struktureller Touch):** alle Screens, die `colorScheme.*` lesen.

**Audit-Surface (hängt NICHT am Scheme — bewusst):**
- `eventlog/EventVisuals` **Severity-Ampel** — semantisch; `railColor(dark)` ist bereits scheme-adaptiv (CYP-274). **Nicht** an Marken-Rollen hängen.
- `ui/SenderPalette` **9 Identitäten** — scheme-unabhängig; Light-Name-Accent via `readableAccentOn(…, surface)` (CYP-275). Auf der **schwärzeren** Night-Surface re-verifizieren (die Pastelle tragen auf `#06121A` mind. so gut wie auf `#0A1922`).
- `core/model/ColorDerivation` (`:core`) — **⚠️ Dep:** `NEUTRAL_SURFACE` ist **hartcodiert dunkel** (`#1E1E1E`). Für die schwärzere Night-Surface (`#06121A`) sollte die Referenz **parameterisiert** werden (Surface-Param statt Konstante) — das ist die schon geflaggte „Border-on-light"/deriveScheme-Forward-Sorge (CYP-275 §5). Sonst driften abgeleitete Ränder/Kanten gegen die falsche Bezugsfläche. **Kein Blocker für den Farb-Teil**, aber Teil der Theme-Migration.

**Struktureller Delta — `tertiary` ent-überladen (Paket a0, §3.3, harte Vorbedingung für Grün):** die Semantik-Konsumenten von `tertiary`/`tertiaryContainer` auf korrekte Rollen umhängen — WARN-Severity → Severity-Palette (CYP-274), EFFECT_DEFERRED → `secondaryContainer`, Denied → Fehler/neutral, Pending/Running → neutral/`secondary`. **`TonedHint(INFO)` bleibt unverändert** (nutzt `secondary`). Per-Site-Liste im Migrations-Plan a0. **Dies ist kein optionaler Feinschliff — ohne a0 verletzt die Night-Anwendung §9-Inv.1.**

**Landing/Doku:** interim Graphit/Petrol-Teal-CSS → `--maritime-*`; Redoc-`theme` light+dark (CYP-234-Pre-Scope §3); Deploy-Re-Verify unter CYP-287.

---

## §9 — Invarianten (= UX-QA-Abnahmekante, 10) — der Ehrlichkeits-Anker ist HART

1. **Grün ist nie ein Status/Erfolgs-Signal** — nicht alleiniger Träger irgendeines Zustands; **kein Semantik-Konsument zieht Farbe aus `tertiary*`** (Vorbedingung: `tertiary` ent-überladen, Paket a0 — WARN/Deferred/Denied/Pending umgehängt; `TonedHint(INFO)` bleibt `secondary`); Grün nur Marke/Akzent/Fokus/Dekor (WCAG 1.4.1). *(HARTE Kante — ohne a0 verletzt.)*
2. **`error` bleibt rot** und **farbton-distinkt** von Grün in **beiden** Schemes.
3. **Severity-Ampel + 9 Sender-Identitäten** bleiben semantisch und **scheme-unabhängig**; Semantik erhalten.
4. **Day = CYP-268 `MaritimeLight` verbatim** — kein Drift eines abgenommenen AA-clean Themes.
5. **Alle fg/bg-Paare WCAG AA in BEIDEN Schemes** (Text ≥ 4.5, UI/Non-Text ≥ 3) — §2/§3-Tabellen + volle Rollen-Prüfung beim Bau.
6. **`primary` = blau in beiden Schemes** (Markenidentität konstant) — außer der Auftraggeber kippt E1.
7. **EIN kanonisches Token-Set**; App **und** Landing/Doku leiten daraus ab (keine divergente 2. Palette).
8. **Non-Text-UI** (Outline, Fokus-Ring, Rails, Icon-only) ≥ 3:1 (WCAG 1.4.11).
9. **Night schwärzere Surfaces** senken **kein** Paar unter AA (verifiziert, §3).
10. **0 strings/testTag/Layout** aus dem Theme selbst (reine Rekolorierung + Token-Adoption); die einzigen strukturellen Deltas (INFO/deferred-Umhängung) sind in §8 enumeriert.

---

## §10 — Hand-off

- **Kein Bau, kein Merge** vor Auftraggeber-Abnahme. Docs-only auf `feature/maritime-design-system`.
- **PO legt E1 (Grün-Prominenz) + E2 (Dark-Ablöse) dem Auftraggeber vor** (§0). Alles andere ist ratifiziert.
- **Nach Abnahme:** Dev baut die Theme-Migration (App-Seam bleibt, INFO/deferred umhängen, `:core`-Surface-Param, Landing/Doku-CSS + Redoc-Theme); ich mache die **UX-QA gegen die 10 §9-Invarianten**, gerendert light+dark, App **und** Landing/Doku (letztere fällt mit CYP-287 zusammen).
- **§-Ask offen (nicht-blockierend):** Schriftfamilie (§4, Empfehlung B).
