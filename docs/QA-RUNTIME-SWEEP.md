# QA-Sweep — Tests, die warten statt zu prüfen

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/composition-sweep`, Basis `origin/develop` @ `ec9c537`
> Anlass: Backend2s Befund zu CYP-371 — `deliberateStop_isStopped_neverError: 5.396 s` bei `sleep 5`.
> **Der Test hat den Deadlock nicht bestanden, er hat ihn überlebt.**

> **Die Laufzeit eines Tests ist eine Beobachtung. Wir haben sie noch nie gelesen.**
> Sie steht in jedem `TEST-*.xml` als `time="…"`, direkt neben der `mtime`, die wir seit CYP-357 lesen.

---

## 1. Suchkommando

```bash
# 1) Laufzeiten aus den Reports (Bestandsdaten — aber die Herkunft prüfen!)
find . -path '*/build/test-results/*' -name 'TEST-*.xml' -not -path './build/*'
#    -> <testcase name="…" time="…"> einsammeln, absteigend sortieren

# 2) Warte-Konstanten im zugehörigen Quelltext
grep -nE 'Thread\.sleep\([0-9_]+|(?<!\w)delay\([0-9_]+|withTimeout(OrNull)?\([0-9_]+|sleep [0-9]+' <testfile>

# 3) Das Signal ist NICHT "langsam", sondern:   Laufzeit ≈ Summe der Wartekonstanten
```

**Die Herkunft zuerst.** Die vorgefundenen Reports waren bis zu **106 Minuten alt**, `app/shared/jvmTest`
enthielt **eine einzige** Datei (Rest meiner gefilterten Läufe), und die 217 `server`-Reports stammten von
**meinen eigenen roten Testplan-Branches**. Ein Sweep darüber wäre ein Sweep über meine eigene Arbeit gewesen.
Also einmal frisch: `--rerun-tasks --continue` über `server · connector-core · core · app:shared ·
remote-runtime · e2e` auf sauberem `ec9c537`.

**1972 Testfälle, 169,7 s gesamt. 43 über 1 s, einer über 3 s.**
*(Ein Zwischenstand nannte 39, ein weiterer 28. Beide waren Artefakte meiner eigenen Sondenläufe — siehe §10.)*

---

## 2. Der Treffer — dieselbe Krankheit wie CYP-371, in `:server`

### `SessionReadonlyWsTest` — 4,546 s, der langsamste Test des Repos

```kotlin
private suspend fun ApplicationTestBuilder.memberAdmitted(path: String): Boolean {
    …
    wsClient.webSocket(path, …) {
        val reason = withTimeoutOrNull(1_500) { closeReason.await() }   // <-- hier sitzt die Zeit
        rejected = reason?.code == CloseReason.Codes.VIOLATED_POLICY.code
    }
    return !rejected
}
```

**Wird die Session zugelassen, bleibt der Socket offen — `closeReason.await()` kehrt nie zurück.**
`withTimeoutOrNull` wartet die vollen 1,5 s ab, liefert `null`, und daraus wird `admitted = true`.

> **Das positive Ergebnis „zugelassen" entsteht dadurch, dass der Test die Zeit überlebt.**

Drei solcher Aufrufe ⇒ 4,5 s. **Die Zahl stand die ganze Zeit im Report.**

Was der Test **nicht** unterscheiden kann: „bleibt offen" von „wird bei 1,6 s geschlossen". Seine Zusage ist in
Wahrheit *„der Socket bleibt 1,5 Sekunden offen"* — und genau das sagt sein Name nicht.

### Weitere Treffer der Form *Laufzeit ≈ Wartesumme*

| Laufzeit | Wartekonstanten | Test |
|---:|---|---|
| **4,546 s** | 3 × `withTimeoutOrNull(1_500)` | `SessionReadonlyWsTest.memberSession_admittedOnReadWs_…` |
| **1,645 s** | `withTimeout(1500)` | `J6AgentLifecycleE2eTest.wsLifecycle_snapshot_participant_…` |
| **1,624 s** | `delay(1_500)` | `CommWsChurnReproTest.idleHeldOpenSocket_doesNotChurn_…` |
| **1,536 s** | `withTimeoutOrNull(1_500)` | `SessionReadonlyWsTest.cyp230_operatorSessionAdmitted_…` |
| **1,505 s** | `delay(1_500)` | `Cyp330RestartRobustnessTest.restartWithLiveResume_…` |
| **0,771 s** | `delay(750)` | `CommWsTest.doesNotPushUnreadableChannelToParticipant` |
| **0,520 s** | `delay(500)` | `WireHandshakeTimeoutTest.handshookInTime_isNotReaped` |

`CommWsChurnReproTest` ist der Vakuum-Fall in Zeitform: 1,5 s warten, dann **Abwesenheit** von Churn
behaupten. Er kann „kein Churn" nicht von „Churn bei 1,6 s" unterscheiden.

---

## 3. `withTimeoutOrNull` ist die gefährliche Form

`withTimeout` **wirft** — der Test wird rot. `withTimeoutOrNull` **liefert `null`** — der Test läuft weiter und
verrechnet die Abwesenheit einer Antwort als Antwort.

> **Ein Timeout, der nicht wirft, verwandelt einen Hang in ein Ergebnis.**

Das ist die Laufzeit-Schwester von `assertIsDisplayed()` bei 1 dp (CYP-363): eine grüne Zusicherung, die etwas
Schwächeres prüft, als ihr Name behauptet. Nur steht die Wahrheit hier **im Report**, nicht im Baum.

---

## 4. Strukturell: es gibt **kein hartes Zeitlimit**

```bash
grep -rn 'timeout' --include=build.gradle.kts server/ app/shared/ connector-core/ e2e/ core/
#  -> keine Treffer
```

**Kein Gradle-`Test`-Task hat ein `timeout`.** Ein Hang wird nicht rot — er wird still, und der Build hängt, bis
jemand ihn abschießt. `BridgeLazyInitE2eTest` hatte **zwei** `withTimeout` im Körper und hing trotzdem (CYP-362):
ein Timeout **innerhalb** einer blockierten Coroutine rettet nichts, wenn der Thread selbst in `readLine()` steht.

**Empfehlung (eine Zeile, gehört zu CYP-371):**

```kotlin
tasks.withType<Test> { timeout.set(java.time.Duration.ofMinutes(5)) }
```

Das macht einen Hang **rot statt still** — die Mindestbedingung dafür, dass die Laufzeit überhaupt gelesen wird.

---

## 5. Ein Befund, den ich **nicht** gemeldet habe

Ein Cluster von ~25 Tests lag auffällig eng bei **1,00–1,03 s**, ohne jede Wartekonstante im eigenen File.
Sieht nach einem versteckten Ein-Sekunden-Timeout aus. **Gemessen, statt gemeldet:**

```
nackte CIO-Engine hochfahren + schliessen   0,467 s
gar nichts tun                              0,004 s
```

Es ist **Infrastruktur** (Engine-Start + eingebetteter Server), kein Hang. **Der Cluster ist kein Befund.**

---

## 6. Wo meine eigene Methode blind war

Mein erster Regex kannte nur `withTimeout(`. **`withTimeoutOrNull(` fehlte** — ausgerechnet die gefährliche
Form. `SessionReadonlyWsTest` erschien dadurch in der Kategorie *„langsam ohne jede Erklärung"*, also als
Rätsel statt als Treffer. Erst der Blick in die Datei hat den Fehler gezeigt.

**Und die verbleibende Blindheit, die ich benenne statt zu verschweigen:** Die Korrelation liest **nur die
eigene Datei des Tests**. Eine Wartekonstante in einem gemeinsamen Fixture oder Helper ist unsichtbar — dieselbe
Restlücke wie *„Vorbedingung außerhalb der Datei"* aus dem Vakuum-Sweep. Backend2s `sleep 5` stand im Test.
Hätte es in einer Hilfsklasse gestanden, hätte mein Kommando es nicht gefunden.

---

## 7. Kandidat, kein Urteil

`Cyp247TeardownOnSwitchE2eTest.switch_synchronouslyDrainsTheOutgoingProject` — **1,697 s, keine
Wartekonstante.** Der Name verspricht *synchron*. Ob die Zeit der Server-Boot ist oder das „synchrone" Drainen,
**habe ich nicht gemessen**. Das ist die nächste Stelle zum Hinsehen, nicht ein Befund.

---

## 8. Severity

| # | Befund | Grad |
|---|---|---|
| 1 | Kein hartes Zeitlimit auf Testebene: ein Hang wird still statt rot | **Live-Lücke im Prozess** — CYP-362 und CYP-371 sind beide daran vorbeigelaufen |
| 2 | `withTimeoutOrNull` als Zusage: das positive Ergebnis entsteht durch Aussitzen | **Fragilität**, mit realem Erkenntnisverlust (die Zusage gilt nur für 1,5 s) |
| 3 | Zeit-gefensterte Abwesenheits-Assertions (`doesNotChurn`) | **Fragilität** |

**Der Produktionscode ist von 2 und 3 nicht betroffen** — betroffen ist, was wir über ihn zu wissen glauben.

---

## 9. Nachtrag: die zwei Sorten, getrennt

**Zahl zuerst.** Von **43** Testfällen über 1,0 s haben **9** eine passende Konstante (Einzelwert, Summe **oder
Vielfaches**), **34** nicht. Die 34 sind ganz überwiegend Postgres-/Container-Tests und Shell-Renderer —
Infrastruktur.

| Test | Laufzeit | Konstante | Sorte |
|---|---:|---|---|
| `SessionReadonlyWsTest.memberSession_admittedOnReadWs_…` | 4,546 s | 3 × `withTimeoutOrNull(1_500)` | **(a)** `null` ⇒ „zugelassen" |
| `SessionReadonlyWsTest.cyp230_operatorSessionAdmitted_…` | 1,536 s | `withTimeoutOrNull(1_500)` | **(a)** derselbe Helfer |
| `CommWsChurnReproTest.idleHeldOpenSocket_doesNotChurn` | 1,716 s | `delay(1_500)` | **(b) Zusicherung** — Abwesenheit von Churn |
| `Cyp330RestartRobustnessTest.restartWithLiveResume_…` | 1,505 s | `delay(1_500)` | **(b) Zusicherung** — „wird nicht proaktiv gelöscht" |
| `ResumingSessionExitTest.deathOfAStaleResumeAttempt_…` | 1,520 s | `withTimeoutOrNull(1_500)` | **(a′) korrekt** — mit positiver Kontrolle |
| `J2IsolationE2eTest.heldCommConn_afterSwitch_dropsForeign` | 1,012 s | 4 × `delay(250)` | **(a′) korrekt** — expliziter *„positive control"* |
| `J7ObservabilityE2eTest.metadataOnly_…` | 2,348 s | `withTimeout(2000)` | **(b′)** Timeout als **Schleifenende** eines `while(true) receive()` |
| `J6AgentLifecycleE2eTest.wsLifecycle_snapshot_…` | 1,645 s | `withTimeout(1500)` | **(b′)** dito |
| `AgentEventStoreTest.subscribe_concurrentAppendBurst_…` | 1,818 s | Stress-Konstanten | **ungeprüft** |

### (a) — wo `null` zur Zusicherung wird

`SessionReadonlyWsTest.memberAdmitted()` wartet auf ein `closeReason`, das bei Erfolg **nie kommt**, und liest
das Ausbleiben als „zugelassen". **Ein Hang ist dort nicht nur unsichtbar — er ist das erwartete Ergebnis.**

**Die Alternative steht bereits im Repo**, in `WireHandshakeTimeoutTest.handshookInTime_isNotReaped`:

```kotlin
delay(500)                                  // weit über der 200-ms-Frist
sendFrame(WireSend("po-backend", "still alive"))
assertIs<WireAck>(recv())                   // "a response proves the connection was not reaped"
```

**Er tut etwas, statt nichts zu beobachten.** Ein Frame hin, ein `Ack` zurück — der Socket ist *positiv* als
lebend belegt, in Millisekunden statt in 1,5 Sekunden. Genau das braucht `memberAdmitted()`.

### (b) — Zusicherung oder Bequemlichkeit?

* `CommWsChurnReproTest`: **Zusicherung.** `assertEquals(1, connectedCount)` belegt, dass **einmal** verbunden
  wurde — **nicht**, dass der Socket die 1,5 s überlebt hat. Stirbt er still und verbindet nie neu, bleibt der
  Zähler 1 und der Test ist grün. **Die Vorbedingung, die fehlt, ist die Lebendigkeit am Ende des Fensters.**
* `Cyp330RestartRobustnessTest`: **Zusicherung**, aber mit positiver Beobachtung (`assertNotNull(entry)`). Das
  Fenster bleibt willkürlich: ein Löschen bei 1,6 s bestünde den Test.
* `J7`/`J6`: **Bequemlichkeit** — der Timeout beendet eine `while(true) receive()`-Schleife. Der Test *zahlt*
  die Konstante immer; sie misst nichts.

---

## 10. Zwei weitere Fehler von mir

**Mein Abgleich kannte keine Vielfachen.** `SessionReadonlyWsTest` (4,546 s) hat eine Konstante von 1,5 s — der
Helfer wird **dreimal** gerufen. Mein erster Lauf sortierte den wichtigsten Treffer des Sweeps unter „langsam
ohne Erklärung" ein.

**Und ich habe meine eigene Datenbasis zerstört.** Meine Wegwerf-Sonde lief mit `--tests '*ZzProbe*'` und hat
`app/shared/jvmTest` von **193 Reports auf 1** reduziert. Die Zahl „über 1 s" fiel dadurch von 39 auf 28 — kein
Befund, ein Artefakt. Wiederhergestellt, neu gerechnet: **43**.

> **Ein gefilterter Testlauf ist ein Löschbefehl für den Rest des Reports.**
