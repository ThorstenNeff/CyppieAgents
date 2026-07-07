# Migrations-Plan — Maritime Design-System (Ticket-Input für PO)

> **Status:** PLANUNG (kein Bau, kein Merge) · Owner: UIUX · Auftraggeber-**ratifiziert** 2026-07-07 (E1 grün=`tertiary`-Akzent, E2 Night löst CYP-268-Dark ab). Migration **greenlit**, gegated nur auf Dev-Kapazität.
> **Zweck:** die §8-Migration in schneidbare Arbeitspakete zerlegen — Scope, betroffene Dateien/Seams (gegen echten Code `develop d89b5bd`), Abhängigkeiten, UX-QA-relevante §9-Invarianten. Daraus schneidet der PO Epic + Stories.
> **Companion:** `maritime-design-system-{spec,tokens}` (dieselbe Branch).

---

## Teil A — Font-Entscheidung (§-Ask §4, PO entscheidet)

Die Typo-Skala (`tokens.json → typography`) ist **font-agnostisch** und trägt so oder so. Offen ist nur die **Familie + der Bundling-Weg**. Constraint (PO): **self-hosted / Data-URI, kein CDN** (wie die CYP-234-Shell egress-frei war).

| Option | Was | Für | Wider |
|---|---|---|---|
| **A** | Plattform-/Compose-Default | 0 Bundling, 0 Lizenz | plattform-**uneinheitlich** (Roboto/Android vs. System/Desktop/Wasm) → untergräbt „produktweite" Sprache |
| **B (Empf.)** | **eine gebundelte neutrale Grotesk (OFL)** | konsistent über App **+** Landing **+** Doku | Bundling-Footprint (mit Subsetting klein) |

**Meine Empfehlung: B.** Konkretes Paar (beide OFL, self-hostbar, kein Reserved-Name-Problem wenn nicht umbenannt):
- **UI/Text/Display = Inter** — beste UI-Legibilität, neutral-ruhig (maritim/anti-hype), nahe an M3-Metriken.
- **Code/Mono = JetBrains Mono** — für die Doku-Code-Panels (§7 code-bg ozean-dunkel) + Inline-Code. Eine gemeinsame Code-Schrift über App-Logs und Doku.
- *Alternative aus einer Familie:* **IBM Plex Sans + IBM Plex Mono** (ein Typ-System, Engineering-Ton) — wenn du „ein Haus, eine Familie" bevorzugst.

**Bundling-Mechanik (self-hosted, kein CDN):**
- **App (Compose MP):** subsettete statische Weights **400/500/700** als gebundelte Ressourcen via `Font(...)`/`FontFamily`; Wasm liefert sie **vom selben Origin** (kein CDN); Android/JVM ressourcen-gebündelt; iOS Stub (N/A).
- **Web (Landing + Redoc):** `@font-face` mit **self-hosted WOFF2** (auf dem Doku-Origin) **oder** **Data-URI-base64 inline** in der CSS (max. Selbst-Enthaltung, wie die CYP-234-Shell). Redoc: `typography.fontFamily` + `typography.code.fontFamily`.
- **Subsetting:** Latin + DE-Umlaute → hält Asset/Data-URI klein.
- **Lizenz (OFL):** die `OFL.txt` **muss mitreisen** (mit den Font-Dateien gebündelt) — dieselbe „Lizenz reist mit"-Disziplin wie beim DiceBear-CC-BY-Fund (CYP-212). Kein In-App-Credit nötig (OFL ≠ CC-BY), aber die Lizenzdatei ist Pflicht.

**PO-Entscheidung nötig:** (1) Paar — **Inter + JetBrains Mono (Empf.)** vs. Plex-Paar; (2) Web-Bundling — **self-hosted WOFF2** (cachebar, Empf.) vs. **Data-URI** (falls strikte Ein-Datei-Selbst-Enthaltung gefordert); (3) Bestätigen, dass **400/500/700** reichen. Ist das entschieden, hängt Paket **b** (App-Typo) nicht mehr am Font.

---

## Teil B — Arbeitspakete (schneidbar)

### ⚠️ a0 — `tertiary` ent-überladen (HARTE Vorbedingung für Grün) — der Kern-Fund

**Fund (gegen Code, spec §3.3):** `tertiary`/`tertiaryContainer` ist **keine reine Dekor-Rolle**, sondern heute **semantisch überladen**. Grün einfach anzuwenden würde **WARN-Warnungen nachts grün** färben. **a0 gated `a` (Grün) — ohne a0 verletzt die Night-Anwendung §9-Inv.1.**

| Site (Datei:Zeile) | Heutige Nutzung | In Night (grün) | Ziel-Re-Point |
|---|---|---|---|
| `report/ProductLeadPanel.kt:301` | `Severity.WARN -> tertiary` | ❌ Warnung grün | **Severity-WARN-Palette** (CYP-274-Familie, amber) |
| `window/WindowBadge.kt:100` | `Severity.WARN -> tertiary/onTertiary` | ❌ Warn-Badge grün | **Severity-WARN-Palette** |
| `ui/TonedHint.kt:52,87` | EFFECT_DEFERRED-Banner = `tertiaryContainer` | ❌ „gespeichert, noch nicht aktiv" grün=Erfolg | **`secondaryContainer`/`onSecondaryContainer`** (blau) |
| `connector/ConnectorCapabilityViews.kt:90,213` | EFFECT_DEFERRED-Äquiv. | ❌ | **`secondaryContainer`** |
| `report/ProductLeadPanel.kt:72` | `access_denied` in `tertiary` | ❌ Denied grün | **`error`/neutral** |
| `report/ProductLeadPanel.kt:83` | `generating` in `tertiary` | ⚠️ | **`secondary`/neutral** |
| `agentview/AgentWindow.kt:241` | `startPending`-Dot = `tertiary` | ⚠️ Pending grün=„ready" | **neutral/`secondary`** (Pending ≠ ready) |
| `agentview/AgentWindow.kt:332` | `ToolStatus.RUNNING ⟳` = `tertiary` | ⚠️ grenzwertig (aktiv) | **Team-Call:** `secondary` oder bewusst Akzent-grün („läuft") — mit Glyph als Nicht-Farb-Träger |
| `agentview/AgentWindow.kt:222` | Label-Farbe `tertiary` | Audit | Kontext prüfen → `secondary` |
| `acl/AclPanel.kt:110` | Zellen-Fill `tertiaryContainer/on` | Audit — was bedeutet die Zelle? | rollenkorrekt (semantisch), nicht Marken-Grün |
| `agentmgmt/AgentManagementPanel.kt:319` | `labelSmall` in `tertiary` | Audit | `secondary` |

- **`TonedHint(INFO)` bleibt unverändert** — nutzt `secondary` (blau), nie tertiary (`ui/TonedHint.kt:89`).
- **Nebenbefund/Konsolidierung:** WARN hat **zwei** Darstellungen (Event-Log-Rails CYP-274 vs. diese Badges auf `tertiary`). a0 konsolidiert WARN auf die **Severity-Palette** → schließt den latenten Bug (Warn-auf-Marken-Rolle), schon **vor** Night sichtbar.
- **Scope:** Re-Point der ~11 Sites; ggf. eine kleine Severity-Farb-Helferrolle (WARN) exponieren, damit Rails **und** Badges dieselbe Quelle nutzen.
- **Deps:** keine (kann **vor** dem restlichen Night-Bau laufen — ist der saubere erste Schritt).
- **UX-QA §9:** **Inv.1 (grün≠Status = harte Kante)**, Inv.3 (Severity semantisch/scheme-unabhängig), Inv.10 (0 strings/tags — reiner Rollen-Swap).

---

### a — App Night-`ColorScheme` (löst CYP-268-Dark ab)

- **Scope:** `MaritimeDark` in `MaritimeTheme.kt` durch die **Night-Tokens** ersetzen (schwärzere Surfaces `#06121A`, `tertiary`=Signal-Grün); optional die `surfaceContainer*`-Leiter (§6) ergänzen. `MaritimeLight` bleibt **unangetastet** (kanonisch).
- **Dateien/Seams:** `app/shared/.../ui/MaritimeTheme.kt` (`MaritimeDark`, ggf. Light-`surfaceContainer*`). Der `App.kt`-Seam (`MaterialTheme(colorScheme = maritimeColorScheme(dark))`) **bleibt** — nur die Palette dahinter ändert sich. R3-Day/Night-Toggle unberührt.
- **Deps:** **a0 zuerst** (sonst Grün korrumpiert Semantik). Tokens ratifiziert (done).
- **UX-QA §9:** Inv.1/2 (grün≠Status nach a0, error rot distinkt), **Inv.5/8/9** (alle Paare AA in Night inkl. schwärzerer Surfaces — §3-Tabelle beim Bau voll durchziehen), Inv.3 (Severity/Sender unberührt), Inv.10 (reine Rekolorierung). **Gerendert light+dark.**

---

### b — Non-Color-Tokens app-weit (Typo / Shape / Elevation)

- **Scope:** maritime `Typography` (M3 15-Stufen), `Shapes` (M3-Radien), Elevation via `surfaceContainer`-Nutzung. In den `MaterialTheme(colorScheme=…, typography=…, shapes=…)`-Seam einhängen. **Audit** auf hardcodierte `TextStyle`/`fontSize`/`RoundedCornerShape` über die Screens (die den Theme-Wert überschreiben würden).
- **Dateien/Seams:** `App.kt` (`MaterialTheme(...)` um typography/shapes erweitern); neu `MaritimeTypography.kt` / `MaritimeShapes.kt`; Screen-weiter Audit.
- **Deps:** **Font-Entscheidung (Teil A)** für die Typo-Familie. Shape/Elevation font-unabhängig → können vorziehen.
- **UX-QA §9:** Inv.5 (Kontrast hält mit neuen Weights — leichte Weights auf Small-Text bleiben AA), **Inv.10 (Reflow — das größte Layout-Risiko: geänderte Zeilenhöhen/Größen dürfen keine Fenster/Panels sprengen)**. Riskantestes Paket fürs Layout → sorgfältige UX-QA.

---

### c — `:core deriveScheme` Surface-Parameter (Border-Forward)

- **Scope:** die hartcodierte **dunkle** Referenz `NEUTRAL_SURFACE = 0xFF1E1E1E` (`ColorDerivation.kt:36`) **parameterisieren**, so dass abgeleitete Ränder/Kanten gegen die **tatsächliche** Surface zielen (Day Weiß / Night `#06121A`), nicht gegen eine feste Dunkel-Referenz. Der Border-Trim (`ColorDerivation.kt:97`, `contrastRatio(border, NEUTRAL_SURFACE)`) bekommt die Surface als Argument.
- **Dateien/Seams:** `core/.../model/ColorDerivation.kt` (`deriveScheme`, `NEUTRAL_SURFACE`, Border-Loop); Call-Sites (SenderPalette-Border, Avatar-/Fenster-Kanten) reichen die aktive Surface durch. `:core` ist compose-frei → reine Funktions-Signatur-Änderung + Call-Site-Threading.
- **Deps:** Night-Surface-Hex (done). Landet mit/nach **a** (Night-Surface ist die neue Bezugsfläche). Das ist die schon geflaggte CYP-275-§5-„Border-on-light"-Forward.
- **UX-QA §9:** **Inv.8 (Non-Text-UI ≥3:1 — Ränder/Kanten auf beiden Surfaces)**, Inv.5.

---

### d — Landing / Doku: CSS-Vars + Redoc-Theme (light+dark)

- **Scope:** interim **Graphit/Petrol-Teal**-CSS durch `--maritime-*` (Day+Night) ersetzen; Redoc-`theme`-Mapping light+dark (aus CYP-234-Pre-Scope §3); self-hosted Fonts (`@font-face`, kein CDN). „Marke kräftig / Lesen ruhig" + Code-Panel ozean-dunkel beide Modi.
- **Dateien/Seams:** `site/index.html` + `site/docs/index.html` (die Landing/Doku-Seiten); `server/.../resources/docs/shell.html` (**trägt bereits `--maritime-*` Day** — Night-Vars + Redoc-Dark-Theme sind die Ergänzung); `routing/DocsRoutes.kt` (Redoc-`theme`-Injektion). *(App-Web-Entry `app/webApp/.../index.html` = Compose-Canvas, fällt unter die App-Theme-Migration, nicht hier.)*
- **Deps:** **CYP-287** (Doku-Deploy-Runbook — die Redoc/AsyncAPI-Bundles sind ein Human-Deploy-Step, no-CDN); Font-Entscheidung (Teil A) für `@font-face`. **Fällt mit CYP-287 zusammen.**
- **UX-QA §9:** Inv.5 (Render-WCAG beide Modi), **Inv.1 (grün nie Status auch in Doku — Method-/Tier-Badges dürfen Grün nicht als Erfolg/OK nutzen)**, Inv.7 (ein Token-Set — Landing/Doku leiten aus denselben Tokens ab). **Coincidet mit meinem CYP-287-Render-Re-Verify** (§6=10/§8=8, Real-Browser-Smoke).

---

## Teil C — Vorgeschlagene Schneidung + Reihenfolge

| Paket | Größe (grob) | Reihenfolge | Gate |
|---|---|---|---|
| **a0** ent-überladen | S–M | **zuerst** (unabhängig, schließt latenten WARN-Bug) | — |
| **a** Night-ColorScheme | S | nach a0 | a0 |
| **b** Non-Color-Tokens | M | parallel zu a möglich (Shape/Elev.); Typo nach Font-Entscheid | Font (Teil A) |
| **c** deriveScheme-Surface-Param | S | mit/nach a | a |
| **d** Landing/Doku | M | mit CYP-287 | CYP-287, Font |

**Empfehlung:** **a0 → a → c** als eine kohärente App-Farb-Linie; **b** (Non-Color) parallel, sobald der Font steht; **d** faltet in CYP-287. **a0 ist der wichtigste erste Schnitt** — er ist eigenständig wertvoll (schließt den WARN-auf-Marken-Rolle-Bug) und entriegelt Grün ehrlich.

**Hand-off:** kein Bau vor Dev-Dispatch. Pro Paket mache ich die **UX-QA gegen die gelisteten §9-Invarianten**, gerendert light+dark (App) bzw. beide Modi (Landing/Doku, mit CYP-287). Font-Entscheidung (Teil A) = einziger PO-Vorab-Call, der **b** entriegelt.
