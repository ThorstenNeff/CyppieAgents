# Zeitstempel im Agenten-Transkript — UX/UI-Spec (CYP-335)

> Owner: UIUX-Designer · Story **CYP-335** · Stand 2026-07-09 · Basis **develop `f8063c1`**
> Scope: **nur WASM-App (Browser)**. Docs-only — kein Code in diesem Branch.
> Begleit-Artefakte: `transcript-timestamp-tokens.json`, `transcript-timestamp-keys.md`, `transcript-timestamp-tags.md`.
> Reuse-Linie: die Zeitzellen-Behandlung des **Event-Logs** (`EventRowUi.kt:135` — Monospace + `labelSmall`)
> und die Dekorativ-Marker-Disziplin aus **CYP-323/326** (`›` / `⇥`).
> Quellen real gelesen @ `f8063c1`: `agentview/AgentWindow.kt`, `agentview/TranscriptFolding.kt`,
> `agentview/AgentEvent.kt`, `agentview/AgentViewTags.kt`, `eventlog/EventVisuals.kt`, `eventlog/EventRowUi.kt`,
> `ui/MaritimeTheme.kt`, `docs/TEST-CONTRACT.md` §2.

---

## 0. Was hier (nicht) entworfen wird

**Im Scope:** die **Zeitspalte** links an jeder der sechs Transkript-Zeilenarten — Geometrie, Ausrichtung,
Gewicht, Farbe/Kontrast (hell+dunkel), Wiederholungsverhalten, Screenreader-Ansage, Test-Tag.

**Ausdrücklich außerhalb (PO):** Sekunden, Datum, relative Zeiten, Tageswechsel-Trenner, Hover-Tooltips,
Zeitzonen-Umschalter.

**Zwei Befunde, die die Datenschicht betreffen** (nicht Optik, aber sie entscheiden, ob die Spalte die
Wahrheit sagt) — **§7**. Sie sind der Grund, warum ich diese Spec nicht rein kosmetisch abliefere.

---

## 1. Leitentscheidung: die Zeitspalte gehört dem **Transkript**, nicht den sechs Zeilen

Der PO beschreibt das Problem präzise: sechs Zeilenarten mit verschiedenen Höhen, Einzügen und Gewichten.
Würde jede Zeile ihre Zeit selbst rendern, erbte die Zeit den Einzug, den Hintergrund und die vertikale
Ausrichtung ihrer Zeile — sechs verschieden weit eingerückte Zeitstempel.

**Deshalb:** Die Zeit ist eine **Rinne (Gutter) des Transkript-Containers**, kein Bestandteil der Zeile.
Ein gemeinsamer Wrapper legt sich um **alle sechs** bestehenden Zeilen-Composables, ohne eine einzige von
innen zu ändern:

```kotlin
// AgentWindow.kt — EIN neuer privater Wrapper, sechs unveränderte Bodies
@Composable
private fun TranscriptRow(time: String?, agentId: String, index: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        TimeCell(time, agentId, index)          // 44 dp, rechtsbündig, alignByBaseline
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).alignByBaseline()) { content() }   // UserTurnRow / AssistantTextRow / …
    }
}
```

Daraus folgen drei Eigenschaften, die zusammen die „ruhige linke Kante" **sind**:

1. **Zwei senkrechte Linien statt sechs Einzüge.** Rechte Kante der Zeitspalte (bündige Ziffern) und linke
   Kante des Inhalts (bei `12 + 44 + 8 = 64 dp`) sind über alle sechs Zeilenarten identisch.
2. **`ResultRow`s getönter Container beginnt erst hinter der Rinne.** Heute ist er `fillMaxWidth()` +
   `background(container)` (`AgentWindow.kt:410–411`) — unter der Rinne läge die Zeit auf `errorContainer`
   bzw. `surfaceVariant`. Durch die
   Rinne liegt die Zeit **immer** auf `surface`. Das ist nicht nur ruhiger, es **halbiert die Kontrast-Matrix**
   (§4): genau ein Paar statt drei.
3. **Null Änderung an den sechs Bodies.** Ihre Einzüge, Marker (`›`, `⇥`, `⟳`), Hintergründe und `testTag`s
   bleiben bitgenau, wo sie sind. Reuse statt Umbau.

---

## 2. Ausrichtung & Breite (PO-Frage 1)

### 2.1 Feste Breite: ja — `44 dp`, rechtsbündig

| Entscheidung | Wert | Begründung |
|---|---|---|
| Spaltenbreite | **`44 dp`** fest | Eine intrinsische Breite (`IntrinsicSize.Min`) teilt sich über `LazyColumn`-Items **nicht** — sie würde pro Zeile neu gemessen. Eine Konstante ist die einzige Form, die über recycelte Items hält. |
| Textausrichtung | **`TextAlign.End`** | Der Abstand Ziffern→Inhalt bleibt konstant. Belt-and-braces: falls die Wasm-Font-Auflösung je auf eine proportionale Fallback-Schrift zurückfällt, bleibt die **Inhaltskante** trotzdem exakt. |
| `maxLines` | **1**, `softWrap = false` | `HH:mm` bricht nie um. |

**Warum 44 dp reicht:** `HH:mm` sind fünf Glyphen; Monospace bei `labelSmall` (11 sp) ≈ 6,6 dp Vorschub
⇒ ≈ **33 dp**. 11 dp Reserve (33 %).

> **Scope-ehrliche Einschränkung:** Im Browser skalieren `dp` und `sp` gemeinsam (Zoom) — 44 dp ist im
> **WASM-Scope sicher**. Auf **Android** skaliert `sp` unabhängig: bei `fontScale 1,3` → 42,9 dp (passt knapp),
> bei `1,5` → 49,5 dp (**Clipping**). Wenn CYP-335 später auf Android gezogen wird, muss die Zelle auf
> `widthIn(min = 44.dp)` wechseln — dann wandert die Inhaltskante bei großen Schriftskalen mit, statt zu
> beschneiden. **Kein Android-Versprechen in dieser Spec.**

### 2.2 Vertikale Ausrichtung: **erste Grundlinie**, nicht Oberkante

Die Vermutung des PO („oben bündig zur ersten Textzeile") zielt richtig, trifft aber die falsche Kante.

`Alignment.Top` richtet die **Boxen** aus, nicht die Schrift. Die Zeit ist `labelSmall` (11 sp / 16 sp
Zeilenhöhe), der Inhalt `bodyMedium` (14 sp / 20 sp). Bei Oberkanten-Bündigkeit sitzt die Grundlinie der
kleineren Schrift **sichtbar höher** — die Ziffern „schweben". Bei sechs Zeilenarten mit drei
Typo-Stufen (`bodyMedium`, `bodySmall`, `labelSmall`) driftet das pro Zeilenart unterschiedlich weit.

**Normativ:** `Modifier.alignByBaseline()` auf **Zeitzelle und Inhaltsbox**. Die erste Grundlinie der Zeit
liegt exakt auf der ersten Grundlinie der ersten Inhalts-Textzeile — für **jede** Zeilenart, unabhängig von
deren Typo-Stufe. Bei **mehrzeiligem** Inhalt bleibt die Zeit an der **ersten** Zeile; Folgezeilen haben eine
leere Zelle.

> **Implementierungs-Hinweis + benannter Fallback:** `alignByBaseline()` trägt auch durch `ResultRow`s
> gepolsterten, getönten Container, weil Compose die erste Grundlinie durch `Box`/`padding` propagiert — der
> `vertical = 4.dp`-Innenabstand verschiebt die gemeldete Grundlinie mit, was genau das gewünschte Ergebnis
> ist. **Dev5 verifiziert das an `ResultRow`** (die einzige gepolsterte Zeile). Sollte die Propagation
> ausbleiben: `Alignment.Top` + `Modifier.padding(top = 4.dp)` auf der Zeitzelle **nur für ResultRow** —
> dokumentierte Ausnahme, kein stiller Sonderfall.

---

## 3. Visuelles Gewicht (PO-Frage 2)

Die Zeit ist **Metadaten**. Sie muss lesbar sein, ohne den Inhalt zu überstrahlen. Der Zug, den man hier
reflexhaft macht — Farbe abdunkeln *und* Deckkraft senken — ist genau der, der den Kontrast reißt (§4).
Die Dämpfung kommt deshalb aus **Größe und Rolle**, nicht aus Alpha.

| Eigenschaft | Wert | Herkunft (Reuse, nicht Erfindung) |
|---|---|---|
| Typo | **`labelSmall`** (11 sp) | Die etablierte „Metadaten"-Stufe der App: `NoticeRow`, `LifecycleErrorRow`, `ReconnectingChip` — **und die Zeitzelle des Event-Logs** (`EventRowUi.kt:135`). `bodySmall` (12 sp) läge im *Inhalts*-Band (`ResultRow`) — falsches Signal. |
| Farbe | **`colorScheme.onSurfaceVariant`** | Die neutrale Sekundär-Rolle. Event-Log-Detailzeile setzt sie für Zeitstempel bereits explizit (`EventBrowsePanel.kt:353`). |
| Deckkraft | **`1.0` (kein Alpha)** | §4 — jede Dämpfung unter `α 0.80` verlässt WCAG AA. Und sie ist **unnötig**: `onSurfaceVariant` ist gegenüber `onSurface` bereits klar zurückgenommen (8,7:1 vs. 15,6:1 hell). |
| Schrift | **`FontFamily.Monospace`** | Bereits in `ToolCallRow:385`, `ResultRow:418` und der Event-Log-Zeitzelle im Einsatz. |

### Monospace — die ehrliche Antwort auf die PO-Frage

`HH:mm` ist **null-gepolstert**, `11:11` und `09:44` haben also immer dieselbe *Glyphenzahl*. Feste
Spaltenbreite + `TextAlign.End` würden die **Spaltenkante** damit schon allein stabilisieren.

Monospace kauft etwas anderes, Feineres: es richtet den **Doppelpunkt** und die **Ziffernpaare** *zwischen*
den Zeilen aus. Beim vertikalen Scannen einer Spalte ist genau das der Unterschied zwischen „Zahlenkolonne"
und „zitternde Ziffern". **Ja, Monospace** — und es ist Reuse: die Zeitzelle des Event-Logs ist bereits
`FontFamily.Monospace` + `labelSmall`. Die Alternative (proportionale Schrift + `fontFeatureSettings = "tnum"`)
führt eine zweite Ziffern-Konvention ein — **ein dritter Duplikat-Weg**, ohne Gewinn.

---

## 4. Beide Themes & WCAG AA (PO-Frage 4)

Der PO hat hier den Finger auf die richtige Stelle gelegt: *„Gedämpfte Metadaten-Farben reißen hier gern."*
**Nachgerechnet, nicht geschätzt** (WCAG 2.1 Kontrastformel; `MaritimeLight`/`MaritimeDark` @ `f8063c1`):

### 4.1 Die gewählte Kombination

| Paar | Hell | Dunkel | Urteil |
|---|---|---|---|
| **`onSurfaceVariant` auf `surface`** *(die einzige Kombination, die dank der Rinne §1.2 vorkommt)* | `#3A4E5A` auf `#FFFFFF` = **8,69:1** | `#A6BECD` auf `#06121A` = **9,80:1** | **AAA** ✓ |
| *(Vergleich)* `onSurface` = Inhalt | 15,62:1 | 15,05:1 | Zeit ist klar zurückgenommen |

### 4.2 Die zwei Fallen, die wir damit umgehen

**Falle 1 — `outline` als „gedämpfte" Farbe.** Naheliegend (die `NoticeRow` benutzt sie), aber:

| `outline` auf `surface` | Hell `#6E8C9E` | Dunkel `#57707F` |
|---|---|---|
| Kontrast | **3,55:1** | **3,63:1** |
| WCAG AA Fließtext (4,5:1) | **FAIL** | **FAIL** |

`outline` ist eine **UI-/Rahmen**-Rolle (3:1), keine Textrolle. Für 11-sp-Ziffern ist sie unzulässig.
→ **`outline` ist für die Zeitspalte verboten.**

**Falle 2 — Alpha-Dämpfung.** Der übliche Reflex `α = 0.6` auf `onSurfaceVariant`:

| α | Hell | Dunkel | AA (4,5:1) |
|---|---|---|---|
| 1.00 | 8,69:1 | 9,80:1 | ✓ |
| 0.90 | 6,55:1 | 8,11:1 | ✓ |
| **0.80** | **5,05:1** | **6,62:1** | ✓ ← **harter Boden** |
| 0.75 | 4,43:1 | 5,92:1 | **FAIL (hell)** |
| 0.70 | 3,90:1 | 5,28:1 | **FAIL (hell)** |
| 0.60 | **3,07:1** | 4,18:1 | **FAIL (beide)** |

Das **helle** Theme reißt zuerst — das dunkle würde eine α-0,7-Dämpfung noch tragen und den Fehler
verstecken, bis jemand tagsüber hinschaut. **Normativ: kein Alpha (`1.0`). Wird je eines eingeführt, ist
`0.80` der Boden, und der Nachweis ist im hellen Theme zu führen.**

**Farbe ist nie alleiniger Träger (WCAG 1.4.1):** Die Zeit trägt keine Bedeutung über Farbe — sie ist Text.
Sie nimmt **keine** Semantikfarbe an (nie `error`, nie `primary`) und **nie** den Identitäts-Hue eines Agenten.
Eine `ERROR`-Zeile bekommt keinen roten Zeitstempel: der Fehler gehört der Zeile, nicht der Uhr.

---

## 5. Wiederholung (PO-Frage 3): **stur jede Zeile** — bestätigt, mit fünf Gründen

Der PO neigt zu „stur jede Zeile" und bittet um Gegenrede, falls ich anders denke. **Ich denke nicht anders**
— aber die Begründung „vorhersagbar, scan-bar" ist nicht die stärkste, die zu haben ist. Fünf Gründe, die
das Gegenteil geschlossen ausschließen:

1. **Abwesenheit ist in dieser Codebase schon belegt — mit „unbekannt".** Der Provider-Chip fehlt, wenn der
   Provider nicht gemeldet ist. Der Fidelity-Badge fehlt, wenn nichts degradiert ist. Fail-closed durch
   Absenz ist eine tragende Konvention. Würde eine leere Zeitzelle „gleiche Minute wie oben" bedeuten, wäre
   **Absenz doppelt belegt** — an genau der Stelle, an der wir sie (§6.3) für „Zeitstempel fehlt" brauchen.
   Eine weggelassene Zeit ist keine Aussage über die Minute darüber; sie ist **keine Aussage**.
   *Das ist der Disclosure-Grund und für mich der entscheidende.*
2. **Die WCAG-legale Form von „dämpfen" existiert nicht.** Dämpfen heißt Alpha senken — §4.2 zeigt, dass
   das im hellen Theme unter AA fällt. Die einzige normkonforme „Dämpfung" wäre **Weglassen**, und das ist
   Grund 1. Es gibt also gar keine dritte Option.
3. **Falten.** Der PO ahnt es („kein Sonderfall beim Falten"): eine Regel *„zeige nur, wenn die Minute
   wechselt"* macht Zeile *N* abhängig von Zeile *N−1*. Sobald irgendetwas Zeilen ein- oder ausblendet, kann
   die **einzige** zeitmarkierte Zeile einer Minutengruppe die versteckte sein — die sichtbare Gruppe verliert
   ihren Zeitstempel ganz. Und `foldEvent` **aktualisiert Zeilen in place** (`TranscriptFolding.kt:22–40`):
   eine wachsende `AssistantText`-Zeile müsste ihre Nachbarin rekomponieren, damit deren Zeit erscheint oder
   verschwindet. Zeilen-lokal → nicht zeilen-lokal.
4. **Ragged column ≠ ruhige Kante.** Die Aufgabe lautet „ruhige, ausgerichtete linke Kante". Eine Spalte mit
   Lücken ist optisch *unruhiger* als eine vollständige: das Auge, das eine Ziffernkolonne herunterläuft,
   verliert bei Lücken den vertikalen Rhythmus und springt in den Text. Die volle Spalte **ist** die ruhige
   Kante.
5. **Screenreader.** Wer nicht sieht, kann keine Zeit „von oben erben". Die Zeit muss ohnehin an **jeder**
   Zeile im a11y-Baum hängen (§6). Sie visuell wegzulassen erzeugte eine zweite, abweichende Wahrheit
   zwischen Auge und Ohr.

> **Der Preis, offen benannt:** sechs Zeilen mit `09:15` untereinander sind visuell redundant. Das ist der
> Preis dafür, dass eine leere Zelle **immer** „keine Zeit bekannt" heißt — nie „schau oben nach". Ich halte
> ihn für richtig bezahlt: `onSurfaceVariant` bei 11 sp ist leise genug, dass sechs gleiche Zahlen zu einer
> ruhigen Textur verschmelzen statt zu pochen.

---

## 6. Barrierefreiheit (PO-Frage 5)

### 6.1 Was der Screenreader hört

**Nackte Ziffern sind wertlos — richtig.** Die Zelle trägt eine **gelabelte** `contentDescription`:

| Locale | Key | Text | Ansage bei `09:14` |
|---|---|---|---|
| DE (`values/`) | `a11y_transcript_time` | `um %1$s Uhr` | „um 09:14 Uhr" |
| EN (`values-en/`) | `a11y_transcript_time` | `at %1$s` | „at 09:14" |

Die Zelle folgt dem Idiom der Datei: **sichtbarer Text ≠ gesprochener Text**
(`Modifier.clearAndSetSemantics { contentDescription = … }` wie `ResultRow:419`, `UserTurnRow:479`).

> **Warum gelabelt, obwohl das Event-Log (`a11y_event_row`, `%4$s`) die Zeit **ungelabelt** anhängt?**
> Dort steht sie als vierte Position in einem festen Komma-Satz („WARN, TOOL_CALL, von frontend, 09:14:33").
> Im Transkript endete die Beschreibung nach **beliebigem Nutzertext** auf einer nackten Zahl —
> „Deine Nachricht: treffen wir uns um 3, 09:14". Das Label trennt Inhalt von Metadatum. **Bewusste,
> begründete Abweichung**, keine Drift.

### 6.2 Vor oder nach dem Nachrichtentext? → **davor**, und das ist ein Kompromiss

Die Hauskonvention (`a11y_event_row`: `„%1$s, %2$s, von %3$s, %4$s"` mit `%4$s` = Zeit) sagt **danach**. Für
das Transkript empfehle ich trotzdem **davor** — weil der Weg zu „danach" hier teuer ist:

Um die Zeit **anzuhängen**, müsste sie in die `contentDescription` der *Zeile* wandern. Fünf der sechs Zeilen
haben eine solche Beschreibung. **`AssistantTextRow` hat keine** (`AgentWindow.kt:338–342`): ihr Text ist ein
echter **Text-Knoten**. Ihn in eine `contentDescription` zu verwandeln, macht **den längsten Inhalt der
App — die Antworten des Agenten — für Screenreader-Nutzer nicht mehr wort- und zeilenweise navigierbar**.
Das ist eine echte a11y-Regression, eingehandelt für eine Wortstellung.

**Also:** Die Zeit bleibt ein **eigener, kurzer, gelabelter Knoten** vor dem Inhalt. Kosten: ein
überspringbarer Stopp pro Zeile. Gewinn: der Textknoten des Agenten bleibt unangetastet, **null** bestehende
a11y-Keys ändern sich, alle sechs Zeilen verhalten sich gleich, und Auge und Ohr lesen dieselbe Reihenfolge
(„um 09:14 Uhr" → „Deine Nachricht: …") — was zählt, sobald jemand am Telefon sagt „die Zeile um 09:14".

> **§-Ask 3 an den PO:** Wenn dir „Inhalt zuerst" wichtiger ist als die Navigierbarkeit des Assistant-Textes,
> kippe ich auf Suffix — dann braucht `AssistantTextRow` einen neuen Key `a11y_assistant_text` („Agent: %1$s")
> **und** verliert ihren Textknoten. Der Preis ist benannt; die Entscheidung ist deine.

### 6.3 Fehlender Zeitstempel — fail-closed

Liefert die Datenschicht `ts` als `null` (Legacy-Replay, alter Payload), gilt:

- Zelle bleibt **leer**. **Nie** `00:00`, **nie** `--:--` (beides behauptet eine Zeit bzw. ein Format-Artefakt).
- Die Zeilenbeschreibung erhält **keine** Zeitklausel — kein „Uhrzeit unbekannt"-Rauschen auf jeder Altzeile.
- Eindeutig genau deshalb, weil §5 Wiederholungen **nie** ausblendet: **leer ⇔ unbekannt**, ausnahmslos.

### 6.4 RTL

Zeitzelle als erstes `Row`-Kind + `Modifier.width` + `TextAlign.End`: In RTL wandert die Rinne
richtungskorrekt an die **Start**-Kante (rechts), `End` hält die Ziffern weiterhin am Inhalt. `09:14` ist ein
LTR-Ziffernlauf und bleibt es (der Doppelpunkt ist ein numerischer Trenner). Keine harten `left`/`right`-Werte.

> **Ehrliche Einschränkung:** Die App liefert heute nur `values/` (DE) und `values-en/` (EN) — **kein
> RTL-Locale**. Die Konstruktion ist richtungssicher, aber **derzeit nicht verifizierbar**. Kein RTL-Anspruch.

---

## 7. Zwei Befunde für die Datenschicht (Dev5) — **die Optik ist der kleinere Teil**

> Der PO bat: *„Wenn dir auffällt, dass eine der sechs Zeilenarten die Zeit strukturell nicht tragen kann, sag
> es früh."* Es ist **eine** — und darüber hinaus kann die App die geforderte Zeit derzeit gar nicht bilden.

### 7.1 🔴 `ToolCallRow` verliert ihren Zeitstempel im Reducer (`TranscriptFolding.kt:36`)

`foldEvent` behandelt `ToolCall` als **Vollersatz**:

```kotlin
is AgentEvent.ToolCall ->
    if (idx >= 0 && current[idx] is AgentEvent.ToolCall) current.toMutableList().also { it[idx] = event }
    //                                                                                    ^^^^^^^^^^^^^^^ neues ts gewinnt
```

Ein `ToolCall` wird **mit derselben `id` erneut emittiert**, um `RUNNING → OK/ERROR` zu aktualisieren
(`AgentEvent.kt:31` KDoc). Trägt das Event ein `ts`, überschreibt das Update-`ts` das Start-`ts`.

**Konkreter Fehlerfall:**

| t | Ereignis | Spalte nach dem Fold |
|---|---|---|
| 09:14 | `ToolCall(id=t1, RUNNING)` | Zeile 0 → `09:14` |
| 09:15 | `AssistantText(id=a1)` | Zeile 1 → `09:15` |
| 09:17 | `ToolCall(id=t1, OK)` | Zeile 0 → **`09:17`** ← über Zeile 1 |

Die Spalte läuft **rückwärts**: `09:17` steht über `09:15`. Und die Zeile behauptet, das Werkzeug sei um
09:17 *aufgerufen* worden — tatsächlich wurde es da beendet.

**Normative Anforderung an die Datenschicht:**

> **`ts` ist „first-seen" und unveränderlich.** Der Zeitstempel einer Transkriptzeile ist der Zeitpunkt, zu
> dem die Zeile **zum ersten Mal beobachtet** wurde — nie der ihrer Aktualisierung.

Fix in `foldEvent`: `it[idx] = event.copy(ts = (current[idx] as AgentEvent.ToolCall).ts)`.

**Die anderen fünf sind durch die bestehende Konstruktion bereits korrekt** — bitte so lassen:

| Zeilenart | Fold-Verhalten | `ts`-Semantik | Status |
|---|---|---|---|
| `AssistantText` | `existing.copy(text = existing.text + …)` (`:25`) | `ts` des **ersten Deltas** = Turn-Beginn | ✅ safe by construction |
| `ToolCall` | `it[idx] = event` (`:36`) | würde auf Abschluss springen | 🔴 **muss `copy(ts = existing.ts)`** |
| `Result` · `Notice` · `UserTurn` · `IncomingSystem` | `if (idx >= 0) current else current + event` (`:50`) | Dedupe behält das Erste | ✅ safe |

> **Nicht „reparieren":** dass `AssistantText` den Zeitstempel des **ersten** Deltas zeigt, ist **gewollt** —
> die Zeile sagt, wann der Turn **begann**, nicht wann das letzte Zeichen ankam. Sonst wanderte die Zeit
> während des Streamens.

#### Nachtrag (CYP-335-Impl @ `dcbcaf9`): die Invariante ist **eine Ebene feiner**, als ich sie geschrieben habe

Oben steht „`ts` ist first-seen und **unveränderlich**". Für Zeilen **von der Leitung** stimmt das
uneingeschränkt. Für die zwei **im Client geborenen** Zeilen (`UserTurn`, `conn-error`-`Notice`) ist es zu
absolut: sie tragen zunächst die Browser-Uhr, und der erste Server-Stempel hebt sie **einmalig** auf die
Server-Zeitbasis (`AgentViewModel.observeServerClock` → `shiftedBy`). Das ist ein **Wechsel der Zeitbasis**,
kein Um-Datieren auf ein späteres Ereignis — ihr Stempel war immer eine lokale Schätzung, die auf einen Anker
wartete. Präzise Fassung:

> Die Zeit einer Zeile ist die ihrer **Entstehung**, nie die ihrer Aktualisierung. Zeilen von der Leitung sind
> ab dem ersten Rendern unveränderlich; im Client geborene Zeilen sind **vorläufig**, bis der erste
> Server-Stempel sie **einmal** verankert.

Sichtbare Folge, offen benannt: die Uhrzeit einer selbst gesendeten Nachricht kann sich **einmal** ändern.
Bei `localhost` (MVP) liegt die Verschiebung unter einer Sekunde und bleibt in `HH:mm` fast immer unsichtbar.

### 7.2 🔴 „Lokale Browser-Zeit" ist mit dem vorhandenen Formatter nicht erreichbar — er ist **UTC**

`eventlog/EventVisuals.kt:141–153`:

```kotlin
/** UTC wall-clock `HH:MM:SS.mmm` from epoch ms … No timezone lib in commonMain; display only … */
fun formatTs(ts: Long): String { … }
```

`kotlinx-datetime` steht **nicht** im Versionskatalog. `commonMain` hat heute **keine Zeitzonen-Fähigkeit**.

**Die Gefahr ist still.** `formatTs` liegt genau daneben, ist Monospace-formatiert und liefert `HH:MM:SS.mmm`
— es ist der naheliegendste Griff. Wer ihn tut, rendert im Sommer in Deutschland **jede Uhrzeit zwei Stunden
falsch**, und nichts an der Oberfläche verrät es. Ein Nutzer, der eine Agentenantwort auf `09:14` datiert,
während seine Uhr `11:14` zeigt, hat keine Chance, den Fehler als Zeitzone zu erkennen.

**Normativ:**
1. **`formatTs` NICHT wiederverwenden.** CYP-335 braucht einen eigenen `formatClock(ts): String` → `HH:mm`,
   24 h, führende Null, **lokale Zone**.
2. **Beweisender Test (Pflicht, nicht optional):** ein Test mit einem Offset ≠ 0, der zeigt, dass
   `formatClock` ≠ UTC-Stunde. Ein Test, der nur bei `TZ=UTC` grün ist, beweist nichts.
3. **Weg zur lokalen Zone — PO/Dev5-Entscheidung (§9-Ask 1):**
   - **(a) `kotlinx-datetime`** (`TimeZone.currentSystemDefault()`) — neue Dependency; multiplattform; räumt
     den Weg auch für 7.3. **Meine Empfehlung.**
   - **(b) `expect`/`actual`-Offset-Provider** (wasm: `Date().getTimezoneOffset()`) — minimal, aber führt ein
     `expect`/`actual` in eine Schicht zurück, aus der 05 §4/D6 es bewusst entfernt hat.

### 7.3 ⚠️ Außerhalb dieses Tickets, aber derselbe Fehler, schon live

`formatTs` (UTC) wird **ungelabelt** gerendert in `EventRowUi.kt:135`, `EventBrowsePanel.kt:353`,
`ProductLeadPanel.kt:160/177/213`, `CompactPanel.kt:207`, `CrossProjectControls.kt:82`. Für einen Operator in
`Europe/Berlin` sieht das aus wie Ortszeit und ist es nicht. Der KDoc weiß es („UTC wall-clock"), die
Oberfläche sagt es nicht.

**Das ist ein Disclosure-Defekt** (die UI behauptet implizit eine Zeit, die sie nicht meint) und **nicht durch
CYP-335 gedeckt**. Empfehlung an den PO: **eigenes Ticket** — entweder `formatTs` auf Ortszeit ziehen (dann
wäre 7.2(a) die gemeinsame Grundlage) oder die Anzeigen mit „UTC" labeln. Ich habe hier nichts an fremden
Specs geändert.

> **Keine Ordnungs-Behauptung.** `EventVisuals.kt:143` hält fest: *„ordering is always by `seq`, never this"*.
> Dieselbe Disziplin gilt hier: die Zeitspalte ist **Anzeige**, nie Sortierschlüssel. Die Renderreihenfolge
> ist die Ankunftsreihenfolge (`foldEvent`, append-only).

---

## 8. Mock — die sechs Zeilenarten untereinander

Zwei senkrechte Linien: rechte Kante der Ziffern, linke Kante des Inhalts. `‖` markiert sie.

```
 ┌ contentPadding 12dp
 │        ┌ 44dp, rechtsbündig, mono, labelSmall, onSurfaceVariant
 │        │      ┌ 8dp Rinne
 │        │      │  ┌ Inhaltskante @ 64dp — für ALLE sechs Zeilenarten identisch
 ▼        ▼      ▼  ▼
        09:14 ‖    ‖ › Kannst du die Zeitspalte spezifizieren?          UserTurnRow      secondary
        09:14 ‖    ‖ Ich sehe mir den Renderer an und melde mich        AssistantTextRow onSurface
              ‖    ‖ gleich mit einer Spec.▌                              └ Folgezeile: Zelle LEER
        09:15 ‖    ‖ ⇥ System  /compact                                  IncomingSystemRow onSurfaceVariant
        09:15 ‖    ‖ ⟳ Read(AgentWindow.kt)                              ToolCallRow      mono
        09:15 ‖    ‖▒▒ 538 Zeilen gelesen ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒    ResultRow        Container beginnt
              ‖    ‖▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒     ERST hinter der Rinne (§1.2)
        09:16 ‖    ‖ Sitzung neu verbunden                               NoticeRow        outline
              ‖    ‖ Antwort ohne Zeitstempel (ts == null)               fail-closed: leer, nie 00:00 (§6.3)
              ‖    ‖
   rechtsbündig    linke Inhaltskante
```

Worauf zu achten ist:

- **Zeile 2/3:** mehrzeiliger Assistant-Text — die Zeit hängt an der **ersten Grundlinie**, Folgezeilen sind leer.
- **Zeile 6/7:** `ResultRow`s getönter Block (`▒`) **beginnt hinter der Rinne**. Die Zeit steht auf `surface`,
  nicht auf `errorContainer`/`surfaceVariant` — deshalb genügt **ein** Kontrastpaar (§4.1).
- **`09:15` dreimal:** so soll es aussehen (§5). Kein Ausblenden, keine Dämpfung.
- **Letzte Zeile:** leere Zelle = **kein Zeitstempel bekannt**, nie „gleiche Minute wie oben".
- Die Marker `›` `⇥` `⟳` behalten ihre Position **innerhalb** der Inhaltskante — die Rinne verschiebt sie
  gemeinsam, sie zerfasern die Kante nicht.

---

## 9. §-Asks an den PO

| # | Frage | Meine Empfehlung |
|---|---|---|
| 1 | **Zeitzonen-Fähigkeit** (§7.2): `kotlinx-datetime` als Dependency, oder `expect`/`actual`-Offset nur für Wasm? | **`kotlinx-datetime`** — trägt auch 7.3; der `expect`/`actual`-Weg holt eine Naht zurück, die 05 §4/D6 gestrichen hat. Dependency-Entscheidung liegt bei dir/Dev5. |
| 2 | **`foldEvent`-Fix** (§7.1) — gehört die `ts`-Erhaltung in CYP-335 oder in ein Dev5-Folgeticket? | **In CYP-335.** Ohne sie zeigt eine der sechs Zeilen die falsche Zeit; die Spalte wäre nicht monoton. Keine Design-Frage, aber ein Blocker für die Anzeige-Wahrheit. |
| 3 | **SR-Reihenfolge** (§6.2): Zeit **vor** dem Inhalt (mein Vorschlag) oder danach wie `a11y_event_row`? | **Davor.** „Danach" kostet den Textknoten von `AssistantTextRow`. Kippbar, Preis benannt. |
| 4 | **UTC-Anzeigen** (§7.3) in Event-Log/Compact/Report/Cross-Project — eigenes Ticket? | **Ja, eigenes Ticket.** Ich habe fremde Specs nicht angefasst. |
| 5 | **Kein DE/EN-Paritäts-Guard** (§10) — `CommI18nDisclosureTest` prüft nur das Disclosure-Trio. | Tester2 könnte einen Paritäts-Test ergänzen (`values` vs. `values-en`, Key-Mengen gleich). Kleines Ticket, hoher Schutz. |

---

## 10. Shared-Key-Sync — Flag an Dev5 (CLAUDE.md-Pflicht)

`a11y_transcript_time` landet in **beiden** `strings.xml` (`values/` **und** `values-en/`; heute 466 = 466
Einträge → 467 = 467). Das konsumierende Modul (`:app:shared`) muss nach dem Key-Landen **re-syncen**, sonst
bricht ein Shared-Check. **Key-Lieferung mit der Impl timen** — ich liefere den Key auf Zuruf des
Koordinators, nicht vorab in `develop`.

---

## 11. Reuse statt Neuerfindung (gegen `f8063c1` real verifiziert)

| Reuse | Quelle | Zweck hier |
|---|---|---|
| Monospace + `labelSmall`-Zeitzelle | `eventlog/EventRowUi.kt:135` | Identische Typo-Behandlung — die App hat **eine** Zeitstempel-Sprache |
| `onSurfaceVariant` für Zeitstempel-Text | `eventlog/EventBrowsePanel.kt:353` | Farbrolle, nicht neu erfunden |
| „Sichtbarer Marker dekorativ, Zeile trägt gelabelte Beschreibung" | `UserTurnRow:473`, `IncomingSystemRow:441` (`›`, `⇥`) | a11y-Idiom für die Zeitzelle |
| Fail-closed durch Absenz | Provider-Chip (CYP-137), Fidelity-Badge (CYP-123) | leere Zelle ⇔ unbekannt (§6.3) |
| „Anzeige, nie Sortierschlüssel" | `EventVisuals.kt:143` | Zeitspalte behauptet keine Ordnung (§7.3) |
| Tag-Schema `<area>.<scopeId>.<element>.<selectorId>.<qualifier>` | `TEST-CONTRACT.md` §2 | `agent.<id>.event.<i>.time` — **keine** neue Area |
| Bestehende sechs Zeilen-Bodies | `AgentWindow.kt:335–493` | unverändert; nur umschlossen (§1) |

**Keine neue Area. Keine neue Farbe. Keine neue Typo-Stufe. Kein zweites Zeitformat neben `formatTs` —
sondern eines, das das erste *nicht* imitiert, weil das erste UTC ist (§7.2).**

---

## 12. Self-Validation

- **Counts konsistent:** **1** neuer Key (`a11y_transcript_time`, DE+EN) — `transcript-timestamp-keys.md`.
  **1** neuer Tag (`AgentViewTags.eventTime`) — `transcript-timestamp-tags.md`. **10** Token-Slots —
  `transcript-timestamp-tokens.json`. **0** geänderte bestehende Keys, **0** geänderte bestehende Tags,
  **0** neue `EventKind`-Werte.
- **Alle sechs Zeilenarten adressiert** (§8 Mock, §7.1 Tabelle): `UserTurn` · `AssistantText` ·
  `IncomingSystem` · `Notice` · `ToolCall` · `Result`. Genau **eine** (`ToolCall`) kann die Zeit strukturell
  heute nicht tragen — mit Fix und Fehlerfall belegt.
- **Kontrast gerechnet, nicht geschätzt:** gewähltes Paar 8,69:1 (hell) / 9,80:1 (dunkel) = **AAA**;
  `outline` als FAIL nachgewiesen; α-Boden bei 0,80 quantifiziert. Beide Themes.
- **Disclosure:** leere Zelle ⇔ unbekannt (nie „wie oben"); `ts` = first-seen, nie Update-Zeit; keine
  Ordnungs-Behauptung; keine Semantikfarbe auf der Uhr; UTC-Falle benannt und verboten.
- **Alle sechs PO-Fragen beantwortet:** 1 → §2 · 2 → §3 · 3 → §5 · 4 → §4 · 5 → §6 · 6 → `-tags.md`.
- **Docs-only:** kein Code geändert. Kein Merge nach `develop`.
