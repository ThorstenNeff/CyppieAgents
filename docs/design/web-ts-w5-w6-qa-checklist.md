# Web→TS W5/W6 — UX-QA-Checkliste (Composer- + Scrollbar/Retention-Parität)

> Owner: UIUX-Designer · Stand 2026-07-11 · Basis `origin/develop` `b3d08dae` · Scope: DOM-Port, Ziel = **Parität**
> Docs-only, **Vorbereitung**: gegen diese Liste QA ich Dev5s W5 (fertig) + W6 (startet) DOM-Impl. **Kein Bau.**
>
> **Quelle der Wahrheit = die bestehende Compose-Impl.** Die DOM-Umsetzung muss dasselbe **Verhalten** liefern,
> nicht dieselbe Mechanik. Jeder Check nennt (a) den Paritäts-Vertrag aus dem Compose-Code, (b) die DOM-Erwartung,
> (c) **welche falsche Implementierung er ablehnt** (diskriminierend, nicht „läuft's?").
>
> **Compose-Anker:** `agentview/InputHistory.kt`, `agentview/ComposerRecall.kt` (W5); `ui/ThinVerticalScrollbar.kt`,
> `agentview/TranscriptAutoScroll.kt` (W6). **Parität ≠ Nachbau:** wo DOM etwas **nativ** löst (Scrollbar, Uniform-
> Geste), ist der Compose-Seam **nicht** zu portieren — Check W6.9.

---

# W5 — Composer / Input-History (Parität zu CYP-387)

## W5.A — History-Store (`InputHistory`)

| # | Paritäts-Vertrag (Compose) | DOM-Erwartung | Lehnt ab (falsche Impl) |
|---|---|---|---|
| W5.1 | Newest-last, begrenzter Ring; **Inhalt pro Agent** (lebt an der Agenten-Session) | ein Recall-Store je Agent | ein globaler Store, der Historien mischt ⇒ rot |
| W5.2 | **N ist EINE globale Präferenz** (Default **20**, Stepper **0..200**), **`0 = aus`** | ein globaler Setting-Wert, kein per-Agent-N | N pro Agent / Default ≠ 20 / kein 0-Aus ⇒ rot |
| W5.3 | **`N ≤ 0` → Historie AUS**: `record` ist No-op, `entries` leer (ehrlicher Schalter) | bei 0 wird nichts aufgezeichnet **und** nichts angeboten | 0 zeichnet weiter auf / ↑ zeigt Alt-Einträge ⇒ rot |
| W5.4 | **N ist ein Live-Supplier**: Änderung von N wirkt auf einen **schon offenen** Agenten, **ohne** den Inhalt zu wipen | N ändern → nächster Read ist beschnitten, Inhalt bleibt | N-Änderung baut den Store neu / löscht Inhalt ⇒ rot |
| W5.5 | Bei N: **ältesten** Eintrag verdrängen (HISTSIZE-Modell) | Ring-Verhalten | wächst unbegrenzt / verdrängt den neuesten ⇒ rot |
| W5.6 | **Kein Dedup in v1**; aufeinanderfolgende Duplikate beide behalten | Duplikate bleiben | „intelligentes" Dedup ⇒ rot (Verhalten weicht ab) |
| W5.7 | **Blank wird nie aufgezeichnet** (Trim; Defence-in-depth mit dem Sende-Pfad) | leere/whitespace-Sends nicht in der Historie | Blank landet im Ring ⇒ rot |

## W5.B — Recall-Cursor (`ComposerRecall`) — die Interaktion

| # | Paritäts-Vertrag (Compose) | DOM-Erwartung | Lehnt ab |
|---|---|---|---|
| W5.8 | **↑ betritt Historie und stasht den Live-Draft**; danach Schritte zu älteren | erster ↑ merkt den getippten Entwurf, zeigt den neuesten Eintrag | ↑ verwirft den Entwurf ⇒ rot |
| W5.9 | **↓ am Neuesten stellt den gestashten Draft wieder her** (verlässt Historie) | ↓ zurück bis zum Entwurf, exakt wie getippt | ↓ endet auf Leer / letztem Eintrag statt Draft ⇒ rot |
| W5.10 | **Recall-Edit ist transient**: editiert man einen zurückgeholten Eintrag, liest das nächste ↑/↓ den **ORIGINAL**-Nachbarn (Store nie mutiert) | Bearbeiten der Recall-Anzeige ändert die Historie nicht | Edit überschreibt den Historien-Eintrag ⇒ rot |
| W5.11 | **`null` = nicht konsumiert**: ↑ bei leerer Historie, ↓ am Live-Draft → der Tastendruck wird **nicht** geschluckt | bei „nichts zu tun" kein `preventDefault` (Caret/Default-Verhalten bleibt) | ↑/↓ schluckt den Keypress ins Leere ⇒ rot |
| W5.12 | Am **ältesten**: kein Move, aber **konsumiert** (Draft unverändert) | ↑ am Ende der Historie bewegt nicht weiter, frisst aber die Taste (kein Caret-Sprung) | springt darüber hinaus / Caret wandert ⇒ rot |
| W5.13 | **Nach Send: `reset`** — `navIndex`+`stash` geleert, der Send ist als neuer Eintrag im Store | nach Senden startet ↑ wieder beim neuesten (inkl. dem eben Gesendeten) | Cursor bleibt mitten in der Historie / gesendeter Text fehlt ⇒ rot |
| W5.14 | **Einzeiliger Composer** (Spec §0): ↑/↓ = reine Historien-Nav, keine Cursor-Zeilen-Logik | einzeiliges `<input>`/`<textarea rows=1>`; ↑/↓ nie als Zeilennavigation | mehrzeilig, ↑/↓ bewegt Cursor statt Historie ⇒ rot |

> **W5-DOM-Falle:** „nicht konsumiert" (W5.11) ist im DOM ein bewusstes **Weglassen** von `preventDefault` — leicht
> zu übersehen, weil ein Handler oft pauschal `preventDefault()` ruft. Sonst kann man in einem einzeiligen Feld
> nicht mehr per ↑/↓ ans Feldende springen.

---

# W6 — Scrollbar + Scroll-Retention (Parität zu CYP-392/393)

## W6.A — Thin-Scrollbar-Stil (`ThinVerticalScrollbar`)

| # | Paritäts-Vertrag (Compose) | DOM-Erwartung | Lehnt ab |
|---|---|---|---|
| W6.1 | **Dicke 8 / Radius 4 / Min-Thumb 24** (24 ≥ WCAG 2.5.8 Zielgröße) | `scrollbar-width: thin` + WebKit `::-webkit-scrollbar{width:8px}`, Thumb-Radius 4, Mindest-Thumb-Höhe 24px | Thumb < 24px / abweichende Maße ⇒ rot |
| W6.2 | **Rest-Farbe = `outline`**, ≥ 3:1 auf `surface` in **beiden** Themes, **KEIN Alpha** (CYP-337: `outline` sitzt am 3:1-Boden) | `scrollbar-color: var(--md-sys-color-outline) transparent` (bzw. WebKit-Thumb), **ohne** `opacity`/Alpha | Alpha auf dem Thumb (fällt unter 3:1) / anderer Farbton ⇒ rot |
| W6.3 | **Aktiv (Hover/Drag) = `onSurfaceVariant`** | Hover/Active-Thumb → `onSurfaceVariant` | Hover-Farbe fehlt/falsch ⇒ rot |
| W6.4 | **Present-when-scrollable** (Sichtbarkeit = Caller): Balken nur bei Überlauf | nativer Overflow zeigt den Balken nur bei Überlauf; kurze Liste → keiner | Balken auch ohne Überlauf / immer sichtbar als Deko ⇒ rot |

## W6.B — Scroll-Retention (`transcriptAtBottom` + Follow/Release)

| # | Paritäts-Vertrag (Compose) | DOM-Erwartung | Lehnt ab |
|---|---|---|---|
| W6.5 | **„Am Boden" = letztes Item sichtbar UND seine Unterkante ≤ Viewport-Ende + ~48 px Toleranz**; leere Liste = am Boden (frisch gepinnt) | `scrollTop + clientHeight >= scrollHeight − TOLERANCE(~48px)`; leer = gepinnt | exaktes `!canScrollForward` (0 Toleranz) → Flicker beim Streaming ⇒ rot |
| W6.6 | **Follow folgt dem WACHSEN des Tails, nicht `events.size`**: ein Streaming-Delta wächst **ein** Item in-place (TranscriptFolding), die Listengröße bleicht konstant | native `scrollHeight` misst die reale Höhe → Follow keyt darauf, nicht auf Elementzahl | Follow an Element-Count gebunden → bleibt beim In-place-Delta stehen ⇒ rot |
| W6.7 | **Release NUR bei Nutzergeste**, nie beim eigenen Follow/Agent-Tippen; deckt **Rad + Tastatur + Drag + Touch einheitlich** ab | Release am nativen `scroll`-Event (feuert für alle vier Gesten); Resume bei Rückkehr an den Boden | **Drag-only**-Detektor (verpasst das Rad im Web!) ⇒ rot — genau der CYP-393-Grund, warum `isScrollInProgress` die DragInteraction schlug |
| W6.8 | **Follow-Scroll ist INSTANT** (Compose: `scrollToItem`, nie `animateScrollToItem`) — sonst triggert der eigene Scroll die Release-Bedingung und löst sich selbst | programmatischer Sprung ans Ende **ohne** `scroll-behavior: smooth` (instant `scrollTop = scrollHeight`) | smooth/animierter Auto-Scroll → self-release/Kampf mit dem Pin ⇒ rot |

## W6.C — Parität-mit-Vereinfachung (nicht nachbauen)

| # | Contract | DOM-Erwartung | Lehnt ab |
|---|---|---|---|
| W6.9 | Compose hat einen `expect/actual`-**skiko-Seam** (`renderThinScrollbar`, android/ios No-op), weil `VerticalScrollbar` in commonMain mit Android-Target unresolved ist | **DOM hat KEINEN Seam** — die Scrollbar ist **nativer Overflow** + CSS; der skiko-Draw wird **nicht** portiert | ein nachgebautes Custom-Scrollbar-Widget statt nativem Overflow ⇒ rot (Parität heißt hier: einfacher, nicht gleicher Mechanismus) |

---

## Querschnitt (beide Slices) — DOM-Ehrlichkeit, die ich mitprüfe

- **Kontrast auf gerendertem DOM**, nicht Kit-Default: Thumb-Farbe (W6.2) real messen; Composer-Placeholder/
  Disabled-`opacity` gegen Maritime-Ratios.
- **Kein `text-overflow: ellipsis`** auf Nachrichten-/Hinweistext, der Bedeutung trägt (Offenlegung bricht um).
- **Zielgröße ≥ 24 px** (WCAG 2.5.8) für den Scrollbar-Thumb (W6.1) und interaktive Composer-Elemente.
- **Tokens portiert, nicht Kit-Optik** (Migrations-Grundsatz): `outline`/`onSurfaceVariant` als CSS-Variablen mit
  den identischen Ratios.

## Abnahme-Priorität

Die **Ehrlichkeits-/Verhaltens-Checks** wiegen schwerer als die Maße: **W5.10** (Recall-Edit transient),
**W5.11** (Keypress nicht schlucken), **W6.7** (Uniform-Geste, kein Drag-only), **W6.2** (kein Alpha am Thumb).
Ein Abweichen hier ist ein echter Paritäts-Bruch; ein um 1 px falscher Radius ist Politur.

**Nichts hier gebaut — QA-Vorbereitung. Ich fahre die Liste gegen Dev5s W5/W6-DOM-Impl, sobald die SHA da ist,
und melde den Befund als priorisierte Liste (Severity + konkreter Fix) an den PO.**
