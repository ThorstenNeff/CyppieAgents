# QA-Testplan — CYP-371: Die Zange um `closeAndAwait()`

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/CYP-371-testplan`, Basis `origin/develop` @ `bc38cbe`
> **Vor dem Fix geschrieben. T-Term heute ROT, T-Flush heute GRÜN.** Kein Fix — der Pfad gehört Backend2.
> Vorbild: `qa/CYP-351-testplan` (T7/T4b). Dieselbe Mechanik: zwei Backen, die gegeneinander ziehen.

## 1. Der Defekt

`ClaudeCodeSession.closeAndAwait()`:
```kotlin
readerJob?.cancelAndJoin()   // (1) wartet auf den Reader
process.destroy()            // (2) killt den Prozess ERST danach
process.awaitTerminated()
```
Ist der Prozess **still und lebendig** (ein `claude --resume`, das auf einen Turn wartet, CYP-170), steht der
Reader in einem blockierenden `readLine()`. `cancelAndJoin` wartet auf einen Thread, der nie fertig wird —
**Deadlock.** `POST /stop` gegen so einen Prozess kehrt nie zurück.

## 2. Die Zange

| Fix in `closeAndAwait()` | T-Term | T-Flush |
|---|---|---|
| **heute**: `cancelAndJoin` VOR `destroy` | **ROT** (Deadlock) | grün |
| **faul**: `cancel()` statt `cancelAndJoin` | grün | **ROT** (Zeile verloren) |
| **richtig**: `destroy` VOR `cancelAndJoin` | grün | grün |

**Gemessen, nicht behauptet** (`:connector-core:test`, jede Mutation frisch, Report 0 s):
```
                          T-Term        T-Flush
heute (join vor destroy)  ROT (3,36s)   GRUEN (0,36s)
faul (cancel ohne join)   GRUEN(0,36s)  ROT   (0,06s)
richtig (destroy vor join) GRUEN(0,37s) GRUEN (0,36s)
```

**Nur der richtige Fix macht beide grün.** Der faule tauscht die eine Backe gegen die andere: Er schneidet den
Reader ab, damit T-Term nicht mehr hängt — und verliert dabei die in-flight-Zeile, die CYP-247 garantiert.
Es gibt keinen Weg, beide grün zu bekommen, außer die Pipe **vor** dem Join zu schließen: dann bekommt
`readLine()` EOF, der Reader läuft normal aus (liefert seine letzte Zeile), und *dann* kehrt der Join zurück.

## 3. T-Term — Terminierung (heute ROT)

`sh -c 'while read _; do :; done'`: liest stdin, druckt nie, EOFt nie bis `destroy()`. Ein **echter** Prozess
über `ProcessBuilderSpawner` — der Deadlock lebt in einem nativen, thread-blockierenden Read; ein Fake mit
`emptyFlow()`/Channel ist immer abbrechbar und kann ihn **nie** zeigen. (Genau deshalb ist der bestehende
`ClaudeCodeSessionTest.closeAndAwait_waitsForProcessTermination` mit seinem `TerminatingProcess`-Fake heute
grün, obwohl der Defekt live ist.)

**Hartes Zeitlimit auf THREAD-Ebene, nicht `withTimeout` im Körper.** Ein `withTimeout` kann einen Thread in
nativem `readLine()` nicht unterbrechen — die Lehre aus CYP-362 (`BridgeLazyInitE2eTest` hatte zwei
`withTimeout` und hing trotzdem). `Thread.join(3_000)` kehrt **immer** zurück; der Daemon-Worker wird
aufgegeben und blockiert den JVM-Exit nicht. So wird ein Hang **rot statt still**. *(Nach `proc.destroy()`
bekommt der abgehängte Reader EOF und läuft aus — kein Zombie.)*

## 4. T-Flush — die Gegenbacke (heute GRÜN, Wächter)

Eine Zeile ist im Reader **in-flight** (der Observer-Body verarbeitet sie, nicht-suspendierend, wie der echte
Body — das Rendezvous aus `Cyp247SwitchAttributionTest`). `closeAndAwait` muss ihn **ausreden lassen**;
`bodyCompleted` muss `true` sein, wenn `closeAndAwait` zurückkehrt.

Hier ein **deterministischer Fake** (kein echter Prozess): das Flush-Fenster wäre mit einem echten Prozess ein
Rennen; das In-flight-Halten über einen Latch ist deterministisch. **Jede Backe nimmt das Werkzeug, das *ihre*
Eigenschaft deterministisch macht** — T-Term den echten Prozess, T-Flush das Rendezvous.

T-Flush ist **heute grün und aus dem richtigen Grund** (das aktuelle `cancelAndJoin` hält die Zusage). Sein
Zweck ist der faule Fix: `cancel()` ohne Join kehrt zurück, bevor der Body fertig ist ⇒ `bodyCompleted == false`
⇒ ROT. Ohne diese Backe würde Backend2 T-Term „reparieren", indem er den Reader abschneidet, und CYP-247 fiele
still.

## 5. Was der Plan **nicht** prüft

- **Den echten `claude`-Prozess.** Kein API-Key im Test; T-Term benutzt `sh`. Die **Mechanik** des Deadlocks ist
  dieselbe (blockierender Read gegen `cancelAndJoin`); die Integration mit dem echten Binary bleibt benannt-offen.
- **`ResumingSession`/`ConnectorSession`-Wrapper.** Sie delegieren an `inner.closeAndAwait()`
  (`ResumingSession.kt:188`, `ConnectorSession.kt:126`) — der Fix an `ClaudeCodeSession` trägt durch, aber die
  Wrapper-Kette ist hier nicht separat gefahren.
- **`POST /stop` end-to-end.** Der Deadlock ist am Session-Objekt festgenagelt; dass er über die Route
  durchschlägt, ist gelesen (`LifecycleManager` → `stop` → `closeAndAwait`), nicht gefahren.

## 6. Reihenfolge

1. **T-Term + T-Flush zusammen schreiben** — sie sind die Zange, einzeln beweist keine.
2. Backend2 baut den Fix; die Matrix aus §2 ist die Abnahme. **Melde, welche Zelle sich bewegt**, nicht „grün".
3. Beweistest und Fix landen **in einem Zug** (ein roter T-Term auf `develop` nähme allen das Gate).

> Diese Tests liegen in `connector-core` — Backend2s Modul. Sie stehen auf meinem Branch, damit er sie **hat**.
