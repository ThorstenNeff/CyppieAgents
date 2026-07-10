# `outline` / `outlineVariant` — Verwendungs-Audit (CYP-337)

> Owner: UIUX-Designer · Ticket **CYP-337** · Stand 2026-07-10 · Basis **develop `461a0ce`**
> Scope: **WASM-App** — `app/shared/src/commonMain` (+ `wasmJsMain`, `app/webApp`). Docs-only, kein Code.
> Auslöser: Nebenbefund aus dem UX-QA-Pass zu CYP-335 (`NoticeRow` färbt ihren **eigenen** Text mit `outline`).
> Auftrag: nicht die eine Zeile reparieren, sondern **die Regel belegen**.

---

## 0. Die Regel — und was dieses Dokument dazu beiträgt

> **Metadaten-Textfarbe ist `onSurfaceVariant`. `outline` ist für Rahmen und Trenner.**

Diese Kurzform trägt nicht. Die Fassung, die trägt (§2.3, entschieden 2026-07-10):

> Eine Verwendung von `outline`/`outlineVariant` ist **korrekt**, wenn sie
> **(1) keinen Text färbt** — Text im Sinne der WCAG-Definition: *eine Zeichenfolge, die etwas in
> **menschlicher Sprache** ausdrückt* — **und (2)** entweder **≥ 3:1** gegen ihren Untergrund misst
> **oder** ihre Information **redundant** von Text/`contentDescription` getragen wird.
>
> **(3)** Die Regel gilt dem **Paar**, nicht der Rolle: `outline` als *Hintergrund* hinter
> `surface`-farbenem Inhalt ist **dasselbe 3,55:1-Paar mit vertauschten Seiten**.

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

### 2.1 Nicht jede Farbverwendung schuldet 4,5:1

| Kat. | Was | WCAG | Schwelle |
|---|---|---|---|
| **T — Text** | eine Zeichenfolge, die etwas in **menschlicher Sprache** ausdrückt | 1.4.3 Contrast (Minimum) | **4,5:1** |
| **S — Symbol/Grafikobjekt** | ein Zeichen oder eine Form, die **keine** Sprache ist (Glyph, Punkt, Balken, Pill) | 1.4.11 Non-text Contrast | **3:1** |
| **D — Dekor** | Bedeutung liegt **vollständig** im begleitenden Text | 1.4.3 „incidental" / 1.4.11 „required to understand" | **keine** |

### 2.2 Das Kriterium ist **nicht** „Glyph oder Prosa", sondern WCAG's eigene Text-Definition

Ein `Text`-Composable macht noch keinen Text im Sinne der Norm. WCAG definiert **text** als

> *„sequence of characters … **expressing something in human language**"*

und 1.4.3 nimmt „incidental" Zeichen ausdrücklich aus. Ein `·`, ein `⚠`, ein `✓` sind **Icons, die zufällig
aus einer Schrift stammen** — sie drücken nichts in menschlicher Sprache aus. Sie fallen unter **1.4.11**.

**Das ist die einzige Trennlinie, die hält.** „Glyph vs. Prosa" wäre eine Faustregel; die Sprach-Definition
ist die Norm und entscheidet auch die Fälle, die uns morgen begegnen — etwa ein `Text(severityLabel(sev),
color = severityColor(sev))`, das das **Wort** „Debug" in `outline` malte: eine Zeichenfolge in menschlicher
Sprache, also **1.4.3**, also **4,5:1**, also ein Verstoß.

### 2.3 Die Regel, verbindlich formuliert

> Eine Verwendung von `outline`/`outlineVariant` ist **korrekt**, wenn:
>
> **(1) sie färbt keinen Text** (menschliche Sprache, s. §2.2), **und**
> **(2)** sie misst **≥ 3:1** gegen ihren Untergrund **oder** ihre Information wird **redundant** getragen
> (sichtbarer Text bzw. `contentDescription` daneben — dann greift 1.4.11 „required to understand" nicht).
>
> **(3) Die Regel gilt dem Paar, nicht der Rolle.** Ob `outline` vorn oder hinten steht, ändert den Kontrast
> nicht: `surface`-Inhalt auf `outline`-Container misst **exakt dieselben 3,55:1 / 3,63:1**.

**Warum die drei Klauseln nicht kürzbar sind:**
- Ohne **(1)** wäre `NoticeRow` erlaubt (3,55:1 ≥ 3, aber es ist Prosa).
- Ohne **(2b)** wären der UNKNOWN-Punkt (1,41:1) und der Avatar-Rahmen (1,21:1) Verstöße — sie sind es nicht.
- Ohne **(3)** ist die Pill (§3.2) **unauffindbar**: dort ist `outline` der Hintergrund.

Alle Werte gegen `MaritimeLight` / `MaritimeDark` (`ui/MaritimeTheme.kt` @ `461a0ce`), WCAG-2.1-Formel.

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

### 3.2 ✅ **Entschieden:** die zwei DEBUG-Fälle sind **Grafikobjekte** — korrekt, unverändert

Beide entspringen `eventlog/EventVisuals.kt`:

| # | Quelle | Renderstelle | Was steht auf was | hell | dunkel |
|---|---|---|---|---|---|
| 4 | `:76` `severityColorFor(DEBUG) = scheme.outline` | `report/ProductLeadPanel.kt:262` | `outline`-Glyph `·` auf `surface` | 3,55:1 | 3,63:1 |
| 5 | `:84` `severityContainerFor(DEBUG) = outline to surface` | `window/WindowBadge.kt:101` → `Pill` | `surface`-Glyph `·` auf `outline`-Container | 3,55:1 | 3,63:1 |

**Entscheidung: 1.4.11 (Grafikobjekt, 3:1). Beide bestehen. Keine Änderung.**

**Begründung:** Ein `·` drückt nichts in **menschlicher Sprache** aus (§2.2). Es ist ein Icon, das zufällig
aus einer Schrift stammt und in einem `Text`-Composable landet. Die Norm knüpft an die **Zeichenfolge**, nicht
an den Composable.

> **Eine Prämisse muss ich korrigieren — meine eigene wie die im Ticket.** Es hieß, „in beiden Fällen steht
> ein sichtbares Textlabel daneben". **Das stimmt nicht.** In `DefectRow` ist `severityLabel(…)` **nur**
> `contentDescription` (`ProductLeadPanel.kt:267`); sichtbar sind der Glyph und `item.text`. In der `Pill`
> ist der Glyph der **einzige** sichtbare Inhalt (`WindowBadge.kt:133`, `Text(text = glyph, …)`), die Severity
> steckt sonst nur in `a11y_badge_severity`.
>
> **Beide Glyphen sind also visuell erforderlich, um den Inhalt zu verstehen** — Klausel (2b) trägt sie
> **nicht**. Sie bestehen über die **Zahl**: `3,55:1 ≥ 3:1`. Das ist ein Unterschied mit Folgen: **wer sie
> künftig dimmt und sich dabei auf „ist ja redundant" beruft, bricht sie.** Sie haben die Redundanz nicht,
> die man ihnen unterstellt hat.

**Was die Ausnahme im Guard begrenzt (§5):** Sie gilt der **Renderstelle**, die einen sprachlosen Glyphen
malt — **nicht** der Farbquelle `severityColorFor`/`severityContainerFor`. Eine Ausnahme an der *Quelle*
segnete jeden künftigen Konsumenten mit, auch einen, der `Text(severityLabel(sev), color =
severityColor(sev))` schriebe: das **Wort** „Debug" in `outline` — menschliche Sprache, 1.4.3, **4,5:1**,
Verstoß. Die Ausnahme muss also **dort** stehen, wo bewiesen ist, dass ein Icon gerendert wird.

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

| Kategorie | Anzahl | Fundstellen | trägt über |
|---|---|---|---|
| 🔴 Verstoß — **Text** (menschliche Sprache) unter 4,5:1 | **3** | 1 · 2 · 3 | — |
| ✅ Korrekt — **Grafikobjekt**, besteht 3:1 | **2** | 4 · 5 | Klausel (2a) |
| ✅ Korrekt — **Grafikobjekt/Dekor**, unter 3:1, aber redundant | **2** | 7 · 8 | Klausel (2b) |
| ✅ Korrekt — **Dekor**, besteht ohnehin 3:1 | **1** | 6 | (2a) **und** (2b) |
| | **8** | | |

**`outlineVariant` wird nirgends für Text verwendet** (Fundstellen 7, 8 sind Punkt und Rahmen) — für diese
Rolle ist die Regel heute schon lückenlos eingehalten.

> **Der Satz „fünf `outline`-Verwendungen bleiben, keine färbt Text" ist falsch** und gehört korrigiert:
> **zwei von ihnen färben sehr wohl einen `Text`-Knoten** (Fundstellen 4 und 5). Sie sind trotzdem korrekt —
> aber aus dem Grund in §2.2, nicht weil sie „keinen Text färben". Der Unterschied ist genau der, der einen
> Guard baubar macht.

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

## 5. Der Guard — und warum er **zweiseitig** suchen muss

Ein Audit verfällt. Das Projekt hat für genau dieses Problem bereits ein Muster:
`ui/TertiarySourceGuardTest.kt` (CYP-303) scannt `commonMain` und wird rot, sobald `colorScheme.tertiary*`
außerhalb von `MaritimeTheme.kt` wieder auftaucht.

### 5.1 Die Pill braucht einen eigenen Satz — sie ist sonst unauffindbar

Ein Guard, der nach **`outline` als Textfarbe** sucht, findet Fundstelle 5 **prinzipiell nicht**: dort ist
`outline` der **Hintergrund** (`severityContainerFor(DEBUG) = outline to surface`, `Pill` malt
`background(container)` + `Text(color = content)`). Es ist **dasselbe Farbpaar mit vertauschten Seiten** und
**exakt derselbe Kontrast** — 3,55:1 / 3,63:1, nachgerechnet in beiden Richtungen.

> **Deshalb ja: der Pill-Fall braucht einen eigenen Satz.** Nicht weil er anders zu bewerten wäre, sondern
> weil eine **rollenbasierte** Suche ihn nicht sieht. Die Regel muss dem **Paar** gelten (§2.3 Klausel 3),
> und der Guard muss **beide Seiten** abtasten:
>
> 1. `outline*` als **Vordergrund** eines `Text(color = …)`
> 2. `outline*` als **Hintergrund** (`background(…)`, `BorderStroke(…)`, `(container, onColor)`-Paare wie
>    `severityContainerFor`), **hinter** dem Text oder ein Icon liegt

Ohne (2) ist die Regel eine Regel über Kotlin-Bezeichner, nicht über Kontrast.

### 5.2 Die Ausnahme braucht eine **Grenze**, sonst verrottet sie

Developer5 hat die zwei DEBUG-Fälle als namentliche Ausnahmen eingetragen. **Richtig — aber sie müssen an der
Renderstelle hängen, nicht an der Farbquelle.**

| Anker | Wirkung |
|---|---|
| ❌ `EventVisuals.severityColorFor` / `severityContainerFor` | segnet **jeden künftigen Konsumenten** mit — auch `Text(severityLabel(sev), color = severityColor(sev))`, also das **Wort** „Debug" in `outline`: 1.4.3, 4,5:1, **Verstoß** |
| ✅ `ProductLeadPanel.kt:262` und `WindowBadge.kt` (`Pill`) | segnet genau die zwei Stellen, an denen bewiesen ein **sprachloses Icon** gerendert wird |

Im Test-KDoc gehört der **Grund** neben die Ausnahme, nicht nur die Zeilennummer:
*„`·` ist keine menschliche Sprache → 1.4.11 → 3:1 → 3,55:1 besteht. Die Severity ist hier **nicht**
redundant sichtbar; die Ausnahme trägt über die Zahl, nicht über Redundanz. Wer die Farbe dimmt, bricht sie."*

**Eine Ausnahme ohne Grund verrottet** — der Nächste liest sie als „DEBUG darf alles".

### 5.3 Der Pill-Satz, wörtlich

Zum Einsetzen in Spec und Test-KDoc:

> **Die Regel gilt dem Farbpaar, nicht der Rolle.**
> `outline` als **Container** hinter `surface`-farbenem Inhalt ist **dasselbe Paar** wie `outline`-Inhalt auf
> `surface` — identischer Kontrast (**3,55:1** hell / **3,63:1** dunkel), nur mit vertauschten Seiten. Eine
> Prüfung, die `outline` als **Vordergrund** sucht, ist gegen diesen Fall **strukturell blind**: dort ist
> `outline` der Hintergrund. Geprüft wird deshalb der **Kontrast des Paares**, gleich aus welcher Rolle es
> entsteht — `Text(color = …)` über `background(…)`, `(container, onColor)`-Paare, `BorderStroke` gegen seinen
> Untergrund.

### 5.4 **Erfassen statt benennen** — und warum das kein Stilwunsch ist

Der PO fragt, ob die Regel den Fall erfassen soll, statt ihn im KDoc zu benennen. **Ja. Und die Begründung
steht in unserem eigenen Audit:**

> Eine Grenze, die nur im Kommentar lebt, ist **Wurzel B, fünfte Ausprägung** — Dokumentation als
> Stellvertreter für Code (`observation-vs-derivation-audit.md` §6.3). Sie berichtet die Absicht zum Zeitpunkt
> des Schreibens, nicht den heutigen Zustand. Genau so hat uns `AgentShell.kt:188` in die falsche Schicht
> geführt.

**Zwei Prüfungen, komplementär, weil die Klauseln verschiedene Naturen haben:**

| Klausel | Prüfung | Art |
|---|---|---|
| **(1)** „färbt keinen Text (menschliche Sprache)" | **Quell-Guard** (`OutlineTextGuardTest`, Muster CYP-303): `outline*` darf nicht `color =` eines `Text` sein. Ausnahmen **an der Renderstelle**, Grund = die Zahl. | bezeichnerbasiert — *muss* es sein: nur der Mensch sieht, ob eine Zeichenfolge Sprache ist |
| **(2)+(3)** „≥ 3:1 **oder** redundant · Paar, nicht Rolle" | **Paar-Test** (`SeverityContrastTest`, neu): berechnet den **Kontrast**, statt nach Namen zu suchen | wertbasiert — **rollenblind, farbblind, zukunftssicher** |

**Der Paar-Test ist billig und total.** `severityColorFor(sev, scheme, dark)` und `severityContainerFor(...)`
sind **reine** Funktionen (`EventVisuals.kt:72/80`) — keine Composition nötig. Der Test iteriert **4 Severities
× 2 Schemes** und prüft drei Paare je Zelle: Vordergrund gegen `surface`, `onColor` gegen `container`,
`container` gegen `surface`. Schwelle **3:1** (Grafikobjekt), Ausnahme **DEBUG-Rail** (1,90:1) mit
**benanntem** Träger (`·`-Glyph + Typtext daneben, `EventRowUi.kt:134`).

**Er hätte die Pill gefunden, ohne `outline` je zu erwähnen.** Und er findet den Fall von übermorgen, in dem
jemand eine ganz andere Farbe einsetzt.

**Mutationsprobe:** `severityContainerFor(DEBUG)` von `outline` (3,55:1) auf `outlineVariant` (**1,41:1**)
ziehen ⇒ **rot**. Wird er das nicht, prüft er die Rolle statt das Paar.

> **Was der Automat nicht kann:** Klausel **(2b)** — „wird redundant getragen" — ist eine Aussage über die
> *danebenstehende* Oberfläche. Sie bleibt eine **geprüfte, benannte** Ausnahmeliste mit dem **Träger** je
> Eintrag: UNKNOWN-Punkt → Statuslabel + `a11y_agent_status`; Avatar-Rahmen → `primary` + `3.dp` +
> `selected`-Semantics; DEBUG-Rail → Glyph + Typtext. **Wer einen Träger entfernt, muss den Eintrag streichen.**
> Das ist der einzige Teil der Regel, der ein Mensch bleibt — und deshalb der einzige, der aufgeschrieben
> gehört.

**Und zum Namen:** dass Developer5 `certifiedDecorativeUses` in `permittedOutlineUses` umbenannt hat, ist mehr
als Kosmetik. Der alte Name **behauptete** „dekorativ" — und genau das sind die zwei DEBUG-Fälle **nicht**:
sie sind visuell erforderlich und bestehen über die Zahl. Ein Feldname, der eine falsche Begründung mitführt,
ist dieselbe Falle wie eine Ausnahme ohne Grund. Jeder Eintrag sollte seinen Grund als Text tragen, z. B.
`reason = "· ist keine menschliche Sprache → 1.4.11 → 3,55:1 ≥ 3:1; NICHT redundant"`.

### 5.3 Was der Guard **nicht** anfassen darf

Fundstellen 6–8 (Statuspunkte, Avatar-Rahmen) sind Dekor und tragen über Klausel (2b). Ein Guard, der sie
mitreißt, macht einen leisen Punkt laut und zerstört die Zustands-Hierarchie. **Die Regel sagt nicht
„`outline` ist schlecht", sie sagt „`outline` ist keine Sprache".**

**Timing:** sinnvoll **nach** dem CYP-337-Fix — vorher wäre der Guard per Konstruktion rot.

---

## 6. §-Asks an den PO

| # | Frage | Meine Empfehlung |
|---|---|---|
| 1 | **Grenzfälle §3.2** — Symbol oder Text? | **Entschieden (2026-07-10): Grafikobjekt (1.4.11).** Ein `·` ist keine menschliche Sprache. Beide bestehen mit 3,55:1 **über die Zahl**, nicht über Redundanz (§3.2). Unverändert lassen, im Guard **an der Renderstelle** ausnehmen. |
| 2 | **Source-Guard** (§5) — eigenes Ticket nach dem Fix? | **Ja.** Muster liegt mit `TertiarySourceGuardTest` bereits im Repo. |
| 3 | **CYP-337-Scope:** Das Ticket nennt eine Fundstelle, es sind **drei** (§3.1). Alle drei im selben Fix? | **Ja** — identische Ursache, identische Korrektur, ein Commit. Sonst bleiben zwei AA-Verstöße stehen, die dieses Audit namentlich kennt. |

---

## 7. Self-Validation

- **8 Fundstellen gesucht, 8 klassifiziert**, Summe der Kategorien (3 + 2 + 2 + 1) = 8. Keine Fundstelle ohne
  Urteil, und **jede** trägt über eine **benannte Klausel** der Regel (§2.3) statt über eine Behauptung.
- **Zwei Prämissen korrigiert, eine davon meine:** „keine der verbleibenden Verwendungen färbt Text" ist
  falsch (zwei färben einen `Text`-Knoten, §3.4), und „neben beiden Glyphen steht ein sichtbares Label" ist
  ebenfalls falsch (es ist nur `contentDescription`, §3.2) — die zwei bestehen über die **Zahl**, nicht über
  Redundanz. Wer das verwechselt, dimmt sie später mit gutem Gewissen kaputt.
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
