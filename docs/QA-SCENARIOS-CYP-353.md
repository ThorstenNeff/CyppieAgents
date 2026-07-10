# QA-Testplan — CYP-353: Telemetrie zählt Meldungen statt verworfener Ereignisse

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/CYP-353-testplan`
> **Vor dem Fix geschrieben, heute rot.** Basis geprüft, nicht angenommen:
> `git merge-base --is-ancestor origin/develop HEAD` → `06263f8`.
>
> *(Der Auftrag nannte `b3a5870`. `origin/develop` ist inzwischen auf `06263f8` (CYP-348); `b3a5870` ist dessen
> Vorfahre. Ich habe auf dem aktuellen Kopf gebrancht und sage es, statt es stillschweigend zu tun.)*

---

## 1. Was ich nachgeprüft habe, statt es zu übernehmen

| Behauptung im Ticket | Verifiziert |
|---|---|
| `ReportGenerator.kt:103` zählt Meldungen | ✅ `val dropped = events.count { it.type == EventType.LOG_DROPPED }` |
| `EventRecorder.kt:100` legt `dropped` (Delta) **und** `total` (kumulativ) ins `detail` | ✅ `put("dropped", delta); put("total", total)` |
| `total` ist kumulativ **seit Serverstart** | ✅ `droppedCount` ist ein Feld des Recorders; ein neuer Prozess beginnt bei 0 |

Beide stimmen. **Ein dritter Punkt stand nicht im Ticket** — §3.

---

## 2. Der Trennversuch — gemessen, nicht behauptet

Ein roter Test, der nur „nicht 8" sagt, benennt keine Ursache. Der Test muss sagen, **welche** falsche
Implementierung läuft. Also habe ich die fünf plausiblen Varianten **gebaut** und den Test gegen jede gefahren.

**Fall 1:** Vorlast bis `total = 10` **vor** dem Fenster, dann zwei Meldungen mit Deltas 3 und 5 (Totals 13, 18).
**Fall 2:** dieselben Deltas, dazwischen ein **Serverneustart** (Totals 13, dann 5).
**Fenster:** die Vorlast liegt vor `since` und darf nicht zählen.

| Implementierung in `:103` | Fenster | **Fall 1** | Fall 2 |
|---|---|---|---|
| `count { LOG_DROPPED }` — **heute** | rot | **2** | 2 |
| `sum { detail.total }` | rot | **31** | 18 |
| `last.detail.total` | rot | **18** | 5 |
| `last.detail.dropped` | **grün** | **5** | 5 |
| `last.total − first.total` | rot | 5 | **rot** |
| **`sum { detail.dropped }` — richtig** | grün | **8** | grün |

Die vier vom Ticket vorhergesagten Zahlen (**2, 31, 18, 5**) treten exakt so auf. **Nur die Summe der Deltas
besteht alle drei Fälle.**

Zwei Beobachtungen, die man nur beim Bauen sieht:

* **`last.detail.dropped` überlebt den Fenstertest.** Wer nur die Fenster-Grenze prüft, hält diese
  Implementierung für korrekt. Erst Fall 1 tötet sie.
* **`last.total − first.total` meldet in Fall 2 gar nichts.** Die Differenz wird `5 − 13 = −8`, damit ist
  `if (dropped > 0)` falsch und **die Lücken-Zeile verschwindet vollständig**. Eine negative Zahl verworfener
  Ereignisse meldet nicht falsch — sie meldet nicht. Genau deshalb ist Fall 2 Pflicht.

Der Test parst die Zahl aus dem Reporttext, statt sie zu suchen: **`text.contains("8")` ist auch für `18` wahr**
— einen der vier falschen Werte. Ein Beleg, der die Frage nicht stellen kann, die er zu beantworten vorgibt,
ist kein Beleg.

---

## 3. Fünfter Befund (nicht im Ticket) — die Lücke erreicht den Report nie

`EventRecorder.reportDropsIfAny()` schreibt die Meldung mit

```kotlin
agentId = PLATFORM, projectId = PLATFORM      // "platform"
```

`ReportGenerator.query()` filtert seit CYP-255 projektbezogen:

```kotlin
EventFilter(..., projectId = state.activeProjectId)   // "default"
```

**`"platform" != "default"` ⇒ das `log.dropped`-Event wird herausgefiltert, bevor irgendetwas gezählt wird.**

Damit sitzt der Zählfehler **hinter einer Unsichtbarkeit**: Ob in `:103` `count` oder `sum` steht, ändert im
echten Betrieb nichts — die Zeile erscheint überhaupt nicht. Der Kommentar darüber lautet:

> *„log.dropped is itself an observation gap — surface it honestly, not as a clean bill."*

**Er stellt heute einen sauberen Befund aus, weil ihm die Beobachtung fehlt.** Dieselbe Klasse wie CYP-351:
eine Ableitung, die ihre Quelle nie bekommt.

**Belegt am echten Pfad**, nicht an einem nachgebauten Event: `Cyp353TelemetryGapNeverReachesTheReportTest`
fährt den **echten** `EventRecorder` (`capacity = 1`) gegen einen gestauten `LatchableEventSink`, erzwingt echte
Drops, prüft als Vorbedingung `recorder.dropped > 0` und `gap.projectId == "platform"` — und liest den Report
über denselben Sink. *(Lektion aus CYP-351: POSIX nachweisen ist nicht unser System nachweisen.)*

### Severity

| # | Befund | Grad |
|---|---|---|
| 1 | Die Telemetrie-Lücke erscheint im Report **überhaupt nicht** (Projekt-Scope) | **Live-Lücke** — der Operator sieht einen sauberen Bericht, während Ereignisse verloren gehen |
| 2 | Wenn sie erschiene, nennte sie die **Zahl der Meldungen** statt der verworfenen Ereignisse | **Live-Lücke**, aber heute von #1 verdeckt |

**Reihenfolge des Fixes:** #1 zuerst. Wer nur `:103` repariert, macht eine Zahl richtig, die niemand zu sehen
bekommt — und ein grüner Fall-1-Test würde ihm bescheinigen, fertig zu sein. Deshalb liegen die Fälle 1/2 auf
Fixtures mit `projectId = "default"`: **so benennt jeder rote Test genau eine Ursache.** Zwei Defekte in einem
Testfall ergeben einen Test, der nach dem halben Fix immer noch rot ist und niemandem sagt, warum.

---

## 4. Was der Fix **nicht** leistet

**Er macht die Zahl richtig, nicht vollständig.**

`droppedCount` zählt, *dass* verworfen wurde — nie, *was*. Die verworfenen Ereignisse existieren nirgends; sie
wurden nie geschrieben. Der Report kann nach dem Fix sagen: „8 Ereignisse verloren." Er kann **nicht** sagen,
welche, von welchem Agenten, welcher Schwere. Ein Fehler, der in genau diesen 8 steckte, bleibt unsichtbar.

**Das gehört ins Dokument, nicht in die Zusage.** Ein Report, der „8 verworfen" meldet, ist ehrlich; einer, der
daraus „alles andere ist vollständig" folgert, ist es nicht. *Verworfen ist verworfen; was fehlt, weiß niemand.*

Zusätzlich ungeprüft und hier benannt:

* **Der `total`-Wert selbst.** Nach dem Fix wird er nicht mehr für die Summe gebraucht. Ob er im Report
  erscheinen soll (als „kumulativ seit Serverstart"), ist eine Produktentscheidung, keine QA-Frage.
* **Mehrere Serverneustarts im selben Fenster.** Fall 2 prüft einen. Die Summe der Deltas ist gegen beliebig
  viele robust — das ist Argument, nicht Messung.

---

## 5. Eine Leitplanke, die der Fix braucht

`ReportGenerator` dokumentiert als **Hard-Enforcement** (Reviewer-Fokus):

> *„every `ReportItem` is composed from event/message **metadata only** … and NEVER from `Event.detail`."*

**Der Fix muss `detail` lesen.** Das ist kein Widerspruch, aber es ist eine Grenze, die jemand ziehen muss,
bevor der nächste Reviewer entweder den Fix blockiert oder die Regel im Ganzen aufweicht:

> **Einen Zähler aus `detail` lesen ist erlaubt. `detail` ausschütten ist es nicht.**

`readingTheCounterFromDetail_neverLeaksTheRestOfDetail` nagelt das fest: Die `log.dropped`-Meldung bekommt ein
`secret`-Feld ins `detail`; die Zahl darf gelesen werden, das Feld darf nirgends im Report auftauchen.
Der Test ist **heute grün — aus dem falschen Grund** (es wird gar nichts aus `detail` gelesen). Sein Zweck
beginnt mit dem Fix. Ich sage das, statt ihn als „grün, also sicher" zu verkaufen.

---

## 6. Stand

```
:server:test --rerun-tasks     937 Tests, 4 rot, 3 skipped     alle Reports frisch

  Cyp353DroppedIsASumNotACountTest              3 rot / 5
     ROT  twoReportsOverThreeAndFive_areEightLostEvents_notTwoMessages   (gelesen: 2)
     ROT  serverRestartResetsTotal_theSumOfDeltasSurvivesIt              (gelesen: 2)
     ROT  theWindowIsHonored_preloadOutsideItIsNotCounted                (erwartet 3, gelesen 1)
     GRÜN withoutAnyDrop_thereIsNoGapLine_…                              (heute korrekt)
     GRÜN readingTheCounterFromDetail_neverLeaksTheRestOfDetail          (grün aus dem falschen Grund, §5)

  Cyp353TelemetryGapNeverReachesTheReportTest   1 rot / 1
     ROT  realDroppedEventsAreScopedToPlatform_andTheProjectScopedReportNeverSeesThem
```

**Die 4 roten sind die einzigen roten im gesamten `:server:test`** — keine Kollateralschäden.
Sie bleiben rot, bis der Fix steht. Danach sind sie seine Wächter.

> Diese Tests liegen in Backend2s Modul. Sie stehen auf meinem Branch, damit er sie **hat** —
> nicht, damit sie von dort landen. Ein roter Test auf `develop` nimmt allen das Gate:
> **Beweistest und Fix landen in einem Zug.**
