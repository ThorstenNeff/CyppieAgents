# QA-Testplan — CYP-335: Zeitstempel (HH:mm, 24h, lokal) im Agenten-Nachrichtenfenster

> Autor: QA / Test Engineer (Team2) · Stand: 2026-07-09
> Basis: `develop` @ `f8063c1` · Branch: `feature/CYP-335-qa-testplan`
> Scope: **nur die WASM-App (Browser)**; die übrigen Targets müssen grün bleiben.
> Status: **vor dem Code geschrieben.** Der Plan ist gegen die Akzeptanzkriterien gebaut, nicht gegen die
> Implementierung. Die Architekturvorgaben des PO (§0.1) sind eingearbeitet.

---

## 0. Ausgangslage — am Code verifiziert, nicht vermutet

Alles unten ist an `f8063c1` gelesen. Die Baseline habe ich laufen lassen, bevor ich irgendetwas behaupte:

```
$ ./gradlew :app:shared:wasmJsBrowserTest        →  BUILD SUCCESSFUL in 2m 38s
   21 Testklassen, 141 Tests, 0 Fehler, 0 übersprungen   (Headless-Chrome)
```

### 0.1 Architektur, wie vom PO festgelegt

* `AgentWsClient.events` liefert künftig `Flow<StoredAgentEvent>` statt `Flow<StreamJsonEvent>`.
* Der Stempel wird **nach** dem Mapping angehängt; `StreamJsonMapper.map(...)` bleibt **zeitagnostisch**.
* `map()` ist ein **Fan-out**: aus *einem* `tool_result`-Wire-Event entstehen **zwei** `AgentEvent`s
  (`StreamJsonMapper.kt:82-97`) — beide tragen denselben `tsMs`.
* `UserTurn` und der `conn-error`-`Notice` bekommen die Zeit aus einem injizierten `nowMs: () -> Long`
  am `AgentViewModel`.
* Der `conn-error`-`Notice` bekommt eine **eindeutige** id (statt der heutigen Konstante).

**Die wichtigste Folgerung daraus — sie entscheidet, wo getestet wird:** Wenn der Stempel nach dem Mapping
angehängt wird, trägt der aufgelöste `ToolCall` beim Verlassen des Mappers die **Endzeit** (die `tsMs` des
`tool_result`-Wire-Events). Damit die Zeile trotzdem die **Startzeit** zeigt, **muss `foldEvent` die Regel
„erste Zeit gewinnt" besitzen.** Es gibt keine zweite Stelle mehr, an der sie leben könnte.

Das beantwortet meine ursprüngliche offene Frage O1 — sie ist geschlossen, bevor sie jemanden Zeit gekostet hat.
Und es macht **T2** zum wichtigsten Test dieses Tickets.

### 0.2 Was ich im Code gefunden habe, das die AC-Liste nicht nennt

#### F1 — `foldEvent` ist asymmetrisch; das Loch sitzt bei `ToolCall`

```kotlin
// TranscriptFolding.kt:24 — kopiert von `existing` → tsMs bliebe automatisch erhalten
it[idx] = existing.copy(text = existing.text + event.text, complete = event.complete)

// TranscriptFolding.kt:36 — ersetzt die Zeile KOMPLETT → Default ist last-wins
if (idx >= 0 && current[idx] is AgentEvent.ToolCall) { current.toMutableList().also { it[idx] = event } }
```

`AssistantText` erfüllt **AC 4 by construction**. Der Test dort ist eine Regressionsklammer, kein Fehlerfänger.
`ToolCall` verliert die Startzeit — **AC 5 ist der wahrscheinliche Bruch.** (Deckungsgleich mit dem Review-Befund
des PO.)

#### F2 — Der beschriebene Reconnect-Fehler kann so nicht auftreten

```kotlin
// AgentWsClient.kt:90 — Cursor-Dedup: alles ≤ lastSeq wird verworfen
if (stored.seq > lastSeq) { lastSeq = stored.seq; … }

// AgentWsClient.kt:120 — `since` fehlt NUR beim ERSTEN Connect
val since = if (lastSeq >= 0L) "&since=$lastSeq" else ""
```

Bei einem **Reconnect innerhalb der Sitzung** liefert der Server ab dem Cursor, und was doppelt kommt, verwirft
der Client. Der `_transcript` wird beim Drop nicht geleert. **Ein reiner Mid-Session-Reconnect-Test wäre
vakuum-grün.**

Der Zustand, in dem die Historie wirklich vollständig über die Leitung kommt, ist der **erste Connect** — also
**Seitenreload / neu geöffnetes Fenster**. Genau dort würde eine Wall-Clock die *ganze* Historie auf „jetzt"
stempeln. **T4 fährt deshalb den Erst-Connect als Fehlerfänger**, den Drop/Reconnect nur als Dedup-Klammer.

#### F3 — `formatTs` ist nicht nur UTC; es fehlt der Negativ-Guard

```kotlin
// eventlog/EventVisuals.kt:145 — doppeltes Modulo, mit Absicht
val dayMs = ((ts % 86_400_000L) + 86_400_000L) % 86_400_000L
```

Wiederverwendung wäre doppelt falsch (Zeitzone **und** Format `HH:MM:SS.mmm`). Interessanter: der neue
Formatierer rechnet `epochMs + offsetMs`, und dieser Wert kann durch einen **negativen Offset über die
UTC-Tagesgrenze** rutschen (`00:30` UTC bei `−02:00` → lokal `22:30` am Vortag). Ohne dasselbe doppelte Modulo
liefert `(epochMs + offset) % 86_400_000` einen **negativen** Wert; `padStart` auf `"-1"` ergibt Unsinn.
**Diese Fehlerklasse steht in keinem AC.** T1 deckt sie ab.

#### F4 — Ein Default-Wert für `tsMs` macht den Fehler unsichtbar (Design-Warnung, vor dem Code)

Bekommt `AgentEvent.tsMs` einen Default (`= 0L`), dann
* kompiliert `StubAgentSession` unverändert und rendert im Demo-Pfad überall `00:00` bzw. `01:00`,
* rutscht jede vergessene Stempel-Stelle **still** durch — falscher Wert statt Compile-Fehler.

**Empfehlung an Developer5: kein Default.** `AgentEvent` ist `sealed` → ein `withTs(t) = when (this) { … }` ist
compiler-erzwungen vollständig, und jede Konstruktionsstelle muss eine Zeit liefern. Das verwandelt eine Klasse
stiller Renderfehler in Buildfehler. Kostet nichts, spart einen QA-Zyklus.

#### F5 — Kleinkram, verifiziert

* `NoticeRow` wird **ohne** Kind-Qualifier getaggt (`AgentWindow.kt:319`) — `Notice` hat keinen `EventKind`.
  Ein Render-Test „alle sechs Zeilenarten" kann die Notice-Zeile nicht per Kind selektieren → §1.
* `AgentViewTags.kt` hat **57 Zeilen**; der genannte Tag an `:51` existiert noch nicht.
* `AgentWsClient` liegt in `agentview/`, nicht in `net/`.
* `AgentWsReconnectTest.frame(seq)` erzeugt `"tsMs":0` für **alle** Frames — als Fixture für CYP-335 wertlos.
* `commonTest` hat `compose.uiTest` (`build.gradle.kts:90`), und `AvatarWasmRenderSmokeTest` (`wasmJsTest`)
  beweist, dass `runComposeUiTest` **im echten Browser läuft**. Das ist der Hebel für §4.

---

## 1. Test-Contract-Delta

Ich brauche **einen** additiven Tag. Ich spezifiziere ihn, statt auf UIUX-Designer2 zu warten:

```kotlin
// AgentViewTags.kt — additiv
/** CYP-335: die Zeitzelle der N-ten Transkript-Zeile. */
fun timestamp(agentId: String, index: Int) = "agent.$agentId.event.$index.ts"
```

Ohne ihn ist die `Notice`-Zeile nicht adressierbar (F5), und eine Textsuche nach `"09:14"` im Semantik-Baum ist
ein kollisionsanfälliger Selektor. **Steht der Vorschlag des Designers, übernehme ich seinen Namen** — der Tag
muss existieren und pro Zeile eindeutig sein, mehr verlange ich nicht.

Zusätzlich: die Zeitzelle braucht eine `contentDescription`, sonst ist der Zeitstempel für Screenreader eine
nackte Zahl (a11y-Parität zu den bestehenden Zeilen).

---

## 2. Die Tests

Acht benannte Tests. Je: **Ort · welches AC · was er pinnt · welcher konkrete Fehler rot wird.**

### T1 — `TimestampFormatTest` (AC 7) · `commonTest/…/agentview/`

Reiner Formatierer `formatHhMm(epochMs: Long, offsetMs: Long): String`. Offset ist **Parameter**, nicht Umgebung
→ deterministisch, und **läuft dadurch auch im Browser** (§4).

| Fall | Eingabe | Erwartet | Fängt |
|---|---|---|---|
| Mitternacht | `00:03` UTC, Offset 0 | `00:03` | fehlende Stunden-Null |
| Einstellige Std+Min | `09:04` UTC, Offset 0 | `09:04` | `9:4` |
| Positiver Offset | `12:00` UTC, `+02:00` | `14:00` | Offset ignoriert |
| Negativer Offset | `12:00` UTC, `−05:00` | `07:00` | Vorzeichenfehler |
| **Halbstunden-Offset** | `12:00` UTC, `+05:30` | `17:30` | Offset in vollen Stunden gerechnet |
| **Tagesgrenze rückwärts** | `00:30` UTC, `−02:00` | `22:30` | **F3**: negatives Modulo |
| **Tagesgrenze vorwärts** | `23:30` UTC, `+02:00` | `01:30` | Überlauf > 24 h |
| Epoch 0 | `0`, Offset 0 | `00:00` | — |

Die drei fett markierten Zeilen stehen nicht im Ticket.

### T2 — `foldEvent`: parallele Tool-Calls, vertauschte Abschlussreihenfolge (AC 4 + AC 5) · `commonTest/…/TranscriptFoldingTest.kt`

**Der Test, der beißt.** Aufbauend auf dem Review-Befund des PO, mit einer Verschärfung:

Der PO schlägt A und B mit **gleicher** Startzeit (14:03) vor. Dann ist die Pro-Zeile-Assertion nicht
trennscharf — beide Zeilen zeigen dieselbe Zahl, und ein Test kann A's Stempel nicht von B's unterscheiden.
Ich gebe ihnen **verschiedene** Startzeiten. Das erhält die Monotonie-Aussage und macht jede Zeile einzeln
prüfbar:

| Ereignis | Zeit |
|---|---|
| ToolCall **A** startet (RUNNING) | `14:03` |
| ToolCall **B** startet (RUNNING) | `14:04` |
| **B** wird fertig (OK) → `ToolCall B` + `Result B` | `14:05` |
| **A** wird fertig (OK) → `ToolCall A` + `Result A` | `14:07` |

Erwartete gefaltete Liste, **in Erstauftritts-Reihenfolge**:

| # | Zeile | Zeit | Warum |
|---|---|---|---|
| 0 | ToolCall A | `14:03` | Start, nicht Ende |
| 1 | ToolCall B | `14:04` | Start, nicht Ende |
| 2 | Result B | `14:05` | Ergebnis trägt legitim das **Ende** |
| 3 | Result A | `14:07` | dito |

Assertions:
1. `ToolCall A == 14:03` und `ToolCall B == 14:04` — die Startzeit überlebt RUNNING→OK.
2. `Result B == 14:05`, `Result A == 14:07` — die **Endzeit geht nicht verloren**, sie wandert in die Result-Zeile.
3. **Die Zeitspalte läuft monoton nach unten** (`14:03 ≤ 14:04 ≤ 14:05 ≤ 14:07`).

Bei `it[idx] = event` (heutiger Stand, last-wins) zeigt Zeile 0 **14:07** und Zeile 1 **14:05** → Assertion 1 und
Assertion 3 fallen **beide**, die Spalte läuft rückwärts. Genau das ist der Fehler, den ein
Ein-Tool-Call-Test nicht sieht.

Dazu, in derselben Datei, die Regressionsklammer:
* `AssistantText("a-1", t=10_000)` + `AssistantText("a-1", t=99_000)` → Zeile trägt `10_000` (heute schon
  erfüllt, F1 — Severity niedrig, aber sie fixiert die Eigenschaft gegen einen späteren „Aufräum"-Patch).

### T3 — Fan-out: ein Wire-Event, zwei `AgentEvent`s, **ein** Stempel (AC 2) · `commonTest/…/StreamJsonMapperTest.kt`

`StreamJsonMapper.map(tool_result)` emittiert **zwei** Events (`StreamJsonMapper.kt:82-97`): den aufgelösten
`ToolCall` und das `Result`. Nach dem Anhängen des Stempels müssen **beide** die `tsMs` **desselben**
Wire-Events tragen.

Fängt: eine Stempel-Schleife, die nur das *erste* (oder nur das *letzte*) Element der Fan-out-Liste stempelt und
das andere auf dem Default stehen lässt — der Fehler, den F4 still macht.

> Hinweis: Weil der Mapper zeitagnostisch ist, prüft dieser Test **die Stempel-Naht** (`MappingAgentSession`),
> nicht den Mapper. Er darf **nicht** erwarten, dass der aufgelöste `ToolCall` hier schon die Startzeit trägt —
> das tut erst `foldEvent` (§0.1). Ein Test, der das verwechselt, macht einen korrekten Patch rot.

### T4 — `AgentWsTimestampReplayTest` (AC 3) · `jvmTest/…/agentview/`

Embedded-Ktor `/ws/agent` wie in `AgentWsReconnectTest`, aber mit **unterscheidbaren** `tsMs` (das heutige
`"tsMs":0` taugt nicht) und einer gefälschten Uhr, die **weit weg** von den Server-Zeiten liegt:

1. **Erst-Connect (kein `since`)** — Server liefert seq 1..3 mit `tsMs = 1_700_000_000_000` / `+60_000` / `+120_000`.
   `nowMs()` liefert `1_800_000_000_000`.
   → Die drei Zeilen tragen **die Server-Zeiten**. *Das ist der Fehlerfänger* (Seitenreload-Pfad, F2).
2. **Drop → Auto-Reconnect (`?since=3`)** — Server schickt 3, 4, 5.
   → seq 3 wird dedupliziert; Zeile 3 trägt **unverändert** ihre ursprüngliche `tsMs`; 4 und 5 ihre eigenen.
3. Kein Zugriff auf die Systemuhr → deterministisch, nicht flaky.

Ohne Schritt 1 ist der Test vakuum-grün. Ohne Schritt 2 fehlt die Dedup-Klammer.

### T5 — Injizierte Uhr, client-synthetisierte Zeilen (AC 6) · `jvmTest/…/agentview/`

* `AgentViewModel(nowMs = { 1_700_000_000_000L })` → `onSend("hallo")` → `UserTurn` trägt exakt diesen Wert.
* Session-Flow wirft → `conn-error`-`Notice` trägt exakt diesen Wert.
* `nowMs` ist ein `() -> Long`, **kein** `Clock`-Interface aus einer neuen Abhängigkeit — sonst holt sich
  CYP-335 kotlinx-datetime herein, das das Ticket ausdrücklich nicht will.

> **Warum `jvmTest`, nicht `commonTest`:** kein `commonTest` instanziiert heute ein `AgentViewModel`, und
> `kotlinx-coroutines-test` ist im `:app:shared`-Build **nicht** als Testabhängigkeit eingetragen (geprüft).
> Die VM-Tests (`AgentHumanTurnEchoTest` u. a.) leben in `jvmTest` und benutzen `runComposeUiTest`. Ich folge
> dem Muster. Verlust: null — T5 und T6 berühren **keine** Plattform-API, nur die injizierte Uhr. Die JVM ist
> hier ein vollwertiger Beweis, keine Analogie.

### T6 — Zweiter Verbindungsverlust: der Zeitstempel, der lügt (AC 6) · `jvmTest/…/agentview/`

Aus dem Review-Befund des PO. Heute: `Notice("conn-error", …)` hat eine **konstante** id und landet im
Dedup-Zweig (`TranscriptFolding.kt:49-51`, `if (idx >= 0) current` → bestehende Zeile bleibt unangetastet).
Mit Zeitstempel behauptet die Zeile beim **zweiten** Verlust weiterhin die Uhrzeit des **ersten**.

Zwei Verbindungsverluste, `nowMs` liefert nacheinander `14:03` und `14:09`.
→ Erwartet: die angezeigte Uhrzeit ist die des **jeweiligen** Ereignisses.

Der Test hält Developer5 dazu an, der Zeile eine eindeutige id zu geben (wie vom PO entschieden).

> **Zweitwirkung, die ich mitprüfe:** eine eindeutige id nimmt die Zeile aus dem Dedup. Bei *n* Verlusten
> entstehen *n* Zeilen. Das ist die gewollte Semantik, aber es ist eine **Verhaltensänderung** — der Test pinnt
> ausdrücklich „zwei Verluste ⇒ zwei Zeilen", damit niemand später die Dedup „repariert" und AC 6 still bricht.

### T7 — `TranscriptTimestampRenderTest` (AC 1) · **`wasmJsTest`** + Spiegel in `jvmTest`

Transkript mit **allen sechs** Zeilentypen, jede mit eigener bekannter `tsMs`. Pro Zeile:
`onNodeWithTag(AgentViewTags.timestamp("backend", i)).assertTextEquals(<erwartet>)`.

Abgedeckt: `UserTurn`, `AssistantText`, `IncomingSystem`, `Notice`, `ToolCall`, `Result`. **Fester Offset**, nicht
`localUtcOffsetMs()` — sonst hängt der Test an der Maschinen-Zeitzone und wird flaky.

> **Warum `wasmJsTest` und nicht `commonTest`:** Der Scope ist der Browser, und `AvatarWasmRenderSmokeTest`
> (CYP-216) belegt, dass `runComposeUiTest` dort **wirklich läuft** — das ist der Präzedenzfall, dem ich folge.
> `commonTest` wäre verlockend (ein Test, alle Targets), zöge den Render-Test aber auch in `androidHostTest` und
> `iosSimulatorArm64Test`, wo **keine** Compose-UI-Test-Runtime verdrahtet ist (`build.gradle.kts` hat Test-Deps
> nur für `commonTest` und `jvmTest` — geprüft). Ein `commonTest`-Render-Test riskiert also, `./gradlew check` auf
> Android rot zu machen, ohne dass am Feature etwas falsch ist. Der `jvmTest`-Spiegel (neben
> `IncomingSystemRenderTest.kt`) kostet wenig und hält den Desktop-Pfad ehrlich.

### T8 — `LocalUtcOffsetBrowserTest` (AC 2, echte Browser-Uhr) · `wasmJsTest/…/`

Der einzige Test, der die umgebungsabhängige `expect`-Naht anfasst. Er kann keinen festen Erwartungswert haben
(die Zeitzone des Test-Chrome ist Umgebung, nicht Eingabe). Deshalb ein **Orakel-Test**, gültig in *jeder* Zone:

```
formatHhMm(t, localUtcOffsetMs(t))  ==  pad2(Date(t).getHours()) + ":" + pad2(Date(t).getMinutes())
```

Der Browser ist seine eigene Referenz. Fängt:
* **Vorzeichenumkehr.** `Date.getTimezoneOffset()` liefert die Minuten, die man zur **lokalen** Zeit addiert, um
  UTC zu erhalten (Berlin im Sommer: `-120`). Also `localUtcOffsetMs = -getTimezoneOffset() * 60_000`. Ein
  vergessenes Minus **ist** der „zwei Stunden falsch"-Bug.
* Versehentliche UTC-Rechnung (fällt in jeder Nicht-UTC-Zone).
* Halbstunden-Zonen (`+05:30`).

**Wirksam nur, wenn die Test-Zeitzone ≠ UTC ist** — siehe §4.

---

## 3. Gate

| # | Kommando | Warum |
|---|---|---|
| G1 | `./gradlew check` | alle Targets grün; die `expect`-Deklaration braucht **fünf** `actual`s (jvm/js/wasmJs/android/ios) |
| G2 | `./gradlew :app:shared:wasmJsBrowserTest` | echtes Headless-Chrome; fängt skiko-/wasm-Laufzeitfehler, die sauber kompilieren (CYP-216) |
| G3 | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` + Screenshot | AC 9; grüne Unit-Tests sind notwendig, nicht hinreichend |

G1 und G2 sind **beide** nötig: `check` beweist den Browser nicht, `wasmJsBrowserTest` beweist die übrigen
Targets nicht. Screenshot hänge ich an CYP-335 an.

Baseline vor der Arbeit (damit ein späteres Rot zuordenbar ist): `wasmJsBrowserTest` auf `f8063c1` =
**141 Tests, 0 Fehler**.

---

## 4. Die Zeitzonen-Frage — die ehrliche Antwort

> Der PO fragt: Deckt der Plan die Browser-Zeitzone wirklich ab, oder bleibt sie ungetestet?

**Teils, teils — und der ungedeckte Teil ist genau der gefährliche. Hier ist die Trennlinie.**

### 4.1 Was der Browser wirklich prüft (belegt, nicht vermutet)

`commonTest` hängt an `compose.uiTest` (`build.gradle.kts:90`), und `wasmJsBrowserTest` führt commonTest mit aus
— belegt durch den Baseline-Lauf: `CommReducerTest` liegt in `commonTest` und erscheint in den
Browser-Testergebnissen. `AvatarWasmRenderSmokeTest` (CYP-216) beweist zusätzlich, dass **`runComposeUiTest` im
echten Chrome läuft**.

Daraus folgt, stärker als ich es vor dem Lauf behauptet hätte:

* **T1 läuft im Browser** und ist dort deterministisch, weil der Offset ein **Parameter** ist. AC 7 ist im
  Browser abgedeckt, nicht nur auf der JVM.
* **T2 und T3 laufen im Browser** (`commonTest`, reine Logik).
* **T7 (Render) läuft im Browser** als `wasmJsTest`, dem Präzedenzfall `AvatarWasmRenderSmokeTest` folgend.
  AC 1 ist damit im echten Chrome belegt, nicht per JVM-Analogie.
* **T8** prüft die einzige umgebungsabhängige Stelle (`localUtcOffsetMs`, wasmJs-`actual`) gegen den Browser
  selbst.
* **T5 und T6 laufen nur auf der JVM** — bewusst, und ohne Beweiskraftverlust: sie berühren keine
  Plattform-API, nur die injizierte Uhr (Begründung bei T5).

### 4.2 Was NICHT abgedeckt ist — benannte Lücken

1. **T4 läuft nur auf der JVM.** Der Reconnect-Test braucht den embedded Ktor-Server (`jvmTest`-only). AC 3 ist
   damit auf der JVM bewiesen und im Browser **nicht**. Ich halte das für vertretbar: `AgentWsClient` ist
   `commonMain`, die Dedup-/Cursor-Logik ist plattformunabhängig, und die Ktor-Engine ist nicht das, was AC 3
   riskiert. **Aber es ist eine Analogie, kein Beweis** — und ich nenne sie so.
2. **Die Zeitzone des Test-Chrome ist die des Hosts.** Läuft CI in UTC, ist der Offset 0 — und dann ist ein
   **Vorzeichenfehler in `localUtcOffsetMs` unsichtbar**. T8 wäre grün, und die „Deutschland zeigt zwei Stunden
   falsch"-Klasse ginge glatt durch. **Das ist der gefährlichste Punkt des Tickets, und es ist ein
   Konfigurations-, kein Testproblem.** Gegenmaßnahme: `TZ` für den `wasmJsBrowserTest`-Task pinnen.
   → **empirisch geprüft, Ergebnis in §4.3.**
3. **DST-Wechsel** ist per Ticket außerhalb des Scopes. Ich teste ihn nicht und behaupte nicht, dass er
   funktioniert. `localUtcOffsetMs(atEpochMs)` behandelt historische Stempel *im Prinzip* korrekt; ungetestet
   bleibt es trotzdem.
4. **Die iOS-/Android-`actual`s werden nicht ausgeführt** (kein Device-Run in Team2s Scope). Sie müssen nur
   kompilieren. Offene Kante — jemand muss sie tragen, ich tue es nicht.

### 4.3 Empirischer Befund zum TZ-Pinning — **geprüft, nicht angenommen**

Ich habe einen Wegwerf-Test in `wasmJsTest` gesetzt, der `new Date().getTimezoneOffset()` und
`Intl.DateTimeFormat().resolvedOptions().timeZone` aus dem laufenden Karma-Chrome meldet, und den
`wasmJsBrowserTest`-Task zweimal mit unterschiedlicher `TZ` gestartet:

```
$ TZ=Asia/Kolkata ./gradlew :app:shared:wasmJsBrowserTest --tests '*TzProbeTest*'
   TZ-PROBE offsetMinutes=-330  resolvedTimeZone=Asia/Calcutta

$ TZ=UTC          ./gradlew :app:shared:wasmJsBrowserTest --tests '*TzProbeTest*'
   TZ-PROBE offsetMinutes=0     resolvedTimeZone=UTC
```

(Der Probe-Test ist wieder entfernt; er war ein Spike, kein Artefakt.)

**Zwei Aussagen, beide belegt:**

1. **`TZ` propagiert.** Die Umgebungsvariable des Gradle-Aufrufs erreicht den von Karma gestarteten
   Chrome-Prozess. Ein Pinning ist also **möglich und billig** — kein Karma-Config-Hack nötig.
   `Asia/Kolkata` liefert `−330` Minuten, d. h. **UTC+05:30**: fängt Vorzeichenfehler **und**
   Halbstunden-Offset in einem Wert. Genau darum dieser Vorschlag.
2. **In UTC ist der Bug unsichtbar.** `offsetMinutes=0` — jede Vorzeichenumkehr in `localUtcOffsetMs`
   rechnet `-0 == 0` und **T8 wäre grün**. Läuft CI ohne Pinning (Default auf den meisten Runnern: UTC),
   ist der namensgebende Fehler des Tickets **strukturell untestbar**.

Damit ist O3 keine Geschmacksfrage mehr, sondern die Bedingung dafür, dass T8 überhaupt etwas beweist.
Ohne Pinning muss ich T8 im Abnahmebericht als **wirkungslos** ausweisen — eine grüne Zusicherung, die nichts
zusichert, ist genau das, was hier niemand will.

**Konkreter Vorschlag** (Developer5 oder ich, wie der PO es routet) — in `app/shared/build.gradle.kts`:

```kotlin
tasks.named("wasmJsBrowserTest") {
    // CYP-335: nicht-UTC + Halbstunden-Offset, damit ein Vorzeichenfehler in localUtcOffsetMs sichtbar wird.
    // In UTC (CI-Default) ist der Offset 0 und der Test wirkungslos. Empirisch verifiziert: TZ erreicht Chrome.
    environment("TZ", "Asia/Kolkata")
}
```

Zu prüfen bleibt, ob `environment(...)` auf dem Task dasselbe leistet wie die Prozess-Umgebung — der Beleg oben
gilt für die **Aufruf**-Umgebung. Das verifiziere ich, sobald der Task angefasst wird; falls nicht, ist der
Fallback ein `TZ=…`-Präfix in der CI-Zeile. **Ungeprüft behaupte ich es nicht.**

---

## 5. Offene Punkte — Entscheidung durch den PO

| ID | Frage | Status |
|---|---|---|
| ~~O1~~ | Wo lebt „erste Zeit gewinnt"? | **Geschlossen** durch §0.1: Stempel nach dem Mapping ⇒ nur `foldEvent` kann es. T2 pinnt das. |
| ~~O2~~ | Verhalten des `conn-error`-`Notice` beim zweiten Verlust? | **Geschlossen**: eindeutige id, Zeit des jeweiligen Ereignisses. T6 pinnt das — **inklusive** der Zweitwirkung „n Verluste ⇒ n Zeilen". |
| **O3** | Wird `TZ` für `wasmJsBrowserTest` gepinnt (Vorschlag: `Asia/Kolkata`, +05:30 — fängt Vorzeichen **und** Halbstunde)? | **offen, aber empirisch entschieden**: TZ propagiert bis in den Karma-Chrome (`-330`), in UTC ist der Offset `0` und T8 wirkungslos (§4.3). Ohne Pinning weise ich T8 im Abnahmebericht als wirkungslos aus. |
| **O4** | Tag-Name der Zeitzelle: mein `AgentViewTags.timestamp(agentId, index)` oder der Vorschlag von UIUX-Designer2? | **offen** — Test-Contract ist eine API; ich brauche *einen* Namen, nicht *meinen*. |
| **O5** | Bekommt `AgentEvent.tsMs` einen Default-Wert? | **Empfehlung: nein** (F4). Ein Default verwandelt Compile-Fehler in stille Renderfehler. |

---

## 6. Erwartete Befunde, vorab nach Severity — Prognose, keine Feststellung

| Severity | Vermutung | Test |
|---|---|---|
| **Hoch** | `ToolCall` verliert beim RUNNING→OK die Startzeit (`it[idx] = event`); bei parallelen Calls läuft die Zeitspalte rückwärts | **T2** |
| **Hoch** | Vorzeichenfehler bei `Date.getTimezoneOffset()` → 1–2 h falsch, **in UTC-CI unsichtbar** | **T8 + O3** |
| **Mittel** | Wall-Clock statt `stored.tsMs` ⇒ Historie nach Reload auf „jetzt" gestempelt | T4 (Phase 1) |
| **Mittel** | Fan-out: nur eines der zwei Events aus `tool_result` wird gestempelt | T3 |
| **Mittel** | `conn-error` zeigt beim zweiten Verlust die Zeit des ersten | T6 |
| **Mittel** | Negatives Modulo an der Tagesgrenze bei negativem Offset | T1 |
| **Niedrig** | Fehlende führende Null (`9:4`) | T1 |
| **Niedrig** | `AssistantText` verliert die Erst-Zeit (heute by construction erfüllt) | T2 |
| **Niedrig** | `Notice`-Zeile ohne Zeitstempel, weil per Kind nicht adressierbar | T7 + neuer Tag |

---

## 7. Ablauf

1. Plan an den PO. **Erledigt, vor dem Code.**
2. O3–O5 klären. T7/T8 hängen an O3 und O4 — **vor** dem Merge, nicht danach.
3. Developer5 liefert → T1–T8 ausschreiben, G1–G3 laufen lassen, Screenshot an CYP-335.
4. Befunde als `Bug` in `CYP` anlegen, mit CYP-335 verknüpfen, Severity + konkreter Fix.
5. Nach dem Fix **re-verifizieren, dass genau der Befund geschlossen ist** — nicht nur, dass die Suite grün ist.
