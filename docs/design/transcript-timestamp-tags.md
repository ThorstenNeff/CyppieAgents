# Zeitstempel im Agenten-Transkript — Test-Tags (CYP-335)

> Owner: UIUX-Designer · Spec: `transcript-timestamp-spec.md` · Basis `develop f8063c1`
> Adressat: **Tester2** (+ Dev5 als Implementierer) · Vertrag: `docs/TEST-CONTRACT.md` §2 (v0.6)
> **1 neuer Tag. 0 umbenannte Tags. 0 neue `EventKind`-Werte. Keine neue Area.**

---

## 1. Der neue Tag

```
agent.<agentId>.event.<index>.time
```

| Segment | Rolle laut Test-Contract §2 | Wert |
|---|---|---|
| `agent` | `<area>` (feste Vokabelliste) | — |
| `<agentId>` | `<scopeId>` (instanz-skaliert) | z. B. `po`, `frontend` |
| `event` | `<element>` | — |
| `<index>` | `<selectorId>` | 0-basierte, additiv-stabile Render-Reihenfolge |
| `time` | `<qualifier>` | **Sub-Teil** der Event-Zeile |

Vorgeschlagene Ergänzung in `agentview/AgentViewTags.kt` (additiv, neben den bestehenden `event(...)`-Overloads):

```kotlin
/** CYP-335: die Zeitzelle der N-ten Event-Zeile. `time` ist ein Sub-Teil-Qualifier, KEIN EventKind. */
fun eventTime(agentId: String, index: Int) = "agent.$agentId.event.$index.time"
```

---

## 2. `time` ist **kein** `EventKind` — bitte nicht in das Enum aufnehmen

Der Qualifier-Slot trägt heute zwei verschiedene Dinge:

| Beispiel | Was im Qualifier steht | Vokabular |
|---|---|---|
| `agent.po.event.3.toolCall` | eine **Event-Art** | `EventKind` (5 Werte, geschlossen) |
| `agent.po.event.3.time` | ein **Sub-Teil** der Zeile | frei, wie `read`/`write` in der ACL-Matrix |

Der Test-Contract definiert `<qualifier>` explizit als *„Sub-Teil (z. B. `read`/`write`)"* — der
Kind-Qualifier ist die **optionale Zusatznutzung** desselben Slots. `time` ist damit die **contract-nativere**
Verwendung von beiden.

**Konsequenz für QA:** `time` ∉ `EventKind`, also matcht keine kind-basierte Assertion (`.toolCall`,
`.assistantText`, …) je die Zeitzelle. Ein Regex `agent\.po\.event\.\d+\..*` trifft beide — wer nur Zeilen
will, filtert gegen die `EventKind`-Vokabelliste.

`EventKind` bleibt bei **fünf** Werten: `assistantText` · `toolCall` · `toolResult` · `userTurn` · `system`.

---

## 3. Wo der Tag hängt — und was sich **nicht** bewegt

Die Zeitspalte ist eine **Rinne des Transkript-Containers** (Spec §1). Der Wrapper `TranscriptRow` ist
**untagged**; die Zeitzelle bekommt ihren eigenen Tag als **Geschwister** der Inhaltsbox.

```
Row (TranscriptRow)                              ← KEIN Tag
├── Text  "09:14"                                ← agent.<id>.event.<i>.time      NEU
└── Box                                          ← agent.<id>.event.<i>.<kind>    UNVERÄNDERT
    └── UserTurnRow / AssistantTextRow / …           (bzw. agent.<id>.event.<i> bei Notice)
```

> **Bewusst so herum.** Der Kind-Tag bleibt **exakt** auf dem Knoten, auf dem er heute sitzt, und umschließt
> **exakt** den bisherigen Inhalt. Hätte der Wrapper den Kind-Tag übernommen, umfasste der getaggte Knoten
> plötzlich auch die Zeitzelle — **jede** bestehende textbasierte Assertion auf `event.<i>.<kind>` wäre ein
> potenzieller Fehlalarm. **Null Churn für QA.**

Der `stream`-Tag (`agent.<id>.stream`) und `contentPadding`/`verticalArrangement` der `LazyColumn` bleiben
unverändert.

---

## 4. Adressierbarkeit trotz `clearAndSetSemantics` — **Gotcha für Dev5**

Die Zeitzelle bekommt eine gelabelte `contentDescription` (Spec §6.1), damit der Screenreader nicht nackte
Ziffern liest. Sie folgt dem Idiom der Datei: `Modifier.clearAndSetSemantics { … }`.

> **Der Tag gehört *in* den Block:**
> ```kotlin
> Modifier.clearAndSetSemantics {
>     contentDescription = timeDescription            // "um 09:14 Uhr"
>     testTag = AgentViewTags.eventTime(agentId, index)
> }
> ```
>
> **Nachgemessen (Dev5, CYP-335 @ `dcbcaf9`) — der ursprünglich hier behauptete Gotcha reproduziert sich
> nicht.** Auf **Compose 1.9 / Kotlin 2.4** überlebt ein *vor* dem Block gesetztes `Modifier.testTag(…)` das
> Clearing; `TranscriptTimestampRenderTest` bleibt in beiden Varianten grün. Ich hatte den Verlust als
> wahrscheinlich beschrieben — er ist es auf diesem Stand nicht.
>
> Die Platzierung im Block bleibt trotzdem die Vorgabe: sie ist **reihenfolge-unabhängig** und damit
> Versicherung gegen ein Verhalten, das wir nicht kontrollieren (die Semantik-Merge-Regeln sind kein
> zugesichertes API) — **nicht** die Behebung eines beobachteten Bruchs. Der Test soll die
> **Adressierbarkeit** festnageln, nicht den Gotcha.

> **Regel, die daraus folgt:** Diese Spec sagt „mit `onNodeWithTag` belegen, nicht annehmen" — das galt auch
> für ihre eigene Warnung. Der Befund ist hier festgehalten, statt als Gerücht weiterzuwandern.

`testTagsAsResourceId` ist auf Wasm der Mechanismus, über den Maestro den Tag sieht
(`testing/TestTagsResourceId.kt`) — er wird einmalig am App-Root gesetzt und ist hier nicht anzufassen.

---

## 5. Was Tester2 damit prüfen kann

| Assertion | Tag / Quelle | Erwartung |
|---|---|---|
| Jede der sechs Zeilenarten trägt eine Zeitzelle | `agent.<id>.event.<i>.time` für `i = 0..n` | existiert für **alle** `i` — auch bei identischer Minute (Spec §5: **nie** ausgeblendet) |
| Format | Textinhalt der Zeitzelle | `HH:mm`, 24 h, führende Null (`09:14`, nie `9:14`) |
| **Lokale Zone, nicht UTC** | Zeitzelle vs. bekannter Offset | bei Offset ≠ 0 **ungleich** der UTC-Stunde. Ein Test, der nur bei `TZ=UTC` grün ist, beweist nichts (Spec §7.2). |
| **Monotonie der Spalte** | Zeitzellen `i` aufsteigend | nie rückwärts. **Reproduziert heute den Bug** aus Spec §7.1: `ToolCall(RUNNING)` @ 09:14 → `AssistantText` @ 09:15 → `ToolCall(OK)` @ 09:17 ⇒ Spalte liest `09:17` über `09:15`. |
| `ts` ist first-seen | `ToolCall` `RUNNING → OK` mit gleicher `id` | Zeitzelle **ändert sich nicht** |
| Streaming-Turn | `AssistantText`-Deltas mit gleicher `id` | Zeitzelle zeigt **Turn-Beginn**, wandert während des Streamens nicht |
| Fail-closed | `ts == null` | Zelle **leer**; **kein** `00:00`, **kein** `--:--` |
| Screenreader | `contentDescription` der Zeitzelle | „um 09:14 Uhr" (DE) / „at 09:14" (EN) — **nie** nackte Ziffern |
| Kind-Tags unverändert | `agent.<id>.event.<i>.<kind>` | bestehende Assertions bleiben grün (§3) |

**Reihenfolge im a11y-Baum:** Zeitzelle **vor** dem Inhalt (Spec §6.2). Falls der PO auf Suffix kippt
(§9-Ask 3), ändert sich diese eine Erwartung — der Tag nicht.

---

## 6. Self-Validation

- **1** neuer Tag-Identifier (`eventTime`), Schema-konform: Segmente `[A-Za-z0-9-]+`, Trenner nur `.`,
  Area aus der festen Liste (`agent`), `<index>` ziffernbasiert → Maestro-Regex-sicher.
- **0** Umbenennungen, **0** verschobene Tags, **0** neue Areas, **0** neue `EventKind`-Werte.
- Counts stimmen mit `transcript-timestamp-spec.md` §12 und `transcript-timestamp-keys.md` §5 überein
  (1 Tag · 1 Key · 10 Token-Slots).
