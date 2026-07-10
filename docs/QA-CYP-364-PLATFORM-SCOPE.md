# QA-Messung — CYP-364: Ist `log.dropped` ein Plattform- oder ein Projekt-Ereignis?

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/CYP-364-investigation`, Basis `origin/develop` @ `f04b372`
> (`git merge-base --is-ancestor origin/develop HEAD` geprüft, nicht angenommen.)
>
> Auftrag: **eine Messung, keine Meinung.** Was der Code *tut*, nicht was er meinen könnte.

---

## 1. Antwort

**`log.dropped` ist ein Plattform-Ereignis. `EventRecorder` schreibt den richtigen `projectId`.
Der Defekt sitzt auf der Leseseite: `ReportGenerator` muss Plattform-Ereignisse *zusätzlich* zum aktiven
Projekt einbeziehen.**

---

## 2. Woraus das folgt — drei Messungen

### 2.1 Beide `PLATFORM`-Konstanten bedeuten dasselbe

Kein zufällig gleicher String. Beide sind ein **Sentinel für „gehört zu keinem Projekt"**:

| Ort | Was der Code sagt |
|---|---|
| `SpoolReader.kt:93-94` | `projectId = obj.str("projectId") ?: obj.str("teamId") ?: PLATFORM` — Kommentar: *„else the PLATFORM sentinel (platform-internal hook event, **no specific project**)"*. Also der **Fallback**, wenn die Spool-Zeile kein Projekt nennt. |
| `EventRecorder.kt:123` | *„System source id for **platform-level** telemetry (e.g. `log.dropped`)."* |

### 2.2 Ein Verwurf ist keinem Projekt zurechenbar

* **Ein** `EventRecorder` pro Server: `BootOrchestrator.kt:288`, Kommentar *„one EventRecorder feeds the shared sink"*.
* In **seine eine** Warteschlange schreiben mindestens vier Quellen mit **verschiedenen** `projectId`s:

| Quelle | `projectId` |
|---|---|
| `HubWireRoutes.kt:220` | `activeProjectId()` |
| `SignalSink.kt:39` | `signal.projectId` (beliebig) |
| `SpoolTailer` → `SpoolReader.kt:94` | pro Spool-Zeile, sonst Sentinel |
| `ConnectorOptIn.kt:39` | `projectId()` |

* `droppedCount` zählt über **alle** hinweg. Was verworfen wird, entscheidet der **Rückstau**, nicht das Projekt.

**Also:** Das verlorene Ereignis kann jedem Projekt gehört haben. Es dem *aktiven* zuzuschreiben wäre eine
**Erfindung** — und der Verwurf hat die Information vernichtet, die sie widerlegen könnte.

Gemessen in `dropsAreProjectAgnostic_soTheReportEventCarriesTheSentinel_notAProject`: drei Projekte speisen
eine gestaute Warteschlange, echte Drops, und die Meldung trägt den Sentinel **und keine** Projekt-Zuordnung
(`gap.detail["projectId"] == null`). **Grün — sie misst, was heute korrekt ist.**

### 2.3 Der Sentinel ist kein anforderbarer Projektname

`resolveEventScope("platform", active, authorized)` → **`active`**. `"platform"` steht nie in
`authorizedProjects`, der Resolver fällt fail-closed zurück. **Der einzige Weg zu Plattform-Ereignissen ist
`unscoped` (`projectId = null`)**, den `?projectId=all` einem Operator öffnet.

---

## 3. Die Asymmetrie — derselbe Event-Log, zwei Leser, nur einer ist blind

| Leser | Scope | Sieht `log.dropped`? |
|---|---|---|
| `/api/events`, `/ws/events` (Operator, `?projectId=all`) | `resolveEventScope(…)` → `null` | **ja** |
| `ReportGenerator.kt:123` | `projectId = state.activeProjectId`, **fest verdrahtet** | **nein** |

Der Report hat **keinen** unscoped Ausweg. Die Folge ist nicht eine falsche Zahl, sondern **Stille**:
`if (dropped > 0)` ist nie wahr ⇒ die Zeile erscheint nie ⇒ **ihre Abwesenheit wird als „nichts verworfen"
gelesen.**

Der Kommentar über der Stelle lautet: *„log.dropped is itself an observation gap — surface it honestly, not as
a clean bill."* Genau das tut er nicht. **Er stellt einen sauberen Befund aus, weil ihm die Beobachtung fehlt.**

---

## 4. Der Guard — und warum ein einzelner Test ihn nicht leisten kann

Der Guard (`theEventBrowserSeesTheGap_butTheProjectScopedReportSwallowsIt`) belegt **erst die Vorbedingung
positiv**, dann die Sichtbarkeit:

1. Der unscoped Leser **sieht** die Meldung → das Ereignis liegt nachweislich im Sink.
2. Der projekt-gefilterte Leser sieht sie nicht, **sieht aber die Projekt-Defekte sehr wohl** → der Filter ist
   der Mechanismus, nicht ein leerer Sink.
3. *Dann* erst: der Report muss sie melden. **Heute rot.**

Ohne Schritt 1 und 2 wäre es eine Abwesenheits-Assertion, die auch bei einem leeren Sink „bestünde".

**Die naheliegende Über-Korrektur ist „dann filtern wir eben gar nicht".** Sie macht den Guard grün und reißt
die CYP-255-Grenze nieder. Deshalb steht daneben `theFixMustNotUnscopeTheReport_foreignProjectsStayOut`.
Gemessen, nicht behauptet:

| Zustand von `ReportGenerator.query()` | Guard | Leitplanke |
|---|---|---|
| heute (`projectId = activeProjectId`) | **ROT** | grün |
| **Fix A:** aktives Projekt **plus** `"platform"` | grün | grün |
| **Fix B:** Filter entfernt (unscoped) | grün | **ROT** |

**Kein einzelner Test lenkt den Fix. Das Paar tut es** — es gibt keine Änderung, die beide grün macht, außer
`platform` **zusätzlich** einzulassen.

---

## 5. Reihenfolge (bestätigt)

**CYP-364 vor oder mit CYP-353. Nie CYP-353 allein.**
Wer nur die Arithmetik repariert, bekommt eine korrekt gerechnete **Null** und hält sie für einen Beleg.

---

## 6. Was diese Messung **nicht** sagt

* **Ob `HOOK_FIRED` mit Sentinel-Projekt dasselbe Problem hat.** `SpoolReader` vergibt denselben Sentinel an
  Hook-Ereignisse ohne `projectId`. Sie verschwinden aus dem Report auf demselben Weg. **Nicht gemessen** —
  ich habe keinen Spool-Pfad gefahren. Der Fix aus §4 würde sie mit einschließen; das ist eine Folge, kein Beleg.
* **Ob ein Mehr-Projekt-Betrieb existiert.** MVP ist Ein-Projekt (`all == unscoped`, `EventScope.kt:14`). Die
  Zurechenbarkeits-Aussage aus §2.2 gilt trotzdem: sie folgt aus der **geteilten Warteschlange**, nicht aus der
  Anzahl der Projekte.
* **Ob der Report `platform` auch anzeigen *soll*, wo kein Drop vorliegt.** Das ist Produkt, nicht QA.

---

## 7. Stand

```
:server:test --rerun-tasks     935 Tests, 1 rot, 3 skipped     Reports frisch
  Cyp364PlatformScopeTest   1 rot / 4
     ROT   theEventBrowserSeesTheGap_butTheProjectScopedReportSwallowsIt
     GRÜN  dropsAreProjectAgnostic_…                (Messung: heute korrekt)
     GRÜN  theSentinelIsNotARequestableProject_…    (Messung: heute korrekt)
     GRÜN  theFixMustNotUnscopeTheReport_…          (Leitplanke gegen Fix B)
```

Die eine rote ist die einzige rote im gesamten `:server:test` — keine Kollateralschäden.

> Referenzbranch, kein Merge-Wunsch. Der rote Guard nimmt allen das Gate:
> **Beweistest und Fix landen in einem Zug** — und CYP-353 hängt hinten dran.
