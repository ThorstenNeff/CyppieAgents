# Beobachtung vs. Ableitung — Audit der angezeigten Werte (WASM-App)

> Owner: UIUX-Designer · Stand 2026-07-10 · Basis **develop `5c79a79`** · Scope **WASM-App**, nur **angezeigte** Werte
> Docs-only. Nichts angefasst.
> Anlass: die Fehlerklasse, die aus CYP-336 und CYP-346 hervorging.
> **Ticket-Zuordnung offen:** provisorisch an **CYP-346** gehängt (dort steht der Klassen-Satz). PO möge umhängen.

---

## 0. Ergebnis vorweg

**Die dritte und die vierte Instanz sind gefunden. Die dritte ist die schwerste.**

> **Der Lifecycle-Status eines Agenten — der grüne Punkt und „Läuft" — ist keine Beobachtung des Prozesses,
> sondern ein gemerkter Eintrag in einer Map, der beim erfolgreichen Spawn geschrieben wurde.**
> Stirbt der `claude`-Prozess, **schreibt niemand ihn zurück.** Der Punkt bleibt grün.

Die Wahrheit existiert: der Prozesstod wird beobachtet und als `process.exit`-Event (Severity WARN)
protokolliert. Sie wird nur nicht dorthin geschrieben, wo der Operator sie liest.

Der Rest der Anwendung ist **bemerkenswert ehrlich** (§4). Das ist kein Freibrief, sondern der Grund, warum
dieser eine Befund so scharf sticht: die Konvention „fail-closed durch Absenz" wird überall sonst
durchgehalten — `null ≠ 0` bei Token-Zahlen, kein Phantom-Provider, kein `*` für einen ungesehenen Agenten.
Genau **eine** tragende Anzeige weicht ab.

| | Befund | Schaden | Einstufung |
|---|---|---|---|
| **B1** | Lifecycle „Läuft" ist ein Flag, keine Beobachtung | Operator wartet auf einen toten Agenten — **und der Start-Knopf ist deshalb ausgegraut** | 🔴 **Ticket** (CYP-351) |
| **B2** | Der Report zählt `log.dropped`-**Meldungen** statt verworfener **Ereignisse** | 47 verlorene Ereignisse lesen sich als „1 Event verworfen" — und zwar **am stärksten untertrieben, wenn die Lücke am größten ist** | 🔴 **Ticket** |
| N1 | `formatCompactTokens`-KDoc verspricht einen exakten Wert, den es nirgends gibt | keiner (die Kürzung rundet nach unten) | Notiz |
| N2 | `crossproject_status_shared` erfindet `1970` statt „unbekannt" | heute unerreichbar (Server garantiert den Wert) | Notiz |
| N3 | Correlation-Chip zeigt 8 von 36 Zeichen ohne Auslassungszeichen | keiner (der Drilldown nutzt die volle ID) | Notiz |

---

## 1. Die Methode

Ein angezeigter Wert ist eine **Beobachtung**, wenn er so von der Leitung kommt oder vom Nutzer stammt. Er ist
eine **Ableitung**, wenn die Anwendung ihn rechnet, umformt, aggregiert oder formatiert.

Die Frage ist nicht „ist die Ableitung korrekt?", sondern:

> **Sagt die Oberfläche, dass es eine Ableitung ist — oder steht die Wahrheit nur im KDoc?**

**Kalibrierung an den zwei bekannten Instanzen** (beide hatten einen konkreten Schaden):

| | Sah aus wie | War | Schaden |
|---|---|---|---|
| **CYP-336** `formatTs` | Ortszeit | **UTC** | Ein Operator in `Europe/Berlin` datiert einen Vorfall zwei Stunden falsch und korreliert die falschen Ereignisse. |
| **CYP-346** `eventTs − now` | Uhrendifferenz | **Ereignisdauer** | Die eigene, um 09:02 getippte Nachricht trägt `22:14`. |

Beide Male stand die Wahrheit im KDoc (*„UTC wall-clock"*), nie auf der Oberfläche.

**Leitplanken (PO):** nur **angezeigte** Werte; jeder Befund braucht einen **benannten Schaden** — *was glaubt
ein Operator fälschlich, und was tut er deshalb?* Ein Befund ohne Schaden ist eine Notiz, kein Ticket. Diese
Regel ist unten **gegen mich selbst** angewandt: drei Kandidaten, die ich für Funde hielt, sind Notizen
geworden, weil ich den Schaden nicht benennen konnte.

---

## 2. 🔴 B1 — „Läuft" ist ein Gedächtnis, keine Messung

### 2.1 Der Beleg

`LifecycleManager` hält den Zustand in einer Map:

```kotlin
fun runStateOf(agentId: String): AgentRunState? = synchronized(lock) { status[agentId] }   // :95
private fun setRunState(agentId: String, next: AgentRunState) { status[agentId] = next }   // :185
```

`setRunState` wird an **fünf** Stellen gerufen — `:108`, `:118`, `:158`, `:163`, `:182` — und **alle fünf
liegen auf den eigenen Start-/Stop-/Spawn-Pfaden des Managers.** Keine davon reagiert auf den Tod des
Prozesses.

Der Prozesstod **wird** beobachtet:

```kotlin
// connector-core/…/ClaudeCodeSession.kt:113 — stdout endete, der Prozess ist weg
observer?.onProcessExit(agentId, boundSessionId)
```

und der einzige Empfänger schreibt ihn ins Event-Log — **und sonst nirgendwohin**:

```kotlin
// server/…/RecordingSessionObserver.kt:77
override fun onProcessExit(agentId: String, sessionId: String?) {
    if (recorder != null && projector != null) recorder.record(projector.processExit(agentId, sessionId, null))
}
```

`RecordingSessionObserver` wird ohne jede Referenz auf den `LifecycleManager` konstruiert
(`RecordingSessionObserver.kt:39/41`). Es gibt **keinen** zweiten Observer, keinen Composite, keinen Watchdog,
der `RUNNING` revidiert. Der `StallDetector` ignoriert Lifecycle-Events ausdrücklich
(`scanner/StallDetector.kt:66`).

Das Ergebnis erreicht die UI unverändert: `/ws/lifecycle` und der Snapshot speisen sich aus derselben Map,
und `StatusIndicator` (`AgentWindow.kt:243`) malt daraus den Punkt und das Wort.

### 2.2 Der Schaden — die Kette

Ein Agent stirbt (Absturz, OOM, abgelaufener API-Key, `kill`). Der Operator:

1. **sieht den grünen Punkt und „Läuft"** — die Anzeige, der er am meisten vertraut;
2. **tippt eine Nachricht.** Sie erscheint **sofort im Transkript**: `onSend` echoed den `UserTurn`
   **bedingungslos** und ruft erst danach `session.sendMessage` — ein fire-and-forget ohne Rückgabe
   (`AgentViewModel.kt:153–158`). *Ein toter Agent sieht damit exakt aus wie ein zuhörender.*
3. **wartet.** Es kommt nichts. Kein Hinweis im Transkript — `onProcessExit` erzeugt keine `Notice`.
4. **sucht die Ursache und findet keine.** Der Fenster-Badge bleibt aus: er hängt an `deriveStatus(transcript)`
   (`AgentStatus.kt:21`), und das letzte Transkript-Element ist der eigene, gerade echoete `UserTurn` → `IDLE`,
   nicht `ERROR`.
5. **kann den Agenten nicht neu starten.** Der Start-Knopf ist **ausgegraut**:
   ```kotlin
   enabled = canControl && state != AgentLifecycleState.RUNNING   // AgentWindow.kt:203
   ```
   Der Zustand *sagt* `RUNNING`, also ist Start gesperrt — **die Genesungs-Affordanz wird durch genau die
   Falschbehauptung blockiert, die sie heilen soll.** „Stopp" ist aktiv und wirkt sinnvoll. Dass „Neustart"
   trotzdem funktioniert, verdankt sich CYP-330, wo es aus einem *anderen* Grund für jeden Zustand
   freigeschaltet wurde — ein zufälliger Rettungsanker.
6. Die Antwort liegt derweil im **Event-Log**, einem **anderen Fenster**: `process.exit`, Severity WARN.

**Was der Operator fälschlich glaubt:** „Der Agent arbeitet noch, die Aufgabe ist nur schwer."
**Was er deshalb tut:** wartet, schickt nach, wartet länger — und kann den einen Knopf nicht drücken, der hilft.

> **Das Muster hat hier schon Symptome behandelt.** Der `StallDetector` (CYP-61/63) existiert, weil Agenten
> „still werden". Der Start-Pending-Watchdog (CYP-269) existiert, weil ein Start hängen kann. Beide raten aus
> dem Schweigen, was ein `process.exit` bereits **weiß**. Die Grundwahrheit war da und wurde verworfen.

### 2.3 Die Form der Fehlerklasse, präzise

`status[agentId] = RUNNING` heißt „**wir haben ihn gestartet, und niemand hat ihn hier gestoppt**".
Angezeigt wird es als „**er läuft**". Das ist derselbe Kategoriewechsel wie bei den beiden Vorgängern:

| | Behauptung der Oberfläche | Tatsächlicher Inhalt |
|---|---|---|
| CYP-336 | „so spät ist es hier" | „so spät ist es in UTC" |
| CYP-346 | „so weit gehen die Uhren auseinander" | „so lange ist das Ereignis her" |
| **B1** | „der Prozess läuft" | „der Prozess wurde gestartet und hier nicht gestoppt" |

### 2.4 Nicht verwechseln: der Client-**Stub** ist nicht der Produktionspfad

Es gibt eine zweite, sehr ähnlich aussehende Stelle, und sie führt in die Irre:

```kotlin
// agentview/AgentLifecycleApi.kt:63–65 — StubAgentLifecycle
override suspend fun start(agentId: String)   = set(agentId, AgentLifecycleState.RUNNING)
override suspend fun stop(agentId: String)    = set(agentId, AgentLifecycleState.STOPPED)
override suspend fun restart(agentId: String) = set(agentId, AgentLifecycleState.RUNNING)
```

Hier ist der Zustand **wörtlich das zuletzt Befohlene**. Aber `StubAgentLifecycle` wird **nur von drei
Tests** benutzt (`AgentSpawnStartingTest`, `AgentWindowCapabilityBadgeTest`, `AgentLifecycleHeaderTest`) —
**nirgends produktiv**. `app/webApp` übergibt keine Lifecycle-Quelle, also greift der Default aus
`AgentShell.kt:459–462`:

```kotlin
val defaultLifecycleSource = remember(httpClient, cfg) { AgentLifecycleLiveSource(…) }   // /ws/lifecycle + GET /api/agents
```

**Die WASM-App liest den echten Server.** Der Client rendert damit *treu*, was der Server behauptet — und der
Server behauptet `RUNNING` (§2.1). Zwei Konsequenzen:

1. **Der Fix gehört auf den Server.** Repariert man nur den Client, meldet der Server weiterhin `RUNNING` für
   einen toten Agenten, und die Oberfläche zeigt es gewissenhaft an.
2. **Eine Fail-closed-Regel „im Zweifel `UNKNOWN`, Start entsperrt" ist richtig, aber allein nicht
   hinreichend** — denn der Client hat **keinen Zweifel**: der Server sagt `RUNNING`, ohne Vorbehalt. Der
   Zweifel muss dort entstehen, wo die Beobachtung verworfen wird (`onProcessExit`). Als *zusätzliche*
   Leitplanke bleibt die Regel wertvoll: ein fälschlich anklickbarer Start ist harmlos, ein gesperrter Start
   bei totem Agenten nicht.

> **Und die Zeile, die diese Verwechslung erzeugt, ist selbst ein Fall dieser Fehlerklasse:**
> `AgentShell.kt:188` und `:190` sagen *„`null` → the in-memory stub until the REST client lands"* bzw.
> *„… until `/ws/lifecycle` lands"*. **Beide sind gelandet** — drei Zeilen weiter unten wird der Live-Client
> gebaut. Der Kommentar beschreibt einen Zustand, den es nicht mehr gibt, und schickt jeden Leser auf die
> falsche Fährte. Zwei Sätze KDoc, keine Verhaltensänderung.

### 2.5 Zwei Wege (Design-Sicht; ich implementiere nicht)

1. **Die Beobachtung anschließen** — richtig, und der ganze Weg: `onProcessExit` muss den `LifecycleManager`
   erreichen (`STOPPED` bei Exit-Code 0, sonst `ERROR`). Dann stimmt der Punkt, der Start-Knopf wird frei, und
   der Badge (über `deriveStatus`) folgt. **Meine Empfehlung.**
2. **Die Behauptung verengen** — wenn (1) nicht sofort geht: `RUNNING` darf nicht als „läuft" gerendert werden,
   solange es „gestartet" bedeutet. Dann müsste die Oberfläche das sagen, und der Start-Knopf dürfte nicht am
   Zustand hängen. Das ist die schlechtere Lösung; ich nenne sie nur, damit die bessere nicht als teuer
   erscheint.

> **Disclosure-Regel, die ich daraus ableite und die über diesen Fall hinausgeht:**
> **Ein Zustand, der einen fremden Prozess behauptet, muss aus dessen Beobachtung stammen — oder er muss
> sagen, dass er es nicht tut.** Ein Zustand, der eine Bedienung sperrt (hier: Start), trägt zusätzlich
> **Bedienlast** und darf nicht auf einer nicht nachgeführten Annahme ruhen.

---

## 3. 🔴 B2 — Die Telemetrie-Lücke zählt **Berichte**, nicht **Ereignisse**

### 3.1 Der Beleg

Der Product-Lead-Report ist das **Defekt-Register**. Sein eigener Kommentar nennt den Anspruch:

```kotlin
// server/…/report/ReportGenerator.kt:102–105
// log.dropped is itself an observation gap — surface it honestly, not as a clean bill.
val dropped = events.count { it.type == EventType.LOG_DROPPED }
listOf(ReportItem("Telemetrie-Lücke: $dropped Event(s) verworfen (log.dropped)", …))
```

`events.count { … }` zählt die **`log.dropped`-Ereignisse**, also die *Meldungen über einen Verlust* — nicht
die **verworfenen Ereignisse**. Die Abbildung ist **1 : N**, nicht 1 : 1: der `EventRecorder` verwirft bei
voller Warteschlange beliebig viele Events und schreibt **eine** Meldung, die das Delta mitführt:

```kotlin
// server/…/events/EventRecorder.kt:107–110
detail = buildJsonObject { put("dropped", delta); put("total", total) }
```

Der Text wird gerendert (`ProductLeadPanel.kt:270` (bzw. `:239`), `item.text`).

**47 verworfene Ereignisse in einem Schwall ⇒ eine Meldung ⇒ der Report sagt: „Telemetrie-Lücke: 1 Event(s)
verworfen".**

### 3.2 Der Schaden

Der Product Lead liest ein Register, das ausdrücklich beansprucht, **kein Persilschein** zu sein, und findet
darin eine Lücke von **einem** Ereignis. Er schließt: „Das Log ist praktisch vollständig, die Defektliste
darunter ist belastbar." Tatsächlich fehlt der ganze Schwall.

**Und es untertreibt genau dann am stärksten, wenn es am meisten darauf ankommt.** Der Recorder verwirft,
wenn die Warteschlange **voll** ist — also unter Last, im Störungsfall, wenn Fehlerereignisse in Serie
auflaufen. Je größer die echte Lücke, desto größer die Untertreibung. Ein einzelner Ausfall mit 500
verlorenen Ereignissen liest sich wie ein Rundungsfehler.

**Was der Operator fälschlich glaubt:** „Ein Ereignis ging verloren."
**Was er deshalb tut:** hält die Defektliste für erschöpfend und stellt keine weiteren Fragen.

### 3.3 Die richtige Zahl ist da — und wird anderswo schon korrekt gelesen

- Sie steht **im selben Payload**: `detail["dropped"]` (Delta) und `detail["total"]` (kumulativ).
- Eine **andere Oberfläche desselben Repos liest sie korrekt**: die Gap-Zeile im Event-Log
  (`EventRowUi.kt:210`, `droppedCount`) nimmt `detail["dropped"]`.
- Der **Stub ist ehrlicher als der Generator**: `StubReportRepository.kt:95` schreibt
  „Telemetrie-Lücke: Events verworfen (log.dropped)" — **ohne Zahl**. Wer keine Zahl hat, nennt keine.

> **Der Einwand, den ich ernst nehme:** Der Generator liest `e.detail` bewusst **nie** („NEVER `e.detail`",
> `ReportGenerator.kt:99`) — die Report-Einträge sind **inhaltsfrei** (PRD §3.5). Das ist eine Datenschutz-Leitplanke, kein
> Versehen. Sie steht dem Fix aber nicht entgegen: `detail` eines `log.dropped` ist **per Konstruktion**
> inhaltsfrei — zwei Zahlen, vom `EventRecorder` selbst gebaut, nie aus einer Agenten-Nachricht.

**Fix (Design-Sicht):** die Deltas **summieren** statt die Meldungen zu zählen. Fehlt `detail` (alter
Payload), dann **keine Zahl nennen** — „Telemetrie-Lücke: Events verworfen", wie der Stub. **Nie** eine Zahl,
die untertreibt.

### 3.4 Die Form

| | Behauptung der Oberfläche | Tatsächlicher Inhalt |
|---|---|---|
| **B2** | „so viele Ereignisse gingen verloren" | „so oft haben wir *gemeldet*, dass etwas verlorenging" |

---

## 4. Notizen — geprüft, Schaden **nicht** benennbar (kein Ticket)

Diese drei hielt ich beim Sichten für Funde. Sie sind keine. Ich führe sie mit Begründung, damit der nächste
Sweep sie nicht ein zweites Mal „findet" — dieselbe Buchführung wie beim DEBUG-Rail im `outline`-Audit.

**N1 — `formatCompactTokens` (`window/TokenFormat.kt`).** Der KDoc sagt: *„the a11y label + the optional hover
tooltip carry the exact/precise value."* **Beides existiert nicht.** Der a11y-Text wird mit dem **gekürzten**
Wert formatiert (`a11y_agent_context_tokens`, `WindowManager.kt:723`), ein Tooltip gibt es nicht. Der exakte
Wert steht **nirgends**.
*Kein Schaden:* die Anzeige ist ehrlich — das `k` sagt selbst, dass gerundet wurde, und die Kürzung geht
**nach unten** (`n / 1_000`), überschätzt die Kontext-Belegung also nie. Ein Screenreader-Nutzer hört exakt
das, was ein Sehender liest. **Aber:** die Aussage im KDoc ist die Zusage einer Absicherung, die es nicht
gibt — genau der Mechanismus, über den sich diese Fehlerklasse fortpflanzt. Ein Satz KDoc streichen.

**N2 — `crossproject_status_shared` (`CrossProjectControls.kt:82`).**
`formatTs(state.sharedAt ?: 0L)` — wäre `sharedAt` je `null`, während `shared == true`, stünde dort
„freigegeben am **00:00:00.000**", also die Epoche als Tatsache. Das Muster („unbekannt" wird zu einem Wert)
ist genau falsch herum; die Konvention des Hauses ist fail-closed durch Absenz.
*Kein Schaden heute:* der Server garantiert die Kombination nicht (`ChannelShareRecord.sharedAt: Long` ist
nicht-nullable, `ChannelShareRoutes.kt:98` liefert `shared = true` immer mit Zeit). Der `?: 0L` ist eine
**latente** Erfindung, die eine DTO-Änderung scharf schaltet. Wenn CYP-336 diese Aufrufstelle ohnehin anfasst
(sie rendert UTC), dort mit erledigen: `?: return` statt `?: 0L`.

**N3 — Correlation-Chip (`EventRowUi.kt:168`).** `"· ${it.take(8)}"` zeigt 8 von 36 Zeichen einer UUID, ohne
Auslassungszeichen — ein gekürzter Bezeichner, der wie ein Bezeichner aussieht.
*Kein Schaden:* der Drilldown übergibt die **volle** ID (`EventBrowseViewModel.kt:98–99`), und die
Drilldown-Kopfzeile zeigt sie vollständig (`EventBrowsePanel.kt:303`). Es gibt kein Freitextfeld, in das ein
Operator die acht Zeichen tippen und ein stilles leeres Ergebnis ernten könnte. *(Ich hatte zuerst eine
ULID vermutet — deren erste Zeichen kodieren die **Zeit**, unabhängige Ereignisse teilten sich dann den
Präfix. Es ist eine `UUID.randomUUID()` aus `CompactOrchestrator.kt:56`, 32 zufällige Bit. Hypothese geprüft
und verworfen.)* Ein `…` wäre trotzdem freundlich.

---

## 5. Geprüft und **ehrlich** — damit es nicht erneut aufgerollt wird

Diese Anzeigen sind Ableitungen und sagen es. Sie sind der Grund, warum B1 und B2 auffallen.

| Anzeige | Ableitung | Warum ehrlich |
|---|---|---|
| Kontext-Token im Titel (`137k`) | `input + cacheRead + cacheCreation`, dann gerundet | `null` ⇒ **nichts**, nie `0` (`TokenUsageViewModel`). Das `k` deklariert die Rundung. |
| Busy-Marker `*` | aus `/ws/busy-state` | „unbekannt ≠ beschäftigt": ungesehener Agent ⇒ **kein** `*` (`BusyStateViewModel:18`) |
| Provider-Chip / Fidelity-Badge | Connector-Meldung | fehlt, wenn nicht gemeldet — kein Phantom |
| „Startet…" / „Neustart…" | **Client-seitig**, vor der Server-Bestätigung | transient, löst bei jedem Lifecycle-Event auf; nie ein aufgelöster Zustand vor der Bestätigung |
| `· ausstehend` (Comm) | optimistisch, lokal | **beschriftet** als ausstehend |
| `N Events verworfen` (Gap-Zeile) | Server liefert `dropped` (Delta) **und** `total` | die UI liest das **Delta** — richtig für eine Zeile *an dieser Stelle*. ⚠️ Der Client-KDoc nennt es fälschlich *„cumulative"*, und `detail["count"]` ist ein Schlüssel, den der Server nie sendet (`EventRecorder.kt:105`). **Verhalten korrekt, Kommentar falsch** — wer den Kommentar „repariert", baut den Fehler ein. |
| „Verbindung getrennt – Stand %1$s" | — | bekommt den Literal `"—"` übergeben, erfindet keinen Zeitpunkt (`EventTailPanel.kt:185`) |
| API-Key `Hinterlegt: %1$s` | **server**-maskiert | der Klartext verlässt den Server nie |
| Fenster-Badge „Achtung" | `deriveStatus(transcript)` | KDoc: *„never claims RUNNING without an open stream/tool"* — und es ist eine **andere** Achse als der Lifecycle, wird nie als „läuft" gerendert |

> **Adjazent, außerhalb der Leitplanke:** `ContextUsageBander` rechnet den Füllstand gegen ein **angenommenes**
> Kontextfenster (Default ~1 M; der KDoc sagt selbst *„not in PRD §8's list — flagged, not invented"*). Der
> Prozentwert wird **nirgends angezeigt** — deshalb kein Befund. Er beeinflusst aber, **wann** ein
> `context.usage`-Event überhaupt entsteht. Sobald irgendeine Oberfläche einen Prozentwert zeigt, wird daraus
> sofort ein Fall dieser Klasse: ein Anteil, dessen Nenner geraten ist.

---

## 6. Die Wurzeln — und warum es **nicht** ein Muster mit vier Symptomen ist

Der PO hat die richtige Frage gestellt: *dieselbe Wurzel im Code, oder nur dieselbe Form?*
Die ehrliche Antwort ist **zwei Wurzeln, je zwei Symptome** — und das ist die nützlichere Antwort, weil die
beiden **verschiedene Reparaturen** brauchen. „Ein Muster" wäre schöner formuliert und falsch.

### 6.1 Wurzel A — der Bezugsrahmen passt nicht in den Typ (Instanzen 1 + 2)

| | Wert | Typ | Was der Typ **nicht** sagt |
|---|---|---|---|
| CYP-336 | `formatTs(ts)` | `Long` | in **welcher Zone** die Wanduhr steht |
| CYP-346 | `eventTs − now` | `Long` | ob es ein **Zeitpunkt** oder eine **Dauer** ist |

**Gemeinsame Wurzel, im Code nachweisbar:** epoch-ms ist in dieser Codebasis ein **nacktes `Long`**, und es
bezeichnet **beides** — Zeitpunkt (`Event.ts`, `AgentEvent.tsMs`, `sharedAt`, `generatedAt`) *und* Dauer
(Skew, verstrichene Zeit). Es gibt **keine** `value class`, kein `typealias`, das sie trennt (geprüft über
`core`, `app/shared`, `server`: null Treffer). Deshalb kompiliert `eventTs − now` klaglos und ergibt wieder
ein `Long`, das man in `formatTs` stecken kann. Der Compiler kann den Fehler nicht sehen, also muss ihn ein
KDoc sehen — und KDocs werden nicht mitkompiliert.

**Reparatur:** den Rahmen in den **Namen oder Typ** heben. `formatUtcClock(...)` hätte CYP-336 nie überlebt.
Ein `value class Instant(val epochMs: Long)` neben `value class Millis(val value: Long)` hätte CYP-346 zur
Compilezeit erledigt.

### 6.2 Wurzel B — ein **Stellvertreter** wird als die Sache gerendert (Instanzen 3 + 4)

| | Angezeigt als | Tatsächlich | Abbildung |
|---|---|---|---|
| **B1** | „der Prozess läuft" | „wir haben ihn gestartet und hier nicht gestoppt" | 1 : **vielleicht** |
| **B2** | „so viele Ereignisse gingen verloren" | „so oft haben wir Verlust *gemeldet*" | 1 : **N** |

Beide Stellvertreter sind **billiger** zu bekommen als die Sache selbst: ein Map-Eintrag ist billiger als
Prozess-Überwachung; `count { … }` ist billiger als das Summieren eines Deltas aus dem Payload.

**Und beide irren in dieselbe Richtung.** Das ist die eigentliche Entdeckung:

> **Ein Stellvertreter, der aus dem eigenen Handeln abgeleitet ist, irrt immer zugunsten der Beruhigung.**
> `RUNNING` überschätzt die Gesundheit. `1 Event verworfen` unterschätzt den Verlust. Beide erzählen, was
> **wir getan haben** (gestartet; gemeldet), nicht, was **die Welt getan hat** (gestorben; 47 Ereignisse
> verschluckt). Aus der eigenen Perspektive gelingt jede eigene Handlung.

Deshalb sind Wurzel-B-Fehler gefährlicher als Wurzel-A-Fehler: eine falsche Zone fällt irgendwann jemandem
auf, weil sie *auffällig* falsch ist (zwei Stunden). Ein optimistischer Stellvertreter ist **unauffällig**
richtig — er sagt genau das, was man erwartet.

**Reparatur:** die Beobachtung dort anschließen, wo sie verworfen wird (`onProcessExit` → `LifecycleManager`;
`detail["dropped"]` summieren). **Nicht** die Anzeige umformulieren.

### 6.3 Die fünfte Ausprägung: Dokumentation als Stellvertreter für Code

`AgentShell.kt:188/190` — *„`null` → the in-memory stub until `/ws/lifecycle` lands"* — beschreibt eine Naht,
die **drei Zeilen weiter unten geschlossen** ist (§2.4). Das ist Wurzel B, eine Ebene höher: der Kommentar
berichtet die **Absicht zum Zeitpunkt des Schreibens** als wäre sie der heutige Zustand. Er hat den PO und
mich in die falsche Schicht geführt.

**Die ersten vier Instanzen zeigen einen falschen Wert. Die fünfte zeigt einen falschen Ort** — und ist damit
die teuerste, weil sie die Reparatur der anderen fehlleitet.

### 6.4 Was daraus folgt (Vorschlag, kein Ticket-Anspruch)

1. **Ein Wert, der einen fremden Prozess oder eine fremde Uhr behauptet, braucht eine Quelle** — oder er muss
   sagen, dass er keine hat. (Beides Wurzel B.)
2. **Ein Wert, der eine Bedienung sperrt, trägt Bedienlast** und darf nicht auf einem Stellvertreter ruhen.
   Der Start-Knopf ist der Prüfstein: hätte jemand gefragt „woher weiß dieser Zustand das?", wäre B1 beim
   Schreiben von CYP-73 aufgefallen.
3. **Wo der Compiler den Rahmen tragen kann, soll er ihn tragen** (Wurzel A). Ein KDoc ist der Ort, an dem ein
   Bezugsrahmen **stirbt**, nicht der, an dem er überlebt.
4. **Wer keine Zahl hat, nennt keine.** `StubReportRepository` macht es vor.

## 7. Self-Validation

- **Leitplanke 1 (nur angezeigte Werte) eingehalten:** `bandPct` und `contextWindowTokens` sind **nicht**
  angezeigt → als adjazentes Risiko benannt, **nicht** als Befund gezählt.
- **Leitplanke 2 (Schaden benennen) gegen mich selbst angewandt:** drei Kandidaten (N1–N3) sind zu Notizen
  degradiert, weil ich keinen Schaden belegen konnte. Eine falsche ULID-Hypothese ist geprüft und **verworfen**
  im Dokument protokolliert.
- **Zwei Wurzeln, nicht ein Muster** (§6): A = Bezugsrahmen passt nicht in den Typ (nachgewiesen: **kein**
  `value class`/`typealias` für epoch-ms in `core`/`app/shared`/`server`); B = optimistischer Stellvertreter.
  Sie brauchen **verschiedene** Reparaturen — Typen bzw. Verdrahtung der Beobachtung. Die bequeme Antwort
  („ein Muster, vier Symptome") wäre falsch gewesen.
- **B2 erfüllt beide Leitplanken:** der Text wird gerendert (`ProductLeadPanel.kt:270` (bzw. `:239`)), und der Schaden ist
  benannt und **richtungsgebunden** (Untertreibung wächst mit der Lücke). Der Datenschutz-Einwand gegen
  `e.detail` ist geprüft und trägt hier nicht (das `detail` von `log.dropped` ist per Konstruktion
  inhaltsfrei).
- **Der Befund ist am Code verifiziert, nicht am KDoc:** alle fünf `setRunState`-Aufrufstellen gelistet;
  `onProcessExit` bis zum einzigen Empfänger verfolgt; das Fehlen eines zweiten Observers und eines Watchdogs
  geprüft; die Schadenskette bis zum ausgegrauten Start-Knopf durchgezogen (`AgentWindow.kt:203`).
- **Ehrliche Anzeigen sind namentlich verzeichnet** (§4), damit eine spätere Bereinigung sie nicht mitreißt —
  dieselbe Buchführung wie beim DEBUG-Rail im `outline`-Audit.
- **Docs-only.** Kein Code geändert.
