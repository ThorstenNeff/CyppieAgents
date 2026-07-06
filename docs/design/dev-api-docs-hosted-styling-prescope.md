# Design-Pass — Maritime Stylung der gehosteten Doku-Seite (CYP-234 · 234a-3 Pre-Scope)

> **Status:** Design-Pass (kein Bau) · Owner: UIUX-Designer · Ungated Zwischenarbeit, PO-getriggert 2026-07-06.
> **Feeds:** 234a-3 (gehostete API-Referenz-Rendering-Fläche) · **Grounding:** `dev-api-docs-experience-{spec,tokens}` (ratifiziert, `@ c881201`), `[[design-language-maritime-m3]]`, CYP-268 App-Palette (`e930802`, R2-AA-verifiziert).
> **Was das ist:** die konkrete, buildbare Übersetzung des kräftigen maritimen M3-Themes auf die tatsächliche **Redoc**-Render-Fläche (+ AsyncAPI-Seite) — Header, Nav, Code-Panels, Farbrollen. Erweitert `D5` + den skeletthaften `renderer_mapping`-Block der Tokens; ersetzt keine ratifizierte Entscheidung.
> **Renderer:** Redoc (PO-§-Ask entschieden). Kein „Try it" v1. Sprache EN.

---

## §0 — Zwei Sätze

Die gehostete Doku ist **eine maritime Shell (unser HTML/CSS)**, die **zwei Renderer einbettet** (Redoc = REST, AsyncAPI-React = WS, D1) — dieser Pass sagt, **welcher maritime Farbwert wo im Renderer landet** und **was wir selbst stylen vs. was der Renderer über sein Theme-Objekt trägt**. Kräftig = die **Marken-Rollen** (Header, primary, Links, aktive Nav) tragen das satte CYP-268-Blau; die **Lese-Flächen** (Sidebar-BG, Body, Panels) bleiben ruhig/hell — Doku ist Langform-Lesen, übersättigte Chrome ermüdet.

---

## §1 — DIE eine Entscheidung: Doku-Palette an CYP-268-„kräftig" angleichen (§-Ask für PO/Auftraggeber)

**Problem:** Die Doku-Tokens (`dev-api-docs-experience-tokens.json` v1.1) wurden **vor** dem Auftraggeber-Override „kräftiger" (CYP-268) autorisiert. Zwei primary-Werte existieren für **eine** Sprache:

| Rolle | Doku heute (v1.1) | App CYP-268 (kräftig, R2-AA) | Kontrast auf Weiß |
|---|---|---|---|
| `primary` (light) | `#14567A` (tiefes ruhiges Ozean) | `#0A5AA0` (sattes Marine) | 7.95:1 / 7.04:1 — **beide AA+AAA** |

→ Der Unterschied ist **Sättigung/Charakter, kein Kontrast-Blocker.** „Eine Sprache, zwei Surfaces" + der explizite Bold-Wille sprechen für **Angleichung**.

**Meine Empfehlung (RECOMMENDED):** Doku-**Marken-Rollen** auf die kräftigen CYP-268-Hues ziehen, **Lese-Flächen ruhig lassen**:

| Doku-Rolle | v1.1 | → Vorschlag (kräftig-angeglichen) | Begründung |
|---|---|---|---|
| `primary` (Redoc `colors.primary.main`, Header, aktive Nav) | `#14567A` | **`#0A5AA0`** (light) / **`#6FBEEA`** (dark) | Marken-Identität = identisch zur App |
| `link` | `#1C6FB0` | **`#0A5AA0`** (light) / **`#6FBEEA`** (dark) | ein Link-Ton, app-konsistent |
| `secondary` | `#3E7CA6` | **`#0F5B88`** (light) / **`#93CCEA`** (dark) | R2-AA-getunte CYP-268-secondary |
| `tertiary`/accent | `#2E8FA6` | **`#0B7E9C`** (light) / **`#4FC6DE`** (dark) | maritimes Teal, app-konsistent |
| **Surfaces** (`surface`/`surfaceVariant`/Sidebar-BG) | hell/foam | **unverändert lassen** | Lese-Ruhe; nur Akzent geht kräftig |
| Severity/Method/Tier-Badges | s. Tokens | **unverändert** (semantisch, bereits AA) | Bedeutung ≠ Marke |

**Wenn der Auftraggeber die Doku bewusst ruhiger als die App will** (defensibel: Doku ist Referenz-Lesen), bleibt v1.1 — dann steht in der Trust-Zeile nichts anderes, nur der Farbcharakter divergiert leicht. **Ich empfehle Angleichung; final = PO/Auftraggeber.** Ich re-tokene erst nach dem Call (kein voreiliges Umschreiben der ratifizierten Tokens).

---

## §2 — Drei Stil-Ebenen: was WIR stylen, was der Renderer trägt

| Ebene | Wer stylt | Umfang |
|---|---|---|
| **A — Shell-Chrome** (unser HTML/CSS) | wir | Top-Bar (Wortmarke, Version, Trust-Zeile, Tier-Legende, **REST ↔ WS-Toggle**), Footer, globale CSS-Variablen, Dark-Toggle |
| **B — Redoc (REST)** | Redoc `theme`-Objekt | Nav-Sidebar, Endpoint-Body, Method-Badges, Schema-Tabellen, rechtes Code-Panel |
| **C — AsyncAPI-React (WS)** | AsyncAPI `config.theme` | Kanal-/Frame-Union-Darstellung unter **derselben** Shell-Chrome (D1) |

**Leitsatz:** Header/Legende/Toggle sind **unsere** Chrome (Redoc themt sie nicht) — dort lebt die kräftige Marke am stärksten. Redoc-intern bleibt es lese-ruhig mit kräftigem Akzent.

---

## §3 — Redoc `theme`-Mapping (buildbarer Anker, CE 2.x-Keys verifiziert)

> Keys gegen aktuelle Redoc-Doku geprüft (`colors.primary.main`, `colors.http.*`, `sidebar.*`, `rightPanel.*`, `typography.headings/code/links`). Werte = kräftig-angeglichen (§1-RECOMMENDED); bei „ruhig bleiben"-Call → v1.1-Werte einsetzen.

**Light:**
```
theme:
  colors:
    primary:   { main: '#0A5AA0' }            # Marke = App
    text:      { primary: '#0C2635', secondary: '#3A4E5A' }   # onSurface / onSurfaceVariant
    border:    { light: '#CBDCE7', dark: '#6E8C9E' }          # outlineVariant / outline
    http:                                       # method_badges.* (Tokens, unverändert, AA)
      get:    '#1C5E93'
      post:   '#1F6B4A'
      put:    '#6A4A12'
      delete: '#8A2F2F'
      patch:  '#5A3E86'
  sidebar:
    backgroundColor: '#F4F8FB'                  # ruhige Lese-Fläche (surfaceContainer)
    textColor:       '#3A4E5A'
    activeTextColor: '#0A5AA0'                  # kräftiger Marken-Akzent NUR am aktiven Item
  rightPanel:
    backgroundColor: '#0C2635'                  # dunkles Ozean-Code-Panel (auch im Light-Mode, §5)
    textColor:       '#E7F1F6'
  typography:
    fontFamily:  "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    headings:    { fontWeight: '600' }
    code:        { fontFamily: "'JetBrains Mono', ui-monospace, monospace", color: '#E7F1F6', backgroundColor: '#0C2635' }
    links:       { color: '#0A5AA0' }
```

**Dark** (Shell-Toggle swappt das Theme-Objekt, §6):
```
theme:
  colors:
    primary:   { main: '#6FBEEA' }
    text:      { primary: '#DCE7ED', secondary: '#A6BECD' }
    border:    { light: '#263946', dark: '#57707F' }
    http: { get:'#7FB3D9', post:'#7FC6A3', put:'#D9BE7F', delete:'#E39A9A', patch:'#B79FD9' }  # dark-getunte Method-Töne (AA auf dunklem BG, §7)
  sidebar:
    backgroundColor: '#12232C'
    textColor:       '#A6BECD'
    activeTextColor: '#6FBEEA'
  rightPanel:
    backgroundColor: '#06141C'
    textColor:       '#DCE7ED'
  typography:
    links: { color: '#6FBEEA' }
    code:  { color: '#DCE7ED', backgroundColor: '#06141C' }
```

> **Method-Badges Dark:** die Light-`method_badges` sind für helle Container getunt; auf dunkler Fläche brauchen sie eigene Töne (oben skizziert). Exakte Dark-Method-Hex = **kleiner offener Punkt** (§9-1), im R2-Stil AA-zu-verifizieren.

---

## §4 — Shell-Chrome (unsere maritime CSS, kräftigster Marken-Ort)

- **Top-Bar:** Vollton-Marken-Blau `#0A5AA0` (light) / dunkles Navy `#0A1922` mit `#6FBEEA`-Wortmarke (dark). Wortmarke + Version + **Trust-Zeile** (D6, `info/neutral`, nie ERROR). Höhe/Padding = M3-Toolbar.
- **Tier-Legende** (immer sichtbar, D4): die drei Tier-Badges (`PUBLIC`/`PARTICIPANT`/`OPERATOR`) + `TOKEN-ONLY WS` als **label-tragende** Chips (Tokens `tier_badges.*`, unverändert). Operator = distinktes Control-Blau, **nie Alarm-Rot** (= „mehr Zugang", kein Fehler).
- **REST ↔ WS-Toggle:** ein Segmented-Control in der Chrome, das zwischen Redoc- und AsyncAPI-Einbettung wechselt — **eine** Shell, zwei Renderer (D1). Aktiver Reiter = kräftiger primary-Akzent.
- **Footer:** Version + Trust-Zeile (Wiederholung, D6) + Lizenz-/Kontakt-Zeile.

---

## §5 — Code-Panels: dunkel in BEIDEN Modi (bewusst)

Das rechte Code-Sample-Panel (`rightPanel`) bleibt **dunkles Ozean** (`#0C2635` light / `#06141C` dark) — Redoc-Konvention + Code liest sich auf dunkel ruhiger. `codeFg` `#E7F1F6` auf `#0C2635` = **hoher Kontrast** (>12:1), AAA. Das ist die **eine** dunkle Fläche im Light-Mode und ist Absicht, kein Bruch der „hellen Surfaces"-Regel.

---

## §6 — Dark-Mode-Seam (Implementierungs-Hinweis, nicht Bau)

Redoc themt **nicht** live um. Der Dark-Toggle lebt in **unserer Shell**: bei Umschaltung wird (a) das Redoc-`theme`-Objekt neu gesetzt / die Instanz re-rendert mit dem Dark-Theme, und (b) unsere CSS-Variablen (`--maritime-*`) auf die Dark-Werte gesetzt. **Default = follow-system** (konsistent zur App-Toggle-Policy CYP-268). Kein Persistenz-Zwang v1; `prefers-color-scheme` genügt.

---

## §7 — WCAG-Re-Verifikation an der Render-Fläche (AA hart, wie CYP-268 R2)

| Paar | Light | Dark | AA |
|---|---|---|---|
| `text.primary` auf `surface` | `#0C2635`/`#FFF` ~14:1 | `#DCE7ED`/`#0A1922` ~13:1 | ✓ AAA |
| `text.secondary` auf `surface` | `#3A4E5A`/`#FFF` ~8:1 | `#A6BECD`/`#0A1922` ~7:1 | ✓ |
| `link`/`activeTextColor` auf `surface` | `#0A5AA0`/`#FFF` **7.04:1** | `#6FBEEA`/`#0A1922` ~7:1 | ✓ |
| `sidebar.textColor` auf `sidebar.bg` | `#3A4E5A`/`#F4F8FB` ~7.5:1 | `#A6BECD`/`#12232C` ~6:1 | ✓ |
| `codeFg` auf `rightPanel.bg` | `#E7F1F6`/`#0C2635` >12:1 | `#DCE7ED`/`#06141C` >13:1 | ✓ AAA |
| Method-Badge fg/bg (light) | Tokens `method_badges.*` alle ≥ AA | Dark-Töne **§9-1 offen** | ⚠ |
| Tier-Badge fg/bg | Tokens `tier_badges.*` ≥ AA | (heller Badge auch im Dark, kontrolliert) | ✓ |

**Farbe nie alleiniger Träger** (WCAG 1.4.1): Method-/Tier-Badges tragen **immer** Text-Label; Trust-Zeile ist Text; aktive Nav zusätzlich durch Position/Gewicht markiert, nicht nur Farbe.

---

## §8 — Invarianten (= UX-QA-Abnahme für die 234a-3-Stylung, 8)

1. **Eine Shell, zwei Renderer** — REST (Redoc) und WS (AsyncAPI) unter identischer maritimer Chrome; ein Toggle, kein Stil-Bruch.
2. **Marke kräftig, Lesen ruhig** — primary/Links/aktive Nav tragen das satte Blau; Sidebar-BG/Body/Panels bleiben hell/ruhig (bzw. dunkel-ruhig im Dark).
3. **Ein Link-Ton, app-konsistent** — kein divergenter dritter Blau-Ton; Doku-primary = App-primary (nach §1-Call).
4. **Code-Panel dunkel, bewusst** — `rightPanel` Ozean-dunkel in beiden Modi, AAA-Kontrast.
5. **Badges label-tragend** — Method/Tier nie Farbe allein; Operator nie Alarm-Rot.
6. **AA hart, light UND dark** — jedes Text-/UI-Paar an der Render-Fläche ≥ AA (Method-Dark = §9-1 zu schließen).
7. **Trust-Zeile ehrlich** — nur Schema-Treue behauptet (Shapes/Tiers/Envelope), nie Rate-Limits/Deprecation-Fristen (advisory).
8. **0 App-Berührung** — reine gehostete HTML-Surface; 0 Compose-`ColorScheme`/`strings.xml`/`testTag`-Änderung.

---

## §9 — Offene Punkte / §-Asks (nicht-blockierend)

1. **§-Ask (mir zu liefern):** Dark-Mode-Method-Badge-Hex (5 Töne) — im R2-Stil AA gegen dunkle Container verifizieren. Kleiner, ich ziehe das mit der finalen Token-Fassung nach dem §1-Call nach.
2. **§-Ask (PO/Auftraggeber):** §1 — Doku-Marke an CYP-268-„kräftig" angleichen (RECOMMENDED) oder bewusst ruhiger halten? Ich re-tokene erst nach diesem Call.
3. **Info:** AsyncAPI-React-Theme-Keys weniger standardisiert als Redoc; die Frame-Union-/Kanal-Darstellung erbt `palette_* + typography + tier_badges` — exaktes Key-Mapping beim 234a-3-Bau gegen die dann gepinnte AsyncAPI-Version fixieren (versionsempfindlich, wie beim Renderer üblich).

---

## §10 — Hand-off

- **Kein Bau** — Design-Pass, feeds 234a-3. **PO/Auftraggeber:** §1-Call (kräftig-angeglichen vs. ruhig). Danach falte ich die finalen Werte in `dev-api-docs-experience-tokens.json` (`renderer_mapping` → konkrete Hex) + schließe §9-1.
- **UX-QA später:** die 8 Invarianten §8 sind meine Abnahme-Checkliste, sobald 234a-3 gebaut ist (zusammen mit der §6=10-Invarianten-Abnahme des Haupt-Specs).
