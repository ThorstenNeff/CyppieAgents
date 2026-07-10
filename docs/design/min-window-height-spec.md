# Minimale benutzbare Höhe eines Inhaltsfensters — Spec (CYP-338)

> Owner: UIUX-Designer · Ticket **CYP-338** · Stand 2026-07-10 · Basis **develop `5c79a79`** · Scope **WASM-App**
> Docs-only. Adressat: **Backend2** (Implementierung + Regressionstest).
> Gegenstück zu `TILED_CONTENT_WINDOW_MIN_WIDTH` (CYP-26 §2.2) — dieselbe Fensterklasse, dieselbe Logik, andere Achse.

---

## 0. Die Zahl

> ### `TILED_CONTENT_WINDOW_MIN_HEIGHT = 266f` (dp)
>
> und die Invariante, die sie schützt:
>
> ### Ein Inhaltsfenster darf **nie** unter **176 dp** fallen — darunter existiert die Eingabezeile nicht mehr.

`266` ist keine gewählte Zahl, sondern eine **Summe** (§2). `176` ist das reine Chrome — die Höhe, unterhalb
derer das Fenster aufhört, ein Agentenfenster zu sein. **Nicht auf 272 „glätten":** jeder dp über der
Herleitung ist ein dp, bei dem Fenster früher kollidieren.

**Und der Befund, der den Bug erklärt:** Das heutige `MIN_WINDOW_HEIGHT = 120 dp`
(`WindowManagerState.kt:43`) ist **56 dp kleiner als das feste Chrome allein**. Ein Agentenfenster bei 120 dp
kann die Eingabezeile nicht rendern — nicht „knapp", sondern **konstruktiv unmöglich**.

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

| # | Bestandteil | Quelle | Rechnung | Höhe |
|---|---|---|---|---|
| 1 | **Titelleiste** | `WindowManager.kt` — `Row(padding(horizontal = 12, vertical = 8))`; höchstes Kind ist der `⋮`-`TextButton` | `40 + 2×8` | **56 dp** |
| 2 | **Agent-Header** | `AgentWindow.kt:185` — `Row(padding(horizontal = 8, vertical = 4))`; höchste Kinder sind die `TextButton`s Start/Stop/Neustart | `40 + 2×4` | **48 dp** |
| 3 | **Composer** | `AgentWindow.kt:513` — `Row(padding(8.dp))` um ein `OutlinedTextField` | `56 + 2×8` | **72 dp** |
| 4 | **Transkript** | `AgentWindow.kt:300` — `LazyColumn(contentPadding = 12.dp, verticalArrangement = spacedBy(6.dp))` | s. §2.2 | **90 dp** |

> **Titelleiste = 56 dp** gilt für Agentenfenster, weil nur sie den `⋮`-Button tragen (System-Fenster:
> `titleBarLeading`/`settingsFor` = `null` → 36 dp). Die Regel wird von der **teuersten** Variante bestimmt.
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
 56  Titelleiste
 48  Agent-Header
 72  Composer          ← nicht verhandelbar (Frage 3)
────
176  dp  FESTES CHROME  ← die harte Untergrenze
+ 90  Transkript (3 Textzeilen + Zwischenraum + Padding)
────
266  dp  TILED_CONTENT_WINDOW_MIN_HEIGHT
```

---

## 3. Der Bug, quantifiziert (Frage 3: der Composer ist nicht optional)

`AgentWindow.kt:106` ist eine `Column` mit — in dieser Reihenfolge — `LifecycleErrorRow?`, `AgentHeader`,
`AgentTranscript(Modifier.weight(1f))`, `MessageComposer`.

Compose misst in einer `Column` zuerst die **ungewichteten** Kinder, dann verteilt es den Rest an die
gewichteten. Bei `MIN_WINDOW_HEIGHT = 120`:

```
120  Fensterhöhe
− 56  Titelleiste
────
 64  dp bleiben für den Fensterinhalt

  ungewichtet:  Header 48  →  16 dp übrig
                Composer will 56  →  bekommt 16      ← das 56-dp-Feld ist nicht darstellbar
  gewichtet:    Transkript  →  0 dp
```

Der Composer ist das **letzte ungewichtete** Kind und erbt, was übrig ist. **Er verschwindet nicht durch einen
Rundungsfehler, sondern weil 176 > 120.** Ein Agentenfenster ohne Composer ist kein Agentenfenster — die
`176 dp` sind deshalb eine **Invariante**, keine Empfehlung: unterhalb davon darf ein Inhaltsfenster nie
existieren, egal über welchen Pfad.

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
| **Chrome** | **176** | **128** |
| bei 266 dp bleibt der Liste | 90 dp = **3 Textzeilen** | 138 dp ≈ **3–4 Nachrichten** (Zeile ≈ 36 dp: Avatar 28, bzw. Name 16 + Text 20) |

Comm ist **nicht** der bindende Fall — es bekommt bei 266 dp mehr, als es selbst bräuchte (≈ 230 dp).
**Eine Regel, bemessen am teuersten Mitglied der Klasse: dem Agentenfenster.** Sie trägt.

> **Event-Log, ACL, Settings usw. sind keine Inhaltsfenster** und behalten `MIN_WINDOW_HEIGHT = 120`. Sie haben
> **keinen Composer**, den sie verlieren könnten; eine Liste bleibt bei 120 dp gestaucht, aber scrollbar und
> funktionsfähig. Kein Grund, sie mitzuziehen — und ein guter Grund, es **nicht** zu tun (§7 der
> `outline`-Bereinigung lässt grüßen: eine Regel, die richtige Fälle mitreißt, wird abgeschaltet).
>
> **Falls Comm je einen Header bekommt**, ist die 266 neu herzuleiten — dann könnte Comm der bindende Fall
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
424  dp nutzbare Höhe   ≥   266 dp
```

**Die Mindesthöhe ist in dem Bereich, in dem es Fenster gibt, immer erfüllbar** — mit 158 dp Luft. Ein kleines
Browserfenster führt nicht dazu, dass „gar nichts mehr geht": es führt in den Pager, der die Mindesthöhe gar
nicht kennt. Es braucht **keinen** Notfallpfad.

**Für den Grenzfall beim Kacheln** (viele Fenster, wenig Höhe) gilt die **bestehende** Regelung der
Breitenachse unverändert weiter — *„Applied by `WindowReducer.tile` only when room allows — **fully visible**
wins over it"* (`WindowManagerState.kt:31–33`). Mit **einem** Zusatz, und das ist der Unterschied zur Breite:

> Ein zu schmales Fenster ist unbequem. Ein zu **niedriges** Fenster **verliert seine Eingabezeile**.
> Beim Kacheln darf ein Inhaltsfenster deshalb unter 266 dp gedrückt werden, **niemals unter 176 dp**.

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
| `286` | `tile` | **ja**, mit der 176-dp-Klemme (§5) | „Fully visible wins", aber nie composer-los |
| `407` | Fallback-Layout ohne Hostgröße | **ja** | Erzeugt Fenster mit `MIN_WINDOW_WIDTH × MIN_WINDOW_HEIGHT` — hier fehlt sogar die **Breiten**regel (`160` statt `320`) |

> **Nebenbefund, Zeile 407:** derselbe Asymmetrie-Fehler auf der Breitenachse. Fenster, die vor der ersten
> Host-Messung angelegt werden, bekommen `160 dp` statt `320 dp` — auch für Inhaltsfenster. Kein Teil von
> CYP-338; gemeldet, nicht angefasst.

---

## 7. Was der Regressionstest prüfen sollte (für Backend2)

Der Test soll die **Invariante** festnageln, nicht die Zahl abschreiben — sonst bestätigt er nur, dass eine
Konstante eine Konstante ist.

1. **Der Bug selbst:** Ein Agentenfenster, das **nach** dem ersten Layout erscheint (`syncWindows`, der reale
   Pfad — die Agentenliste kommt asynchron über HTTP), hat `height ≥ 266`.
2. **Der Resize-Boden:** `resizeBy(agentId, 0, −10_000)` lässt `height ≥ 266` (bzw. ≥ 176 im gekachelten
   Grenzfall), **nie** darunter.
3. **Die harte Invariante, klassenweit:** für **jedes** Fenster in `contentWindowIds`, über **jeden** Pfad
   (`placeNewWindow` · `resizeBy` · `expandCentered` · `tile` · Fallback): `height ≥ 176`.
4. **Nicht-Inhaltsfenster bleiben unberührt:** ein Event-Log-Fenster darf weiterhin 120 dp sein — sonst reißt
   die Bereinigung richtige Fälle mit.
5. **Mutationsprobe:** `TILED_CONTENT_WINDOW_MIN_HEIGHT` auf 120 setzen ⇒ Test 1–3 **rot**. Wird er das nicht,
   prüft er den falschen Pfad (z. B. `resetTo` statt `syncWindows`).

---

## 8. §-Asks an den PO

| # | Frage | Meine Empfehlung |
|---|---|---|
| 1 | Beim Kacheln: darf ein Inhaltsfenster unter 266 dp gedrückt werden (bis zur 176-dp-Klemme), oder soll `tile()` lieber die Zeilenzahl reduzieren? | **Drücken bis 176.** Es ist das Verhalten der Breitenachse; Zeilenreduktion wäre ein neuer Mechanismus. |
| 2 | `WindowManagerState.kt:407` — Fallback erzeugt Inhaltsfenster mit 160 dp Breite statt 320. Eigenes Ticket? | **Ja**, eigenes kleines Ticket. Gleiche Asymmetrie, andere Achse; nicht in CYP-338 mischen. |
| 3 | Soll `MIN_WINDOW_HEIGHT` (120) für Nicht-Inhaltsfenster bleiben? | **Ja, unverändert.** Sie haben keinen Composer zu verlieren. |

---

## 9. Self-Validation

- **Eine Zahl, vollständig hergeleitet:** `56 + 48 + 72 + 90 = 266`. Jeder Summand hat eine Code- oder
  Bytecode-Quelle; **kein** Summand ist geschätzt.
- **Alle fünf Fragen beantwortet:** 1 → §2.1 · 2 → §2.2 · 3 → §3 · 4 → §4 · 5 → §5.
- **Die Zahl ist gegen beide Fehlrichtungen geprüft:** zu knapp → §3 zeigt, dass 176 dp die harte Grenze ist
  und 266 nur 90 dp Transkript gibt (drei Zeilen, kein Luxus); zu großzügig → §5 zeigt 158 dp Luft auf dem
  kleinsten Host, auf dem Fenster überhaupt existieren.
- **Der Composer ist in jedem Pfad enthalten** (§6 listet alle sechs), und die 176-dp-Invariante gilt
  klassenweit, nicht nur beim Erzeugen.
- **Keine richtige Verwendung mitgerissen:** Nicht-Inhaltsfenster behalten 120 dp (§4).
- **Docs-only.** Kein Code geändert.
