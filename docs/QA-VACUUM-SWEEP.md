# QA-Sweep — Abwesenheits-Assertions ohne positiv belegte Vorbedingung

> QA / Test Engineer (Team2) · 2026-07-10 · Basis: `develop` @ `7665733` · Branch `qa/vacuum-sweep`
> Anlass: Die Fehlerklasse ist heute **zweimal zufällig** aufgefallen (CYP-340, CYP-352). Beim dritten Mal
> gesucht statt gefunden.

---

## 1. Die Fehlerklasse

> **Eine Abwesenheits-Assertion beweist nichts über den Grund der Abwesenheit.** Ein Bildschirm, der gar nichts
> komponiert hat, erfüllt jedes `assertDoesNotExist`. Behauptet der Test damit eine *Eigenschaft* — erst recht
> eine **Sicherheitsaussage** —, ist er grün, ohne sie geprüft zu haben.

Die Regel aus CYP-340, wörtlich, weil ihre Formulierung sich hier bewährt hat:

> **Keine Abwesenheits-Assertion ohne positiv belegte Vorbedingung.**

Nicht „ohne `assertExists`". Siehe §4, Fall 3 — die engere Fassung hätte in diesem Sweep zwei korrekte Tests
verschlechtert.

---

## 2. Methode

1. Statischer Scan über alle Compose-UI-Tests (`runComposeUiTest`) in `app/shared/src/*Test`: Testkörper mit
   `assertDoesNotExist` / `assertIsNotDisplayed` und **ohne** jede positive Knoten-Assertion.
2. Jeder Treffer **von Hand gelesen** — der Scanner ist ein Suchwerkzeug, kein Urteil.
3. Der einzige echte Verdacht wurde **per Mutation entschieden**, nicht per Argument: der Prüfling wird
   kurzgeschlossen (rendert nichts). Bleibt der Test grün, ist er vakuum-fähig.
4. Bei jedem Lauf die **Änderungszeit des Testreports** mitgelesen. Ein Testreport, dessen Herkunft man nicht
   kennt, ist kein Beleg — dieser Sweep entstand teilweise, weil ich genau darauf einmal hereingefallen bin.

**7 Treffer. 1 echter Fund. 6 erklärt.**

---

## 3. Der Fund

`CompactPanelTest.nonOperator_hasNoEventSection` — eine **Gating-Aussage**: ein Member sieht keinen
Event-Abschnitt.

`CompactPanel` kurzgeschlossen (`if (true) return`), `CompactPanelTest` gefahren:

| | Ergebnis |
|---|---|
| **vorher** | 13 Tests, **12 rot** — grün blieb **nur** `nonOperator_hasNoEventSection` |
| **nachher** (Deckung: `compact.panel` + `compact.gateHint`) | 13 Tests, **13 rot** |
| unmutiert, nachher | 13 Tests, **0 rot** |

**Zwölf Tests schrien, einer nickte — und ausgerechnet der trug die Sicherheitsaussage.**
Die Trennprobe in der richtigen Richtung: nicht „der neue Test wird rot", sondern **„der alte blieb grün, wo er
es nicht durfte"**. Der Guard ist der Unterschied, nicht die Mutation.

---

## 4. Die sechs Nicht-Funde — drei verschiedene Gründe

**Fall 1 — Fehlalarm des Scanners.**
`AgentWindowCapabilityBadgeTest → src`. `src()` ist eine **private Hilfsfunktion**, kein Test. Mein Regex hielt
sie für einen Testkörper. *Ein Sweep, der nur seine Treffer nennt, täuscht eine Trefferquote vor.*

**Fall 2 — strukturell nicht deckbar, aber gedeckt.**
`ConnectorCapabilityViewTest.providerChip_absentWhenNull_…`, `…badge_absentForFullAgent_…` und
`ConnectorCapabilityLoadSuppressionTest`. Das Composable rendert im Null-Fall **per Design nichts**:

```kotlin
val p = provider ?: return   // Fail-closed by absence
```

Es gibt keinen zweiten Knoten, an dem man sich festhalten könnte — eine Deckung **im** Test ist unmöglich. Drei
Geschwistertests derselben Datei assertieren die Anwesenheit im Positiv-Fall und sterben, wenn das Composable
stirbt. **Die Deckung läuft über die Datei, nicht über den Test.** Das ist eine schwächere Aussage als eine
Deckung im Testkörper, und sie steht hier als schwächere Aussage.

**Fall 3 — gedeckt, aber nicht über einen UI-Knoten.**
`AclReconnectTest` und `EventTailReconnectTest`:

```kotlin
waitUntil { source.subscriptions.value >= 2 && onAllNodesWithTag(CONNECTION).fetchSemanticsNodes().isEmpty() }
```

`subscriptions.value >= 2` belegt **positiv**, dass der Client wieder verbunden ist. Kein `assertExists`, und
trotzdem eine echte Vorbedingung. **Genau deshalb heißt die Regel „positiv belegte Vorbedingung".** Die engere
Fassung hätte hier zwei korrekte Tests „repariert" und einen starken Beleg durch eine schwächere
Knoten-Assertion ersetzt.

---

## 5. Was dieser Sweep **nicht** deckt

Er hat eine Fehlerklasse in einer Schicht gesucht. Er ist kein Freibrief.

| Nicht gedeckt | Warum |
|---|---|
| **Vorbedingungen außerhalb der Datei** | Fall 2: die Deckung liegt bei Geschwistertests. Wird der Positiv-Test gelöscht, verliert der Negativ-Test seine Deckung — **und nichts merkt es.** Der Scanner sieht Testkörper, nicht Dateien, und schon gar nicht Absichten. |
| **Vorbedingungen, die keine Knoten sind** | Fall 3: der Scanner kann `subscriptions.value >= 2` nicht von Dekoration unterscheiden. Ein Mensch schon. |
| **Nicht-UI-Tests** | `assertNull` auf einem Reducer ist unbedenklich: die Funktion **lief**. Die Vakuum-Klasse braucht ein Medium, das schweigen kann — eine Oberfläche, einen DOM, ein Bild. |
| **Maestro-Flows** | Anderer Mechanismus (`assertNotVisible`), anderes Werkzeug. Klassifiziert in `QA-MAESTRO-FLOW-AUDIT-CYP-340.md`. |
| **Mobile-Targets** | `androidHostTest` und `iosTest` wurden **nicht ausgeführt** — Browser-only, kein Emulator, keine KVM. Der Scan hat dort Treffer gemeldet; sie sind **erschlossen, nicht belegt**. |
| **Die andere Richtung** | Ein Test, der eine *Anwesenheit* behauptet, kann ebenso aus dem falschen Grund grün sein — etwa wenn sein Erwartungswert durch dieselbe Naht läuft, die er prüft (CYP-343, CYP-358). Dieser Sweep sucht das nicht. |

---

## 6. Warum ich **keinen** automatischen Wachhund vorschlage

Ich hatte angeboten, die Regel als Test über die Testquellen zu gießen. Der Sweep beantwortet das selbst:

**1 Fund, 1 Fehlalarm, 3 unmögliche Fälle, 2 falsch-positive Treffer** — und der gefährlichste Fehlalarm wäre
der gewesen, der einen **guten** Test durch eine schwächere Assertion ersetzt (Fall 3).

Ein Wächter mit dieser Quote wird nach dem dritten Fehlalarm abgeschaltet, und dann ist auch der eine echte Fund
weg. **Eine Regel, die ihre eigenen Ausnahmen erzwingt, stirbt an ihnen.**

Die Regel gehört in den **Test-Contract**, gelesen von Menschen. Sie hat heute dreimal funktioniert — zweimal
angewandt von zwei verschiedenen Leuten, unabhängig, am selben Tag.
