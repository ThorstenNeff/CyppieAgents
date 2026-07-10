# CYP-369 — Warum Stopp und Neustart bei 320 dp verschwinden (und was es *nicht* ist)

> Owner: UIUX-Designer · Ticket **CYP-369** · Stand 2026-07-10 · Basis **`origin/develop` = `f04b372`** · Scope **WASM-App**
> Docs-only. Adressat: Implementierung + Test. Bündel: **CYP-363 (Boden) + CYP-350 (Header-Höhe) + CYP-369 (Erreichbarkeit)**.
>
> **Flip-unabhängig.** Team1s `CYP-333-flip` verändert die `ModeToggleRow`. Der **Header** ist davon nicht
> berührt — die Messungen dieses Dokuments überleben den Flip. Die Chrome-**Höhen** (CYP-363) messe ich erst
> danach neu, wie vereinbart.

---

## 1. Der Mechanismus — gemessen, und er ist nicht der vermutete

**Der `Spacer(weight(1f))` ist unschuldig.** Eine `Row` misst **alle ungewichteten** Kinder zuerst, in
Index-Reihenfolge; jedes bekommt `maxWidth = was die vorherigen übrig ließen`. Der **gewichtete** Spacer wird
**zuletzt** bedient und bekommt nur, was dann noch da ist.

Gemessen bei drei Breiten (Bounds in dp, Zustand: Operator, `caps = null`, Socket LIVE). Der Spacer trägt
keinen `testTag`; ich berichte deshalb die **gemessene Lücke** zwischen der Badge-Kante und dem Start-Knopf,
nicht eine daraus abgeleitete Spacer-Breite:

| Breite | Status-Label | **Fidelity-Badge** | Lücke Badge→Start | Start | Stopp | Neustart |
|---|---|---|---|---|---|---|
| **320** | 74 (`8…82`) | **199** (`90…289`) | **9** | **15** | **0** | **0** |
| 520 | 74 | 199 | 16 | 59 | 58 | 76 |
| 640 | 74 | 199 | **134** | 59 | 58 | 76 |

In diese 9 dp bei 320 dp müssen die Arrangement-Abstände **und** der Spacer hineinpassen. **Für den Spacer
bleibt dort nichts.** Bei 640 dp umfasst dieselbe Lücke 134 dp — dorthin fließt der Überschuss. Das ist genau
die Semantik von `weight`: **der gewichtete Spacer bekommt, was übrig ist; er nimmt niemandem etwas weg.**

> **Der Schuldige ist der Fidelity-Badge mit 199 dp.** Er ist ein `Row` aus Glyph **+ Textlabel**, und im
> ausgelieferten Fehlerfall-freien Zustand (`caps = null`, fail-closed) lautet das Label
> **„Fähigkeiten noch nicht gemeldet"**. `defaultMinSize(minWidth = 48.dp)` ist ein **Minimum**, keine
> Obergrenze.

Rechnung mit den gemessenen Zahlen: `8 (Polsterung) + 74 (Status) + 8 + 199 (Badge) = 289`. Der Header endet
bei `312`. Für **drei** Knöpfe bleiben **~15 dp** — Start nimmt sie alle, Stopp und Neustart bekommen **null**
und melden `displayed = false`.

---

## 2. Zwei Fixe, die **nicht** funktionieren

### 2.1 `maxLines` ist nicht der Fix — der PO hat recht, und der Grund ist wichtig

**Breite 0 entsteht nicht durch Umbruch.** Der Umbruch ist die *Folge* einer zu kleinen Breite, nicht ihre
Ursache. `maxLines = 1` macht die Knöpfe **einzeilig und trotzdem 0 dp breit** — dann sind sie nicht mehr hoch,
sondern nur noch unsichtbar. **Das macht den Defekt schlimmer, nicht besser:** heute ist das kaputte Layout
wenigstens *sichtbar* kaputt (ein 116 dp hoher Buchstabenturm). Mit `maxLines` allein wäre es unsichtbar kaputt.

### 2.2 Und der Glyph-Modus aus CYP-350 reicht **auch nicht** — eine Korrektur an meiner eigenen Spec

CYP-350 §1.2 lässt die Knopf-Labels unterhalb einer Breite zu Glyphen degradieren. Ich hatte stillschweigend
angenommen, das stelle die Erreichbarkeit wieder her. **Die Messung sagt nein.** Ein Knopf wird mit
`maxWidth = Rest` gemessen, und der Rest ist **15 dp** — unabhängig davon, ob der Knopf 76 dp oder 40 dp
*bräuchte*. Ein schmalerer Wunsch bekommt nicht mehr Platz.

> **Der Glyph-Modus verkleinert den Bedarf. Er vergrößert nicht das Angebot.**

Solange der Badge 199 dp nimmt, bleiben ~15 dp für drei Knöpfe — egal, was auf ihnen steht.

---

## 3. Der Fix, zweiteilig

### 3.1 Struktur: **was nie verschwinden darf, wird nicht zuletzt gemessen**

Heute sind die Chips **starr** und der Spacer **flexibel**. Genau falsch herum: die Chips beschreiben einen
Zustand, die Knöpfe *ändern* ihn. Bei Platzmangel gibt heute die Handlungsfähigkeit nach.

> **Umkehren:** Der Chip-Cluster (Status + Reconnect + Provider + Fidelity) wird das **flexible** Glied; die
> drei Lifecycle-Knöpfe bleiben **starr**. Dann werden die Knöpfe im ersten Durchgang mit ihrer Wunschbreite
> bedient, und der Cluster bekommt, was übrig ist.

Der `Spacer` entfällt dabei — seine Aufgabe (Knöpfe nach rechts drücken) übernimmt der flexible Cluster.

### 3.2 Der Badge braucht eine **schmale Form** — und zwar eine ganze, keine abgeschnittene

Der flexible Cluster allein würde den Badge-Text **beschneiden**. Das ist nach der Regel aus CYP-363 §5
verboten: „Fähigkeiten noch nicht gemeldet" ist ein **Offenlegungssatz** — er sagt *fail-closed*, und ein
halber Satz sagt es nicht.

**Die Auflösung ist die Unterscheidung, die CYP-350 schon für die Knöpfe trifft:**

| | Form | erlaubt? |
|---|---|---|
| **Kürzen** (`Ellipsis`, Clipping) | „Fähigkeiten noch nicht ge…" | **nein** — eine Offenlegung, die aussieht, als hätte sie stattgefunden |
| **Ersetzen** (vollständige Alternativform) | Glyph `○` + `contentDescription` = der volle Satz + Panel per Klick | **ja** |

> **Ellipse zerstört. Substitution bewahrt.** Eine vollständige Alternativform trägt dieselbe Aussage in einem
> anderen Medium — genau wie der Glyph-Knopf mit gesprochenem Namen. Ein abgeschnittener Satz trägt sie nicht.

Also: **unterhalb der Schwelle zeigt der Badge nur seinen Glyph**, behält seine 48-dp-Zielgröße, seinen
`testTag` und seinen vollen `contentDescription`. **Er verschwindet nie** — seine Abwesenheit ist bereits mit
„nicht degradiert" belegt (CYP-350 §3). Dasselbe gilt für den Provider-Chip.

**Das Status-Label bleibt vollständig.** Es ist der einzige nicht-farbliche Träger des Lifecycle-Zustands
(WCAG 1.4.1, `AgentWindow.kt:274`: *„Colour is never the sole signal"*). Es darf nicht zum Glyph werden.

---

## 4. Abnahme — als Messung, nicht als Arithmetik

Ich beziffere hier **keine** Zielbreiten. Was der Badge im Glyph-Modus misst und ob es dann reicht, ist eine
Messung nach der Umsetzung — jede Zahl, die ich jetzt hinschriebe, wäre eine Ableitung unter stiller Annahme.
Stattdessen ein Kriterium, das sich selbst prüft:

**G-369.** Bei **320 · 360 · 400 · 480 · 520 · 640 dp**, für `{Operator} × {caps = null, caps = degraded}`:

```
für jeden der drei Knöpfe (start, stop, restart):
    assert displayed == true
    assert width  >= 24.dp        // WCAG 2.5.8 Zielgröße
    assert height >= 24.dp
assert fidelityBadge.displayed == true      // verschwindet nie (§3.2)
assert status.displayed        == true      // WCAG 1.4.1
```

**Mutationsproben:**

| # | Mutation | erwartet |
|---|---|---|
| M1 | Chip-Cluster wieder starr (Spacer zurück) | **rot** bei 320 (`stop.width == 0`) |
| M2 | Badge-Glyph-Modus entfernen | **rot** bei 320 |
| M3 | `maxLines = 1` an den Knöpfen **statt** §3.1 | **rot** bei 320 — der Nachweis aus §2.1 |
| M4 | Badge bei Enge ausblenden statt Glyph | **rot** (`fidelityBadge.displayed == false`) |
| M5 | Status-Label zum Glyph degradieren | **rot** (`status` trägt keinen Text mehr) |

**M3 ist der wichtige.** Er belegt im Test, was §2.1 behauptet: `maxLines` heilt nichts. Ohne ihn wird der
Defekt beim nächsten Mal als Layout-Hygiene abgetan.

---

## 5. Warum das zusammen mit CYP-351 und CYP-368 gelesen werden muss

- **CYP-351** macht ehrlich, dass ein abgestürzter Agent nicht mehr `RUNNING` ist — der Operator soll dann
  **starten**. Im gekachelten Fenster ist *Start* ein **15 dp** breiter Buchstabenturm und *Neustart* gar nicht
  vorhanden. Die ehrliche Anzeige ohne erreichbare Handlung ist eine halbe Reparatur.
- **CYP-368**: ein Doppelklick auf *Start* spawnt zwei Prozesse. Ein 15 dp breites Ziel **erzeugt**
  Fehlklicks — die Zielgröße aus WCAG 2.5.8 ist genau dafür da.

> **Wir haben den Zustand ehrlich gemacht, die Reaktion darauf unerreichbar gelassen und die einzige
> erreichbare Aktion zum Doppelklick eingeladen.** Die drei Tickets sind ein Defekt in drei Ansichten.

---

## 6. Self-Validation

- **Der Mechanismus ist gemessen, nicht übernommen.** Die Lücke, in die der Spacer fällt, misst bei 320 dp
  **9 dp** und bei 640 dp **134 dp** — er bekommt Überschuss, er nimmt nichts. Die **199 dp** des Badge stehen
  in der Messung, nicht in meiner Vermutung. Und `8 + 74 + 8 + 199 = 289` ist exakt die gemessene rechte
  Badge-Kante — die Rechnung prüft sich selbst.
- **Keine Spacer-Breite behauptet.** Der Spacer trägt keinen `testTag`; eine aus der Lücke abgeleitete Breite
  ging nicht auf (zwei 8-dp-Abstände passen nicht in 9 dp). Also berichte ich die Lücke, nicht die Ableitung.
- **Ich widerspreche der Begründung im Ticket, nicht seiner Schlussfolgerung.** `maxLines` ist nicht der Fix —
  das stimmt. *Weil* der Rest 15 dp beträgt, nicht *weil* etwas umbricht.
- **Eine eigene Annahme widerlegt** (§2.2): CYP-350s Glyph-Modus stellt die Erreichbarkeit **nicht** her. Der
  Glyph verkleinert den Bedarf, nicht das Angebot. Das stand implizit in meiner Spec und war falsch.
- **Keine Zielbreiten erfunden** (§4). Die Abnahme ist eine Messung mit einer Schwelle aus der Norm (24 dp),
  keine Arithmetik von mir.
- **Die Disclosure-Regel ist geschärft, nicht gebeugt:** Kürzen verboten, **Substitution durch eine
  vollständige Alternativform** erlaubt. Das trennt den Glyph-Badge sauber vom Ellipsen-Badge.
- **Docs-only.** Die Sonde war ein Messinstrument und ist entfernt; `:app:shared:jvmTest` unverändert grün.
