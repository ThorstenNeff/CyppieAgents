# CYP-392 — Vertikaler Scrollbalken im Transcript (Visual-Spec, leicht)

> Owner: UIUX-Designer · Ticket **CYP-392** (Story) · Stand 2026-07-11 · Basis **`origin/develop` = `9cea1ed6`** · Scope **WASM-App (Browser) + Desktop**
> Docs-only. **Design-System-konform, keine neuen Farben.** Adressat: Developer5 (mechanische Verdrahtung).
> Scope: **nur das Agenten-Nachrichtenfenster** (`AgentTranscript`, `AgentWindow.kt:537`). Comm-Timeline vorerst außen vor.

---

## 0. Der Anker: der Balken ist neu im Haus → **wiederverwendbar**, nicht ein Einzelfall

Es gibt heute **keinen** `VerticalScrollbar` im Code. Ich definiere den ersten — also als **ein** teilbarer
Baustein, damit die Comm-Timeline ihn später **ohne Divergenz** übernimmt (eine Zeile, kein zweiter Stil):

```kotlin
// ui/ThinVerticalScrollbar.kt — der eine Scrollbalken-Stil des Hauses
@Composable fun ThinVerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier)
```

Das Transcript ist **reines Compose** (`LazyColumn` + `rememberLazyListState`, `:542`). Der Z-Order-Vorbehalt
aus `04 §5` (SwingPanel/DOM-Overlay über dem Compose-Layer) gilt hier **nicht** — der war für das
**Terminal**-Overlay. Ein Compose-Overlay-Scrollbalken über dem `LazyColumn` ist unbedenklich.

**Targets:** `app:shared` baut **iOS + jvm + wasmJs** (kein Android). `VerticalScrollbar` /
`rememberScrollbarAdapter` sind skiko-gestützt und auf **allen dreien** verfügbar — kein Target-Seam nötig,
`commonMain` genügt.

---

## 1. Stil — alles aus dem Design-System (`ScrollbarStyle`, verifiziert Compose 1.10.3)

`ScrollbarStyle` trägt genau: `thickness`, `shape`, `minimalHeight`, `hoverDurationMillis`, `unhoverColor`,
`hoverColor` (aus dem gebauten `foundation`-Artefakt gelesen). Belegung:

| Feld | Wert | Begründung |
|---|---|---|
| `thickness` | **8 dp** | greifbar, unaufdringlich; passt **in** das bestehende `contentPadding = 12 dp` (`:551`) → **kein Textüberlapp**, kein zusätzlicher Gutter nötig |
| `shape` | `RoundedCornerShape(4.dp)` | Pille (= thickness/2, runde Enden); konsistent mit dem 8-dp-Rundungsvokabular der Fenster |
| `minimalHeight` | **24 dp** | ein sehr langer Verlauf behält einen **greifbaren** Thumb (≥ WCAG-2.5.8-Zielhöhe für den Ziehgriff) |
| `unhoverColor` (Ruhe) | **`outline`** | siehe §2 |
| `hoverColor` (Hover/Drag) | **`onSurfaceVariant`** | siehe §2 |
| `hoverDurationMillis` | Default (~300 ms) | eine **Farb**-Blende, keine Bewegung (§4) |

**Kein Track** (trackless): nur der Thumb, kein Hintergrundband — leiser, moderner Overlay-Stil.

---

## 2. Farben — zwei Design-System-Rollen, beide gerechnet, **keine neue**

Ein Scrollbalken-Thumb ist ein **graphisches Objekt** (WCAG **1.4.11** → **3:1** gegen den Hintergrund, nicht
4,5:1 — er trägt keinen Text).

| Zustand | Rolle | Kontrast gegen `surface` (hell / dunkel) | ≥ 3:1 |
|---|---|---|---|
| **Ruhe** (`unhoverColor`) | **`outline`** | **3,55 : 1** / **3,63 : 1** | ✅ |
| **Hover / Drag** (`hoverColor`) | **`onSurfaceVariant`** | **8,69 : 1** / **9,80 : 1** | ✅ (deutlich kräftiger) |

- **Kein Alpha auf `outline`.** `outline` liegt mit 3,55 nahe am 3:1-Boden; jede Transparenz drückte es
  **darunter**. Deshalb voll deckend. (Das ist die Lehre aus CYP-337 — `outline` ist als graphische Grenze
  richtig, aber ohne Reserve nach unten.)
- **Hover verstärkt** (`outline` → `onSurfaceVariant`), statt eine neue Farbe einzuführen. Intuitiv: der Thumb
  wird beim Anfassen dunkler/präsenter.

---

## 3. Sichtbarkeit — **präsent, wenn scrollbar; verschwunden, wenn nicht**

### 3.1 Immer sichtbar (leise), nicht rein Hover-autohide

**Entscheidung: der Thumb ist präsent, sobald der Inhalt scrollbar ist** — leise in Ruhe (`outline`), kräftiger
bei Hover. **Nicht** vollständig hover-autohide.

**Begründung (Disclosure, nicht Geschmack):** Das Transcript **auto-scrollt** ans Ende (`:546`
`animateScrollToItem`). Der ruhende Thumb sagt dem Operator, **wo** er ist — vor allem, **ob er im
Rückblick-Verlauf steht** (nicht am Live-Ende) oder unten gepinnt. Diesen Positions-Hinweis wegzublenden, nähme
einer Live-Ansicht eine echte Information. `outline` in Ruhe ist leise genug, um kein lautes Chrome zu sein.

> **Verworfene Alternative:** reines Hover-Autohide (Thumb nur sichtbar, wenn die Maus in der Nähe ist). Preis:
> auf einen Blick ist nicht erkennbar, dass es Verlauf oberhalb gibt / dass man nicht am Ende steht. Für eine
> **auto-scrollende** Fläche ist das der falsche Handel. (Touch-only-Geräte haben ohnehin keinen Hover — dort
> wäre Autohide = fast nie sichtbar.)

### 3.2 Nicht scrollbar → **kein** Thumb

Passt der Inhalt vollständig, wird **nichts** gerendert — kein voller inerter Balken, kein toter Affordance.
Prädikat direkt am State:

```kotlin
if (listState.canScrollForward || listState.canScrollBackward) { ThinVerticalScrollbar(listState, …) }
```

> Ehrlich: ein Scrollbalken erscheint **genau dann**, wenn es etwas zu scrollen gibt.

---

## 4. Platzierung, RTL, Bewegung

- **Overlay am nachlaufenden Rand**, innerhalb der 12-dp-Polsterung → überdeckt keinen Text. Im `Box` des
  Inhaltsrechtecks per `Alignment.CenterEnd`.
- **RTL:** „Ende" spiegelt automatisch auf die **linke** visuelle Kante — `Alignment.CenterEnd` folgt der
  `LayoutDirection`, kein Sonderfall. (Kurz gegenprüfen: Thumb links bei RTL.)
- **Bewegung:** die einzige Animation ist die **Hover-Farbblende** (`hoverDurationMillis`) — ein
  Farbübergang, **keine** Positions-/Slide-Bewegung. Damit ist der „reduce motion"-Aspekt unkritisch; nichts
  bewegt sich, es wechselt nur eine Farbe.

---

## 5. Barrierefreiheit

- **Der Balken ist eine Ergänzung, kein Ersatz.** Tastatur-Scrollen (fokussierte Liste, Bild-auf/-ab) und der
  Inhalt selbst bleiben der primäre Weg. Der Thumb ist **nicht fokussierbar**, **kein** Keyboard-Trap.
- **Screenreader:** ein Scrollbalken-Thumb ist Dekoration über einer bereits zugänglichen Liste — **keine**
  eigene Ansage nötig; die Zeilen tragen ihre Semantik. Kein `contentDescription` am Thumb (sonst doppelt
  angesagt).
- **`nicht scrollbar → weg`** (§3.2) ist selbst ein a11y-Gewinn: kein Fokus-/Vorlese-Rauschen für einen Balken,
  der nichts tut.

---

## 6. Abnahme

1. **Scrollbar → Thumb da:** langer Verlauf ⇒ Thumb sichtbar, Ruhefarbe `outline`. **Mutation:** Thumb bei
   nicht-scrollbarem Inhalt rendern ⇒ Thumb erscheint bei 2 Zeilen ⇒ **rot** (§3.2-Gate entfernt).
2. **Nicht scrollbar → kein Thumb:** wenige Zeilen ⇒ `scrollbar`-Tag **abwesend**.
3. **Kontrast (Paar-Test, wie CYP-359):** `unhoverColor` gegen `surface` ≥ 3:1 in **beiden** Themes.
   **Mutation:** `unhoverColor = outlineVariant` (1,41) ⇒ **rot**.
4. **Keine neue Farbe:** `unhover`/`hover` ∈ { `outline`, `onSurfaceVariant` } (Design-System-Rollen).
   **Mutation:** eine Roh-`Color(0x…)` ⇒ **rot** (Quell-Guard, Stil CYP-303).
5. **Maße:** `thickness == 8.dp`, `minimalHeight == 24.dp`, Pillenform.
6. **RTL:** bei `LayoutDirection.Rtl` liegt der Thumb an der linken visuellen Kante.
7. **Nur Agenten-Transcript:** die Comm-Timeline zeigt **keinen** Balken (Scope). Der wiederverwendbare
   `ThinVerticalScrollbar` ist vorhanden, aber dort **nicht** verdrahtet.

**Test 3 (Kontrast-Paar) ist der Kern** — er hält den Thumb über der graphischen 3:1-Schwelle in **beiden**
Themes und verbietet den bequemen Griff zu `outlineVariant`.

---

## 7. Nebenbeobachtung (nicht CYP-392 — eigenes Ticket wert)

Das Transcript pinnt bei **jedem** Event ans Ende (`:546`, `animateScrollToItem(lastIndex)`); der Code sagt es
selbst: *„A more refined version would release the pin while the user scrolls up; deferred."* **Der neue
Scrollbalken macht diesen Ruck sichtbar:** scrollt der Operator hoch, um mitzulesen, reißt ihn das nächste
Event zurück ans Ende, und der Thumb springt mit. Das ist **kein** Fehler von CYP-392 — im Gegenteil, der
Balken **legt** das Verhalten offen. Aber „Pin lösen, während der Nutzer hochgescrollt hat" ist ein eigener,
lohnender Slice (die bestehende Deferral-Notiz). **Ich flagge es, ziehe es nicht hinein.**

---

## 8. Self-Validation

- **Wiederverwendbar, kein Einzelfall** (§0): ein `ThinVerticalScrollbar`, damit Comm später ohne Divergenz folgt.
- **Keine neue Farbe** (§2): `outline` (Ruhe) + `onSurfaceVariant` (Hover), beide Design-System-Rollen, beide
  **gerechnet** (3,55/3,63 und 8,69/9,80), **kein Alpha** auf dem randnahen `outline`.
- **`ScrollbarStyle`-Felder am Artefakt verifiziert** (Compose 1.10.3), nicht aus dem Gedächtnis.
- **Sichtbarkeit als Disclosure-Entscheidung begründet** (§3.1): präsent-wenn-scrollbar schlägt Hover-Autohide,
  weil eine auto-scrollende Ansicht den „ich bin im Rückblick"-Hinweis braucht. Alternative + Preis benannt.
- **Nicht-scrollbar → weg** (§3.2): kein toter Balken, ehrliche Affordance.
- **Z-Order-Vorbehalt korrekt abgegrenzt** (§0): gilt fürs Terminal-Overlay, nicht fürs Compose-Transcript.
- **Eine Nebenbeobachtung geflaggt, nicht hineingezogen** (§7): Auto-Scroll-Pin.
- **Docs-only.**
