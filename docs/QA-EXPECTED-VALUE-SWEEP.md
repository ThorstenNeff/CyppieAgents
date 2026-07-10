# QA-Sweep — Der Erwartungswert läuft durch die geprüfte Naht

> QA / Test Engineer (Team2) · 2026-07-10 · Basis: `develop` @ `7665733` · proaktiver Pass, kein Auftrag
> Vorgänger: `QA-VACUUM-SWEEP.md` (Abwesenheit ohne Vorbedingung). Dies ist die **andere Richtung**.

---

## 1. Die Fehlerklasse

`SeverityColorsTest` (CYP-358) zog seinen Sollwert aus einer **zweiten Kopie derselben Formel**. Der Test war
grün, weil beide Seiten identisch falsch rechnen konnten. Die allgemeine Form:

> **Ein Test, dessen Erwartungswert durch dieselbe Naht läuft, die er prüft, prüft nur sich selbst.**

Der Vakuum-Sweep suchte Tests, die **nichts** sehen. Dieser sucht Tests, die **alles bestätigen**.

## 2. Suche

Automatisch über alle Testquellsätze: Prod-Symbole, die ein Test importiert und dann im **Erwartungs-Slot** einer
Assertion aufruft (`assertEquals(prodFn(...), ...)`).

**Ein Treffer:** `core/…/ColorSlotTest.kt` — `unknownIds_areDeterministic`.

```kotlin
assertEquals(colorSlot("alice"), colorSlot("alice"))
```

Eine **reine Funktion** liefert im selben Lauf zwangsläufig denselben Wert. Der Test konnte nicht rot werden —
außer jemand hätte `Random` eingebaut. Sein Name verspricht Determinismus; gepinnt hat er nichts.

Das ist mehr als ein kosmetischer Nit, weil der KDoc der Produktion eine **prüfbare** Zusage macht:

> *„No randomness, no runtime seed — **stable across sessions and clients**."*

Diese Zusage hatte **null** Deckung.

## 3. Was die Suche zusätzlich zutage förderte

Beim Lesen der Produktion (`core/…/model/ColorSlot.kt`) fielen zwei ungetestete Zweige auf:

* `KNOWN_SENDER_SLOTS[id]?.let { if (it < paletteSize) return it }` — der Guard.
* `require(paletteSize > 0)`.

Und eine **Kopplung, die kein Test hielt**: `ColorSlotTest.slot_isWithinPalette` prüft die Schranke gegen die
Konstante `SENDER_PALETTE_SIZE`. `SenderPalette.forSender` übergibt aber `senders.size` und indiziert die Liste
direkt:

```kotlin
senders[colorSlot(id, senders.size)]
```

Heute sind beide Zahlen 8. **Fallen sie auseinander, bleibt der `:core`-Test grün und beweist eine Grenze, die
die Produktion nicht benutzt** — und `forSender` indiziert daneben. Genau dann würde der Guard tragend, den
niemand testet.

## 4. Befund — belegt durch Mutation, nicht durch Lesen

Alle Läufe `:core:jvmTest --rerun-tasks`, Report-Alter jeweils 0 s.

| Mutation in `ColorSlot.kt` | vorher | nachher |
|---|---|---|
| M1 · FNV-Prime `16777619u → 16777639u` (der Hash wandert) | **grün** | ROT (3 Tests) |
| M1b · `encodeToByteArray()` → UTF-16-Zeichen (wie `String.hashCode`) | **grün** | ROT (1 Test) |
| M2 · Guard `if (it < paletteSize)` entfernt | **grün** | ROT |
| M3 · `require(paletteSize > 0)` entfernt | **grün** | ROT |

**Vier Defekte, die die bestehende Suite geschlossen durchgelassen hat.** M1b fällt ausschließlich über den
Nicht-ASCII-Test — bei reinen ASCII-ids ist ein Wechsel auf `String.hashCode()` unsichtbar und fiele erst bei
einem Umlaut-Agentennamen auf, dann aber **pro Plattform verschieden**.

### Severity

| # | Befund | Grad |
|---|---|---|
| 1 | Farbzuordnung ist **ungepinnt**: jede Hash-Änderung würfelt alle Identitätsfarben neu, das Gate bleibt grün | **Fragilität** (keine Live-Lücke: der Code ist heute korrekt) |
| 2 | `SENDER_PALETTE_SIZE` ↔ `SenderPalette.swatches` **entkoppelt** | **Fragilität**, wird zur Live-Lücke, sobald ein Farbton entfällt |
| 3 | Guard + `require` ungetestet | **Fragilität** (Zweig heute unerreichbar) |

**Keine Live-Lücke.** Der Produktionscode ist korrekt; ungeschützt ist er trotzdem. Ich sage das ausdrücklich,
damit die Meldung nicht schwerer klingt, als sie ist.

## 5. Fix

`ColorSlotTest`: `unknownIds_areDeterministic` **ersetzt** durch `unknownIds_arePinnedToTheFnv1aSlot_notMerelyRepeatable`.
Die Sollwerte sind **unabhängig nach der FNV-1a-Spezifikation gerechnet** (offset basis 2166136261, prime
16777619, über die UTF-8-Bytes) — nicht aus `colorSlot` gezogen. Dazu:

* `customPaletteSize_pinsTheSlot_notJustItsRange` — der Modulus muss der Palettengröße folgen. *(Ersetzt
  `customPaletteSize_isRespected`, das ein `return 0` bestanden hätte.)*
* `nonAsciiIds_hashOverUtf8Bytes` — pinnt die Byte-Basis.
* `knownSlotOutsideASmallPalette_fallsBackToTheHash` — der Guard.
* `nonPositivePaletteSize_isRejected` — das `require`.

Neu: `app/shared/…/ui/SenderPaletteSlotCouplingTest.kt` hält die Konstante und die real indizierte Liste
zusammen — dort, wo beide Seiten sichtbar sind.

`commonTest` läuft auf **JVM, JS und wasm**: derselbe Sollwert auf jedem Target ist die einzige Stelle, an der
*„clients färben identisch"* tatsächlich gemessen wird. Vorher lief der Test dort mit und maß nichts.

## 6. Beleg

```
:core:jvmTest :core:wasmJsBrowserTest :core:jsBrowserTest
:app:shared:jvmTest :app:shared:wasmJsBrowserTest :app:shared:jsBrowserTest   --rerun-tasks

  core/jvmTest              122 Tests, 0 rot        ColorSlotTest 8/8
  core/wasmJsBrowserTest    122 Tests, 0 rot        ColorSlotTest 8/8
  core/jsBrowserTest        122 Tests, 0 rot        ColorSlotTest 8/8
  app/shared/jvmTest        783 Tests, 0 rot
  app/shared/wasmJsBrowserTest 187 Tests, 0 rot
  app/shared/jsBrowserTest  177 Tests, 0 rot
  SUMME                    1513 Tests, 0 rot        alle Reports frisch (110 Tasks ausgefuehrt)
```

## 7. Was dieser Sweep **nicht** gefunden hat

Die Suche greift nur den syntaktischen Fall `assertEquals(prodFn(…), …)`. Sie findet **nicht**:

* den Sollwert, der über eine **Zwischenvariable** aus der Naht kommt (`val expected = prodFn(x)`),
* die **nachgebaute Formel** (der CYP-358-Fall selbst — eine zweite Kopie, kein Aufruf),
* Erwartungswerte aus einer **Fixture**, die derselbe Code erzeugt hat.

**Der Sweep hätte seinen eigenen Anlassfall nicht gefunden.** Das ist die ehrliche Grenze: Er deckt die billigste
Variante der Klasse ab, nicht die Klasse. Die teure Variante braucht ein Auge, keinen `grep`.
