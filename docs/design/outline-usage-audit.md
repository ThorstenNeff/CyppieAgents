# `outline` / `outlineVariant` — Verwendungs-Audit (CYP-337)

> Owner: UIUX-Designer · Ticket **CYP-337** · Stand 2026-07-10 · Basis **develop `461a0ce`**
> Scope: **WASM-App** — `app/shared/src/commonMain` (+ `wasmJsMain`, `app/webApp`). Docs-only, kein Code.
> Auslöser: Nebenbefund aus dem UX-QA-Pass zu CYP-335 (`NoticeRow` färbt ihren **eigenen** Text mit `outline`).
> Auftrag: nicht die eine Zeile reparieren, sondern **die Regel belegen**.

---

## 0. Die Regel — und was dieses Dokument dazu beiträgt

> **Metadaten-Textfarbe ist `onSurfaceVariant`. `outline` ist für Rahmen und Trenner.**

Diese Behauptung ist erst dann eine Regel, wenn sie **jede** Aufrufstelle entweder bestätigt oder als Verstoß
ausweist. Deshalb sind unten **alle acht** Fundstellen klassifiziert — auch und gerade die korrekten. Der PO
hat ausdrücklich verlangt, dass eine Bereinigung keine richtigen Verwendungen mitreißt; die **§3-Tabelle
zertifiziert sie namentlich als korrekt**, damit niemand sie später „mit aufräumt".

**Ergebnis in einem Satz:** Von acht Fundstellen sind **drei echte AA-Verstöße** (alle drei: `outline` als
Textfarbe), **zwei Grenzfälle** (DEBUG-Severity als Glyph/Pill), und **drei sind korrekt** und sollen bleiben.
`outlineVariant` wird **nie** für Text benutzt — dort ist die Regel bereits eingehalten.

---

## 1. Vollständigkeit der Suche

| Suchraum | Treffer |
|---|---|
| `colorScheme.outline` / `colorScheme.outlineVariant` in `commonMain` + `wasmJsMain` | 6 |
| Indirekt über `scheme.outline` (die Severity-Quelle `EventVisuals.kt`) | 2 |
| `app/webApp` (Wasm-Entry-Point, `main.kt`) | 0 |
| **Summe auditierter Aufrufstellen** | **8** |

Definitionsstelle `ui/MaritimeTheme.kt:39/61` (setzt die Tokens) ist keine Aufrufstelle und nicht gezählt.

---

## 2. Klassifikation & Schwellen

Nicht jede Farbverwendung schuldet 4,5:1. Drei Kategorien, drei Schwellen — sonst produziert ein Audit
Falschmeldungen:

| Kat. | Was | WCAG | Schwelle |
|---|---|---|---|
| **T — Text** | Ein `Text(color = …)`, dessen **Wortlaut** die Information trägt | 1.4.3 Contrast (Minimum) | **4,5:1** (< 18 pt / 14 pt bold) |
| **S — Symbol/Glyph** | Ein Zeichen, dessen Bedeutung **zusätzlich** durch Label/`contentDescription` getragen wird | 1.4.11 Non-text Contrast | **3:1** |
| **D — Dekor** | Rahmen, Punkt, Trenner; Bedeutung liegt **vollständig** im begleitenden Text | 1.4.1 / 1.4.11 (Ausnahme: rein dekorativ) | **keine** |

> **Warum das zählt:** `outline` liegt in beiden Themes bei ≈ 3,6:1. Das ist **oberhalb** der Symbol-/Dekor-
> Schwelle und **unterhalb** der Textschwelle. Genau deshalb ist `outline` als Rahmenfarbe richtig **und** als
> Textfarbe falsch — es ist dieselbe Farbe, die eine Anforderung erfüllt und die andere nicht. Die Regel ist
> keine Stilpräferenz, sie ist die Konsequenz aus dieser einen Zahl.

Alle Werte gegen `MaritimeLight` / `MaritimeDark` (`ui/MaritimeTheme.kt` @ `461a0ce`) nach der
WCAG-2.1-Kontrastformel gerechnet.

| Rolle | hell | dunkel |
|---|---|---|
| `surface` | `#FFFFFF` | `#06121A` |
| `surfaceVariant` | `#E4EFF8` | `#12242F` |
| `outline` | `#6E8C9E` | `#57707F` |
| `outlineVariant` | `#CBDCE7` | `#243642` |
| `onSurfaceVariant` *(das Ziel)* | `#3A4E5A` | `#A6BECD` |

---

## 3. Die acht Fundstellen

### 3.1 🔴 Text-Verwendungen — **drei Verstöße, alle identisch**

| # | Fundstelle | Was | Typo | Untergrund | hell | dunkel | AA (4,5:1) |
|---|---|---|---|---|---|---|---|
| 1 | `agentview/AgentWindow.kt:489` | `NoticeRow` — der Hinweistext selbst | `labelSmall` 11 sp | `surface` | **3,55:1** | **3,63:1** | **FAIL** |
| 2 | `comm/CommPanel.kt:289` | „· ausstehend" am Nachrichtenkopf (`comm_msg_pending`) | `labelSmall` 11 sp | `surface` | **3,55:1** | **3,63:1** | **FAIL** |
| 3 | `eventlog/EventRowUi.kt:168` | Correlation-Chip `· a1b2c3d4` / `· —` | `labelSmall` 11 sp | `surface` | **3,55:1** | **3,63:1** | **FAIL** |

Alle drei tragen **echte, nur im Wortlaut vorhandene** Information: ein Lifecycle-Hinweis, der Zustand
„ausstehend", eine Correlation-ID. Keine hat ein Symbol oder Label, das die Bedeutung ersatzweise trüge.
Kategorie **T**, Schwelle 4,5:1, beide Themes **darunter**.

> **CYP-337 wurde als Ein-Zeilen-Ticket eröffnet (Fundstelle 1). Es sind drei.** Fundstellen 2 und 3 sind neu
> und waren im Ticket nicht genannt.

**Fix (identisch für alle drei):** `color = MaterialTheme.colorScheme.onSurfaceVariant`
→ **8,69:1 / 9,80:1 (AAA)**. Die Metadaten bleiben zurückgenommen (`onSurface` liegt bei 15,6:1 / 15,1:1);
`outline` war nie nötig, um „leise" zu wirken.

### 3.2 🟠 Grenzfälle — DEBUG-Severity, **eine Entscheidung des POs**

Beide entspringen **derselben Quelle**, `eventlog/EventVisuals.kt`:

| # | Fundstelle | Was | gerendert als | hell | dunkel |
|---|---|---|---|---|---|
| 4 | `EventVisuals.kt:76` → `report/ProductLeadPanel.kt:263` | `severityColorFor(DEBUG) = scheme.outline` | Severity-**Glyph** (`labelMedium`) auf `surface`, mit `contentDescription` = Severity-Label | 3,55:1 | 3,63:1 |
| 5 | `EventVisuals.kt:84` → `window/WindowBadge.kt:101` | `severityContainerFor(DEBUG) = outline to surface` | **Pill**: `·`-Glyph in `surface`-Farbe auf `outline`-Container, mit `a11y_badge_severity` | 3,55:1 | 3,63:1 |

**Beides ist Kategorie S**, wenn man den Glyphen als Symbol liest — und dann **bestehen sie** (≥ 3:1). Beide
Stellen tragen die Bedeutung nachweislich im Text (`contentDescription`), nicht in der Farbe; das entspricht
der WCAG-1.4.1-Disziplin, die dieses Projekt konsequent fährt.

Sie sind trotzdem gelistet, weil beide technisch `Text`-Composables sind: ein Prüfer, der stur nach
`Text(color = …)` greift, wird sie als Verstoß melden. **Meine Einschätzung: kein Verstoß, keine Änderung** —
`3,55:1` für einen `·`-Glyph, dessen Bedeutung ohnehin angesagt wird, ist normkonform und optisch gewollt
(DEBUG ist die leiseste Severity, §3.4). **PO-Ask 1:** bestätigen, dann sind sie in einem Guard (§5)
namentlich auszunehmen.

### 3.3 ✅ Korrekte Verwendungen — **ausdrücklich als korrekt ausgewiesen, nicht anfassen**

| # | Fundstelle | Was | Kat. | hell | dunkel | Urteil |
|---|---|---|---|---|---|---|
| 6 | `agentview/AgentWindow.kt:262` | `AgentLifecycleState.STOPPED` → Punktfarbe (8 dp `Box`) | **D** | 3,55:1 | 3,63:1 | **korrekt** — und läge ohnehin über 3:1 |
| 7 | `agentview/AgentWindow.kt:264` | `AgentLifecycleState.UNKNOWN` → Punktfarbe, `outlineVariant` | **D** | 1,41:1 | 1,52:1 | **korrekt** (s. u.) |
| 8 | `agentsettings/AgentAvatarSection.kt:228` | `BorderStroke(1.dp, outlineVariant)` am **nicht** gewählten Avatar-Preset | **D** | 1,21:1 | 1,27:1 | **korrekt** (s. u.) |

Fundstelle 6 ist die vom PO genannte Referenz — bestätigt: ein Statuspunkt, dessen Bedeutung der danebenstehende
Text trägt (`StatusIndicator` kommentiert es selbst: *„Colour is never the sole signal"*). Rahmen/Dekor,
Regel eingehalten.

Zu **7 und 8**, die unter 3:1 liegen und trotzdem **keine Verstöße** sind — hier ist Genauigkeit wichtiger
als eine rote Zahl:

- **7 (UNKNOWN-Punkt):** Der Zustand steht als **Text** daneben, und der ganze `Row` trägt
  `contentDescription = a11y_agent_status`. Der Punkt ist reine Verstärkung. WCAG 1.4.11 nimmt rein
  dekorative Elemente aus. Dass der leiseste Zustand den leisesten Punkt bekommt, ist **beabsichtigt**.
- **8 (Avatar-Rahmen):** Der Rahmen begrenzt kein Bedienelement, das sonst unsichtbar wäre — die Kachel hat
  einen eigenen `surfaceVariant`-Hintergrund. Die **Auswahl** wird durch `primary` + `3.dp` + das
  `selected`-Semantics-Flag getragen, also durch Form **und** a11y, nicht durch diesen 1-dp-Strich.

> **Konsequenz für die Bereinigung:** Fundstellen 6–8 gehören **nicht** in CYP-337. Wer `outlineVariant`
> „zur Sicherheit" auf `onSurfaceVariant` zöge, machte einen leisen Punkt und einen ruhigen Rahmen laut und
> zerstörte die Zustands-Hierarchie. **Die Regel sagt nicht „outline ist schlecht", sie sagt „outline ist kein
> Text".**

### 3.4 Zusammenfassung

| Kategorie | Anzahl | Fundstellen |
|---|---|---|
| 🔴 Verstoß (Text unter 4,5:1) | **3** | 1 · 2 · 3 |
| 🟠 Grenzfall (Symbol, ≥ 3:1, PO bestätigt) | **2** | 4 · 5 |
| ✅ Korrekt (Rahmen/Dekor) | **3** | 6 · 7 · 8 |
| | **8** | |

**`outlineVariant` wird nirgends für Text verwendet** (Fundstellen 7, 8 sind Punkt und Rahmen) — für diese
Rolle ist die Regel heute schon lückenlos eingehalten.

---

## 4. Separat: weitere ausgelieferte Kontrast-Befunde **ohne** Bezug zu `outline`

> Der PO hat verlangt, das nicht ins Ticket zu mischen. Es ist deshalb eine eigene Liste — und sie ist
> **erfreulich kurz**.

**Echte AA-Verstöße außerhalb von `outline`: keine.** Jedes andere Text-auf-Untergrund-Paar der App besteht
AA in **beiden** Themes, mehrheitlich AAA:

| Paar | Wo | hell | dunkel |
|---|---|---|---|
| `onSurfaceVariant` auf `surface` | Metadaten überall | 8,69:1 | 9,80:1 |
| `onSurfaceVariant` auf `surfaceVariant` | `ResultRow` (Erfolg) | 7,45:1 | 8,24:1 |
| `onErrorContainer` auf `errorContainer` | `ResultRow` (Fehler) | 12,77:1 | 7,17:1 |
| `secondary` auf `surface` | `UserTurnRow`, INFO-Glyph | 7,31:1 | 10,88:1 |
| `primary` auf `surface` | `ToolCall` OK, Streaming-Cursor | 7,04:1 | 9,22:1 |
| `error` auf `surface` | `ToolCall` ERROR | 6,54:1 | 11,09:1 |
| `onSecondaryContainer` auf `secondaryContainer` | `TonedHint(EFFECT_DEFERRED)` | 11,27:1 | 6,77:1 |
| WARN-Amber-Pill (eigene Palette) | `severityContainer(WARN)` | 8,23:1 | 7,17:1 |

**Drei Grafikobjekte liegen unter 3:1 — und alle drei sind ausgenommen, zwei davon nachweislich mit Absicht:**

| Objekt | hell | dunkel | Warum kein Verstoß |
|---|---|---|---|
| Severity-Rail **DEBUG** (4 dp Balken, `EventVisuals.kt:50` dunkel / `:57` hell) | **1,90:1** | **2,91:1** | Der Code sagt es selbst: *„DEBUG is **deliberately dim in BOTH schemes**: the quietest severity earns no ≥3:1 rail; its meaning rides the `·` glyph + text label, never colour alone."* Redundant abgesichert, dokumentiert, von UIUX handverlesen. |
| UNKNOWN-Statuspunkt (Fundstelle 7) | 1,41:1 | 1,52:1 | Bedeutung im Text + `contentDescription`; rein dekorativ. |
| Avatar-Preset-Rahmen (Fundstelle 8) | 1,21:1 | 1,27:1 | Auswahl über `primary` + `3.dp` + `selected`-Semantics; Kachel hat eigenen Hintergrund. |

> **Nebenbeobachtung, kein Befund:** Im hellen Theme ist der DEBUG-Rail mit 1,90:1 praktisch unsichtbar. Das
> ist die dokumentierte Absicht, nicht ein Fehler. Ich nenne es nur, damit es beim nächsten Kontrast-Sweep
> nicht ein zweites Mal „gefunden" wird.

Die Identitäts-Farben (`SenderPalette`, 9 Slots) sind **nicht** gelistet: sie hängen nicht am Scheme und
werden zur Laufzeit über `readableNameAccent()` / `readableAccentOn()` (CYP-275) auf den aktiven Untergrund
korrigiert. Das ist bereits ein Guard, kein offener Punkt.

---

## 5. Empfehlung: die Regel selbst-durchsetzend machen

Ein Audit verfällt. Das Projekt hat für **genau dieses Problem** bereits ein Muster —
`ui/TertiarySourceGuardTest.kt` (CYP-303) scannt `commonMain` und wird rot, sobald `colorScheme.tertiary*`
außerhalb von `MaritimeTheme.kt` wieder auftaucht.

**Vorschlag (Umsetzung, nicht dieser Branch):** ein `OutlineTextGuardTest` nach demselben Bauplan, der auf
`outline` **als Textfarbe** anschlägt statt auf jede Verwendung:

- Rot bei `color = MaterialTheme.colorScheme.outline` (bzw. `outlineVariant`) an einem `Text(…)`.
- Grün für `background(…)`, `BorderStroke(…)`, `Box`-Punktfarben — die Kategorien D.
- Die zwei Grenzfälle aus §3.2 (`EventVisuals.kt`) werden **namentlich ausgenommen**, mit dem Grund im
  Test-KDoc — wie der `tertiary`-Guard `MaritimeTheme.kt` ausnimmt.

So wird aus „wir haben es einmal aufgeräumt" ein Invariant. **PO-Ask 2:** eigenes kleines Ticket für Dev oder
Tester2, sinnvoll **nach** dem CYP-337-Fix (vorher wäre er per Konstruktion rot).

---

## 6. §-Asks an den PO

| # | Frage | Meine Empfehlung |
|---|---|---|
| 1 | **Grenzfälle §3.2** (DEBUG-Glyph + DEBUG-Pill, je 3,55:1 / 3,63:1): Symbol (≥ 3:1, bestehen) oder Text (4,5:1, fallen durch)? | **Symbol → unverändert lassen.** Bedeutung wird in beiden Fällen angesagt; die Farbe ist Verstärkung. |
| 2 | **Source-Guard** (§5) — eigenes Ticket nach dem Fix? | **Ja.** Muster liegt mit `TertiarySourceGuardTest` bereits im Repo. |
| 3 | **CYP-337-Scope:** Das Ticket nennt eine Fundstelle, es sind **drei** (§3.1). Alle drei im selben Fix? | **Ja** — identische Ursache, identische Korrektur, ein Commit. Sonst bleiben zwei AA-Verstöße stehen, die dieses Audit namentlich kennt. |

---

## 7. Self-Validation

- **8 Fundstellen gesucht, 8 klassifiziert**, Summe der Kategorien (3 + 2 + 3) = 8. Keine Fundstelle ohne Urteil.
- **Jede Text-Verwendung** hat gemessenen Kontrast in **beiden** Themes, Schriftgröße und AA-Urteil (§3.1).
- **Jede Rahmen-/Dekor-Verwendung ist ausdrücklich als korrekt ausgewiesen** (§3.3), mit Begründung, warum
  auch die zwei sub-3:1-Fälle keine Verstöße sind — damit eine Bereinigung sie nicht mitreißt.
- **Nicht-`outline`-Befunde stehen getrennt** (§4) und sind nicht ins Ticket gemischt. Ergebnis: **keine**
  weiteren AA-Verstöße; drei sub-3:1-Grafikobjekte, alle ausgenommen, zwei davon dokumentiert absichtlich.
- **Eine Korrektur an meinem eigenen Vorwissen:** `tertiaryContainer` ist in `commonMain` **nicht mehr in
  Gebrauch** (CYP-300/303 haben es de-overloaded, der Guard hält es); die im `MaritimeTheme`-KDoc erwähnte
  Paarung `secondary` auf `tertiaryContainer` existiert im Code nicht mehr. `TonedHint(INFO)` färbt heute
  Text auf `surface`. Gegen `461a0ce` verifiziert, nicht aus dem KDoc übernommen.
- **Docs-only.** Kein Code geändert, keine fremde Spec angefasst.
