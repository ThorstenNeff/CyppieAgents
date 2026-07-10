# Agent-Header darf nicht umbrechen — UX/UI-Spec (CYP-350)

> Owner: UIUX-Designer · Ticket **CYP-350** · Stand 2026-07-10 · Basis **develop `b3a5870`** · Scope **WASM-App**
>
> **Basiswechsel gegenüber der ersten Fassung (`5c79a79`).** CYP-333 hat eine unbedingte vierte Chrome-Zeile
> eingezogen (`ModeToggleRow`). Alle Chrome-Summen dieses Dokuments — und die bereits gemergte Konstante
> `CONTENT_WINDOW_MIN_HEIGHT = 301` — beziehen sich auf die Komposition **davor**. Siehe **§5.1**: das ist kein
> Schönheitsfehler, sondern der Boden, auf dem CYP-338 steht.
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
           || connection != ConnectionStatus.LIVE           // Reconnect-Chip belegt die Breite (§4.1)
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

### 4.1 Ein Loch in meiner ersten Fassung: der Breakpoint war **inhaltsblind**

Backend2 hat bei **gesundem Socket** gemessen — also **ohne** den `ReconnectingChip`. Der ist aber Teil
derselben `Row`, und im Deutschen ist er lang:

| Locale | Text | Länge | ≈ Breite (`labelSmall`) |
|---|---|---|---|
| DE | „Verbindung wird wiederhergestellt…" | 34 Zeichen | **≈ 187 dp** |
| EN | „Reconnecting…" | 13 Zeichen | ≈ 72 dp |

Liegt der Chip an, verschöbe sich die Schwelle auf ≈ **715 dp** — die Labels passten also **selbst in einem
640-dp-Fenster nicht mehr**. Die Höhen-Invariante (§1.1) hielte, aber die Labels würden **beschnitten**. Ein
fester, inhaltsblinder Breakpoint ist damit falsch, sobald der Header seinen Inhalt ändert.

**Entscheidung:** Der Reconnect-Chip **erzwingt** den Glyph-Modus, unabhängig von der Breite.

**Begründung, nicht Bequemlichkeit:** Der Chip erscheint **nur**, wenn der Socket nicht `LIVE` ist — ein
**Ausnahmezustand**. In ihm gehört die Aufmerksamkeit auf den Chip, dessen **Text** die Bedeutung trägt
(§3: er darf nie verschwinden). Die drei Aktionen verlieren dabei nur ihr Wort, nicht ihre Bedeutung: der
Glyph bleibt, der gesprochene Name bleibt, der `testTag` bleibt. Der Wechsel ist eine **lesbare
Zustandsänderung** und kehrt sich um, sobald die Verbindung steht — kein Flackern ohne Ursache.

> **Korrektur an mir selbst.** Die erste Fassung dieses Absatzes rechnete `520 + 195 ≈ 715 dp` und nannte das
> „die Arithmetik stimmt mit der Regel überein". Das ist **meine eigene Regel verletzt**: `520` ist gemessen,
> `195` ist aus der Zeichenzahl geschätzt — **gemessen und gerechnet in derselben Summe**. Die Zahl fällt
> ersatzlos weg. Sie wurde ohnehin nicht gebraucht: die Regel steht auf ihrer Begründung (Ausnahmezustand,
> der Chip-Text trägt die Bedeutung), nicht auf einem Schwellwert. Was ich behaupten kann, ist die **Richtung**
> — der Chip verbraucht Breite in derselben `Row`, also verschiebt er die Schwelle nach oben. Um **wie viel**,
> weiß ich nicht, und deshalb schreibe ich keine Zahl hin.

### 4.2 Warum ein falscher Breakpoint trotzdem nicht gefährlich ist

`AGENT_HEADER_LABELS_MIN_WIDTH = 520.dp`, aus Backend2s Messreihe (§0: bei 480 dp noch zwei Zeilen, bei 520 dp
eine).

Der Wert hängt an Schriftmetrik und Übersetzung — eine längere Lokalisierung verschiebt ihn. **Das ist
verkraftbar, weil die Invariante aus §1.1 nicht an ihm hängt:** ein zu niedriger Breakpoint kostet eine
Ellipse in einem Knopf-Label, **keine** wachsende Zeile und **keine** verschwundene Eingabezeile. Die
Struktur trägt, der Breakpoint poliert.

> Deshalb ist die Reihenfolge im Test wichtig: **zuerst** die Höhen-Invariante, **dann** die Lesbarkeit.

---

## 5. Wirkung auf CYP-338 — und ein Befund, der CYP-338 **heute schon** bricht

### 5.1 Die 301 misst eine Komposition, die es nicht mehr gibt

`CONTENT_WINDOW_MIN_HEIGHT = 301f` trägt im KDoc den Satz *„measured on the rendered composition … title bar
64 + agent header 164 + composer 73"*. **Diese Komposition existiert seit `5cdf89b` nicht mehr.** CYP-333 hat
zwischen Header und Inhaltsrechteck eine **unbedingte** vierte Chrome-Zeile eingezogen:

```kotlin
// AgentWindow.kt (develop) — kein `if`, kein Flag: sie ist immer da
AgentHeader(…)
ModeToggleRow(…)          // ← CYP-333, neu
Box(Modifier.weight(1f))  // Inhaltsrechteck
if (contentMode == ORCHESTRATION) MessageComposer(…)
```

Die Chronologie zeigt, wie es unbemerkt bleiben konnte — **niemand hat etwas übersehen, die Basis ist unter der
Messung weggewandert**:

| | |
|---|---|
| `2ff05a9` 09:28 | CYP-333 fügt `ModeToggleRow` hinzu |
| `5cdf89b` | CYP-333 **nach `develop` gemergt** |
| `de11582` 09:55 | CYP-338 misst `64 + 164 + 73 = 301` — auf einer Basis, die `ModeToggleRow` **nicht enthielt** (`git show de11582:…AgentWindow.kt \| grep -c ModeToggleRow` → **0**) |
| `b436412` | CYP-338 nach `develop` gemergt — die Zahl trifft auf die Komposition, die sie nie gesehen hat |

Zwei Zweige, beide grün, beide korrekt für sich. Der Fehler entsteht **im Merge**, wo keiner von beiden hinsah.

**Die Konsequenz ist nicht kosmetisch.** `CONTENT_WINDOW_MIN_HEIGHT` ist der harte Boden — *„No path —
placement, resize, clamp, tile, fallback — may ever produce less."* Ist das feste Chrome heute **höher** als
301, dann wird bei exakt 301 dp genau das wieder herausgedrückt, wofür CYP-338 existiert: **die Eingabezeile.**

### 5.2 Warum kein Test rot wurde

`WindowSyncTest` ist reine Geometrie — er sieht Komposition nicht. `AgentWindowMinHeightTest` rendert die echte
Komposition, aber bei **`TILED_CONTENT_WINDOW_MIN_HEIGHT` = 391**, und er prüft `assertIsDisplayed` auf den
Composer. Bei 391 bleibt nach dem gewachsenen Chrome noch Platz; das Transkript (`weight(1f)`) schrumpft
lautlos, der Composer wird gerendert, der Test ist grün.

> **Der Boden, der bricht, ist der, den kein gerenderter Test je anfasst.** Die 301 wird nirgends gerendert
> geprüft — nur die 391. Und die 391 überlebt, weil das gewichtete Kind nachgibt, nicht das ungewichtete.
>
> Es ist mein eigener Satz, in der Prüfung statt in der Anzeige: *ein Test, dessen Erwartungswert die
> plausiblen falschen Implementierungen nicht trennt, beweist nichts.* Er kann nicht rot werden für „das
> Chrome ist gewachsen" — er kann nur rot werden für „der Composer fehlt ganz".

**Der fehlende Test ist damit benannt:** die Komposition bei **`CONTENT_WINDOW_MIN_HEIGHT`** rendern (nicht bei
`TILED_…`) und den Composer fordern. Und zusätzlich: bei `TILED_…` **die versprochenen 90 dp Transkript**
fordern, nicht nur seine Anwesenheit — sonst deckt die Nachgiebigkeit des gewichteten Kindes jedes künftige
Chrome-Wachstum zu.

### 5.3 Was ich beziffern darf — und was nicht

Sei `T` die Höhe der `ModeToggleRow`. Dann ist, mit **ausschließlich gemessenen** Summanden:

| | vor CYP-350 | nach CYP-350 |
|---|---|---|
| Header (320 dp) | 164 dp *(gemessen)* | **56 dp** *(gemessen bei 520 dp, §0)* |
| Header (520 dp) | 56 dp *(gemessen)* | 56 dp |
| Festes Chrome (64 + Header + **`T`** + 73) | **`301 + T`** @ 320 dp | **`193 + T`**, breitenunabhängig |
| Mindesthöhe (+ 90 dp Transkript, CYP-338 §2.2) | `391 + T` | **`283 + T`** |

**`T` ist keine Zahl, die ich liefern kann.** Ich kann sie *rechnen* — `OutlinedSegmentedButtonTokens.
ContainerHeight` steht mit **40 dp** im gebauten `material3`-Artefakt (per `javap` gelesen, nicht erinnert),
dazu die `Column`-Polsterung `2 × 2 dp` ⇒ **≈ 44 dp**. Aber genau das ist eine **Ableitung unter stiller
Annahme**, und sie in dieselbe Summe zu schreiben wie die gemessenen 64/73 wäre der Fehler aus §7, ein drittes
Mal. **`T` muss an der echten Komposition gemessen werden**, so wie 64, 164 und 73 gemessen wurden. Bis dahin
steht in den Konstanten `T`, nicht `44`.

> Wenn `T ≈ 44` stimmt, ist der heutige Boden **345**, nicht 301 — **44 dp zu niedrig**.

Der Composer zählt nur in der **Orchestrierungs**-Ansicht mit (`AgentWindow.kt:185`); in der Shell-Ansicht
fehlt er. Der Boden muss den **höheren** der beiden Fälle tragen, also den mit Composer — die Tabelle ist der
ungünstige Fall, richtig herum.

### 5.4 `T` ist nicht einmal konstant — dieselbe Krankheit, eine Zeile tiefer

Die `ModeToggleRow` trägt unter den Segmenten einen **bedingten** Hinweistext, und **er hat kein `maxLines`**:

```kotlin
!canControl              -> Text(stringResource(Res.string.workspace_operator_only), …)    // Nicht-Operator: IMMER da
terminalGatedNote && …   -> Text(stringResource(Res.string.terminal_gated_pending), …)
```

Damit ist `T` eine Funktion von **Rolle** und **Breite** — exakt die Struktur, die CYP-350 im Header beseitigt.
Für einen Nicht-Operator liegt der Hinweis immer an; wird er schmal, bricht er um und `T` wächst. Dieselbe
Diagnose gilt für `LifecycleErrorRow` (`AgentWindow.kt:144`, bedingt, ohne `maxLines`): im Fehlerfall wächst
das Chrome — also gerade dann, wenn der Operator die Eingabezeile am dringendsten braucht.

**Deshalb erweitere ich die Invariante aus §1.1 auf das gesamte feste Chrome:**

> **Jedes `Text` im festen Chrome eines Inhaltsfensters trägt `maxLines` und `overflow = Ellipsis`.**
> Ein Chrome-Element darf in der Höhe nicht von seinem Inhalt abhängen. Wer wachsen will, sitzt im
> `weight(1f)`-Rechteck.

Nur die Segment-Labels der `ModeToggleRow` erfüllen das heute schon (`Text(orchLabel, maxLines = 1)`) — die
zwei Hinweistexte und `LifecycleErrorRow` nicht. Das ist **kein neues Ticket von mir**, sondern der Vorschlag,
CYP-350 um diese drei `Text`-Knoten zu erweitern: derselbe Fix, dieselbe Mutationsprobe, kein zweiter
Mechanismus.

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
7. **Reconnect erzwingt den Glyph-Modus** (§4.1): bei **640 dp** und `connection != LIVE` tragen die drei
   Knöpfe Glyphen, der `ReconnectingChip` ist **vollständig sichtbar**, und die Header-Höhe ist unverändert
   **56 dp**. Ohne diesen Fall bleibt der Test blind für die längste Zeichenkette des Headers — und die steht
   ausgerechnet im **deutschen** Default-Locale.
8. **Der Boden selbst wird gerendert** (§5.2): die Komposition bei `CONTENT_WINDOW_MIN_HEIGHT` — **nicht** nur
   bei `TILED_…` — zeigt den Composer. Heute existiert dieser Test nicht, und deshalb ist §5.1 unbemerkt
   geblieben. **Mutationsprobe:** eine Chrome-Zeile einziehen ⇒ rot. Der bestehende Test bei 391 bleibt dabei
   grün — das ist der Beweis, dass er den Boden nie geprüft hat.
9. **Chrome-Höhe ist rollen- und breitenunabhängig** (§5.4): die Chrome-Höhe bei `canControl = false` ist gleich
   der bei `canControl = true`, und bei 320 dp gleich der bei 640 dp — für beide Werte von `lifecycleError`.
   **Mutationsprobe:** `maxLines` an *einem* Chrome-`Text` entfernen ⇒ bei 320 dp rot.

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
- **Keine gemischte Summe mehr.** `T` bleibt als Symbol stehen, obwohl ich `≈ 44 dp` rechnen kann (§5.3). Eine
  Zahl, die ich nicht gemessen habe, gehört nicht in eine Konstante, die „gemessen" behauptet.
- **Vier eigene Fehler benannt, nicht weggeschrieben:** die Breitenbedingung der 48 dp und das höchste Kind der
  Row (§7, §1.1); der **inhaltsblinde Breakpoint** (§4.1); und die Summe `520 + 195 ≈ 715` (§4.1), in der ich
  Gemessenes mit Geschätztem addiert habe — meine eigene Regel, an mir selbst gerissen. Drei davon fand ich
  beim Selbst-Review, nachdem der PO die Spec **ohne zweite Meinung** freigegeben hatte. Genau dann steigt die
  Sorgfaltspflicht, sie sinkt nicht.
- **Der Befund in §5.1 ist wichtiger als dieses Ticket.** Er betrifft eine bereits **gemergte** Konstante, und
  er entsteht nicht aus Nachlässigkeit, sondern aus zwei grünen Zweigen, die sich im Merge nicht sahen.
- **Docs-only.** Kein Code geändert.
