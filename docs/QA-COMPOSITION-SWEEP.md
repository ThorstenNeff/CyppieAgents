# QA-Sweep — UI-Tests, die gegen einen handgebauten Stapel messen

> QA / Test Engineer (Team2) · 2026-07-10 · Basis `origin/develop` @ `ec9c537`
> Anlass: Mein eigener Fehler in CYP-363. Ich hatte an einer vereinfachten Komposition gemessen.

## Suchkommando

```bash
grep -rl 'setContent' app/shared/src/*Test*/ --include=*.kt            # 98 UI-Tests
  | xargs grep -L 'AgentShell('                                        # 90 ohne echte Shell
  | xargs grep -l 'assertIsDisplayed\|assertHeightIsAtLeast\|assertWidthIsAtLeast\|getUnclippedBoundsInRoot'
                                                                       # 11 platz-abhaengig
```

## Die Schranken-Frage — achsenabhaengig, nicht pauschal

Gemessen (Wegwerf-Sonde, `FloatingWindow` mit `fillMaxSize`-Inhalt):

```
Fenster 360x700  ->  Inhalt 360x664     Verlust: 0 dp BREITE, 36 dp Hoehe
Fenster 411x400  ->  Inhalt 411x364     Verlust: 0 dp BREITE, 36 dp Hoehe
Fenster 320x301  ->  Inhalt 320x265     Verlust: 0 dp BREITE, 36 dp Hoehe
```

* **Breite:** `Box(360.dp)` == `Fenster(360)`. Die Schranke ist **scharf**. Die vier Reflow-Tests
  (`AgentRowReflowTest`, `EventRowReflowTest`, `EventRowCompact411dpVisualTest`, `ProjectRowReflowTest`) sind
  breitengetrieben und damit **nicht betroffen**.
* **Hoehe:** 36 dp Verlust (nackte Titelleiste), bis 89 dp mit Avatar/Busy/Token-Zaehler/Zahnrad + gated-Hinweis.
  Hier ist der schlanke Stapel eine **obere Schranke** auf den Platz: **rot beweist, gruen beweist nichts.**

**Nicht argumentiert, gemessen:** `AgentRowReflowTest` in ein echtes `FloatingWindow(360x700)` gehaengt →
**bleibt gruen.**

## Die pruefbare Bedingung (statt einer Verdaechtigenliste)

> **Die Falle greift nur, wo die Luft kleiner ist als das fehlende Chrome.**

Die 11 Kandidaten rendern in 400–700 dp hohen Boxen; gegen 36 dp Verlust ist das nichts. Gebissen hat sie
ausgerechnet CYP-363: 301 dp Fenster, 245 dp Chrome, **kein Puffer**.

**Ein Test ist nicht verdaechtig, weil er einen handgebauten Stapel nutzt, sondern weil er nahe an einer
Grenze misst.**

## Severity

Keine Live-Luecke, keine Fragilitaet in den 11. Die einzige belegte Fehlmessung war meine eigene.

**Was bleibt:** `assertIsDisplayed()` ist bei 1 dp gruen — **kompositionsunabhaengig**, betrifft alle 11 plus
die acht Shell-Tests. `ProjectRowReflowTest` weiss es und schreibt es in sein KDoc, statt es zu tilgen.
