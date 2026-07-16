# CYP-656 — Titelleisten-Token-Frische: welcher Ton trägt „stale" an `barContent`?

> Owner: UIUX-Designer · Ticket **CYP-656** (Titelleisten-Frische) · Stand 2026-07-16 · **Design-Pass, kein Bau.**
> Gegroundet READ-ONLY gg. develop `788425dd` (Code) + **gemessene** WCAG-Kontraste (nicht Token-Namen-plausibel).
> Antwort auf die PO-Frage: „welcher Ton trägt *stale/nicht-nachweisbar-frisch* an `barContent`, ohne unter 4.5:1
> zu fallen?" · Dev baut den Mechanismus (`titleBarTokenStale(connection) = connection != LIVE` + „Node existiert
> weiter = nicht versteckt") parallel — **nur der Wert/Mechanik fehlt: dieser hier.**

## 0. Der Ehrlichkeits-Call (PO, steht) — und die Mechanik-Frage (meine)

Die Token-Zahl friert über eine Reconnect-Lücke ein und **behauptet damit Frische, die sie nicht hat.** PO-Call:
**auf `!LIVE` das Unwissen zeigen — nicht verstecken, nicht Frische behaupten** (Haltung des UNKNOWN-Dots). Der
Call steht. Offen war nur die **Mechanik**: „ausgrauen" braucht einen Ton, und den gibt es an dieser Stelle nicht
frei — die Frage ist, ob ein AA-sicherer Ton überhaupt existiert.

## 1. Befund (GEMESSEN): Ausgrauen/Dimmen ist an dieser Stelle die falsche Mechanik

Die Titelleiste ist **per-Agent eingefärbt** (`SenderPalette.forAgent` → `deriveScheme`): `barBg` = die
Agent-Farbe (beliebiges `#RRGGBB`, CYP-211 custom!), `barContent` = die abgeleitete **AA-onColor**. Und
`deriveScheme` (`core/…/ColorDerivation.kt`) macht genau das Entscheidende:
> **`onColor` = reines WEISS oder SCHWARZ** (was mehr kontrastiert), dann wird der **Hintergrund** nachgezogen,
> **bis das reine Weiß/Schwarz gerade ≥ 4.5:1 erreicht.**

⟹ `barContent` ist **immer reines Weiß/Schwarz an der 4.5:1-Kante** — für jede „adjusted" Agent-Farbe **null
Headroom**. Jedes Wegbewegen davon (= Grau/Dimmen) **senkt** den Kontrast monoton. Gemessen (WCAG,
`scratchpad/cyp656.py`):

**(1) Schon die 8 festen Slots haben kaum Luft für Weiß:**

| Slot | Fill | cr(Weiß, Fill) | Headroom über 4.5 |
|---|---|---|---|
| pink | `#C24D6A` | **4.60** | **+0.10** |
| bronze | `#9A6B2F` | 4.65 | +0.15 |
| olive | `#6E7A2E` | 4.68 | +0.18 |
| magenta | `#B5419A` | 5.01 | +0.51 |
| teal | `#1F7A6E` | 5.16 | +0.66 |

Custom-Farben landen per Konstruktion **genau an ~4.5:1**. Der Worst Case ist also **generisch, nicht exotisch.**

**(2) Alpha-Dimmen kollabiert** (Bar so getunt, dass Weiß bei ~4.5:1 sitzt — deriveSchemes Landepunkt):

| Weiß @ Alpha | resultierender Kontrast | AA? |
|---|---|---|
| 1.00 | 4.48 | knapp |
| 0.90 | 3.96 | ✗ |
| 0.85 | 3.76 | ✗ |
| 0.75 | 3.30 | ✗ **(genau der Wert, den `WindowManager.kt` abgelehnt hat)** |
| 0.60 | 2.70 | ✗ |

**(3) Kein fester „Stale-Grau"-Token trägt die Familie** — jeder Kandidat fällt gegen die meisten Mitglieder:

| Grau-Kandidat | dark teal | light custom | mid | olive |
|---|---|---|---|---|
| onSurfaceVariant-light `#3A4E5A` | 1.68 ✗ | 4.33 ✗ | 1.89 ✗ | 1.86 ✗ |
| onSurfaceVariant-dark `#A6BECD` | 2.67 ✗ | 1.04 ✗ | 2.38 ✗ | 2.42 ✗ |
| neutral `#9E9E9E` | 1.93 ✗ | 1.33 ✗ | 1.72 ✗ | 1.75 ✗ |

**(4) Der Unfokus-Blend** (`barBg = lerp(agentColor, surface, 0.45)`, `surface` theme-abhängig) macht es
schlimmer: dasselbe Grau fällt **je Theme** unterschiedlich durch (light-unfokus vs `#9E9E9E` = 1.15; dark-unfokus
vs `#3A4E5A` = 1.18) — **kein Grau überlebt beide Themes** auf der geblendeten Familie.

> **★ Fazit (gemessen + algorithmisch bewiesen):** Es gibt **kein** semantisches Grau und **kein** Alpha, das an
> `barContent` (labelSmall ⟹ 4.5:1-Pflicht) über die **beliebige, per-Agent, theme-geblendete** Titelleisten-
> Familie AA-sicher ist. Die Antwort auf „welcher Grau-Ton?" ist ehrlich: **keiner.** „Ausgrauen" ist **hier** die
> falsche Mechanik — genau der Fall, den du vorweggenommen hast. **Kein neues Farb-Token** (es gibt kein ehrliches).

## 2. Empfehlung: die Zahl bleibt voll-kontrastig, Frische über einen NICHT-Farb-Marker

Die Titelleiste hat dasselbe Problem **schon gelöst** — für die Mode-Marker (`◉/→/←/∅`) und das Busy-`*` (beide
labelSmall, `barContent`, WCAG 1.4.1: *„form + label carry the meaning; colour only reinforces"*). Auf einer
beliebig eingefärbten Bar signalisiert man Zustand **nicht über Farbe, sondern über einen glyphischen Marker bei
vollem `barContent`.** Token-Staleness folgt **demselben gelösten Muster**:

- **Die Zahl bleibt in vollem `barContent`** (AA per Konstruktion erhalten — es ist dieselbe garantierte onColor,
  kein Dimmen). Kein Sub-AA-Risiko, für jede Agent-Farbe, beide Themes, fokussiert/unfokussiert.
- **Frische-Marker = führendes `~`** auf dem Wert: **`~137k`.** `~` liest universell „ungefähr / zuletzt bekannt"
  — exakt die ehrliche Semantik („das war der letzte Stand, den ich sah; ich kann *jetzt* nicht bestätigen, dass
  er aktuell ist"). Plain-ASCII (kein Emoji/kein Tofu-Risiko — CYP-54/99), monospace-tauglich (fügt sich in die
  bestehende stabile-Ziffernbreite ein).
- **a11y trägt dieselbe Ehrlichkeit** (nicht nur visuell — sonst „visuell ehrlich, assistiv stumm"): der
  Token-Node bekommt im Stale-Fall die Copy `a11y_agent_context_tokens_stale` = „Kontext: zuletzt bekannt %1$s
  Tokens (nicht live)".
- **QA-Zahn ohne Pixel-Peek:** der Node trägt `stateDescription ∈ {live, stale}` (Idiom real:
  `AgentAvatarSection.kt:110` `stateDescription`) — Tester (CYP-7) assertet Stale deterministisch, nicht über Farbe.

Der Marker **komponiert mit Devs Mechanismus** (`titleBarTokenStale(connection)`; Node bleibt = nicht versteckt):
`~` **ist** der „nicht versteckt, aber markiert"-Render.

## 3. Warum das die Ehrlichkeit trägt (dein Call, andere Mechanik)

| Anforderung | erfüllt durch |
|---|---|
| **Unwissen zeigen** (nicht Frische behaupten) | `~` disclaimt aktiv die Aktualität („zuletzt bekannt") — stärker als ein Grau, das man übersehen kann |
| **nicht verstecken** | die Zahl bleibt sichtbar, voll lesbar (der Node persistiert — Devs Zahn) |
| **keine Frische-Behauptung** | eine blanke `137k` behauptet Aktualität; `~137k` **nicht** |
| **AA gewahrt** | volle `barContent`-onColor, kein Dimmen → 4.5:1 per Konstruktion, jede Farbe/Theme |
| **assistiv konsistent** | `a11y_..._stale` + `stateDescription` — der SR hört „zuletzt bekannt, nicht live" |

## 4. Reuse & Präzedenz

- **Muster gelöst reused:** Nicht-Farb-Marker bei vollem `barContent` — die Mode-Marker (`WindowManager.kt:731-778`)
  und Busy-`*` (`:786-799`) machen es identisch (Glyph trägt, Farbe nur Verstärkung, a11y trägt Bedeutung). Kein
  neues Muster — die **schon vorhandene** Antwort der Titelleiste auf „Zustand auf beliebiger Agent-Farbe".
- **Kein neues Farb-Token** (§1: es gibt kein AA-sicheres). Kein Roh-Hex, kein Bauch-Grau.
- **`stateDescription`-QA-Idiom reused** (`AgentAvatarSection.kt:110`).
- **Marker-Glyph:** `~` (ASCII, tofu-frei). *Alternative* `≈` (stärkeres „ungefähr") **nur** falls Dev die
  Glyph-Zuverlässigkeit auf Desktop-JVM + Web-Wasm verifiziert; Default `~`.

## 5. Keys / Tags

- **Neu:** `a11y_agent_context_tokens_stale` (1 a11y-Key, 1 Arg `%1$s` = compact wie beim Live-Pendant).
  - DE: „Kontext: zuletzt bekannt %1$s Tokens (nicht live)" · EN: „Context: last known %1$s tokens (not live)".
- **Reuse:** der Wert-Node behält seinen Tag `window.<id>.contextTokens` (`WindowTestTags`, Devs „nicht
  versteckt"-Zahn) — **kein neuer Tag**; Stale ist ein `stateDescription`-Zustand desselben Nodes, kein zweiter Node.
- **Reuse:** die Live-a11y `a11y_agent_context_tokens` bleibt unverändert (Fresh-Fall).

## 6. Nahtstelle (→ Dev, der Mechanismus baut schon)

- **Render:** im Stale-Zweig (`titleBarTokenStale == true`) ein führendes `~` vor `formatCompactTokens(n)`; **Farbe
  bleibt `barContent`** (kein Alpha, kein Grau). Node + `stateDescription="stale"` (sonst `"live"`).
- **a11y:** Stale-Zweig nutzt `a11y_agent_context_tokens_stale` statt `a11y_agent_context_tokens`.
- **Reflow:** der seltene Fresh↔Stale-Wechsel verschiebt **eine** Glyph-Breite (Marker kommt/geht). Akzeptabel
  (seltenes Verbindungs-Ereignis, ehrlicher Zustandswechsel) — **nicht** der §8-6-Live-Tick-Jitter. Falls Dev
  Null-Shift will: einen festen führenden Marker-Slot reservieren (Space wenn live, `~` wenn stale) — Dev-Wahl.
- **Keine Farb-Änderung, kein Token** — der „Wert", den du wolltest, ist eine **Marker-Konvention + a11y**, kein Hex.

## 7. Self-Validation

- **GEMESSEN, nicht plausibel:** WCAG-Kontraste gegen die echten Fills + den deriveScheme-Landepunkt + den
  Unfokus-Blend beider Themes (`scratchpad/cyp656.py`, reproduzierbar). Der CYP-643-Fehler (Token-Name grün,
  gemessene Fläche weiß) ist damit ausgeschlossen — die Zahl ist gegen den **echten** Hintergrund geprüft.
- **Ehrlich zur Mechanik:** ich sage explizit „kein AA-sicheres Grau/Alpha existiert hier" statt ein Sub-AA-Token
  zu raten — und liefere die Alternative, die deinen Ehrlichkeits-Call trägt (Marker statt Farbe), wie von dir geöffnet.
- **Reuse-first:** das Nicht-Farb-Marker-Muster ist die schon-gelöste Titelleisten-Antwort (Mode-Marker/Busy);
  1 neuer a11y-Key, 0 neue Farb-Token, 0 neuer Tag (Node + `stateDescription`).
- **AA bewiesen:** volle `barContent` = deriveSchemes garantierte ≥4.5:1, unverändert — der Fix **entfernt** das
  Sub-AA-Risiko, statt eines einzuführen.
- Kein Bau, docs-only auf `feature/CYP-656-titlebar-token-staleness-spec`. Dev-Mechanismus-Branch
  (`feature/CYP-656-titlebar-connection-plumb`) nicht angefasst.
