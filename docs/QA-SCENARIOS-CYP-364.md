# QA-Testplan — CYP-364: Der Report ist blind für die Telemetrie-Lücke

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/CYP-364-testplan`, Basis `origin/develop` @ `3bb2d41`
> (`git merge-base --is-ancestor origin/develop HEAD` geprüft.) Enthält die Messung aus `9c75795` (cherry-pick).
>
> **Vor dem Fix geschrieben. Alle vier Fälle heute rot.** Ich baue den Fix nicht — Backend2 baut, nach CYP-351.

Vorlauf: `QA-CYP-364-PLATFORM-SCOPE.md` (die Messung) · `QA-SCENARIOS-CYP-353.md` (die Arithmetik).

---

## 1. Die beiden Defekte liegen hintereinander auf einem Pfad

```
Sink ──(Projekt-Filter: CYP-364)──> events ──(count statt sum: CYP-353)──> Zeile
```

**Ein Test, der den Filter umgeht und direkt auf `events` rechnet, prüft die Arithmetik und übersieht, dass sie
nie ein Argument bekommt.** Deshalb geht Fall 3 durch den **echten** `ReportGenerator.build(...)`.

---

## 2. Der dritte Defekt, der aus der Messung folgt — der Bezugsrahmen

Die Messung (§2.2 des Vorberichts) ergab: **ein `EventRecorder` pro Server, vier Quellen mit verschiedenen
`projectId`s in einer Warteschlange, und der Rückstau entscheidet, was verworfen wird — nicht das Projekt.**

Daraus folgt zwingend, und ich hatte es gemessen, ohne es auszusprechen:

> **Die Verwurf-Zahl ist server-weit. Der Report ist projektbezogen.**

`ReportSnapshot` trägt ein Feld `projectId` (`ReportModel.kt:48`) — **das Dokument weist sich selbst als
Projekt-Report aus.** Eine unbeschriftete server-weite Zahl darin liest sich als *„in diesem Projekt
verworfen"*. Wer nur die Zahl repariert, hat die Ableitung **vom Filter an die Anzeige verschoben**; der
Filter-Fix erzeugte dann sauber die nächste Lüge.

> Lieber eine unbequeme Zahl mit richtigem Bezugsrahmen als eine bequeme mit falschem.

**Test-Contract (Wortschatz).** Die Zeile muss ihren Bezugsrahmen nennen; akzeptiert wird eines von
`plattformweit · serverweit · server-weit · alle projekte`. Der Fix darf formulieren, wie er will —
**wer den Wortschatz erweitert, ändert ihn sichtbar im Test**, statt still ein neues Wort in die Produktion zu
schreiben.

---

## 3. Die Fälle

| # | Test | Zusage |
|---|---|---|
| 1a | `aPlatformScopedDropEvent_reachesTheReportOfAnyActiveProject` | Ein `log.dropped` **im Sink** (Vorbedingung positiv belegt) erscheint im Report, obwohl das aktive Projekt `default ≠ platform` ist |
| 1b | `theSentinelIsVisibleRegardlessOfWhichProjectIsActive` | dasselbe mit aktivem Projekt `alpha` — der Sentinel gehört keinem Projekt, also **jedem** Report |
| 2 | `theRenderedLine_declaresTheNumberAsPlatformWide_notAsThisProjects` | die gerenderte Zeile weist die Zahl als **plattformweit** aus |
| 3 | `throughTheRealFilter_theNumberIsTheSumOfDeltasInTheWindow` | Vorlast `total = 10` vor dem Fenster, Deltas 3 + 5 ⇒ **8**, **durch den echten Filter hindurch** |

**Vorbedingungen zuerst, überall.** Jeder Fall belegt positiv, dass das Ereignis im Sink liegt (unscoped
abrufbar) und dass der Report echte Defekte meldet — *bevor* er eine Abwesenheit oder eine Zahl prüft. Ohne
das wäre ein leerer Sink ein bestandener Test.

**Die Zahl wird geparst, nicht gesucht.** `contains("8")` ist auch für `18` wahr — einen der vier falschen
Werte aus CYP-353.

---

## 4. Sind die vier Fälle unabhängige Hebel? — gemessen, nicht behauptet

Die Fixes gestaffelt angelegt, jeweils `:server:test --rerun-tasks`, Reports frisch:

| Zustand von `ReportGenerator` | 1a | 1b | 2 | 3 |
|---|---|---|---|---|
| **heute** | ROT | ROT | ROT | ROT |
| **+ Filter** (aktives Projekt **plus** `platform`) | grün | grün | **ROT** | ROT |
| **+ Beschriftung** (`… plattformweit verworfen …`) | grün | grün | grün | **ROT** |
| **+ Summe** (`sum { detail.dropped }`, CYP-353) | grün | grün | grün | grün |

**Jeder Fix macht genau seinen Fall grün. Fall 3 erst, wenn beide drin sind.** Keine Stufe der Treppe lässt
sich überspringen, ohne dass ein Test es sagt.

### Eine Ehrlichkeit, die ins Protokoll gehört

**Fall 2 ist heute an seiner *Vorbedingung* rot, nicht an der Beschriftung** — die Zeile existiert ja gar nicht.
Er wird erst nach dem Filter-Fix zu einem Beschriftungstest. Ein „4 von 4 rot" verschweigt das. Die Treppe oben
zeigt es: Fall 2 bleibt rot, **wenn alles andere schon grün ist**, und genau dort beweist er etwas.

*(Nebenbei: Der erste Lauf meldete „1 failure" — es war ein `InvalidTestClassError`. JUnit4 verlangt `void`;
zwei Methoden gaben den Wert von `assertNotNull` zurück. **Die Klasse lief nie.** Ein rotes Ergebnis, das kein
roter Test ist. Behoben; das ist der Grund, warum ich Testnamen aus dem Report lese und nicht nur die
Fehlerzahl.)*

---

## 5. Was ich **nicht** geprüft habe

* **`HOOK_FIRED` mit Sentinel-Projekt.** `SpoolReader.kt:94` vergibt denselben Sentinel an Hook-Ereignisse ohne
  `projectId`; sie verschwinden auf demselben Weg aus dem Report. Der Filter-Fix schlösse sie ein — **das ist
  eine Folge, kein Beleg.** Ich bin keinen Spool-Pfad gefahren.
* **Der Renderer.** Ich prüfe `ReportItem.text` im Server. Wie die UI die Zeile darstellt (Tooltip, Badge,
  Farbe), ist ungeprüft; eine korrekt beschriftete Zeile kann in der Anzeige immer noch neben einer
  Projekt-Überschrift stehen.
* **Der echte `EventRecorder` im Fall 3.** Fall 3 stellt die drei Meldungen selbst in den Sink, um Fenster und
  Deltas exakt zu setzen. Dass der echte Recorder genau so schreibt, ist in
  `Cyp364PlatformScopeTest.dropsAreProjectAgnostic_…` **separat gemessen** (echter Recorder, echte Drops).

---

## 6. Stand

```
:server:test --rerun-tasks     Reports frisch

  Cyp364ReadPathTest        4 rot / 4    (alle vier: heute rot, wie sie sollen)
  Cyp364PlatformScopeTest   1 rot / 4    (drei Messungen grün — sie messen, was heute stimmt)
```

> Referenzbranch, kein Merge-Wunsch. Rote Tests auf `develop` nähmen allen das Gate.
> **Reihenfolge: CYP-364 vor oder mit CYP-353. Nie CYP-353 allein.**
> Drei rote Testpläne (`CYP-351`, `CYP-353`, `CYP-364`) liegen gleichzeitig — kein Rückstand,
> sondern der Beweis, dass die Reihenfolge bekannt ist.
