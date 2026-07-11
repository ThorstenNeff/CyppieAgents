# CYP-383 — Die „bereit"-Zeile nach Neustart

> Owner: UIUX-Designer · Ticket **CYP-383** · Stand 2026-07-11 · Basis **`origin/develop` = `6b9f89bd`** · Scope **WASM-App**
> Docs-only. Klein: **kein Design-System-Umbau**, ein vorhandener Zeilentyp, **1 neuer Key**, 0 neue Komponenten.

---

## 0. Der eine Satz, an dem alles hängt

> **„Bereit" darf nur eine Beobachtung sein, nie das Echo des Neustart-Befehls.**

Es gibt zwei Kandidaten, die „der Agent läuft wieder" bedeuten könnten, und sie meinen Verschiedenes:

| Signal | heißt in Wahrheit | taugt als „bereit"? |
|---|---|---|
| Lifecycle `RUNNING` (`AgentLifecycleState`) | „**wir haben** einen Neustart **befohlen**" | **nein** |
| stream-json `SystemEvent(subtype = init)` | „der Prozess **hat** sich initialisiert und meldet Modell + Werkzeuge" | **ja** |

Die Titelzeile zeigt heute „Läuft" aus dem **ersten** Signal — und genau deshalb fühlt der Moment sich
„merkwürdig" an: **die Anzeige sagt Erfolg, bevor irgendetwas bestätigt hat, dass der Prozess Eingaben
annimmt.** Das ist wörtlich der Befund aus CYP-351 (`status[agentId] = RUNNING` = Befehlsgedächtnis) und die
Wurzel B des Beobachtungs-Audits: *ein Stellvertreter, der aus dem eigenen Handeln abgeleitet ist, irrt immer
zugunsten der Beruhigung.*

**Die „bereit"-Zeile heilt das nur, wenn sie am zweiten Signal hängt.** Hängt sie am ersten, verschiebt sie die
Lüge bloß von der Titelzeile in die Konsole.

**Das ist die eine Auflage an Backend2:** Der Trigger ist das **erste beobachtete Ereignis, das beweist, dass
der Prozess Eingaben annimmt** — heute der `init`-`SystemEvent` (`StreamJsonMapper.kt:44`). **Nicht** die
`restartPending → RUNNING`-Transition. Wenn der reale „ready"-Punkt ein anderes Ereignis ist (Resume-Pfad,
CYP-356), benennt Backend2 es — die Regel bleibt: **Observation, nicht Kommando.**

---

## 1. Das Vehikel steht schon — Reuse, keine Erfindung

`AgentEvent.Notice` ist der vorhandene System-/Lifecycle-Zeilentyp; sein KDoc nennt ausdrücklich *„session
started, agent stopped, key changed"*. Gerendert von `NoticeRow` (`AgentWindow.kt:829`):

- Farbe **`onSurfaceVariant`** (8,69 : 1 hell / 9,80 : 1 dunkel — leiser als Assistant-Text `onSurface` 15,6 : 1,
  aber klar über 4,5 : 1), Stil `labelSmall`, volle Breite.
- a11y: `clearAndSetSemantics { contentDescription = "Hinweis: <text>" }` (`a11y_notice`).
- **Dedup über `id`** in `foldEvent` (`TranscriptFolding.kt:56`): dieselbe id → **eine** Zeile, auch wenn der
  Server die Historie bei Reconnect erneut abspielt. Das ist das „**einmal**" aus dem Ticket — geschenkt, wenn
  die id stabil an das (Neu-)Start-Ereignis gebunden ist (`idOf(event.uuid)`, schon so).

> **Es wird also keine neue Zeile erfunden — die vorhandene `init`-Notice wird zur „bereit"-Zeile.** Heute
> erzeugt `init` bereits eine Notice, nur mit technischem Wortlaut („Session gestartet"). Zwei Zeilen für ein
> Ereignis wären falsch; die richtige Lösung ist, **die eine** Zeile menschlich zu formulieren.

---

## 2. Microcopy (+ 1 Key, der zugleich ein Loch stopft)

**Heute steht der Text hartkodiert und einsprachig im Code:**

```kotlin
// StreamJsonMapper.kt:133 — kein stringResource, deutsches Literal in commonMain
private fun systemNotice(e: SystemEvent): String =
    "Session gestartet" + (e.model?.let { " · $it" } ?: "")
```

Der englische Build zeigt hier **Deutsch** — dieselbe Lücke wie im `MessageComposer` (CYP-350 §8). CYP-383
schließt sie mit **einem** Key:

| Key | DE | EN |
|---|---|---|
| `agent_ready_notice` | **„Agent bereit"** | **„Agent ready"** |

Modell-Suffix wie gehabt: `"<agent_ready_notice> · <model>"`. Der Mapper ist reiner Kotlin-Code ohne Compose;
den `stringResource`-Lookup gibt der Aufrufer (UI-Schicht) hinein — **Backend2/Developer flaggen die
Key-Landung im selben Commit wie die Impl** (geteilter Key, sonst bricht der `strings.xml`-Paritäts-Check).

**Warum „Agent bereit" und nicht „Session gestartet":**

- **„bereit" ist die Zusage, die das Ticket verlangt** — sie beschreibt den Zustand des Nutzers („du kannst
  jetzt schreiben"), nicht ein internes Ereignis. Sie knüpft an den Composer-Platzhalter „Nachricht an den
  Agenten…" an.
- Sie ist **für Kaltstart und Neustart gleich wahr.** Ein `init` nach frischem Prozess und ein `init` nach
  Neustart bedeuten beide „ab jetzt nimmt der Agent Eingaben an". Eine Zeile deckt beide Fälle — kein
  Zustands-Tracking im Backend nötig.

### 2.1 Optional (PO entscheidet): Neustart eigens benennen

Wenn Backend2 den Neustart vom Kaltstart **unterscheiden kann** (es kennt den zuletzt gesendeten
Restart-Befehl), ließe sich der Neustart-Fall eigens benennen:

| Key | DE | EN |
|---|---|---|
| `agent_restarted_ready_notice` | „Agent neu gestartet · bereit" | „Agent restarted · ready" |

**Ich empfehle es nicht für den MVP.** Es kostet einen zweiten Key **und** eine Korrelation im Mediator
(„welches `init` gehört zu welchem Restart-Befehl?") — genau die Art Zustands-Stellvertreter, die schiefgeht,
wenn zwei Neustarts sich überlappen. Der Gewinn (ein Wort mehr Kontext) wiegt das nicht auf. **Eine Zeile,
„Agent bereit", für beide Fälle.** Aber es ist eine Produktentscheidung, keine Designzwangslage — deshalb steht
sie hier.

---

## 3. Platzierung & Abgrenzung

- **Wo:** an der Stelle im Event-Strom, die der Server-Zeitstempel des `init`-Ereignisses vorgibt (`tsMs` von
  der Leitung, CYP-335) — also **nach** dem Neustart, **vor** dem nächsten Assistant-Turn. Chronologisch
  korrekt, ohne Render-Stempel, überlebt Replay.
- **Abgrenzung zu Assistant-Zeilen:** trägt bereits. Assistant-Text ist `onSurface`/normal; die Notice ist
  `onSurfaceVariant`/`labelSmall` — **leiser, kein Sprecher-Turn.** Der Screenreader sagt „Hinweis: Agent
  bereit", nicht den Text nackt. Keine Verwechslung mit einer Antwort des Agenten.
- **Kein neuer Glyph, keine neue Farbe.** Zwei Gründe: (a) der PO schließt einen Design-System-Umbau aus; (b)
  ein Glyph wäre ein **zweiter** Bedeutungsträger neben dem Wort — dieselbe Ablehnung wie beim `outline`-Guard
  (CYP-337) und beim Overflow-Menü (CYP-350). Das Wort „bereit" trägt die Bedeutung allein; die Notice-Stilistik
  macht sie leise. **Das reicht, und es ist konsistent mit jeder anderen System-Zeile.**

> **Ehrlich benannter Rest:** Die „bereit"-Notice und die Fehler-Notice („Verbindung zum Agenten verloren")
> sehen **identisch** aus — beide `onSurfaceVariant`. Das ist heute schon so und **kein** Regressionsrisiko von
> CYP-383; die Unterscheidung trägt das Wort, nicht die Farbe (CYP-351-Prinzip: Farbe nie alleiniger Träger).
> Ein eigener Fehler-Ton für Fehler-Notices wäre ein sinnvolles **eigenes** Ticket — nicht dieses.

---

## 4. Abnahme

1. **Trigger ist eine Beobachtung, kein Kommando** *(der eigentliche Test)*: Die Zeile erscheint, wenn der
   Session-`init`-/ready-Event eintrifft — **nicht**, wenn `lifecycleState` auf `RUNNING` wechselt.
   **Mutationsprobe:** die Zeile an die `RUNNING`-Transition hängen ⇒ sie erschiene, obwohl der Prozess nie ein
   `init` gesendet hat (Prozess tot, Kommando abgesetzt) ⇒ **muss den Test rot machen.** Test-Aufbau:
   `lifecycleState = RUNNING` setzen **ohne** `init`-Event ⇒ **keine** „bereit"-Zeile.
2. **Genau einmal:** zwei `init`-Events mit derselben id (Reconnect-Replay) ⇒ **eine** Zeile. Zwei *echte*
   Neustarts (verschiedene ids) ⇒ zwei Zeilen. **Mutationsprobe:** konstante id ⇒ der zweite Neustart zeigt
   keine Zeile ⇒ rot.
3. **Lokalisiert:** DE-Build „Agent bereit", EN-Build „Agent ready" — kein hartkodiertes Literal mehr in
   `StreamJsonMapper`. **Mutationsprobe:** Literal zurück ⇒ EN-Build zeigt Deutsch ⇒ rot (Paritäts-/Quell-Guard).
4. **Abgrenzung:** die Zeile rendert als `NoticeRow` (a11y „Hinweis: …"), nicht als Assistant-Turn.

**Test 1 ist der einzige, der die Ehrlichkeit prüft.** Die anderen drei prüfen die Anzeige.

---

## 5. Was Backend2 von mir braucht (die geteilte Naht)

- **Das reale Bereitschafts-Ereignis benennen** — `init` oder, im Resume-Pfad (CYP-356), das Äquivalent, das
  beweist, dass stdin bedient wird. Regel: **Observation, nicht Kommando** (§0).
- **Den Key `agent_ready_notice` mit der Impl im selben Commit landen** (geteilter Key → sonst bricht der
  `strings.xml`-Paritäts-Check; ich liefere den Key-Text, das Timing gehört zur Impl).
- Falls der Wortlaut je Fall unterschiedlich sein soll (§2.1): sagen, **ob** der Mediator Neustart von Kaltstart
  sicher trennen kann. Wenn nicht sicher → eine Zeile für beide (mein Default).

---

## 6. Self-Validation

- **Reuse statt Erfindung:** `AgentEvent.Notice` + `NoticeRow`, der Zeilentyp, den `init` heute schon nutzt.
  0 neue Komponenten, 0 neue Farben, 0 neue Glyphen.
- **Die Kernfrage ist Disclosure-Ehrlichkeit, nicht Microcopy:** „bereit" an die Beobachtung binden, nie an das
  Befehlsgedächtnis. Ohne diese Auflage verschiebt die Zeile die Unwahrheit nur von der Titelzeile in die
  Konsole. Test 1 nagelt es fest.
- **1 neuer Key**, und er stopft zugleich ein hartkodiertes deutsches Literal (`StreamJsonMapper.kt:133`) —
  Nebengewinn, gemeldet.
- **„einmal" ist geschenkt:** `foldEvent`-id-Dedup, sofern die id an das Start-Ereignis gebunden bleibt.
- **Zwei Produktentscheidungen offen gelassen**, nicht erfunden: Neustart-eigener Wortlaut (§2.1) und ein
  eigener Fehler-Ton für Fehler-Notices (§3, eigenes Ticket).
- **Geteilte-Key-Naht geflaggt** (§5) — der Key landet mit der Impl, nicht davor.
- **Docs-only.**
