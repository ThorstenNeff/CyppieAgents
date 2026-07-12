# CYP-432 (P2-c) — Event-Log + Warden im DOM: Browse · Korrelations-Drilldown · Live-Tail

> Owner: UIUX-Designer · Ticket **CYP-432** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-c) · Stand 2026-07-11
> Basis `origin/develop` `3213a1b3` · `09-UI-Funktionskatalog` §6 (Event-Log) + §7 (Warden) · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags.** **Nichts gebaut.**
>
> **Quelle:** `eventlog/EventBrowsePanel.kt` · `EventTailPanel.kt` · `EventVisuals.kt` · `EventLiveSource.kt` ·
> `EventContract.kt` · `EventLogTags.kt`; Requirements `06-Observability-Event-Log.md`; Warden-Typen
> `07-Mediator-Aufsicht`. Keys/Tags: `docs/design/event-log-keys.md` + `event-log-tags.md` (bestehender Vertrag).

---

## 0. Zugriffs-Rahmung zuerst — Client-Gate = defence-in-depth; die Secret-Grenze sitzt am **Server**

> **PRÄZISIERUNG (Reviewer/Tester 4-Quadranten-Check 2026-07-12, am Objekt bestätigt — ersetzt die frühere
> „gated-Bodies"-Rahmung):** das Event-Log ist **secret-free METADATA**, **keine** Bodies. `EventModel.detail` ist
> **content-free by design** (PRD §3.5 — **dieselbe Stufe** wie der Lifecycle-Feed CYP-421, **nicht** eine Ebene darüber),
> jedes Event wird **vor Egress server-seitig maskiert** (Gate #3, `EventProjector`/`ClaudeCodeConnector`), und
> `/api/events` ist **MEMBER-tier** lesbar (CYP-186, `EventRoutes.kt`). Die **echte** Secret-/Scope-Grenze sitzt **am
> Server**: `resolveEventScope` (cross-project-Enum-Block, operator-only Override, fail-closed) + die Masking-Gates
> halten alles Sensible zurück.

**Der Client-Operator-Gate am Event-Log-Fenster BLEIBT — als bewusste zweite Schicht, nicht als Leak-Barriere:**
**Produkt-Scoping** (das Event-Log ist ein Operator-/Observability-Feature) **+ defence-in-depth** (der Server ist die
autoritative Barriere; der Client ist **NICHT** die Sole-Barriere). **Nicht entfernen** — auch wenn der Server die
Metadaten member-tier ausgäbe, wird die Operator-Fläche im Client für Nicht-Operatoren **nicht angeboten**. **Zwei
Client-Gating-Zustände (unverändertes Verhalten), beide fail-closed:**

1. **Kein Operator-Token → das Fenster/die Route wird gar nicht gemountet (Omission).** Es existiert **kein**
   `eventBrowse.*`/`eventTail.*`-Knoten. **DOM-Regel:** **nicht gemountet** (nicht CSS-`hidden`) — die Operator-Fläche
   wird gar nicht ausgeliefert (defence-in-depth + saubere Produkt-Trennung). QA prüft die Omission über die
   **Abwesenheit** von `eventBrowse.table`/`eventTail.stream`, nicht über einen „kein Zugriff"-Tag.
2. **Laufzeit-Entzug (Token am Socket abgelehnt, WS 1008)** → `AccessRevoked`: ehrlicher **„Nur für Operatoren"**-
   Fallback (`event_access_denied`, `eventBrowse.accessRevoked`/`eventTail.accessRevoked`), **nie „live"**, **nie
   Teildaten**. Der Strom stoppt, die Ansicht wird geräumt.

> **Einordnung korrigiert:** Event-Log = **content-free Metadata** (server-maskiert), **auf derselben Stufe wie der
> Lifecycle-Feed CYP-421** — **nicht** eine „gated-Bodies"-Stufe darüber. Die Bodies hält der **Server** zurück; der
> Client-Mount-Gate ist die **bewusste zweite Schicht** (Produkt-Scoping + defence-in-depth), und bleibt.

---

## 1. Zwei getrennte Oberflächen (Mirror `06` §8)

**Bewusst zwei** Fenstertypen, nicht vermischt: **Browse** (historisch, Master-Detail, gepaged) und **Live-Tail**
(Echtzeit, `subscribe`, mit Pause). Beide operator-gated (§0). Warden ist **keine** dritte Fläche — es sind
Event-**Typen** in beiden (§4).

---

## 2. Browse (Mirror `EventBrowsePanel`) — Area `eventBrowse`

**Master-Detail, virtualisiert.** Große Volumina → **gepagt/virtualisiert** über `query` (nie alles laden); im DOM
eine **virtualisierte Liste** (Windowing), kein Render-all. Wide = Tabelle+Detail; narrow (<600) = Single-Pane mit
`comm_back` (Reuse).

**Filterleiste** (`eventBrowse.filterBar`): Agent · Typ · Severity · Zeitfenster · **Korrelations-ID**
(`event_filter_*`). Der Typ-Filter enthält die **Warden-Supervision-Familie** als benannte Gruppe (§4).

**Event-Zeile — drei Achsen, die nie kollidieren (Port `EventVisuals`):**
| Achse | Träger | Regel |
|---|---|---|
| **Severity** | Rail-Farbe **+ Glyph + Text-Label** | die **einzige** farbtragende Achse; **Farbe nie allein** (WCAG 1.4.1) |
| **Typ** | Gruppen-Glyph + **monospace** Text | **kein** Farbton; `UNKNOWN`-Typ zeigt den **rohen** Wire-String (CYP-37), nie ein plattgemachtes „unknown" |
| **Identität** | `SenderPalette` (CYP-14) | agent-Akzent, kontrast-sicher portiert |

**Detail-Bereich** (`eventBrowse.detail`): voller Event **inkl. `detail`-JSON** (`eventBrowse.detail.json` — **der
gated Body**) + `event_detail_source_ts` „Beobachtet: %1$s". **Korrelations-Drilldown** = Kern-Mehrwert: „Zeig den
ganzen Lauf" (`showRun`, über `correlationId`) / „Zeig die ganze Session" (`showSession`, über `sessionId`) →
Timeline aller Events derselben Id in Reihenfolge (`eventBrowse.drilldown`, Header `event_drilldown_correlated_by`).

---

## 3. Live-Tail (Mirror `EventTailPanel`) — Area `eventTail`

Mitlaufender `subscribe`-Strom mit **Pause** zum Inspizieren unter Last. Elemente: `stream` · `pauseToggle` (⏸/▶) ·
`liveIndicator` (● Live, **nur** wenn offen **und** nicht pausiert) · `pausedIndicator` · `bufferedCount`
(„%1$s neue (pausiert)") · `bufferOverflow` („Puffer voll – älteste verworfen") · `trimmed` (Live-Ring) ·
`connection`-Banner.

---

## 4. Warden / Aufsicht (§7) — Typen im Log, **keine** eigene Fläche

Die Supervision-Familie `stall.suspected` · `nudge.sent` · `stall.recovered` · **`stall.escalated`** (07/S11,
CYP-64) sind **Event-Typen** (`06` §4). Konsequenz:
- **filterbar** über den Typ-Filter (benannte Gruppe in Browse **und** Tail),
- **sichtbar** als normale Zeilen mit ihrer Severity-Rail (`stall.escalated` trägt die WARN/ERROR-Rail → wird durch
  ihre Severity hervorgehoben, **nicht** durch Sonder-Chrome),
- **kein Steuer-UI** — der Warden wirkt im Hintergrund; die UI macht ihn *sichtbar*, nicht *steuerbar* (09 §7).

---

## 5. Die Ehrlichkeits-Wirbelsäule — **Absence ≠ All-clear**, keine erfundene Korrelation

Die vom PO benannten Grenzen, portiert aus den Keys (verbindlich, nicht ohne UX-Review ändern):

1. **Leere Liste ist ehrlich, nicht „alles gut":** `event_empty` „Keine Events". **Und** eine **gefilterte
   Teilmenge** trägt `event_filter_active` „Filter aktiv – Teilmenge" — damit eine gefilterte Sicht nie als „nichts
   passiert" fehlgelesen wird. *(Der zentrale „Absence-of-signal ≠ all-clear"-Anker.)*
2. **Lücken explizit, nie still übersprungen:** ein `log.dropped`-Event rendert als **Gap-Zeile**
   (`*.row.<i>.gap`, `event_gap_dropped` „%1$s Events verworfen – Log unvollständig"). **Keine stillen Caps:**
   Client-Grenzen werden angezeigt (`event_tail_trimmed`, `event_tail_buffer_overflow`).
3. **Keine erfundene Korrelation:** `showRun`/`showSession` sind **zwei getrennte** Aktionen, **jede nur aktiv, wenn
   das Feld (`correlationId` bzw. `sessionId`) am Event vorhanden ist**. `event_drilldown_correlated_by %1$s` nennt
   das **tatsächliche** Feld. Nie eine Korrelation aus einer Vermutung bilden; fehlt das Feld → keine Aktion,
   nicht ein geratener Lauf.
4. **`UNKNOWN`-Typ bewahrt:** der rohe Wire-String bleibt (CYP-37) — nie ein plattes „unknown", das verschluckt,
   welcher Typ es war (auch additive 07-`stall.*` neuerer Server).
5. **`sourceTs` = „Beobachtet" (informativ), `ts`/`seq` ordnen:** die beobachtete Quell-Zeit nie als maßgeblich
   ausgeben; die Reihenfolge kommt aus `ts`+`seq` (`06` §6).
6. **Pausiert ≠ live:** bei aktiver Pause ist `liveIndicator` **abwesend**, `pausedIndicator` **präsent** — die
   eingefrorene Ansicht nie als aktuell ausgeben.
7. **Token-Stände gesampelt:** `context.usage` ist **10%-gebändert** (`event_usage_banded_hint`), keine
   kontinuierliche Kurve — nicht als lückenlose Messung darstellen.

---

## 6. Severity-Farben & die drei-Achsen-Regel (Port CYP-274 / CYP-300-a0)

- **Severity ist die einzige farbtragende Achse**, und **immer** mit Glyph + Label (Farbe nie allein). Die
  CYP-274-Palette ist **auditiert** und wird als CSS-Variablen portiert (identische Ratios):
  - hell: ERROR `#B3261E` (6,5:1) · WARN `#9A6400` (5,0:1) · INFO `#567083` (5,2:1) · **DEBUG `#B8BCC4` ~2,0:1
    bewusst dim** — die leiseste Severity verdient keine ≥3:1-Rail; ihre Bedeutung reitet auf `·`-Glyph + Label.
  - dunkel: die CYP-12-Nacht-Palette (ERROR `#FF6B6B` … DEBUG `#5A5E66`).
- **WARN = Amber, nie `tertiary`** (CYP-300-a0): `tertiary` wird nachts grün → eine Warnung läse als „ok" (invertiert).
  Dieselbe Lektion wie W6/ACL — **eine** geteilte Severity-Farbquelle.
- **Token-Ebene** (die Maritime-`--md-sys-color-*`-CSS-Variablen + die Severity-Hues) ist die gemeinsame
  Voraussetzung mit W6/CYP-431; `outline`/Rails **ohne Alpha** (W6-Kontrast-Lektion).

---

## 7. DOM-Spezifika & A11y (mein Revier)

- **Gating am Mount** (§0): keine Event-Log-Route ohne Operator-Token; die Bodies liegen dann nicht im DOM.
  `AccessRevoked` = fail-closed Banner, Strom gestoppt.
- **Virtualisierte Liste** (Volumen) — Windowing, nicht Render-all; die Scroll-/Retention-Bausteine aus W6 reuse.
- **A11y:** Zeile = `a11y_event_row` („%1$s, %2$s, von %3$s, %4$s"); Gap = `a11y_event_gap`; Korrelation =
  `a11y_event_correlation`; Tail-Status = `a11y_event_tail_status` (Live-Region, damit Pause/Live angesagt wird).
- **Kein `text-overflow: ellipsis`** auf Gap-/Filter-aktiv-/Fehler-/Offenlegungstext (darf umbrechen). Der
  `detail`-JSON-Body in `<pre>`/`<code>` mit `white-space: pre-wrap`.
- **Zielgröße ≥ 24px** für Filter-Controls, Pause-Toggle, Drilldown-Aktionen. **Farbe nie alleiniger Träger** (§6).

---

## 8. Abnahme-Zähne (diskriminierend) — je mit der falschen Impl, die er ablehnt

1. **Omission ohne Token** (Client-Gate = defence-in-depth + Produkt-Scoping, §0). Kein Operator → **kein**
   `eventBrowse.table`/`eventTail.stream` im DOM; die Operator-Fläche wird **nicht ausgeliefert**. **Mutation:** Fläche
   gemountet-aber-`hidden` ⇒ rot (Operator-Fläche an Nicht-Operator ausgeliefert — defence-in-depth verletzt; die
   Metadaten-Payload ist zwar server-maskiert/member-tier, aber der Client-Gate bleibt die bewusste zweite Schicht).
2. **Laufzeit-Entzug fail-closed.** WS 1008 → `accessRevoked`, `liveIndicator` weg, keine Teildaten. **Mutation:**
   nach Entzug weiter „live"/Restdaten ⇒ rot.
3. **Absence ≠ all-clear.** Gefilterte Teilmenge trägt `filterActive`; leere Liste `event_empty` (≠ „ok").
   **Mutation:** gefilterte Sicht ohne Hinweis ⇒ rot.
4. **Gap sichtbar.** `log.dropped` → Gap-Zeile. **Mutation:** Drop still übersprungen ⇒ rot.
5. **Keine erfundene Korrelation.** `showRun`/`showSession` nur aktiv, wenn das Feld existiert. **Mutation:**
   Drilldown aktiv/geraten ohne `correlationId` ⇒ rot.
6. **Pausiert ≠ live.** Pause → `pausedIndicator` präsent, `liveIndicator` absent. **Mutation:** „Live" während Pause ⇒ rot.
7. **`UNKNOWN`-Typ bewahrt** (roher String). **Mutation:** auf „unknown" plattgemacht ⇒ rot.
8. **Severity nie Farbe allein.** jede Zeile trägt Glyph+Label. **Mutation:** Severity nur über die Rail-Farbe ⇒ rot.
9. **Warden filterbar+sichtbar, nicht steuerbar.** `stall.*` im Typ-Filter + als Zeilen; **kein** Warden-Control.
   **Mutation:** ein „Warden pausieren/eingreifen"-Control ⇒ rot (09 §7: nur sichtbar machen).

---

## 9. Keys & Tags — alles bestehend (0 neu)

**Keys:** `event_severity_*`, `event_title`/`_empty`/`_filter_*`/`_load_more`/`_detail_source_ts`/`_drilldown_*`/
`_usage_banded_hint`/`_type_unknown`, `event_tail_*`, `event_gap_dropped`/`_connection_offline`/`_access_denied`,
`a11y_event_*` (alle in `event-log-keys.md`). **Tags:** `eventBrowse.*` + `eventTail.*` (in `event-log-tags.md`) →
im DOM als `data-testid`, punktfrei-Schema unverändert (Test-Contract v0.5, geteilt mit QA CYP-7). Omission-QA über
**Abwesenheit**. **Kein neuer Key aus dem Medienwechsel**; taucht ein DOM-Element auf, das Compose nicht hat,
liefere ich den Key impl-nah.

**Nichts gebaut — Spec + Dev5-Referenz. Gemeinsame Voraussetzung: Token-Ebene (§6) + virtualisierte Liste/W6-Scroll.**
