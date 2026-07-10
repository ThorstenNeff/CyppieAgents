# CYP-351 — `UNKNOWN` und `ERROR` müssen unterscheidbar sein (Farbe · Form · Wort · Stimme)

> Owner: UIUX-Designer · Ticket **CYP-351** · Stand 2026-07-10 · Basis **`origin/develop` = `ec9c537`** · Scope **WASM-App**
> Docs-only. Adressat: Implementierung + Test. **0 neue Keys, 0 neue Tags.**

---

## 0. Was ich erwartet hatte — und was die Prüfung ergab

Ich hatte im Ticket verlangt, `UNKNOWN` und `ERROR` müssten unterscheidbar bleiben, visuell **und** für den
Screenreader. **Das sind sie heute schon** — und es gehört gesagt, bevor ich etwas fordere:

```kotlin
AgentWindow.kt   Text(label)                                    // "Unbekannt" / "Fehler"
                 .semantics { contentDescription = description } // "Agentenstatus: Unbekannt" / "…: Fehler"
```

Beide Zustände tragen ein **eigenes Wort** und einen **eigenen gesprochenen Namen**. Der Kommentar im Code hält,
was er verspricht: *„Colour is never the sole signal: the text label carries the meaning; the dot only
reinforces it."* (`AgentWindow.kt:401`).

**Meine Auflage ist also erfüllt, und dieses Dokument ist keine Beschwerde.** Was bleibt, ist **ein** echter
Mangel und **eine** Frage.

---

## 1. Der Mangel: der `UNKNOWN`-Punkt ist unsichtbar

Der Statuspunkt ist eine gefüllte 8-dp-Scheibe (`Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))`).
Gerechnet gegen `surface`, hell **und** dunkel:

| Zustand | Punktfarbe | hell | dunkel | ≥ 3:1 (WCAG 1.4.11) |
|---|---|---|---|---|
| `RUNNING` | `primary` | 7,04:1 | 9,22:1 | ✅ |
| `ERROR` | `error` | **6,54:1** | **11,09:1** | ✅ |
| `STOPPED` | `outline` | 3,55:1 | 3,63:1 | ✅ |
| **`UNKNOWN`** | **`outlineVariant`** | **1,41:1** | **1,52:1** | ❌ |

> Der `UNKNOWN`-Punkt ist auf `surface` **praktisch nicht vorhanden**. Sichtbar ist eine Lücke, wo bei jedem
> anderen Zustand ein Punkt sitzt.

**Formal ist das erlaubt** — nach der in CYP-337 festgelegten Regel darf ein Paar unter 3:1 liegen, wenn die
Information **redundant** getragen wird, und das Wort trägt sie. **Trotzdem ist es falsch entworfen**, aus
einem inhaltlichen Grund:

> **„Wir wissen es nicht" ist nicht „ein bisschen gestoppt".** Heute unterscheiden sich `STOPPED` und `UNKNOWN`
> nur durch die **Blässe derselben Farbe** — `outline` (3,55:1) gegen `outlineVariant` (1,41:1). Das kodiert
> Unwissen als *schwächere Gewissheit* auf derselben Achse. Unwissen ist aber eine **andere Achse**.

Und genau das ist der Kern von CYP-351: ein Agent, dessen Prozess gestorben ist, war bisher „RUNNING". Wenn wir
jetzt ehrlich `UNKNOWN` sagen, darf dieses Wort nicht in der Anzeige verblassen.

---

## 2. Der Fix: eine **Form**, kein neuer Farbton — und das Haus hat sie schon

`ConnectorCapabilityViews.kt:209` malt für „Fähigkeiten noch nicht gemeldet" den Glyph **`○`**. **Im selben
Fenster bedeutet ein leerer Ring bereits „nicht gemeldet".** Also wird er wiederverwendet, nicht neu erfunden.

| Zustand | Form | Farbe | Kontrast (hell / dunkel) |
|---|---|---|---|
| `RUNNING` | gefüllte Scheibe | `primary` | 7,04 / 9,22 |
| `ERROR` | gefüllte Scheibe | `error` | 6,54 / 11,09 |
| `STOPPED` | gefüllte Scheibe | `outline` | 3,55 / 3,63 |
| **`UNKNOWN`** | **Ring** (2 dp Strich, offene Mitte) | **`outline`** | **3,55 / 3,63** |
| `Startet…` / `Neustart…` | gefüllte Scheibe | `onSurfaceVariant` | 8,69 / 9,80 |

**Drei Gewinne auf einmal:**

1. **Kontrast** steigt von 1,41 auf **3,55** — das Paar erfüllt jetzt 1.4.11 aus eigener Kraft, nicht nur über
   die Redundanz-Ausnahme.
2. **`UNKNOWN` unterscheidet sich von `STOPPED` durch die Form**, nicht durch Blässe. Gleiche Farbe, andere
   Gestalt: *„kein Wissen"* statt *„weniger Zustand"*.
3. **`UNKNOWN` unterscheidet sich von `ERROR`** in **Farbe** (`outline` vs `error`, Punkt-zu-Punkt **4,65:1**
   hell / **7,31:1** dunkel), in **Form** (Ring vs Scheibe), im **Wort** („Unbekannt" / „Fehler") und in der
   **Stimme** („Agentenstatus: Unbekannt" / „…: Fehler"). **Vier unabhängige Träger.**

**Größe bleibt 8 dp.** Der Punkt sitzt im Header, dessen Breite bei 320 dp bereits knapp ist (CYP-369): eine
Vergrößerung koste­te Knopfbreite. Ein 8-dp-Ring mit 2-dp-Strich lässt 4 dp offene Mitte — sichtbar, und
**null** Breitenänderung.

> **`RUNNING` und `ERROR` bleiben unverändert.** Rot gegen Blau ist für Farbenblinde nicht trennscharf — aber
> das Wort ist es, und es steht daneben. Ein zusätzlicher Glyph auf `ERROR` wäre ein **dritter** Mechanismus
> neben Farbe und Wort; dieselbe Ablehnung wie beim `outline`-Guard und beim Overflow-Menü.

---

## 3. Die Zusage, die dieses Ticket eigentlich schuldet

`UNKNOWN` ist heute **client-seitig** definiert: *„a client-only state before the `/ws/lifecycle` snapshot
arrives (never claimed as a server fact)"* (`AgentLifecycleApi.kt`). CYP-351 macht daraus einen Zustand, den
auch der **Server** meinen kann („der Prozess ist weg, ich weiß nicht warum").

> **Regel, die nicht verhandelbar ist:** Fehlende Information wird **nie** auf einen aufgelösten Zustand
> abgebildet. Kein `UNKNOWN → STOPPED` („ist ja nicht gelaufen"), kein `UNKNOWN → RUNNING` („lief doch eben").
> Fail-closed heißt hier: **das Unwissen wird angezeigt, nicht ersetzt.**

Das ist genau der Fehler, den CYP-351 repariert, nur eine Schicht höher: `status[agentId] = RUNNING` war die
Erinnerung an einen **Befehl**, nicht die Beobachtung eines **Prozesses**.

---

## 4. Eine offene Frage an den PO — ich beantworte sie nicht selbst

**`ERROR` ohne Grund ist eine halbe Offenlegung.** Der Zustand sagt „Fehler", nennt aber keine Ursache. Die
`LifecycleErrorRow` zeigt heute nur Fehler **einer Aktion** (`already_running`, `spawn_failed`, …), nicht den
Grund eines **Zustands**.

Zwei Wege, und die Wahl ist keine Design-, sondern eine **Vertragsfrage**:

| | |
|---|---|
| **(a)** Der Server liefert zum `ERROR`-Zustand einen Grund | Dann zeige ich ihn — wörtlich, ohne Ausschmückung. |
| **(b)** Der Server liefert keinen | Dann sagt die UI **„Fehler — Grund nicht gemeldet"** und erfindet nichts. |

**Ich brauche die Antwort vom Backend, nicht von mir.** Bis dahin gilt (b), weil sie fail-closed ist.

---

## 5. Abnahme

Alle Werte sind **gerechnet** (kein Bild-Vergleich), gegen `MaritimeLight` und `MaritimeDark`.

1. **Kontrast:** Punkt gegen `surface` ≥ 3:1 in **beiden** Themes für **jeden** Zustand. Heute fällt `UNKNOWN`
   mit 1,41 durch. **Mutationsprobe:** Ring-Farbe zurück auf `outlineVariant` ⇒ **rot**.
2. **Form:** `UNKNOWN` rendert einen Ring, alle anderen eine gefüllte Scheibe. **Mutationsprobe:** `UNKNOWN`
   auf gefüllt ⇒ **rot**. *(Ohne diese Probe genügt es, die Farbe zu heben — und `UNKNOWN` sähe aus wie
   `STOPPED`.)*
3. **Wort:** jeder Zustand rendert sein eigenes Label; `UNKNOWN ≠ STOPPED ≠ ERROR` als Zeichenkette.
4. **Stimme:** `contentDescription` = `a11y_agent_status` mit dem Zustandslabel; für `UNKNOWN` und `ERROR`
   verschieden. **Mutationsprobe:** beide auf denselben String ⇒ **rot**.
5. **Nie-Auflösung:** ein `UNKNOWN` vom Server wird als `UNKNOWN` gerendert. **Mutationsprobe:** Mapping
   `UNKNOWN → STOPPED` ⇒ **rot**. Das ist die eigentliche Zusage (§3) und der einzige Test, der die
   Fehlerklasse trifft.

**Test 5 ist der wichtige.** Die ersten vier prüfen die Anzeige; der fünfte prüft die **Ehrlichkeit**.

---

## 6. Self-Validation

- **Ich habe zuerst geprüft, ob mein eigener Vorwurf trägt** (§0). Er trug nicht: Wort und Stimme trennen die
  Zustände bereits. Eine Forderung zu wiederholen, die schon erfüllt ist, hätte den echten Mangel verdeckt.
- **Alle Kontraste gerechnet**, hell **und** dunkel: `outlineVariant` **1,41 / 1,52** (durchgefallen),
  `outline` **3,55 / 3,63**, `error` **6,54 / 11,09**, Punkt-zu-Punkt `ERROR`↔`UNKNOWN` **4,65 / 7,31**.
- **Reuse statt Erfindung:** der Ring ist der `○` des Fidelity-Badge — im selben Fenster, in derselben
  Bedeutung („nicht gemeldet"). Kein dritter Mechanismus, kein neuer Farbton.
- **0 neue Keys, 0 neue Tags.** `agent_status_unknown` / `_error` und `a11y_agent_status` existieren; DE **und**
  EN sind gepflegt.
- **Der Preis benannt:** die Formänderung greift in `StatusIndicator` ein — eine Codestelle, die CYP-350 und
  CYP-369 ebenfalls anfassen. **Sie gehört in denselben Zug**, sonst kollidieren drei Bäume auf derselben Row.
- **Keine Größenänderung:** 8 dp bleiben 8 dp, weil der Header bei 320 dp um jeden dp kämpft (CYP-369).
- **Eine Frage offen gelassen** (§4), statt sie zu erfinden: den Grund eines `ERROR` liefert der Server oder
  niemand.
- **Docs-only.**
