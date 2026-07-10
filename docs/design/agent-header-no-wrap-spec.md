# Agent-Header darf nicht umbrechen — UX/UI-Spec (CYP-350)

> Owner: UIUX-Designer · Ticket **CYP-350** · Stand 2026-07-10 · Basis **develop `5c79a79`** · Scope **WASM-App**
> Docs-only. Adressat: Implementierung + Test.
> Ursache-Ticket zu **CYP-338** (Mindesthöhe): der Header wächst bei schmaler Breite von 56 dp auf 164 dp und
> drückt die Eingabezeile aus dem Fenster.
> Reuse-Anker: `MessageComposer` (CYP-26 §2.2) — das bereits ausgelieferte Label-→-Glyph-Muster.

---

## 0. Der Defekt ist nicht „die Labels sind zu breit"

> **Die Höhe des Headers ist eine Funktion seiner Breite.**

Gemessen (Backend2, echte Komposition):

| Fensterbreite | Header-Höhe |
|---|---|
| **320 dp** *(= `TILED_CONTENT_WINDOW_MIN_WIDTH`, die Mindestbreite derselben Klasse)* | **164 dp** |
| 420 dp | 164 dp |
| 480 dp | 104 dp |
| **520 dp** | **56 dp** |

Der Grund steht im Code: **im gesamten Header trägt kein einziges `Text` ein `maxLines`.**
`AgentHeader` (`AgentWindow.kt:181–219`) ist eine `Row` aus `StatusIndicator`, `ReconnectingChip`,
`ConnectorProviderChip`, `ConnectorCapabilityBadge`, `Spacer(weight(1f))` und **drei** `TextButton`s. Eine `Row`
misst ihre ungewichteten Kinder der Reihe nach; die Knöpfe stehen **hinten** und bekommen, was übrig ist.
Reicht es nicht, bricht ihr Label um, der Knopf wird höher, und die `Row` wächst mit.

**Jede Korrektur, die nur den Breakpoint verschiebt, lässt diese Funktion stehen.** Wird morgen ein Chip
ergänzt oder eine Übersetzung länger („Neu starten" statt „Neustart"), kehrt der Bug zurück — an einer
Fensterbreite, die niemand getestet hat.

---

## 1. Der Fix ist zweiteilig, und nur der erste Teil ist die Garantie

### 1.1 Struktur-Invariante (die eigentliche Reparatur)

> **Jedes `Text` im Header trägt `maxLines = 1` und `softWrap = false`.**

Damit ist die Header-Höhe **breitenunabhängig**: sie ist die Höhe ihres höchsten Kindes plus die
`Row`-Polsterung `2 × 4 dp`.

**Das höchste Kind ist nicht der Knopf.** Es ist der **Fidelity-Badge**:

```kotlin
// connector/ConnectorCapabilityViews.kt:219
.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)   // WCAG 2.5.8 Zielgröße
```

`48 dp` schlägt die `ButtonDefaults.MinHeight` von `40 dp`. Also:

```
Header-Höhe = 48 + 2×4 = 56 dp   ·   für JEDE Breite ≥ 320 dp
```

Das deckt sich **exakt** mit Backend2s Messung des ungebrochenen Headers bei 520 dp (§0: `520 → 56`) — und es
erklärt sie. **`56 dp`, nicht `48 dp`:** eine frühere Fassung dieses Dokuments rechnete `40 + 8` und übersah
den Badge. Der Badge darf **nicht** verkleinert werden, um die 48 zu erreichen — er ist eine Zielgröße, kein
Schmuck. CYP-350 macht die Höhe **konstant**, es macht sie nicht **kleiner**.

Das ist die Zusage, die der Regressionstest festnagelt. Selbst wenn jede andere Entscheidung dieses Dokuments
falsch wäre, **kann die Eingabezeile nie wieder aus dem Fenster gedrückt werden.**

### 1.2 Progressive Offenlegung (damit nichts zu Unsinn degradiert)

`softWrap = false` allein würde Labels abschneiden. Deshalb: **unterhalb einer gemessenen Breite tragen die
drei Lifecycle-Knöpfe statt ihres Labels einen Glyphen** — mit unverändertem, gesprochenem Namen.

```kotlin
// AgentWindow.kt — BoxWithConstraints um die Header-Row, exakt wie MessageComposer es tut
val compact = maxWidth < AGENT_HEADER_LABELS_MIN_WIDTH      // 520.dp, gemessen (§0)
```

| Aktion | Label (≥ 520 dp) | Glyph (< 520 dp) | Gesprochener Name (beide) |
|---|---|---|---|
| Start | „Start" | **`▶`** | `agent_ctl_start` |
| Stopp | „Stopp" | **`■`** | `agent_ctl_stop` |
| Neustart | „Neustart" | **`↺`** | `agent_ctl_restart` |

**Null neue Keys.** Der `contentDescription` ist derselbe String, den der Knopf im Breitmodus anzeigt — das ist
der Kern des Composer-Musters und der Grund, warum es hier passt.

> **Glyphen-Kollision geprüft** (`commonMain`, alle `.kt`): `▶`, `■`, `↺` kommen **nirgends** vor.
> **`⟳` wurde bewusst NICHT gewählt**, obwohl es der naheliegende Neustart-Glyph wäre: es ist im **selben
> Fenster** bereits belegt — `ToolCallRow` malt damit `ToolStatus.RUNNING` (`AgentWindow.kt:359`). Derselbe
> Glyph für „Werkzeug läuft" und „Agent neu starten" wäre Bedeutungs-Drift auf zwei Zeilen Abstand.
> `⏻` scheidet ebenso aus: im Event-Log bezeichnet es ein **Lifecycle-Ereignis** (`EventVisuals.kt:127`),
> nicht eine Aktion.

---

## 2. Warum Glyphen und **kein** Overflow-Menü

Das Menü ist die andere naheliegende Antwort. Sie ist hier die schlechtere, aus drei Gründen:

1. **Diese drei Knöpfe sind die einzigen Genesungs-Affordanzen des Fensters.** Wie der Beobachtungs-Audit
   (§2.2) zeigt, kann der Start-Knopf bereits heute durch einen veralteten Zustand *gesperrt* sein. Die
   Aktionen zusätzlich einen Klick tief zu vergraben, tauscht einen Layout-Fehler gegen einen
   Auffindbarkeits-Fehler.
2. **Ein zweites `⋮` wäre mehrdeutig.** Die Titelleiste **direkt darüber** trägt bereits ein `⋮`
   (Agent-Einstellungen, CYP-211). Zwei `⋮` im Abstand von ~20 dp, mit verschiedenen Inhalten, sind eine
   Verwechslung, die man nicht mehr wegdokumentiert.
3. **Das Haus hat das Muster schon.** `MessageComposer` (CYP-26 §2.2) degradiert „Senden" → `➤` an einer
   Breitenschwelle und behält den a11y-Namen. Ein Menü wäre ein **dritter Mechanismus** neben Label und Glyph —
   dieselbe Ablehnung wie beim `outline`-Guard: kein zweiter Weg, wenn der erste trägt.

**Der Preis, offen benannt:** Ein Glyph ist schlechter auffindbar als ein Wort, und weil die Mindestbreite der
Klasse 320 dp beträgt, ist der **Glyph-Modus für gekachelte Fenster der Normalfall**, nicht die Ausnahme. Das
ist vertretbar, weil (a) der Screenreader den vollen Namen hört, (b) die drei Glyphen konventionell sind
(Medienspieler-Semantik), und (c) der Nutzer das Fenster jederzeit über 520 dp ziehen kann und dann die Wörter
zurückbekommt. Wer die Wörter dauerhaft will, hat mit CYP-338 ein Fenster, das breit genug dafür ist.

---

## 3. Was **nicht** ausgeblendet werden darf

Der bequeme Weg, Breite zu gewinnen, wäre, bei schmalem Fenster die Chips zu verstecken. **Das ist verboten**,
und der Grund ist derselbe wie bei der Zeitspalte (CYP-335 §5):

> **Abwesenheit ist in dieser Codebasis bereits belegt.** Der Provider-Chip fehlt, wenn der Provider *nicht
> gemeldet* ist. Der Fidelity-Badge fehlt, wenn *nichts degradiert* ist. Würde ein Chip auch wegen
> **Fensterbreite** fehlen, wäre Abwesenheit doppelt belegt — und der Operator könnte „schmal" nicht mehr von
> „nicht gemeldet" unterscheiden.

Daher die **Degradations-Leiter**, in dieser Reihenfolge:

| Stufe | Was nachgibt | Was garantiert bleibt |
|---|---|---|
| 1 | Die drei Knopf-**Labels** → Glyphen (< 520 dp) | die drei Aktionen, benannt für den Screenreader |
| 2 | Restliche Texte kürzen mit `overflow = Ellipsis` | jeder Chip bleibt **anwesend**, wenn sein Zustand ihn verlangt |
| 3 | *(nichts)* | — |

**Das Status-Label verschwindet nie.** Der farbige Punkt allein wäre ein Verstoß gegen WCAG 1.4.1 — der Code
sagt es selbst: *„Colour is never the sole signal: the text label carries the meaning"* (`AgentWindow.kt:274`).
Muss es bei extremer Enge kürzen, bleibt der volle Text in der bestehenden Zeilen-Beschreibung
`a11y_agent_status`. **`ReconnectingChip` bleibt ebenfalls immer sichtbar**, wenn er anliegt: er ist ein
Ausnahme-Marker, und seine Abwesenheit bedeutet „Socket ist live".

---

## 4. Der Breakpoint ist gemessen — und darf trotzdem falsch sein

`AGENT_HEADER_LABELS_MIN_WIDTH = 520.dp`, aus Backend2s Messreihe (§0: bei 480 dp noch zwei Zeilen, bei 520 dp
eine).

Der Wert hängt an Schriftmetrik und Übersetzung — eine längere Lokalisierung verschiebt ihn. **Das ist
verkraftbar, weil die Invariante aus §1.1 nicht an ihm hängt:** ein zu niedriger Breakpoint kostet eine
Ellipse in einem Knopf-Label, **keine** wachsende Zeile und **keine** verschwundene Eingabezeile. Die
Struktur trägt, der Breakpoint poliert.

> Deshalb ist die Reihenfolge im Test wichtig: **zuerst** die Höhen-Invariante, **dann** die Lesbarkeit.

---

## 5. Wirkung auf CYP-338

| | vor CYP-350 | nach CYP-350 |
|---|---|---|
| Header (320 dp) | 164 dp | **56 dp** |
| Header (520 dp) | 56 dp | 56 dp |
| Festes Chrome (Titelleiste **64** + Header + Composer **73**) | **301 dp** @ 320 dp | **193 dp**, breitenunabhängig |
| Mindesthöhe (+ 90 dp Transkript, CYP-338 §2.2) | 391 dp | **283 dp** |

> **Achtung, hier steckt schon wieder derselbe Fehler.** Der KDoc von `CONTENT_WINDOW_MIN_HEIGHT`
> (`de11582`) projiziert `56 + 56 + 72 = 184` — und importiert dabei stillschweigend die **arithmetischen**
> Werte für Titelleiste (56) und Composer (72) zurück, die *derselbe KDoc einen Satz zuvor* durch die
> **gemessenen** 64 und 73 ersetzt hat. Konsistent, nur mit gemessenen Summanden:
> **`64 + 56 + 73 = 193`**, Mindesthöhe **`283`** — nicht `184 / 274`.
>
> Gemessenes und Gerechnetes dürfen nicht in derselben Summe stehen. Es ist dieselbe Fehlerklasse wie in §7,
> nur eine Datei weiter.

---

## 6. Was der Test prüfen muss

Der PO verlangt Messung bei **mehreren Breiten** — zu Recht: *an einer einzigen ist der Defekt unsichtbar*
(bei 520 dp wäre das heutige Verhalten grün).

1. **Höhen-Invariante:** Header-Höhe bei **320 · 400 · 480 · 519 · 520 · 560 · 640 dp** — alle **gleich**, und
   gleich der ungebrochenen Höhe (**56 dp** = Badge-Zielgröße 48 + `2 × 4` Polsterung). Nicht „≤ 56", sondern
   **gleich**: ein zu *kleiner* Wert hieße, der Fidelity-Badge oder ein Knopf ist unter die
   WCAG-2.5.8-Zielgröße gefallen.
2. **Mutationsprobe A:** `maxLines = 1` an **einem** Header-`Text` entfernen ⇒ Test bei 320 dp **rot**. Wird er
   das nicht, misst er die falsche Breite.
3. **Mutationsprobe B:** Breakpoint auf `0.dp` setzen (nie kompakt) ⇒ Test bei 320 dp **rot**. Belegt, dass die
   Messreihe den Glyph-Modus wirklich fordert.
4. **Erreichbarkeit & Benennung** (PO-Auflage): bei **320 dp** trägt jeder der drei Knöpfe seinen
   `contentDescription` (`agent_ctl_start` / `_stop` / `_restart`) und ist über seinen unveränderten
   `testTag` adressierbar (`AgentViewTags.startBtn/stopBtn/restartBtn`) — **0 neue Tags, 0 neue Keys**.
5. **Zielgröße:** jeder Glyph-Knopf ≥ 24 dp (WCAG 2.5.8) — durch `ButtonDefaults.MinHeight = 40 dp` erfüllt,
   aber zu prüfen, weil ein `Modifier.size` es kippen könnte.
6. **Kein Chip verschwindet wegen Breite** (§3): bei 320 dp sind Provider-Chip und Fidelity-Badge genau dann
   vorhanden, wenn sie es bei 640 dp sind.

---

## 7. Warum meine CYP-338-Zahl falsch war — und was daraus folgt

Ich habe die Header-Höhe mit **48 dp** angesetzt: `ButtonDefaults.MinHeight (40) + 2 × 4` Polsterung. Diese
Zahl ist aus dem gebauten Artefakt gelesen und **trotzdem falsch**, weil sie eine stillschweigende Bedingung
trägt: *„sofern das Label nicht umbricht"* — was erst ab ≈ 520 dp Breite gilt. Diese Breite steht in meinem
Dokument **nirgends**.

Für die **Transkript-Zeile** habe ich den Umbruch sorgfältig durchgerechnet (CYP-338 §2.2: 320 dp Breite →
244 dp Textbreite → 2–4 Zeilen). Für den **Header**, eine Komponente weiter oben, habe ich ihn übersehen.

Es ist exakt die Fehlerklasse, die ich zwei Stunden vorher beschrieben habe:

> **Ein Wert, der wie eine Messung aussieht und eine Ableitung unter einer unausgesprochenen Annahme ist.**

`ButtonDefaults.MinHeight` ist eine Messung. `48 dp` war eine **Ableitung**, deren Bezugsrahmen — die
Mindestbreite, ab der ein Label einzeilig bleibt — beim Schichtwechsel verloren ging. Genau wie `formatTs` die
Zone verlor und `eventTs − now` den Bezugspunkt.

**Und sie war doppelt falsch:** selbst ohne Umbruch sind es nicht 48, sondern **56 dp** — weil der
Fidelity-Badge mit seiner 48-dp-Zielgröße höher ist als der Knopf (§1.1). Ich hatte das höchste Kind der Row
nicht gesucht, sondern geraten.

**Konsequenz für dieses Dokument, und die Regel, die ich mir daraus gebe:**
Eine Komponentenhöhe ist erst dann eine Zahl, wenn dabei steht, **unter welcher Breite sie gilt**. Steht es
nicht dabei, ist es keine Höhe, sondern eine Hoffnung. CYP-350 macht die Höhe unbedingt — und beseitigt damit
nicht nur den Bug, sondern die Voraussetzung, unter der ich ihn übersehen konnte.

---

## 8. Nebenbefund: `MessageComposer` ist das richtige Muster mit einem Lokalisierungs-Loch

Ich übernehme sein **Muster**, nicht seinen **Fehler**. `MessageComposer` (`AgentWindow.kt:508–535`) hält drei
**hartkodierte deutsche Literale** in `commonMain`:

```kotlin
placeholder = { Text("Nachricht an den Agenten…", …) }               // :522
.then(if (compact) Modifier.semantics { contentDescription = "Senden" } else Modifier)   // :531
Text(if (compact) "➤" else "Senden")                                  // :533
```

Der englische Build zeigt hier **Deutsch**. Zwei Zeilen weiter existiert bereits der Key
`comm_composer_send` = „Senden" (`values/strings.xml:60`) — er wird nur nicht benutzt.

**Nicht Teil von CYP-350** (ich fasse nichts an), aber es gehört gemeldet: **Lokalisierungs-Lücke + verpasster
Reuse**, eigenes kleines Ticket. Die Glyph-Knöpfe dieser Spec ziehen ihre Namen ausdrücklich aus den
**vorhandenen** `agent_ctl_*`-Resources — damit CYP-350 das Loch nicht vergrößert.

---

## 9. Self-Validation

- **Die Zusage ist eine Invariante, keine Zahl:** Header-Höhe konstant (= 56 dp, gesetzt vom Fidelity-Badge)
  bei **jeder** Breite ≥ 320 dp. Der Breakpoint darf falsch sein, ohne die Zusage zu brechen (§4).
- **0 neue Keys, 0 neue Tags, 0 neue Areas.** Die a11y-Namen sind die bestehenden `agent_ctl_*`-Strings.
- **Glyphen kollisionsfrei verifiziert** (`▶ ■ ↺` = 0 Treffer in `commonMain`), und die zwei naheliegenden
  Fehlgriffe (`⟳` = Tool-RUNNING im selben Fenster, `⏻` = Lifecycle-*Ereignis* im Event-Log) sind namentlich
  ausgeschlossen.
- **Abwesenheit bleibt eindeutig:** kein Chip wird wegen Breite versteckt (§3) — dieselbe Regel wie
  CYP-335 §5.
- **WCAG:** Farbe nie alleiniger Träger (Status-Label bleibt); Zielgröße ≥ 24 dp; jeder Glyph-Knopf benannt.
- **Beide PO-Auflagen erfüllt:** Aktionen erreichbar **und** benannt (§1.2, §6.4); Test misst **mehrere**
  Breiten (§6.1) und ist gegen die bequeme Tautologie abgesichert (§6.2/§6.3).
- **Mein eigener Messfehler ist benannt, nicht weggeschrieben** (§7).
- **Docs-only.** Kein Code geändert.
