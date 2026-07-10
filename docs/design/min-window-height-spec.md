# Minimale benutzbare Höhe eines Inhaltsfensters — Spec (CYP-338)

> Owner: UIUX-Designer · Ticket **CYP-338** · Stand 2026-07-10 · Basis **develop `5c79a79`** · Scope **WASM-App**
> Docs-only. Adressat: **Backend2** (Implementierung + Regressionstest).
> Gegenstück zu `TILED_CONTENT_WINDOW_MIN_WIDTH` (CYP-26 §2.2) — dieselbe Fensterklasse, dieselbe Logik, andere Achse.
>
> **⚠ Korrigiert 2026-07-10 nach Backend2s Messung der echten Komposition.** Meine erste Fassung nannte
> `266 / 176`. Diese Zahlen waren **falsch** — sie unterstellten stillschweigend eine Fensterbreite ≥ 520 dp
> (§2.4). Gültig sind die **gemessenen** Werte unten. Die Ursache ist als **CYP-350** ausgelagert; danach
> fallen die Zahlen auf `274 / 184`.

---

## 0. Die Zahl

> ### `TILED_CONTENT_WINDOW_MIN_HEIGHT = 391f` (dp) — gemessen, bei der Mindestbreite 320 dp
>
> und die Invariante, die sie schützt:
>
> ### Ein Inhaltsfenster darf **nie** unter **301 dp** fallen — darunter existiert die Eingabezeile nicht mehr.

`391` ist keine gewählte Zahl, sondern eine **Summe** (§2). `301` ist das reine Chrome — die Höhe, unterhalb
derer das Fenster aufhört, ein Agentenfenster zu sein. **Nicht „glätten":** jeder dp über der Herleitung ist
ein dp, bei dem Fenster früher kollidieren.

**Und der Befund, der den Bug erklärt:** Das heutige `MIN_WINDOW_HEIGHT = 120 dp`
(`WindowManagerState.kt:43`) ist **181 dp kleiner als das feste Chrome allein**. Ein Agentenfenster bei 120 dp
kann die Eingabezeile nicht rendern — nicht „knapp", sondern **konstruktiv unmöglich**.

> **Diese Zahlen sind ehrlich und teuer.** Die Kachel-Reserve auf dem kleinsten Fenster-Host schrumpft von
> 158 dp auf **33 dp** (§5). Sie sind trotzdem die richtigen: das Agentenfenster hat **produktiv** keine
> Eingabezeile, und das wird nicht hinter einer Design-Runde repariert. **CYP-350** holt die Reserve zurück,
> indem es die Ursache beseitigt — den umbrechenden Header — statt die Zahl zu beschönigen.

---

## 1. Warum die Höhe kein Gegenstück hatte

Für die **Breite** existiert die Sonderregel; für die **Höhe** nicht:

| Achse | Inhaltsfenster | andere Fenster |
|---|---|---|
| Breite | `TILED_CONTENT_WINDOW_MIN_WIDTH = 320f` | `MIN_WINDOW_WIDTH = 160f` |
| Höhe | *(fehlt)* | `MIN_WINDOW_HEIGHT = 120f` |

Die Breitenregel begründet sich aus dem Composer (`COMPOSER_MIN_WIDTH = 280f` + Senden + Padding). **Genau
dasselbe Bauteil begründet die Höhenregel** — es wurde nur nie auf die zweite Achse gezogen. Diese Spec zieht
sie.

---

## 2. Herleitung (Frage 1) — gemessen, nicht geraten

Alle Material-3-Werte sind aus dem **gebauten Artefakt** gelesen (`material3-desktop-1.10.0-alpha05.jar`,
Compose Multiplatform `1.10.3`), nicht aus dem Gedächtnis:

| Konstante | Wert | Herkunft |
|---|---|---|
| `ButtonDefaults.MinHeight` | **40 dp** | Bytecode → `ButtonSmallTokens.ContainerHeight = 40.0d` |
| `TextFieldDefaults.MinHeight` | **56 dp** | Bytecode → `bipush 56` |
| `bodyMedium` Zeilenhöhe | **20 sp** | Bytecode → `TypeScaleTokens.BodyMediumLineHeight` |
| `labelSmall` Zeilenhöhe | **16 sp** | Bytecode → `TypeScaleTokens.LabelSmallLineHeight` |

### 2.1 Die vier Bestandteile

| # | Bestandteil | Quelle | Höhe (gemessen) |
|---|---|---|---|
| 1 | **Titelleiste** | `WindowManager.kt` — `Row(padding(horizontal = 12, vertical = 8))` + `⋮`-`TextButton` + Badge-Pill | **64 dp** |
| 2 | **Agent-Header** | `AgentWindow.kt:185` — `Row` mit Status, Chips und **drei** `TextButton`s | **164 dp @ 320 dp Breite** (s. §2.4) |
| 3 | **Composer** | `AgentWindow.kt:513` — `Row(padding(8.dp))` um ein `OutlinedTextField` | **73 dp** |
| 4 | **Transkript** | `AgentWindow.kt:300` — `LazyColumn(contentPadding = 12.dp, verticalArrangement = spacedBy(6.dp))` | s. §2.2 → **90 dp** |

> **Die Bestandteile 1–3 sind Backend2s Messung der echten Komposition**, nicht meine Arithmetik aus
> Komponenten-Minima. Wo beide auseinandergehen, gilt die Messung — §2.4 erklärt, warum meine Rechnung
> danebenlag.
>
> **Titelleiste** = 64 dp gilt für Agentenfenster, weil nur sie den `⋮`-Button tragen (System-Fenster ohne
> `settingsFor` sind niedriger). Die Regel wird von der **teuersten** Variante bestimmt.
>
> **Nicht eingerechnet:** die `LifecycleErrorRow` (`AgentWindow.kt:151`, `16 + 2×2 = 20 dp`). Sie ist ein
> **transienter Fehlerzustand**, kein Dauerbestandteil. Sie nimmt ihren Platz korrekt aus dem
> **gewichteten** Transkript (`Modifier.weight(1f)`) — nie aus dem Composer. Im Fehlerfall bleibt das
> Transkript bei 70 dp: zwei Textzeilen. Das ist die richtige Degradation und braucht keine Reserve.

### 2.2 Wie viele Transkript-Zeilen? (Frage 2)

Der PO hat den Kern getroffen: *„Eine ist keine Antwort, wenn eine `AssistantTextRow` mehrzeilig ist."*

**Wie breit ist eine Zeile überhaupt?** Bei der Mindestbreite (320 dp) bleibt dem Assistant-Text:

```
                                   heute (5c79a79)   nach CYP-335
320  Fensterbreite                        320             320
− 24  LazyColumn contentPadding (2 × 12)  −24             −24
− 52  Zeitspalten-Rinne (44 + 8)            —             −52
                                   ─────────────   ─────────────
                                        296 dp          244 dp
   bei bodyMedium (14 sp)             ≈ 42 Zeichen    ≈ 35 Zeichen
```

> **Merge-Stand ehrlich benannt:** Die Zeitspalte aus **CYP-335 ist noch nicht in `develop`**. Ich führe beide
> Zustände, weil CYP-338 zuerst landen wird. Die Rinne macht die Zeile **schmaler**, also den Umbruch
> **wahrscheinlicher** — sie verschärft das Argument, sie trägt es nicht. Auf die **Zeilenhöhe** wirkt sie
> nicht (die Zeitzelle ist `labelSmall`, 16 sp < 20 sp), die 90 dp gelten in beiden Zuständen unverändert.

Eine typische deutsche Agentenantwort von 80–120 Zeichen umbricht **in beiden Zuständen** auf 2–4 Zeilen. Die
mehrzeilige `AssistantTextRow` ist nicht der Ausnahmefall, sie ist der **Normalfall**.

**Das Minimum ist deshalb nicht „eine Zeile", sondern die kleinste Ansicht, in der eine umbrochene Antwort
nicht das Einzige ist, was man sieht:**

| Element | Rechnung | Höhe |
|---|---|---|
| `UserTurnRow` — die eigene Frage, 1 Zeile | `1 × 20` | 20 dp |
| Zwischenraum | `spacedBy(6.dp)` | 6 dp |
| `AssistantTextRow` — die Antwort, umbrochen | `2 × 20` | 40 dp |
| `contentPadding` oben + unten | `2 × 12` | 24 dp |
| | | **90 dp** |

**Begründung der drei Textzeilen (nicht zwei, nicht vier):**
- **Zwei** Zeilen fasst eine einzige umbrochene Antwort **vollständig** — und sonst nichts. Der Operator sieht
  eine Antwort ohne die Frage, ohne den vorangegangenen Tool-Aufruf. Das Fenster zeigt Inhalt, ist aber **als
  Transkript nicht lesbar**. Das ist die Attrappe, nur eine Zeile höher.
- **Drei** Zeilen sind die kleinste Ansicht, in der eine umbrochene Antwort **mit einer Nachbarzeile
  koexistiert** — es entsteht ein Gesprächsausschnitt statt eines Fragments.
- **Vier** Zeilen wären bequemer und kosten 20 dp, die beim Kacheln fehlen. Der PO hat vor Großzügigkeit
  gewarnt; die dritte Zeile ist begründbar, die vierte wäre Geschmack.

### 2.3 Summe

```
 64  Titelleiste
164  Agent-Header      ← bei 320 dp Breite; breitenabhängig (§2.4)
 73  Composer          ← nicht verhandelbar (Frage 3)
────
301  dp  FESTES CHROME  ← die harte Untergrenze
+ 90  Transkript (3 Textzeilen + Zwischenraum + Padding)
────
391  dp  TILED_CONTENT_WINDOW_MIN_HEIGHT
```

**Nach CYP-350** (Header wird breitenunabhängig, eine `TextButton`-Zeile):

```
 64  Titelleiste  +  48  Header  +  73  Composer   =  184 dp  Chrome
                                            + 90   =  274 dp  Mindesthöhe
```

> Meine Komposition summiert auf `185`, Backend2 misst `184`. **Die Differenz von 1 dp gehört seiner Messung,
> nicht meiner Arithmetik** — und genau darum steht sie hier so. Die Zahl der Spec ist die gemessene.

### 2.4 Warum meine erste Rechnung falsch war — die Höhe des Headers hängt an der **Breite**

Ich hatte den Header mit `ButtonDefaults.MinHeight (40) + 2×4 = 48 dp` angesetzt. Der Wert ist aus dem
gebauten Artefakt gelesen und **trotzdem falsch**: er gilt nur, **sofern die Knopf-Labels nicht umbrechen**.

Im gesamten Header trägt **kein einziges `Text` ein `maxLines`**. Die drei `TextButton`s stehen hinter dem
`Spacer(weight(1f))` und werden zuletzt gemessen — reicht die Breite nicht, bricht ihr Label um, der Knopf
wird höher, und die `Row` wächst mit:

| Fensterbreite | Header |
|---|---|
| **320 dp** (= Mindestbreite derselben Klasse) | **164 dp** |
| 420 dp | 164 dp |
| 480 dp | 104 dp |
| **520 dp** | **56 dp** ← ab hier galt meine Annahme |

Für die **Transkript-Zeile** habe ich den Umbruch sorgfältig durchgerechnet (§2.2). Für den **Header**, eine
Komponente weiter oben, habe ich ihn übersehen. Es ist dieselbe Mechanik — und dieselbe Fehlerklasse, die ich
im Beobachtungs-Audit beschrieben habe: **eine Zahl, die wie eine Messung aussieht und eine Ableitung unter
einer unausgesprochenen Annahme ist.** `ButtonDefaults.MinHeight` war die Messung; `48 dp` war die Ableitung,
deren Bezugsrahmen — „ab 520 dp Breite" — beim Schichtwechsel verlorenging.

**Regel, die ich mir daraus gebe:** Eine Komponentenhöhe ist erst dann eine Zahl, wenn dabeisteht, **unter
welcher Breite sie gilt.** Steht es nicht dabei, ist es keine Höhe, sondern eine Hoffnung.

Die Ursache ist **CYP-350** (`agent-header-no-wrap-spec.md`): `maxLines = 1` an jedem Header-`Text` macht die
Höhe breitenunabhängig; unterhalb von 520 dp tragen die Knöpfe Glyphen statt Labels.

---

## 3. Der Bug, quantifiziert (Frage 3: der Composer ist nicht optional)

`AgentWindow.kt:106` ist eine `Column` mit — in dieser Reihenfolge — `LifecycleErrorRow?`, `AgentHeader`,
`AgentTranscript(Modifier.weight(1f))`, `MessageComposer`.

Compose misst in einer `Column` zuerst die **ungewichteten** Kinder, dann verteilt es den Rest an die
gewichteten. Bei `MIN_WINDOW_HEIGHT = 120`:

```
120  Fensterhöhe
− 64  Titelleiste
────
 56  dp bleiben für den Fensterinhalt

  ungewichtet:  Header will 164 (bei 320 dp Breite)  →  bekommt 56, 0 dp übrig
                Composer will 73                     →  bekommt  0      ← nicht darstellbar
  gewichtet:    Transkript                           →  0 dp
```

Der Composer ist das **letzte ungewichtete** Kind und erbt, was übrig ist. **Er verschwindet nicht durch einen
Rundungsfehler, sondern weil 301 > 120** — der Header allein übersteigt die Fensterhöhe. Ein Agentenfenster
ohne Composer ist kein Agentenfenster — die `301 dp` sind deshalb eine **Invariante**, keine Empfehlung:
unterhalb davon darf ein Inhaltsfenster nie existieren, egal über welchen Pfad. (Nach CYP-350: `184 dp`.)

---

## 4. Trägt **eine** Regel für alle Inhaltsfenster? (Frage 4) — **Ja, und die Klasse existiert schon**

`AgentShell.kt:424` definiert `contentWindowIds` als „alles **außer**" ACL · Event-Log (Browse + Tail) ·
Settings · Agent-Management · Product-Lead · Roster · Compact. Übrig bleiben genau: **die Agentenfenster und
das Comm-Fenster**.

Das definierende Merkmal dieser Klasse ist **nicht** „viel Inhalt", sondern: **sie hat einen Composer.** Genau
darum gibt es die Breitenregel. Die Höhenregel gehört auf **dieselbe** Klasse — kein neues Prädikat, kein
zweiter Mechanismus.

**Gegenprobe Comm-Fenster** (`CommPanel.kt:77` ist `Row { ChannelListPane, TimelinePane(weight 1f) }` — **kein**
Header-Streifen):

| | Agentenfenster | Comm-Fenster |
|---|---|---|
| Titelleiste | 56 | 56 |
| Header | 48 | — |
| Composer (`Row(padding(8.dp))` + `OutlinedTextField`) | 72 | 72 |
| **Chrome** | **301** @320dp (**184** nach CYP-350) | **137** @320dp (**137**; kein Header) |
| bei 391 dp bleibt der Liste | 90 dp = **3 Textzeilen** | 254 dp ≈ **6–7 Nachrichten** (Zeile ≈ 36 dp) |

Comm ist **nicht** der bindende Fall — es hat **keinen** Header und damit auch nicht dessen Umbruch-Problem.
**Eine Regel, bemessen am teuersten Mitglied der Klasse: dem Agentenfenster.** Sie trägt.

> **Event-Log, ACL, Settings usw. sind keine Inhaltsfenster** und behalten `MIN_WINDOW_HEIGHT = 120`. Sie haben
> **keinen Composer**, den sie verlieren könnten; eine Liste bleibt bei 120 dp gestaucht, aber scrollbar und
> funktionsfähig. Kein Grund, sie mitzuziehen — und ein guter Grund, es **nicht** zu tun (§7 der
> `outline`-Bereinigung lässt grüßen: eine Regel, die richtige Fälle mitreißt, wird abgeschaltet).
>
> **Falls Comm je einen Header bekommt**, ist die Zahl neu herzuleiten — dann könnte Comm der bindende Fall
> werden.

---

## 5. Was, wenn der Host kleiner ist als die Mindesthöhe? (Frage 5) — **Der Fall existiert nicht**

Das ist der Punkt, den ich am liebsten belege, weil die Antwort schon im Code steht.

`WindowManager.kt:157–163`: schwebende Fenster gibt es **nur**, wenn der Host **nicht** kompakt ist —
sonst rendert die App den **Phone-Pager** (eine Seite pro Fenster, ohne Titelleiste):

```kotlin
val isCompact = sizeClass.widthSizeClass  == WindowWidthSizeClass.Compact ||
                sizeClass.heightSizeClass == WindowHeightSizeClass.Compact
if (isCompact) PhonePager(...) else WindowCanvas(...)
```

Die Schwelle, aus dem Artefakt gelesen (`WindowHeightSizeClass$Companion`, Bytecode `sipush 480` / `900`):
**Compact = Höhe < 480 dp.**

```
480  kleinster Host, auf dem überhaupt Fenster existieren
− 56  HOST_AFFORDANCE_BAND (reservierter Streifen oben)
────
424  dp nutzbare Höhe   ≥   391 dp
```

**Die Mindesthöhe ist in dem Bereich, in dem es Fenster gibt, immer erfüllbar** — mit **33 dp** Luft (nach CYP-350 wieder **150 dp**). Ein kleines
Browserfenster führt nicht dazu, dass „gar nichts mehr geht": es führt in den Pager, der die Mindesthöhe gar
nicht kennt. Es braucht **keinen** Notfallpfad.

**Für den Grenzfall beim Kacheln** (viele Fenster, wenig Höhe) gilt die **bestehende** Regelung der
Breitenachse unverändert weiter — *„Applied by `WindowReducer.tile` only when room allows — **fully visible**
wins over it"* (`WindowManagerState.kt:31–33`). Mit **einem** Zusatz, und das ist der Unterschied zur Breite:

> Ein zu schmales Fenster ist unbequem. Ein zu **niedriges** Fenster **verliert seine Eingabezeile**.
> Beim Kacheln darf ein Inhaltsfenster deshalb unter 391 dp gedrückt werden, **niemals unter 301 dp**.

---

## 6. Der Fix ist größer als die eine Zeile im Ticket

Das Ticket nennt `WindowManagerState.kt:461` (`placeNewWindow`). `MIN_WINDOW_HEIGHT` wird an **sechs** Stellen
gelesen. Ein Fix nur an `:461` lässt die Lücke offen — der Nutzer zieht das Fenster einfach wieder klein.

| Zeile | Funktion | Braucht die Fensterklasse? | Warum |
|---|---|---|---|
| `461` | `placeNewWindow` | **ja** | Der gemeldete Bug: neue Agentenfenster entstehen mit 120 dp |
| `124` | `resizeBy` | **ja** | Sonst zieht der Nutzer den Composer wieder heraus |
| `180` | (Clamp) | **ja** | Gleiche Untergrenze auf demselben Pfad |
| `212` | `expandCentered` | **ja** | Nutzt bereits `typeMinW` für die **Breite**, aber `MIN_WINDOW_HEIGHT` für die Höhe — dieselbe Asymmetrie |
| `286` | `tile` | **ja**, mit der 301-dp-Klemme (§5) | „Fully visible wins", aber nie composer-los |
| `407` | Fallback-Layout ohne Hostgröße | **ja** | Erzeugt Fenster mit `MIN_WINDOW_WIDTH × MIN_WINDOW_HEIGHT` — hier fehlt sogar die **Breiten**regel (`160` statt `320`) |

> **Nebenbefund, Zeile 407:** derselbe Asymmetrie-Fehler auf der Breitenachse. Fenster, die vor der ersten
> Host-Messung angelegt werden, bekommen `160 dp` statt `320 dp` — auch für Inhaltsfenster. Kein Teil von
> CYP-338; gemeldet, nicht angefasst.

---

## 7. Was der Regressionstest prüfen sollte (für Backend2)

Der Test soll die **Invariante** festnageln, nicht die Zahl abschreiben — sonst bestätigt er nur, dass eine
Konstante eine Konstante ist.

1. **Der Bug selbst:** Ein Agentenfenster, das **nach** dem ersten Layout erscheint (`syncWindows`, der reale
   Pfad — die Agentenliste kommt asynchron über HTTP), hat `height ≥ 391`.
2. **Der Resize-Boden:** `resizeBy(agentId, 0, −10_000)` lässt `height ≥ 391` (bzw. ≥ 301 im gekachelten
   Grenzfall), **nie** darunter.
3. **Die harte Invariante, klassenweit:** für **jedes** Fenster in `contentWindowIds`, über **jeden** Pfad
   (`placeNewWindow` · `resizeBy` · `expandCentered` · `tile` · Fallback): `height ≥ 301`.
   **Gegen die Invariante prüfen, nicht gegen `TILED_CONTENT_WINDOW_MIN_HEIGHT`** — sonst ist die Assertion
   unter der Mutationsprobe trivial wahr.
4. **Nicht-Inhaltsfenster bleiben unberührt:** ein Event-Log-Fenster darf weiterhin 120 dp sein — sonst reißt
   die Bereinigung richtige Fälle mit.
5. **Mutationsprobe:** `TILED_CONTENT_WINDOW_MIN_HEIGHT` auf 120 setzen ⇒ Test 1–3 **rot**. Wird er das nicht,
   prüft er den falschen Pfad (z. B. `resetTo` statt `syncWindows`).

---

## 8. §-Asks an den PO

| # | Frage | Meine Empfehlung |
|---|---|---|
| 1 | Beim Kacheln: darf ein Inhaltsfenster unter 391 dp gedrückt werden (bis zur 301-dp-Klemme), oder soll `tile()` lieber die Zeilenzahl reduzieren? | **Drücken bis 301.** Es ist das Verhalten der Breitenachse; Zeilenreduktion wäre ein neuer Mechanismus. |
| 2 | `WindowManagerState.kt:407` — Fallback erzeugt Inhaltsfenster mit 160 dp Breite statt 320. Eigenes Ticket? | **Ja**, eigenes kleines Ticket. Gleiche Asymmetrie, andere Achse; nicht in CYP-338 mischen. |
| 3 | Soll `MIN_WINDOW_HEIGHT` (120) für Nicht-Inhaltsfenster bleiben? | **Ja, unverändert.** Sie haben keinen Composer zu verlieren. |

---

## 9. Self-Validation

- **Eine Zahl, vollständig hergeleitet:** `64 + 164 + 73 + 90 = 391` (gemessen bei 320 dp Breite).
  Nach CYP-350: `64 + 48 + 73 + 90 = 274`. Meine erste Fassung nannte `266 / 176` — der Fehler ist in §2.4
  benannt, nicht weggeschrieben.
- **Alle fünf Fragen beantwortet:** 1 → §2.1 · 2 → §2.2 · 3 → §3 · 4 → §4 · 5 → §5.
- **Die Zahl ist gegen beide Fehlrichtungen geprüft:** zu knapp → §3 zeigt, dass 301 dp die harte Grenze ist
  und 391 nur 90 dp Transkript gibt (drei Zeilen, kein Luxus); zu großzügig → §5 zeigt 33 dp Luft auf dem
  kleinsten Host, auf dem Fenster überhaupt existieren.
- **Der Composer ist in jedem Pfad enthalten** (§6 listet alle sechs), und die 301-dp-Invariante gilt
  klassenweit, nicht nur beim Erzeugen.
- **Keine richtige Verwendung mitgerissen:** Nicht-Inhaltsfenster behalten 120 dp (§4).
- **Docs-only.** Kein Code geändert.
