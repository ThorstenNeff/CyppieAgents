# Strang CYP-363/369/370 — die drei offenen Entscheidungen (Skizze für den PO)

> Owner: UIUX-Designer · Stand 2026-07-11 · Basis **`origin/develop` = `bc38cbe`** (Flip drin) · Scope **WASM-App**
> Docs-only. **Keine Böden gerechnet** — der Strang wartet hinter dem Dogfood-Cleanup (382/383) und dem
> Auftraggeber-Hold. Diese Skizze ist zum **Entscheiden**, nicht zum Umsetzen. Böden liefere ich, sobald die
> drei Entscheidungen stehen und der Hold fällt (sonst messe ich wieder eine Komposition, die es beim Merge
> nicht mehr gibt).

Jede Entscheidung: **Optionen · Empfehlung · Produkt-/Auftraggeber-Relevanz.**

---

## Entscheidung 1 — Zählt die Fehlerzeile in den Boden? (CYP-363)

**Worum es geht.** Der Boden eines Inhaltsfensters (`CONTENT_WINDOW_MIN_HEIGHT`) muss die **höchste** Form des
festen Chrome tragen. Die `LifecycleErrorRow` (+20 dp) erscheint nur im Fehlerfall — aber genau dann, wenn der
Operator reagieren muss. Gemessen (§2.4.1): am Boden **mit** Fehlerzeile ist die Eingabezeile weg — *sie war es
ohne Fehlerzeile aber auch schon*; die 20 dp bezahlt die Toggle-Zeile.

| | **A — Fehlerzeile nicht budgetiert** | **B — Fehlerzeile budgetiert** |
|---|---|---|
| Boden trägt | die vier Standard-Chrome-Zeilen | Standard **+ 20 dp** Fehlerzeile |
| Im Fehlerfall | Eingabezeile kann verschwinden | Eingabezeile bleibt |
| Preis | — | **jedes** Inhaltsfenster dauerhaft 20 dp höher |

**Empfehlung: B.** Ein Minimum, das nur die günstigste Form trägt, ist keins. Der Fehlerfall ist der Moment,
in dem die Eingabezeile am dringendsten gebraucht wird; sie dort verschwinden zu lassen, ist die dritte Stelle
im Strang, an der die **Diagnose sichtbar** ist und die **Therapie nicht**.

**Relevanz: Produkt, nicht Auftraggeber.** Es ändert eine Layout-Invariante, die jeder sieht (minimale
Fensterhöhe), aber es ist keine Sichtbarkeits-/Ehrlichkeits-Verletzung. Reine Robustheitsentscheidung.

> Post-flip zu beachten: die höchste Toggle-Zeile ist jetzt **68 dp** (gated-Hinweis mit 84 dp ist weg). Die
> konkreten Bodenzahlen rechne ich nach der Entscheidung — sie hängen an B/A **und** am Kreuzprodukt der
> post-flip-Zustände.

---

## Entscheidung 2 — Der Browser-Schalter (post-flip / CYP-370)

**Worum es geht.** Nach `CYP-333-flip` ist `terminalContent != null` auf **jedem** Target — auch im Browser, wo
das WASM-`TerminalView` ein Stub ist (*„Interactive terminal is available on Desktop only."*). Der Operator
sieht im Browser einen **entsperrten** Shell-Schalter, der einen Platzhalter öffnet, eine Zeile behauptet
„bash-Worktree-Shell", und der ehrliche Hinweis („Shell verfügbar, sobald …") ist unerreichbar geworden.

| | **1 — Schalter fähigkeitsgesteuert sperren** | **2 — so lassen** |
|---|---|---|
| Mechanik | `expect val TERMINAL_INTERACTIVE` (jvm `true`, wasmJs `false`); die Verdrahtung liest **sie** statt der Nicht-Nullheit eines Slots | — |
| Browser zeigt | Segment deaktiviert **mit Begründung** (Key existiert) | einen Schalter, der lügt |
| Kosten | 1 `expect val`, 0 neue Keys | — |

**Das ist keine Geschmacksfrage.** Der Ist-Zustand **verletzt CYP-317 („no fake switch") auf dem einzigen
ausgelieferten Target.** Option 2 steht nur der Vollständigkeit halber da. **Empfehlung: 1.**

**Relevanz: Auftraggeber.** Es ist im Browser sichtbar, es ist eine Ehrlichkeits-Verletzung, und die Regel, die
es verbietet, ist bereits ratifiziert. **Höhere Priorität als die beiden Layout-Zahlen** — ich würde es dem
Hold **nicht** unterordnen, sondern als Korrektheitsdefekt behandeln. (Deine Entscheidung; ich flagge nur die
Dringlichkeit.)

---

## Entscheidung 3 — Hostbreite < 320 dp (CYP-370)

**Worum es ging — und warum es fast gegenstandslos ist.** Die Sorge war: ein Inhaltsfenster kann nicht
gleichzeitig *„nie schmaler als 320"* und *„nie breiter als der Host"* halten, wenn der Host < 320 dp ist.
**Gemessen am Code — die Kollision tritt praktisch nicht auf:**

- Der freischwebende **Fenster-Canvas läuft erst ab Hostbreite ≥ 600 dp.** Darunter (`WindowWidthSizeClass.Compact`,
  `WindowManager.kt:157–163`) übernimmt der **PhonePager**.
- Im Pager füllt jedes Fenster die Seite (`fillMaxSize()`, `:321`) — **die Fensterbreite ist dort bedeutungslos.**

Also: die 320-dp-Mindestbreite gilt **nur** im Canvas, und dort ist der Host **immer ≥ 600 dp** → 320 passt
**immer**. Der CYP-370-Fix (`invariantMinWidth` an den Clamp-Pfaden) ist trotzdem richtig — er verhindert, dass
der **Nutzer** ein Agentenfenster **manuell** unter 320 dp zieht —, aber er kollidiert **nie** mit der
Hostbreite.

| | **A — nichts extra** | **B — expliziter Umgang < 320** |
|---|---|---|
| Wann relevant | nie im Canvas (Host ≥ 600); Pager ignoriert Breite | ein hypothetischer Host 320–599 im Canvas — **existiert nicht** |

**Empfehlung: A — keine Sonderbehandlung.** Der `isCompact`-Übergang bei 600 dp deckt den ganzen schmalen
Bereich ab, bevor die 320-dp-Frage überhaupt entstehen kann. **Der CYP-370-Fix steht für sich** (Nutzer-Resize),
unabhängig von dieser Entscheidung.

**Relevanz: keine, über den CYP-370-Fix hinaus.** Ich führe sie nur auf, weil du sie als offen notiert hattest —
die Messung schließt sie.

---

## Zusammenfassung

| # | Entscheidung | Empfehlung | Relevanz |
|---|---|---|---|
| 1 | Fehlerzeile im Boden? | **B** (budgetieren) | Produkt |
| 2 | Browser-Shell-Schalter | **1** (sperren) — Korrektheitsdefekt, CYP-317 | **Auftraggeber** |
| 3 | Host < 320 dp | **A** (nichts) — durch 600-dp-Pager gedeckt | keine |

**Was ich nach deiner Entscheidung liefere:** die konkreten post-flip-Böden für Fassung B (bzw. A), gemessen
im Kreuzprodukt `{Operator, Nicht-Operator} × {Orchestrierung, Shell} × {Fehler, kein Fehler}` — **nicht**
addiert, sondern vom Guard gerendert. Erst wenn der Hold fällt.
