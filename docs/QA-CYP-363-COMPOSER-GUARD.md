# QA — CYP-363: Der Guard ist die Lieferung, die Konstante nur ihr heutiger Wert

> QA / Test Engineer (Team2) · 2026-07-10 · Branch `qa/CYP-363-guard`, Basis `origin/develop` @ `a9df121`
> Auftrag: **die Trennprobe zum Guard, nicht zur Zahl.** *Gibt es eine Chrome-Änderung, die den Guard grün
> lässt und den Composer trotzdem tötet?*

---

## 1. Antwort

**Ja — und es braucht dafür keine Chrome-Änderung. Der bestehende Guard ist heute grün, während er ein
6 dp hohes Transkript rendert.**

Zwei gemessene Gründe:

1. **`assertIsDisplayed()` ist bei 1 dp grün.** Es unterscheidet „vorhanden" nicht von „zu klein zum
   Benutzen".
2. **`AgentWindowMinHeightTest` rendert nie den Resize-Boden.** Er fährt den gekachelten Pfad. Die Ecke, in der
   beide Invarianten gleichzeitig an ihrem Boden stehen, rendert kein Test.

---

## 2. Die Messungen

Alle am **echten** Stapel (`FloatingWindow` + `AgentWindow`), nicht an Konstantenarithmetik.
Berichte je 0 s alt, `--rerun-tasks`.

### 2.1 Was `assertIsDisplayed()` sieht

| Knotenhöhe | `assertIsDisplayed()` |
|---|---|
| 0 dp | ROT |
| **1 dp** | **GRÜN** |
| 4 dp | GRÜN |
| 33 dp | GRÜN |

**Ein Composer von 1 dp besteht den Guard.** Die Assertion beantwortet die Frage „existiert der Knoten und
liegt er im Fenster", nicht „kann ein Mensch ihn bedienen".

### 2.2 Was der bestehende Guard tatsächlich rendert

Instrumentiert (Wegwerf-Kopie, wieder entfernt):

```
PROBE-ALT po        composerH = 57 dp   composerW = 236 dp   transcriptH =  6 dp   -> GRÜN
PROBE-ALT backend   composerH = 57 dp   composerW = 236 dp   transcriptH =  6 dp   -> GRÜN
```

**Das Transkript ist 6 dp hoch, und der Guard sagt „bestanden".** Seine Grünheit liegt sechs Pixel neben der
Bedeutungslosigkeit — und seine Assertion kann 6 dp von 60 dp nicht unterscheiden.

### 2.3 Das Fenster am Resize-Boden

Ein Content-Fenster darf auf `TILED_CONTENT_WINDOW_MIN_WIDTH = 320` **und** `CONTENT_WINDOW_MIN_HEIGHT = 301`
gezogen werden — **beide Invarianten gleichzeitig an ihrem Boden.**

```
Fenster 520 x 301   composer = 57 dp   transcript = 84 dp     gesund
Fenster 400 x 301   composer = 33 dp   transcript =  0 dp
Fenster 320 x 301   composer = 33 dp   transcript =  0 dp     <- die Invarianten-Ecke
Fenster 320 x 283   composer = 15 dp   transcript =  0 dp     <- der CYP-350-Zielwert (KDoc)
Fenster 320 x 260   composer =  0 dp   transcript =  0 dp
```

**Die Höhe 301 trägt erst ab ~520 dp Breite** — genau dort, wo der Header aufhört umzubrechen. Der KDoc der
Konstante sagt das über den Header (*„width-dependent … 320→164, 480→104, 520→56"*), aber **die Konstante ist
eine Zahl ohne Breite**, und kein Test hält die beiden zusammen.

> Anmerkung zur Ticket-Zahl: UIUX meldet „bei 301 ist die Eingabezeile 0 dp". **Ich messe 33 dp** — und ein
> Transkript von 0 dp. Der Composer stirbt erst bei ~260 dp Fensterhöhe ganz. Die Richtung stimmt, die Zahl
> nicht; möglicherweise wurde mit einer zusätzlichen Chrome-Zeile (`LifecycleErrorRow`) oder anderer Dichte
> gemessen. **Ich melde, was ich gesehen habe, nicht was erwartet wurde.**

---

## 3. Was daraus für den Guard folgt

| Der alte Guard prüft | Der neue Guard prüft |
|---|---|
| `assertIsDisplayed()` | **Höhen**: Composer ≥ 48 dp (Touch-Target), Transkript > 0 dp |
| ein Fenster, das `tile()` zufällig erzeugt | die **definierte Ecke** `TILED_CONTENT_WINDOW_MIN_WIDTH × CONTENT_WINDOW_MIN_HEIGHT` |
| den gekachelten Pfad | den **Resize-Boden**, den der Operator mit der Maus erreicht |

Die neuen Tests nennen **keine Konstante als Erwartungswert**. Sie lesen die Invarianten und prüfen, was das
Rendering daraus macht. **Sie überleben jeden neuen Wert der Konstante und sterben an jeder Chrome-Zeile, die
dazukommt** — das ist die Lieferung.

### Die Kontrolle

`atAComfortableWidth_theSameFloorIsAlreadyHealthy` (grün) hält fest, dass es eine **Ecke** ist und kein
genereller Bruch. Ohne sie wüsste man nach dem Fix nicht, ob die Ecke repariert wurde oder ob jemand die
Konstante hochgedreht hat, bis auch der breite Fall zufällig passt.

---

## 4. Was ich nachgemessen habe, statt es zu glauben — auch bei mir selbst

Ich hatte zwei Hypothesen. **Beide waren falsch, und die Messung hat sie widerlegt:**

1. *„`assertIsDisplayed()` ist blind für Höhe 0."* — **Nein.** Bei 0 dp wird es rot (auch bei voller Breite,
   auch wenn der Knoten aus dem Fenster gedrückt wird). Blind ist es erst ab 1 dp.
2. *„Der alte Guard ist blind für jede Chrome-Zeile."* — **Nein.** Bei angehobenem Boden (380) macht ihn schon
   eine 12-dp-Zeile rot, während meine Tests bis 60 dp grün bleiben. Er ist nicht blind, er ist **knapp**:
   Sein Fenster hat 6 dp Luft. Was wie Deckung aussieht, ist Zufall der Geometrie.

Erst die dritte Messung — **welches Fenster rendert er überhaupt?** — hat den Mechanismus geliefert.
Zweimal hatte ich aus einem Ergebnis auf eine Ursache geschlossen. *Ein Ergebnis ist kein Mechanismus.*

---

## 5. Stand

```
:app:shared:jvmTest --rerun-tasks     808 Tests, 2 rot     Reports frisch

  Cyp363ComposerFloorTest   2 rot / 3
     ROT   atBothInvariantFloors_theComposerKeepsAUsableTouchTarget   (gemessen 33 dp, erwartet >= 48 dp)
     ROT   atBothInvariantFloors_theTranscriptDoesNotCollapseToZero   (gemessen 0 dp)
     GRÜN  atAComfortableWidth_theSameFloorIsAlreadyHealthy           (Kontrolle: 520 dp Breite ist gesund)
```

Die zwei roten sind die einzigen roten im gesamten `:app:shared:jvmTest`.

---

## 6. Was ich **nicht** geprüft habe

* **`LifecycleErrorRow`.** Sie erscheint nur im Fehlerfall und wächst das Chrome. Kein Test rendert sie an der
  Ecke — auch meiner nicht. **Das ist die nächste Zeile, die den Composer tötet**, und sie gehört in den
  Guard, sobald Developer5s Boden steht.
* **Web/wasm.** Diese Tests laufen als `jvmTest`. Ob die Ecke im Browser dieselben Höhen liefert, ist
  ungemessen.
* **Den Fix.** Ich baue ihn nicht. Der Boden ist Developer5s Arbeit; ich liefere die Probe, an der er scheitert
  oder besteht.

> Referenzbranch, kein Merge-Wunsch. Die zwei roten Tests landen zusammen mit dem Boden — in einem Zug.
