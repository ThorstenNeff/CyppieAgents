# a11y — Ansage-Dringlichkeit: `Polite` vs `Assertive` (v1.0)

> Design-System-Regel, teamweit · Owner: UIUX-Designer · Entschieden 2026-07-19 (Design-Owner-Entscheid,
> PO-angenommen; Auslöser: CYP-727 / `LoadErrorRetry`-Flag durch Dev) · Stand develop `419f7458`.
>
> Gilt für **jede** `Modifier.semantics { liveRegion = … }`-Entscheidung in `commonMain`.
> Verwandte Regel: `COLOR-CODING.md §8` (Null-Headroom / Glyph statt Ton).

---

## 1. Die Regel

> **`Assertive`** — wenn die Meldung das **Ergebnis einer abgeschickten oder gestarteten Aktion** ist,
> **oder unaufgefordert** eintrifft. Der Nutzer **wartet darauf** oder **rechnet nicht damit**; sein Blick
> kann woanders sein.
>
> **`Polite`** — wenn die Meldung der **Anfangszustand einer Fläche** ist, die der Nutzer **gerade selbst
> geöffnet** hat. Er schaut bereits hin; die Meldung steht in genau dem Ding, das er aufgeschlagen hat.

**Die Frage, die entscheidet, ist nicht „wie schlimm ist es?", sondern „schaut der Nutzer gerade hin?"**
Schwere und Dringlichkeit sind verschiedene Achsen: ein Ladefehler kann schwer sein und trotzdem `Polite`
gehören, weil der Nutzer ohnehin davorsteht.

`Assertive` **unterbricht** die laufende Screenreader-Ausgabe. Das ist gerechtfertigt, wenn sonst etwas
**verpasst** würde — und es ist Lärm, wenn es etwas ankündigt, worauf die Aufmerksamkeit schon liegt.

---

## 2. Belegt am Bestand (alle `Assertive`-Fundstellen, 2026-07-19)

| Fundstelle | Klasse |
|---|---|
| `firstrun/FirstRunSteps.kt:204` — Clone fehlgeschlagen | Ergebnis einer **langlaufenden** Aktion |
| `migration/MigrationOutcome.kt:58` — Migration fehlgeschlagen | Ergebnis einer **langlaufenden** Aktion |
| `auth/AuthGate.kt:189,193,270,279,379` — Login / Rate-Limit / GitHub / Register | Ergebnis einer **abgeschickten Eingabe** |
| `connect/RemoteOperatorAuthSteps.kt:249,353,364` | Ergebnis einer **abgeschickten Eingabe** |
| `workspace/OverloadBanner.kt:52` — Überlast | **unaufgefordert** |
| `connect/HubConnectSelection.kt:427` — Codes-Fenster abgelaufen | **unaufgefordert** (zeitgetrieben) |

**Kein einziger Fall ist ein Öffnen-Zustand.** Die Regel beschreibt den Bestand, sie erfindet ihn nicht.

**Gegenprobe — `Polite` an der geteilten Ladefehler-Fläche:** `ui/LoadErrorRetry.kt` trägt die
`Polite`-Region **bewusst einmal zentral**, damit alle vier REST-Panels sie erben
(`AgentSettingsPanel.kt:259`, `AgentManagementPanel.kt:169`, `CommPanel.kt:151,232`). Das ist korrekt:
ein fehlgeschlagener Erst-Ladevorgang ist der **Anfangszustand einer soeben geöffneten Fläche.**

---

## 3. Der Fall, an dem die Regel entschieden wurde (CYP-727)

Beide Flächen gehören zum selben Feature, und **beide sind richtig — weil sie verschiedenen Klassen
angehören:**

| Fläche | Klasse | Ansage |
|---|---|---|
| Inventar-**Ladefehler** (Operator öffnet die Migrations-Sektion) | Öffnen-Zustand | **`Polite`** |
| **Migrations-Fehlschlag** (Operator hat den Lauf gestartet, er dauert Minuten) | Ergebnis | **`Assertive`** |

Das ist der Lackmustest der Regel: sie muss **innerhalb eines Features** unterschiedlich ausfallen dürfen,
ohne widersprüchlich zu sein.

---

## 4. Anwendung — zwei Fragen

1. **Hat der Nutzer diese Meldung ausgelöst und wartet auf ihr Ergebnis?** → `Assertive`.
2. **Kommt sie ungefragt, während er etwas anderes tut?** → `Assertive`.
3. Sonst — er hat die Fläche gerade geöffnet und die Meldung ist ihr Anfangszustand → **`Polite`**.

**Bei einer geteilten Komponente entscheidet die Klasse der Komponente, nicht die des Aufrufers.**
Eine geteilte Fläche wird **nicht geforkt**, um an einer Call-Site dringlicher zu klingen: das ändert das
Verhalten aller anderen mit oder erzeugt einen Zwilling. Braucht ein Aufrufer wirklich `Assertive`, ist er
**vermutlich in der falschen Klasse** und braucht eine eigene Fläche (so wie `MigrationOutcome` neben
`LoadErrorRetry` steht) — nicht dieselbe mit anderer Lautstärke.

---

## 5. Was diese Regel NICHT sagt

- **Nicht** „Fehler sind immer `Assertive`". Die Achse ist Aufmerksamkeit, nicht Schwere (§1).
- **Nicht**, dass `Polite` weniger sichtbar ist: Farbe, Glyph und Text sind davon unberührt — ein
  `Polite`-Ladefehler trägt weiterhin `⚠` + `error`-Farbe + Retry (`COLOR-CODING.md §8`: Farbe ist nie
  alleiniger Träger).
- **Nichts** über `stateDescription`. Ein Zustand, der **keinen** Glyph trägt (z.B. „fertig" =
  Belegwert, `CYP-220-migration-ui-spec.md §5.2`), braucht **zusätzlich** eine explizite
  `stateDescription` — sonst ist die glyphlose Lösung eine a11y-Regression. Das ist eine eigene Regel,
  hier nur als Querverweis.

---

## 6. Self-Validation

- **Regel aus dem Bestand abgeleitet, nicht gesetzt:** alle 12 `Assertive`-Fundstellen geprüft (§2), alle
  fallen in die zwei benannten Klassen; keine Ausnahme, keine Umdeutung nötig.
- **Gegenprobe geführt:** die vier `LoadErrorRetry`-Call-Sites sind die Polite-Klasse und bestätigen die
  Linie von der anderen Seite.
- **Am Streitfall verifiziert:** CYP-727 (`Polite`, gemergt `419f7458`) und Spec §7 (`Assertive`, gebaut
  `MigrationOutcome.kt:58`) sind **beide** korrekt — die Regel löst den scheinbaren Widerspruch auf,
  statt eine Seite zu opfern.
- **Grenzen ausdrücklich benannt** (§5), damit die Regel nicht zu „Fehler = laut" verkürzt wird — eine
  überdehnte a11y-Regel erzeugt Lärm und wird dann ignoriert.
- **Fork-Verbot für geteilte Komponenten** aufgenommen (§4), weil genau das der Auslöser war.
