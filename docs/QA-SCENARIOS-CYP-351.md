# QA-Testplan — CYP-351: Der grüne Punkt lügt

> QA / Test Engineer (Team2) · 2026-07-10 · Basis: `develop` @ `7665733`
> **Vor dem Code geschrieben.** Backend2 baut; dieser Plan steht gegen die Akzeptanzkriterien, nicht gegen seine
> Implementierung.

---

## 0. Was ich nachgeprüft habe, statt es zu übernehmen

Vier Befunde stehen im Ticket, alle von anderen. Ich habe sie am Code gelesen. **Alle vier stimmen.**

| Behauptung | Verifiziert |
|---|---|
| `AgentProcess.awaitTerminated()` verwirft den Exit-Code | ✅ `AgentProcess.kt:83-86` — `withContext(Dispatchers.IO) { process.waitFor() }`, Rückgabe `Unit` |
| `RecordingSessionObserver` übergibt hart `null` | ✅ `RecordingSessionObserver.kt:78` — `projector.processExit(agentId, sessionId, null)` |
| `null` wird als sauberer Exit gewertet | ✅ `EventProjector.kt:189` — `if ((exitCode ?: 0) != 0) WARN else INFO`, und `exitCode?.let { put(...) }` schreibt nichts |
| `LifecycleManager.status` ist ein Befehls-Gedächtnis | ✅ `LifecycleManager.kt:69` — `HashMap`, geschrieben nur von `setRunState` |

---

## 1. Ein fünfter Befund — der die PO-Auflage erweitert

Die Auflage lautet: *„Ist ein **Lesefehler** auf der stdout-Pipe immer ein Prozesstod?"* Sie fragt zu eng.

**Gemessen** (`python3`, dieser Host, POSIX-Semantik — sprachunabhängig):

```
Sonde 1: Kind schließt stdout, lebt weiter        sh -c 'exec 1>&-; sleep 3'
   → stdout endet NORMAL (EOF, keine Exception) nach 0.00 s
   → Prozess lebt:            True
   → erst waitFor() weiß es:  exit=0 nach 3.01 s

Sonde 2: Kind per SIGKILL getötet, während gelesen wird   sh -c 'exec sleep 30'
   → stdout endet nach 0.00 s (EOF)
   → waitFor() liefert:       exit=-9
```

**EOF ist kein Tod.** Ein Kind, das seinen stdout schließt und weiterläuft, beendet den `collect` **normal** — und
der Tail hinter dem `collect` meldet `onProcessExit` für einen **lebenden** Prozess.

> Backend2s Befund beschreibt eine **fehlende** Beobachtung (Lesefehler ⇒ gar kein Event).
> Dieser hier beschreibt eine **erfundene** Beobachtung (EOF ⇒ Event, obwohl der Prozess lebt).
> **Beides ist dieselbe Krankheit, mit umgekehrtem Vorzeichen — und die zweite existiert heute schon.**

**Konsequenz für AC 10:** Der Tod darf weder aus dem **Lesefehler** noch aus dem **normalen Streamende**
erschlossen werden. Beides sind Beobachtungen *des Streams*, nicht *des Prozesses*. Die einzige Beobachtung des
Prozesses heißt `waitFor()` — und sie liefert im selben Zug den Exit-Code, den AC 5 ohnehin braucht.

> **`collect` endet ⇒ der Stream ist zu Ende. Nicht: der Prozess ist tot.**

**Der Code sagt es selbst.** `ClaudeCodeSession.kt:107` — der Kommentar über dem Tail:

```kotlin
// NORMAL stdout completion (the process exited on its own); a deliberate close() cancels this job.
…
observer?.onProcessExit(agentId, boundSessionId)   // :113 — ohne waitFor(), ohne Exit-Code
```

„*the process exited on its own*" ist die **Annahme**, nicht die Beobachtung. Kein `waitFor()` steht dazwischen.
Der Kommentar behauptet, was der Code nicht weiß — dieselbe Signatur-lügt-Beobachtung wie bei
`processExit(exitCode: Int?)`.

**Und der Vertrag lügt mit.** `AgentProcess.stdoutLines`, KDoc:

> *„NDJSON lines from the agent's stdout. **The flow completes when the process ends.**"*

Er endet, wenn der **Stream** endet.

### In Kotlin nachgemessen, nicht nur in Python erschlossen

Die python-Sonde belegt POSIX-Semantik. Sie belegt **nicht**, dass `AgentProcess` sich so verhält. Also habe ich
den echten Spawner (`AgentProcess.kt:66-86`) Zeile für Zeile nachgebaut — `ProcessBuilder("sh","-c", …)`,
`redirectError(DISCARD)`, `inputStream.bufferedReader().useLines { … }`, `flowOn(Dispatchers.IO)` — und den Flow
gegen `exec 1>&-; sleep 3` gefahren:

```
assertTrue(proc.isAlive)                       // nach dem collect  → BESTANDEN
assertTrue(afterWait - afterCollect > 1000)    // lebte > 1 s weiter → BESTANDEN
```

`:connector-core:test` → **1 Test, 0 Fehler.** Der Flow endet, der Prozess lebt. *(Wegwerf-Sonde, wieder entfernt
— sie gehört, falls gewünscht, in Backend2s Modul, nicht in meinen Testplan.)*

*(Nebenbei, gegen Backend2s Werkzeug-Hinweis: Auf diesem Host beendet `sh -c "sleep 2"` die Pipe sofort nach
`kill` — die eingebaute `exec`-Optimierung von `dash` greift. Der Hinweis bleibt richtig, ist aber
shell-abhängig. **`exec` gehört trotzdem in jeden Testbefehl** — man verlässt sich nicht auf eine Optimierung.)*

---

## 2. Die Testregel dieses Tickets

Aus AC 3, und sie ist die Regel dieser ganzen Woche:

> **Der Test darf seinen Erwartungswert nicht durch dieselbe Naht ziehen, die er prüft.**

Für CYP-351 heißt das konkret, und hier liegen die Fallen:

* **Nicht** `setRunState()` rufen und danach `status` lesen. Das prüft eine `HashMap`, die sich selbst zitiert.
* **Nicht** `onProcessExit()` direkt aufrufen. Das prüft den Konsumenten, nicht die Beobachtung.
* **Sondern:** einen echten Prozess starten, ihn **töten**, und den Zustand über den **öffentlichen Lesepfad**
  abholen, den auch der Operator sieht — `GET /api/agents` **und** `/ws/lifecycle`.

**Ein Test, der den Befehl setzt und dieselbe Map wieder ausliest, ist die dritte Instanz der Fehlerklasse, die
das Ticket behandelt** — nur im Testcode.

---

## 3. Die Tests

### T1 — Der Kill-Test (AC 1, AC 3)

Echter Prozess (`sh -c 'exec sleep 30'`), `SIGKILL`, **keine** Operator-Aktion. Erwartet: der Serverzustand
wechselt **von selbst** aus `RUNNING`.

* Gelesen über `GET /api/agents` (Snapshot) **und** über ein `/ws/lifecycle`-Delta.
* Erwartungswert kommt aus dem **Prozess** (`Process.exitValue()`), nicht aus dem `LifecycleManager`.
* **Mutation:** `onProcessExit` schreibt den Zustand nicht → T1 rot.

### T2 — Snapshot und Delta stimmen überein (AC 4)

Beide Pfade nach demselben Kill abfragen. **Kein Pfad meldet `RUNNING` für einen toten Prozess.**
Das ist kein Duplikat von T1: T1 prüft, *dass* sich etwas ändert, T2 prüft, dass **beide Quellen dasselbe
sagen**. Ein Fix, der nur das WS-Delta bedient und den Snapshot vergisst, macht T1 grün und T2 rot.

### T3 — Exit-Code kommt an (AC 5)

`sh -c 'exec exit 3'` → `LifecycleManager` sieht `3`, `EventProjector` sieht `3`.
**Mutation:** `awaitTerminated()` verwirft den Code wieder → T3 rot.

### T4 — Absturz ist `WARN` **mit** Code (AC 6)

`SIGKILL` (137) → Event mit `severity >= WARN` **und** `exitCode` im `detail`.
**Heute** erzeugt derselbe Prozess `INFO` ohne Code — der Test ist also **jetzt schon rot** und bleibt es, bis
der Fix landet. Das ist die richtige Reihenfolge.

### T5 — Unbekannt ist nie sauber (AC-Kommentar, Regel 1 + 4)

Kein ermittelbarer Code → `severity >= WARN`.
**Mutation:** jemand baut `?: 0` wieder ein → T5 rot. **Das ist der eigentliche Zweck von T5** — nicht der
heutige Fehler, sondern seine Rückkehr.

### T6 — Absichtlicher Stop erzeugt **kein** `process.exit` (AC 9)

`close()` / `stop` → kein Event, Zustand `STOPPED`, **nie** `ERROR`.
**Mutation:** `CancellationException` wird nicht ausgenommen → T6 rot.

### T7 — **EOF ist kein Tod** (AC 10, aus §1)

`sh -c 'exec 1>&-; sleep 3'`: stdout endet sofort, der Prozess lebt drei Sekunden weiter.
Erwartet: **solange der Prozess lebt, kein `process.exit`, kein Zustandswechsel.** Erst nach seinem echten Ende.

**Dieser Test ist die Trennprobe zwischen Beobachtung und Ableitung.** Er wird rot, wenn jemand den Tail wieder
an `collect` hängt — egal ob an sein normales Ende oder an seinen Fehler.

### T8 — Der Start-Knopf ist in jedem Nicht-`RUNNING`-Zustand bedienbar (AC 2)

`RUNNING` / `STOPPED` / `ERROR` / `UNKNOWN` durchspielen, `enabled` prüfen.
**Guard, damit das kein Vakuum wird:** zuerst positiv belegen, dass der Header überhaupt gerendert hat
(`AgentViewTags.header`), **dann** die Bedienbarkeit prüfen. Ein Knopf, den es nicht gibt, ist nicht „gesperrt".

### T9 — `UNKNOWN` ≠ `ERROR` (Design-Auflage)

Drei Zustände, drei unterscheidbare Darstellungen **und** drei unterscheidbare `contentDescription`s.
„Ich weiß es nicht" darf nicht rot aussehen. **Mutation:** `UNKNOWN` auf die `ERROR`-Farbe legen → T9 rot.

---

## 4. Was ich **nicht** prüfen kann, und warum

| Lücke | Grund |
|---|---|
| Der echte `claude`-Prozess | Kein API-Key, kein Spawn im Test. Alle Tests benutzen `sh`-Prozesse. Die **Mechanik** ist dieselbe; die **Integration mit dem echten Binary** bleibt ungetestet und ist hier benannt. |
| Race nach dem Neustart | Backend2s Entwarnung (`readerJob.cancelAndJoin()` **vor** `destroy()`) habe ich **gelesen**, nicht gemessen. AC 7 verlangt sie im Test festzuhalten — T6 tut das für die Richtung „Stop ⇒ `STOPPED`". Ein echter Nebenläufigkeits-Beweis wäre ein Stress-Test, kein Unit-Test. |
| Der Browser | Die Serverzustände sind JVM. T8/T9 laufen als `runComposeUiTest` (JVM **und** wasm), der Kill-Test nicht. |
| Emulator / KVM | Ausgeschlossen. Browser-only. |

---

## 5. Reihenfolge

1. **T4, T5, T7 zuerst schreiben — sie sind heute rot.** Ein Test, der den Defekt zeigt, bevor der Fix da ist,
   ist ein Beweis. Danach ist er ein Wächter.
2. T1–T3, T6 nach dem Server-Fix.
3. T8, T9 nach der Client-Härtung.
4. Für **jede** Mutation: die Änderungszeit des Testreports mitlesen. *Ein Testreport, dessen Herkunft man nicht
   kennt, ist kein Beleg.*
