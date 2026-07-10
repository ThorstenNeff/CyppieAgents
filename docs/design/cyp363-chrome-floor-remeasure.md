# CYP-363 — Der Chrome-Boden, neu gemessen (und der Guard, der ihn hält)

> Owner: UIUX-Designer · Ticket **CYP-363** · Stand 2026-07-10 · Basis **`origin/develop` = `f04b372`** · Scope **WASM-App**
> Docs-only. Adressat: Implementierung + Test. Voraus-Ticket: **CYP-350** (Header-Höhe unbedingt machen).

---

## 0. Das Ergebnis in einem Satz

> **Bei `CONTENT_WINDOW_MIN_HEIGHT = 301` zeigt ein Agentenfenster heute weder Transkript noch Eingabezeile.
> Es ist zu 100 % Chrome.** Gemessen, nicht gerechnet.

Der Defekt, den CYP-338 geschlossen hat, ist **wieder offen** — nicht weil jemand die Zahl geändert hätte,
sondern weil CYP-333 eine vierte Chrome-Zeile eingezogen hat und die Zahl ihr nicht gefolgt ist.

---

## 1. Wie gemessen wurde

Sechs Compose-UI-Testsonden gegen `origin/develop` (`f04b372`), JVM, `runComposeUiTest` — **kein Browser, kein
Emulator** (Auftraggeber-Regel). Bounds über `getUnclippedBoundsInRoot()`; Zeilen, die keinen eigenen `testTag`
tragen, wurden aus der **Lücke zwischen zwei getaggten Kanten** gemessen, nicht aus ihren Kindern. Quelle im
Anhang (§7), damit niemand sie nachrechnen muss.

**Ein Fehlversuch, offen benannt:** Meine erste Sonde rief `WindowHost` direkt auf und maß die Titelleiste zu
**36 dp**. Das war falsch — sie rendert ohne `titleBarLeading` (Avatar), Badge, Token-Zähler und `⋮`. Die echte
Shell misst **64 dp**. **Eine Messung an einer vereinfachten Komposition ist keine Messung, sondern eine
Ableitung mit Extraschritten.** Genau der Fehler dieses Tickets, in meinem eigenen Werkzeug. Alle Zahlen unten
stammen deshalb aus der **echten Shell** bzw. aus dem echten `AgentWindow`.

---

## 2. Die Zahlen (alle gemessen, Breite 320 dp = `TILED_CONTENT_WINDOW_MIN_WIDTH`)

**Natürliche Höhen** — d. h. gemessen, wo genug Platz war. (Warum das dazugesagt werden muss: §2.2.)

| Chrome-Bestandteil | natürlich | Bedingung |
|---|---|---|
| Titelleiste | **64 dp** | echte Shell (mit Avatar, Badge, Token, `⋮`) |
| Agent-Header | **164 dp** | bei 320 dp — Labels brechen um (CYP-350) |
| `ModeToggleRow` | **84 dp** | Operator **+ Hinweis** `terminal_gated_pending` ⟵ **ausgelieferter Zustand** |
| `ModeToggleRow` | 68 dp | Nicht-Operator (Hinweis `workspace_operator_only`) |
| `ModeToggleRow` | 52 dp | Operator, **ohne** Hinweis (erst wenn `WORKTREE_SHELL_LIVE_ENABLED = true`) |
| Composer-Zeile | **73 dp** | breitenunabhängig (320 wie 640) |

**Der ausgelieferte Zustand ist der ungünstigste.** `AgentShell.kt:173` hält `WORKTREE_SHELL_LIVE_ENABLED =
false` ⇒ `terminalGatedNote = true` ⇒ der Hinweistext steht da und bricht bei 320 dp um. Der Browser-Build
zeigt **84 dp**.

**Header über die Breite** (bestätigt Backend2s Reihe exakt): `320 → 164` · `480 → 104` · `520 → 56` · `640 → 56`.

**Chrome-Summe, ausgeliefert, 320 dp:** `64 + 164 + 84 + 73 = ` **`385 dp`**.

### 2.1 Die Probe am Boden — die Antwort auf die PO-Auflage 2

Echte Shell, `resetTo`/`tile`-Pfad; das Fenster landet auf **`CONTENT_WINDOW_MIN_HEIGHT = 301`**:

```
windowH=301  titleBarH=64  headerH=164  toggleRowH=73(!)
transcriptH=0   composerRowH=0   inputH=0
inputDisplayed=false   streamDisplayed=false
```

> **Antwort: weder noch — es beschneidet nicht, es vernichtet.** Transkript **0 dp**, Eingabezeile **0 dp**,
> beide **nicht sichtbar**. Das Fenster ist zu 100 % Chrome, und selbst das Chrome passt nicht mehr ganz.

### 2.2 Die lehrreichste Falle: `73` war schon eine Stauchung

Man beachte `toggleRowH = 73` oben — aber die **natürliche** Höhe der Zeile ist **84**. Die `Column` misst ihre
ungewichteten Kinder der Reihe nach; nach Titelleiste (64) und Header (164) bleiben von 301 noch **73** übrig,
und die Toggle-Zeile bekommt genau die. Sie ist bereits **gequetscht**, bevor Transkript und Composer
überhaupt an die Reihe kommen — beide erhalten **0**.

**Ich bin selbst darauf hereingefallen** und hatte 73 in die erste Fassung dieser Tabelle als natürliche Höhe
geschrieben. Eine am Boden gemessene Höhe ist **keine Komponentenhöhe, sondern ein Rest**. Wer sie in eine
Chrome-Summe einsetzt, misst den Fehler mit, den er beheben will.

Die Gegenprobe zwingt die Zahl: derselbe Shell-Aufbau auf dem `syncWindows`-Pfad (Fenster = 391 dp) misst
`toggleRowH = 84`, `transcriptH = 6`, `composerRowH = 73`. Und `391 − 385 = 6` — **die Chrome-Summe sagt die
gemessene Transkripthöhe exakt voraus.** Das ist die Selbstprüfung, die 73 nie bestanden hätte.

### 2.3 `assertIsDisplayed` ist keine Zusage über Benutzbarkeit

`AgentWindow` allein, 320 dp breit, 237 dp hoch (= 301 − 64), Operator ohne Hinweis:

```
transcriptHeight=0   inputHeight=5.0   assertIsDisplayed=true
```

**Ein 5 dp hohes Eingabefeld gilt als „displayed".** Ein Guard, der nur `assertIsDisplayed(input)` prüft, wäre
hier **grün**. Das ist kein hypothetischer Einwand, es ist die gemessene Ausgabe — und der Grund, warum der
Guard in §4 die **Höhe** vergleicht, nicht die Sichtbarkeit.

### 2.4 Der fünfte Zustand ist gemessen: `lifecycleError` = **+20 dp**

In der ersten Fassung ließ ich `lifecycleError` ungemessen und nannte deshalb keine Zahl. Auf die Auflage des
PO („der Boden muss ihre höchste Form kennen") habe ich nachgemessen:

| Fehlercode | Breite | Zeilenhöhe | Chrome wächst um |
|---|---|---|---|
| `already_running` („Läuft bereits") | 320 | 16 dp | **20 dp** |
| `operator_required` („Nur der Operator darf den Agenten steuern") | 320 | 16 dp | **20 dp** |
| `operator_required` | 640 | 16 dp | **20 dp** |

**Der lange Text bricht bei 320 dp nicht um** — gemessen, nicht angenommen: er passt in eine `labelSmall`-Zeile.
`16 + 2 × 2 dp` Polsterung = **20 dp**.

> **Empfehlung, mit ihrem Preis:** Die Fehlerzeile **gehört in den Boden budgetiert**. Sie erscheint genau dann,
> wenn der Operator handeln muss — und drückt heute in eben diesem Moment die Eingabezeile heraus, mit der er
> handeln würde. **Der Preis: jedes Inhaltsfenster wird dauerhaft 20 dp höher, für einen seltenen Zustand.**
> Ich halte den Handel für richtig; die Alternative (den Fehler ins scrollbare Rechteck legen) macht ihn
> wegscrollbar und ist dann keine Offenlegung mehr. **Es ist eine Produktentscheidung — sag Nein, dann rechne
> ich ohne die 20.**

### 2.5 Der Nebenbefund, der schwerer wiegt als das Ticket: **bei 320 dp sind Stopp und Neustart nicht bedienbar**

Meine `lifecycleError`-Sonde lief bei 320 dp in einen Timeout — der Klick auf `restartBtn` erreichte das
ViewModel nie. Das war **kein Sondenfehler**. Gemessen:

| Breite | Start | Stopp | Neustart |
|---|---|---|---|
| **320 dp** | `w = 15 dp`, `h = 116 dp`, displayed | **`w = 0 dp`, `displayed = false`** | **`w = 0 dp`, `displayed = false`** |
| 640 dp | `w = 59`, `h = 40` ✓ | `w = 58`, `h = 40` ✓ | `w = 76`, `h = 40` ✓ |

Die `Row` misst ihre ungewichteten Kinder der Reihe nach. Nach den Chips und dem `Spacer(weight(1f))` bleibt für
die drei Knöpfe fast nichts: **Start** bekommt 15 dp und stapelt seine Buchstaben zu einer 116 dp hohen Säule;
**Stopp** und **Neustart** bekommen **null**. (Die 156 dp des Neustart-Knopfs plus `2 × 4 dp` Polsterung sind
exakt die gemessenen 164 dp Header-Höhe — die Zahl erklärt sich selbst.)

> **In einem gekachelten Agentenfenster kann der Operator den Agenten heute weder stoppen noch neu starten.**
> Die einzigen Genesungs-Affordanzen des Fensters sind unerreichbar, und **Start** verletzt mit 15 dp Breite die
> Zielgröße aus **WCAG 2.5.8** (24 dp).

Damit ist **CYP-350 keine Layout-Hygiene, sondern die Reparatur eines Bedienbarkeits-Defekts.** Meine eigene
CYP-350-Spec behauptete nur, der Header *wachse*; dass die Knöpfe dabei *verschwinden*, stand nirgends.

**Und die gescheiterte Sonde war der Beweis:** ein Klick, der nicht ankommt, ist eine Messung. Ich habe die
Fehlerzeile danach am Knopf vorbei ausgelöst (`vm.restart()`) — sonst hätte ich §2.4 nie messen können.

### 2.6 Zwei Pfade, zwei verschiedene Defekte

| Pfad | Fenster | was gemessen wurde | Test heute |
|---|---|---|---|
| `resetTo`/`tile` (Agenten **vor** dem ersten Layout) | **301** | Transkript 0, Composer **0, unsichtbar** | **keiner** |
| `syncWindows`/`placeNewWindow` (Agenten **danach**) | **391** | Composer 73 ✓, Transkript **6 dp** | grün |

`AgentWindowMinHeightTest` **gattert die Agentenliste absichtlich**, um den zweiten Pfad zu erzwingen. Dort ist
die Eingabezeile tatsächlich in Ordnung — der Test hat recht mit dem, was er prüft. **Der erste Pfad hat keinen
gerenderten Test**, und genau dort ist die Eingabezeile weg.

Und die **Kachel-Zusage** ist trotzdem gebrochen: der KDoc verspricht bei `TILED_CONTENT_WINDOW_MIN_HEIGHT`
**90 dp Transkript** („drei Textzeilen"). Gemessen: **6 dp**. Das Fenster ist benutzbar, die Zusage ist es nicht.

---

## 3. Die neuen Zahlen

Alle Summanden **natürlich gemessen**, bei 320 dp. Zwei Fassungen, weil §2.4 eine **Produktentscheidung**
verlangt: zählt die Fehlerzeile in den Boden oder nicht?

**A — ohne Fehlerzeile** (Zustand: Operator + Hinweis, ausgeliefert):

| Konstante | heute | **neu (gemessen)** | nach CYP-350 |
|---|---|---|---|
| `CONTENT_WINDOW_MIN_HEIGHT` = 64 + Header + 84 + 73 | `301` | **`385`** | **`277`** |
| `TILED_CONTENT_WINDOW_MIN_HEIGHT` = obiges + 90 | `391` | **`475`** | **`367`** |

**B — mit Fehlerzeile** (die *höchste* Form, `+ 20 dp`) — **meine Empfehlung:**

| Konstante | heute | **neu (gemessen)** | nach CYP-350 |
|---|---|---|---|
| `CONTENT_WINDOW_MIN_HEIGHT` = 20 + 64 + Header + 84 + 73 | `301` | **`405`** | **`297`** |
| `TILED_CONTENT_WINDOW_MIN_HEIGHT` | `391` | **`495`** | **`387`** |

- A heute: `64 + 164 + 84 + 73 = 385`, `+ 90 = 475` · nach CYP-350: `64 + 56 + 84 + 73 = 277`, `+ 90 = 367`
- B heute: `385 + 20 = 405`, `+ 90 = 495` · nach CYP-350: `277 + 20 = 297`, `+ 90 = 387`

> **Ein Minimum, das nur die günstigste Form trägt, ist kein Minimum.** Deshalb B. Aber die 20 dp kosten jedes
> Fenster Höhe, dauerhaft — deshalb steht die Entscheidung beim PO, nicht bei mir.

> **Die `283` im KDoc (`:68`) ist zu streichen.** Sie stammt aus derselben toten Komposition (ohne
> Toggle-Zeile). Ersatz wäre **`367`** — aber **erst, wenn CYP-350 gelandet ist**. Bis dahin gehört dort **keine
> Zahl**, sondern ein Verweis auf CYP-350. Eine Zusage über eine noch nicht existierende Komposition ist genau
> der Fehler, den dieses Ticket repariert.

---

## 4. Der Guard — die eigentliche Lieferung

Eine korrigierte Zahl hält bis zur nächsten Chrome-Zeile. Der Guard hält länger. **Sein Trick: er vergleicht
zwei Renderings miteinander, nicht ein Rendering gegen eine hartkodierte Zahl.** Damit steht in ihm **keine
einzige Chrome-Konstante** — er kann nicht veralten, so wie die 301 veraltet ist.

### G1 — Der Boden ist *exakt* das Chrome der **höchsten** Form (bidirektional)

Zwei Renderings derselben Komposition bei 320 dp Breite, **beide im maximalen Chrome-Zustand** (Operator +
Hinweis + Fehlerzeile — der Zustand, den §3-B budgetiert):

1. **großzügig** (z. B. 700 dp hoch) → misst die **natürliche** Höhe der Eingabezeile `hᵢ`.
2. **am Boden** (`CONTENT_WINDOW_MIN_HEIGHT`) → dort muss gelten:

```
assert inputHeight      == hᵢ   // nicht gestaucht — NICHT bloss assertIsDisplayed (§2.3)
assert transcriptHeight == 0    // kein Rest: der Boden ist das Chrome, nichts darüber
```

> **Der maximale Zustand ist der einzige, in dem `transcriptHeight == 0` gelten darf.** In jedem *kleineren*
> Zustand bleibt Rest übrig — das ist kein Fehler, das ist der Sinn eines Bodens, der die höchste Form trägt.
> Ein Guard, der `== 0` in *irgendeinem* Zustand fordert, würde die Budgetierung aus §2.4 wieder verbieten.

**Warum beide Richtungen rot werden:**

| Änderung | welche Assertion bricht |
|---|---|
| Chrome-Zeile **kommt** (Konstante folgt nicht) | `inputHeight < hᵢ` — die Eingabezeile wird gestaucht |
| Chrome-Zeile **geht** (Konstante folgt nicht) | `transcriptHeight > 0` — der Boden ist zu hoch |
| Konstante allein erhöht | `transcriptHeight > 0` |
| Konstante allein gesenkt | `inputHeight < hᵢ` |

Das ist die Auflage des PO, wörtlich erfüllt: **rot, wenn eine Chrome-Zeile kommt oder geht, ohne dass die
Konstante folgt** — und ebenso, wenn die Konstante sich ohne die Chrome-Zeile bewegt.

### G2 — Die Kachel-Zusage ist eine Zahl, kein Adjektiv

Am `TILED_CONTENT_WINDOW_MIN_HEIGHT`: `transcriptHeight == 90`. Exakt, nicht `>= 90`. Ein `>=` ließe jedes
künftige Chrome-Wachstum durch, solange nur *irgendetwas* übrig bleibt — genau die Nachgiebigkeit des
`weight(1f)`-Kindes, die den heutigen Defekt verdeckt hat.

### G3 — Die Zustände werden aufgezählt, nicht angenommen

Die Chrome-Höhe hängt vom Zustand ab. Der Guard misst das Kreuzprodukt bei 320 dp

`{Operator, Nicht-Operator} × {Shell gegattert, Shell live} × {kein Fehler, Fehler}`

und fordert zweierlei:

```
assert  chromeHeight(zustand) <= CONTENT_WINDOW_MIN_HEIGHT   // für JEDEN Zustand
assert  max(chromeHeight)     == CONTENT_WINDOW_MIN_HEIGHT   // der Boden ist die höchste Form, nicht mehr
```

Die erste Zeile fängt einen neuen, höheren Zustand. Die zweite fängt einen Boden, der über der höchsten Form
steht — sonst wüchse er unbemerkt weiter.

> **Ich habe die Bestandteile gemessen, nicht jede der acht Zellen.** Die drei Formen der `ModeToggleRow`
> (52 / 68 / 84) und die Fehlerzeile (+20) stammen aus getrennten Messungen. Dass eine `Column` ihre Kinder
> stapelt, ist Struktur und keine Vermutung — **trotzdem addiere ich die Kombinationen nicht, sondern verlange,
> dass der Guard sie rendert.** Genau diese Abkürzung („die Summe wird schon stimmen") hat die `301` erzeugt.

### Mutationsproben (Abnahme = welche rot wurde, nicht „grün")

| # | Mutation | erwartet |
|---|---|---|
| MUT-1 | `Spacer(Modifier.height(8.dp))` ins feste Chrome | **G1 rot** (`inputHeight = hᵢ − 8`) |
| MUT-2 | `ModeToggleRow(…)`-Aufruf entfernen | **G1 rot** (`transcriptHeight = 84`) |
| MUT-2b | `LifecycleErrorRow`-Aufruf entfernen | **G1 rot** (`transcriptHeight = 20`) — nur unter §3-B |
| MUT-3 | `CONTENT_WINDOW_MIN_HEIGHT += 1`, Chrome unverändert | **G1 rot** (`transcriptHeight = 1`) |
| MUT-4 | `TILED_CONTENT_WINDOW_MIN_HEIGHT −= 1` | **G2 rot** (`transcriptHeight = 89`) |
| MUT-5 | in G1 `assertIsDisplayed` statt `inputHeight == hᵢ` | **bleibt grün** — der Nachweis, dass die alte Assertion nicht trägt (§2.3) |

MUT-5 ist keine Mutation des Produktivcodes, sondern **des Tests**. Sie beweist, warum der Guard so aussehen
muss und nicht einfacher.

---

## 5. Was das über die Invariante aus CYP-350 §5.4 lehrt — eine Korrektur an mir

Ich hatte heute früh geschrieben: *„Jedes `Text` im festen Chrome trägt `maxLines` + `Ellipsis`."* **Zu grob.**
Die Messung zeigt zwei verschiedene Sorten Text im Chrome:

| Sorte | Beispiel | Regel |
|---|---|---|
| **Aktionsbeschriftung** | „Start" / „Stopp" / „Neustart" | `maxLines = 1`; Bedeutung überlebt als Glyph + gesprochener Name (CYP-350 §1.2) |
| **Offenlegungssatz** | `terminal_gated_pending`, `workspace_operator_only`, `LifecycleErrorRow` | **darf umbrechen** — Kürzen zerstört die Aussage |

Einen Offenlegungssatz zu ellipsieren, damit die Höhe stimmt, wäre **unehrliche Offenlegung, um eine Zahl zu
retten**. Das ist genau der Handel, den ich in jedem QA-Pass ablehne. Also gilt für sie die andere Regel:

> **Sie dürfen wachsen — und der Boden muss ihre höchste Form kennen. Gemessen, bei der Mindestbreite ihrer
> Klasse, für jeden Zustand, in dem sie erscheinen.**

Die Höhen-Invariante des Headers (CYP-350 §1.1) bleibt davon unberührt: dort trägt kein Text eine Offenlegung.

---

## 6. Self-Validation

- **Jede Zahl in §2 und §3 ist eine Messung**, und zwar eine **natürliche** — an einer Komposition mit Platz.
  `T` steht nicht mehr symbolisch da: es ist **84 dp** im ausgelieferten Zustand. Meine Schätzung war **44 dp**
  — fast halb so groß. Sie stand deshalb zu Recht nie in einer Konstanten.
- **Zwei eigene Messfehler sind dokumentiert, nicht getilgt:** Titelleiste 36 statt 64, weil ich an einer
  vereinfachten Komposition maß (§1); und `T = 73` statt 84, weil ich **am Boden** maß, wo die Zeile bereits
  gequetscht war (§2.2). Beide haben die Form des Befunds selbst.
- **Der Guard enthält keine Chrome-Konstante** (§4) — er vergleicht zwei Renderings. Er kann nicht so veralten,
  wie die 301 veraltet ist.
- **Der Guard ist bidirektional** und hat für jede Richtung eine benannte Mutation. MUT-5 beweist, dass die
  bestehende Assertion nicht ausreicht.
- **Kein ungemessener Zustand wird beziffert.** `lifecycleError` war in der ersten Fassung ohne Zahl; auf die
  Auflage des PO ist er jetzt **gemessen** (+20 dp, §2.4). Die **Kombinationen** der Zustände addiere ich
  weiterhin nicht — der Guard rendert sie (§4 G3).
- **Der Nebenbefund in §2.5 ist schwerer als das Ticket:** bei 320 dp sind Stopp und Neustart `0 dp` breit und
  nicht bedienbar, Start misst 15 dp (WCAG 2.5.8 fordert 24). Er kam aus einer **gescheiterten** Sonde — ein
  Klick, der nicht ankommt, ist eine Messung.
- **Eine eigene frühere Regel wurde eingeschränkt, nicht verteidigt** (§5).
- **Docs-only.** Die Sonden aus §7 waren Messinstrumente und sind wieder entfernt; der Guard ist Lieferung der
  Entwicklung/des Testers, nicht meine.

---

## 7. Anhang: die Sonde (damit niemand nachrechnen muss)

Kern der Messung — die Zeile ohne eigenen Tag wird aus der Lücke zwischen zwei getaggten Kanten bestimmt:

```kotlin
val win = onNodeWithTag(WindowTestTags.window("po"), useUnmergedTree = true).getUnclippedBoundsInRoot()
val tb  = onNodeWithTag(WindowTestTags.titleBar("po"), useUnmergedTree = true).getUnclippedBoundsInRoot()
val hdr = onNodeWithTag(AgentViewTags.header("po"), useUnmergedTree = true).getUnclippedBoundsInRoot()
val cnt = onNodeWithTag(AgentViewTags.content("po"), useUnmergedTree = true).getUnclippedBoundsInRoot()
val inp = onNodeWithTag(AgentViewTags.input("po"), useUnmergedTree = true).getUnclippedBoundsInRoot()

titleBarH   = tb.bottom  - tb.top
headerH     = hdr.bottom - hdr.top
toggleRowH  = cnt.top    - hdr.bottom   // die Zeile trägt keinen eigenen Tag: aus der Lücke messen
transcriptH = cnt.bottom - cnt.top
composerRowH= win.bottom - cnt.bottom
```

Drei Fallen, in alle drei selbst hineingelaufen:
1. **`AgentViewTags.modeToggle` ist nicht die Zeile**, sondern die `SegmentedButtonRow` darin. Die umschließende
   `Column` trägt `2 × 2 dp` Polsterung **und** den Hinweistext. Wer den Tag misst, misst das Kind, nicht die
   Zeile. Deshalb oben die Messung aus der **Lücke**.
2. **`WindowHost` ohne `titleBarLeading`/Badge/`⋮` ist nicht die Shell.** Titelleiste **36 statt 64**.
3. **Wer am Boden misst, misst Reste.** Die Toggle-Zeile ergab dort **73**, natürlich ist sie **84**. Eine
   Komponentenhöhe wird gemessen, wo sie sich entfalten darf — sonst schreibt man den Defekt in die Konstante,
   die ihn verhindern soll.

Alle drei haben dieselbe Form wie der Befund selbst: **man misst etwas, das aussieht wie die Sache.**
