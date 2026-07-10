# CYP-370 — Bindet die Zahl? Audit der vier ungeprüften `MIN`-Konstanten

> Owner: UIUX-Designer · Ticket **CYP-370** · Stand 2026-07-10 · Basis **`origin/develop` = `bc38cbe`** (Flip drin) · Scope **WASM-App**
> Docs-only. Sonden waren Instrumente und sind entfernt; `:app:shared:jvmTest` unverändert grün.

**Die Frage war nicht „steht sie im Code", sondern: gibt es einen erreichbaren Zustand, in dem die Zahl das
verhindert, was ihr Name verspricht?** Gemessen am `WindowReducer`/`WindowManagerState` (reine Zustandslogik,
keine Komposition nötig).

---

## 1. Die Urteile

| Konstante | Wert | Urteil | gemessen mit |
|---|---|---|---|
| `MIN_VISIBLE_WINDOW` | 48 | **bindet** | Fenster 10 000 dp nach außen ziehen → `x = 952` (48 sichtbar), `y = 752` (48 sichtbar); nach innen → `x = −592` (rechte Kante bei 48), `y = 0` |
| `MIN_WINDOW_WIDTH` | 160 | **bindet** (Nicht-Inhaltsfenster) | `resizeBy(−10 000, −10 000)` → `160 × 120` |
| `MIN_WINDOW_HEIGHT` | 120 | **bindet** (Nicht-Inhaltsfenster) | dito |
| `CONTENT_WINDOW_MIN_HEIGHT` | 301 | **bindet** | `resizeBy(0, −10 000)` → `301`; `updateHostSize(1000, 150)` → `301` |
| **`TILED_CONTENT_WINDOW_MIN_WIDTH`** | **320** | **BINDET NICHT** | `resizeBy(−10 000, 0)` → **`160`** · `updateHostSize(200, 800)` → **`200`** |

> **Deine Vermutung war `MIN_VISIBLE_WINDOW`. Sie bindet.** 48 dp bleiben auf jeder Kante, und `minY = 0` hält
> die Titelleiste erreichbar. Gemessen, nicht geglaubt — die Vermutung war falsch, und das ist ein Ergebnis.

---

## 2. Der Befund: die Breite hat keine Invariante

`WindowReducer` besitzt

```kotlin
fun invariantMinHeight(isContent: Boolean): Float =
    if (isContent) CONTENT_WINDOW_MIN_HEIGHT else MIN_WINDOW_HEIGHT
```

**und kein `invariantMinWidth`.** Entsprechend übergeben **alle drei** Clamp-/Resize-Pfade die Höhe und
**keiner** die Breite:

| Pfad | `minHeight` | `minWidth` |
|---|---|---|
| `updateHostSize` (`:416`) | `invariantMinHeight(isContent)` | *Default* `MIN_WINDOW_WIDTH = 160` |
| `resizeBy` (`:586`) | `invariantMinHeight(isContent)` | *Default* `160` |
| `expandOrRestore` → Restore (`:616`) | `invariantMinHeight(isContent)` | *Default* `160` |

Die Platzierungspfade (`placeNewWindow`, `tile`, `resetTo`) fragen dagegen sauber `minWidthFor(id)`. **Die
Breite ist also beim Erzeugen geschützt und beim Anfassen nicht.**

> **Es ist exakt die Asymmetrie, die CYP-338 und CYP-349 schon zweimal repariert haben — nur spiegelverkehrt.**
> Der KDoc in `placeNewWindow` sagt es selbst: *„the height had `MIN_WINDOW_HEIGHT` one line below the width's
> `typeMinW` — the same asymmetry."* Damals fehlte die Höhe neben der Breite. Jetzt fehlt die Breite neben der
> Höhe. Dieselbe Zeile, andere Achse, dritte Fundstelle.

---

## 3. Warum das jetzt teuer ist

**Die 320 dp sind die Kalibrierbreite von allem, was heute gebaut wurde.**

- **CYP-369:** Developer5 hat gerechnet — *Glyph-Badge + Glyph-Controls = 298 dp von 304 nutzbaren, 6 dp Luft.*
  Bei **160 dp** Fensterbreite sind **144 dp** nutzbar. Die drei Knöpfe sind wieder weg. **Ein Zug am
  Resize-Griff hebt CYP-369 auf.**
- **CYP-363:** Jede Chrome-Höhe dieses Tages ist bei 320 dp gemessen. Schmaler bricht der Header wieder um, das
  Chrome wächst, und der Boden (der die Höhe *sehr wohl* durchsetzt) ist zu niedrig.
- **`MIN_WINDOW_WIDTH = 160` bindet** — aber es bindet **an der falschen Stelle**: es ist der stille Default für
  **Inhaltsfenster** auf jedem Clamp-Pfad. Eine Zahl, die für ein Comm-Fenster richtig ist und für ein
  Agentenfenster falsch, steht dort ohne Fallunterscheidung.

**Kein Nutzerschaden ohne Nutzerhandlung** — aber die Handlung ist ein Mausziehen, und die Eigenschaft, die
verlorengeht, ist die Bedienbarkeit der einzigen Genesungs-Affordanzen. Das ist mehr als eine tote Zusage: es
ist eine Zusage, die auf **einem** Pfad gilt und auf **drei** nicht.

---

## 4. Der Fix und sein Guard

**Fix:** `invariantMinWidth(isContent)` neben `invariantMinHeight` — **eine** Quelle, wie CYP-349 sie für die
Platzierung geschaffen hat — und an allen drei Pfaden übergeben.

**Guard (bidirektional, ohne hartkodierte Chrome-Zahl):**

```
für jeden Pfad ∈ { resizeBy, updateHostSize, expandOrRestore-Restore }:
    assert breite(inhaltsfenster) >= TILED_CONTENT_WINDOW_MIN_WIDTH
    assert breite(nicht-inhaltsfenster) >= MIN_WINDOW_WIDTH     // 160 bleibt gültig, wo es hingehört
```

| # | Mutation | erwartet |
|---|---|---|
| M1 | `minWidth` an *einem* der drei Pfade weglassen | **rot** (Breite fällt auf 160) |
| M2 | `invariantMinWidth` gibt pauschal 320 zurück | **rot** — das Comm-Fenster darf 160 bleiben (Spiegelbild, wie CYP-349) |
| M3 | Host auf 200 dp schrumpfen | **rot**, solange die Breite nicht geklemmt wird |

**M2 ist der wichtige.** Ohne ihn repariert man die eine Richtung und bricht die andere — genau der Fehler, den
CYP-349 als *„spiegelbildlicher Fehler"* schon einmal gefangen hat.

> **Offene Frage, die ich nicht selbst beantworte:** Was soll geschehen, wenn der **Host schmaler als 320 dp**
> ist? Dann kollidieren zwei Zusagen — *„nie schmaler als 320"* und *„nie breiter als der Host"*. Beim Boden
> hat die Invariante gewonnen (das Fenster ragt heraus, gemessen: 391 dp hoch auf einem 260-dp-Host). Für die
> Breite ist das eine **Produktentscheidung**: horizontal scrollen, oder den Phone-Pager zeigen. Der Code hat
> mit `isCompact` bereits einen Pager — vermutlich greift er vorher. **Das ist zu messen, nicht zu vermuten.**

---

## 5. Nebenbefund: die Höhen-Invariante schützt das Layout, nicht die Sicht

Gemessen: `updateHostSize(1000, 150)` lässt das Inhaltsfenster **301 dp** hoch — auf einem **150 dp** hohen
Host. `syncWindows` auf einem 260-dp-Host erzeugt ein **391 dp** hohes Fenster. Die Eingabezeile ist dann im
Layout vorhanden und liegt **unterhalb des sichtbaren Bereichs**.

Das ist **kein Widerspruch zu CYP-338** — dort ging es darum, dass der Composer überhaupt gemessen wird — aber
es ist dieselbe Familie: **eine Zusage, die im Baum gilt und nicht im Blickfeld.** Severity gering (ein 150 dp
hoher Browser ist keine reale Lage), aber sie gehört benannt, weil der KDoc *„never larger than the host"*
verspricht und die Höhen-Invariante ihn überstimmt. **Zwei Zusagen, eine gewinnt, keine sagt es.**

---

## 6. Self-Validation

- **Fünf Konstanten geprüft, jede mit einer Sonde**, nicht mit einem Blick. Vier binden, eine nicht.
- **Die Vermutung des PO (`MIN_VISIBLE_WINDOW`) ist widerlegt** — und das steht hier, weil ein widerlegter
  Verdacht ein Ergebnis ist, kein Nichts.
- **Der Befund ist strukturell belegt, nicht nur empirisch:** `invariantMinHeight` existiert, `invariantMinWidth`
  nicht; drei Aufrufstellen übergeben genau eine der beiden Achsen.
- **Kein Schaden behauptet, der nicht gezeigt ist.** Die 160 dp entstehen nur durch eine Nutzerhandlung. Der
  Schaden ist benannt: er hebt CYP-369 auf, dessen Kalibrierung bei 320 dp **6 dp** Luft hat.
- **Eine Frage offen gelassen** (§4): das Verhalten unter 320 dp Hostbreite ist zu **messen**, nicht zu raten.
- **Docs-only.**
