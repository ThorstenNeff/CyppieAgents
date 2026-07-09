# CYP-327 — Compact-Fenster-Enhancements · UI/UX Design-Spec

> **Story:** CYP-327 — Compact-Fenster-Enhancements (auf CYP-326). Design-Pass, **kein Bau**.
> **Autor:** UX/UI-Designer · **Branch:** `feature/CYP-327-compact-enhancements-spec` (off `origin/develop @ 2bf6abc`, docs-only).
> **Kontext:** CYP-326 ist **gebaut & in develop gemergt** (inkl. §7-Dogfood-Follow-ups: Sichtbarkeit + Kill-Switch). CYP-327 legt **zwei UI-Flächen** auf das **laufende** Compact-Fenster — es baut auf realem Code, nicht auf einem Design-Doc.
> **Reuse-first:** Alle Anker gegen den echten, gemergten Code (`compact/CompactPanel.kt`, `CompactViewModel.kt`, `CompactTags.kt`, `:core/CompactModel.kt`, `eventlog/*`) verifiziert.

---

## §0 — Ist-Zustand (verifiziert gegen gemergtes CYP-326)

Das Compact-Fenster existiert und ist verdrahtet (`AgentShell` `COMPACT_WINDOW_ID`, sichtbar für alle, `CompactViewModel(editable = isOperator)`). `CompactPanel.kt` rendert heute (top-down, `Column` + `verticalScroll`, `CompactTags.PANEL`):
Control (Operator-`Checkbox` / Member-read-only-Chip ✓/–) · Pflicht-Disclosure-`TonedHint(INFO)` · Gate-`TonedHint(GATED, workspace_operator_only)` (Member) · `HorizontalDivider` · **`status?.let {}`** Server-Spiegel-Fakten: **Schwelle read-only** (`LabelValueRow` via `formatCompactTokens`, `CompactPanel.kt:110-115`, `CompactTags.THRESHOLD`) · Status (off/running/idle, `liveRegion=Polite`) · Last-Run X/N (`aborted`/`timeout`/`ok`, `severityColor(WARN)` bzw. `onSurfaceVariant`).

Kontrakt vorhanden (`:core/CompactModel.kt`): `CompactConfig(allowed, thresholdTokens=500_000)` · `CompactStatus(allowed, thresholdTokens, armed, running, lastRun)` · `CompactRunSummary(completed, total, pendingAgentIds, startedTs, finishedTs, aborted)`. Endpoint **`POST /api/compact/config`** operator-gated, **antwortet mit `CompactStatus`** (Server-Spiegel nach Schreiben). VM `setAllowed` (`CompactViewModel.kt:65-73`) = **non-optimistischer Server-Spiegel** (adoptiert `s` bei `onSuccess`, unverändert bei Fehler).

**Was CYP-327 hinzufügt (heute NICHT vorhanden, verifiziert):** (A) **editierbare** Schwelle (heute read-only; kein `setThreshold`) · (B) eine **per-Sequenz Compact-Event-Liste** (Events des aktuellen/letzten Laufs; heute rendert das Fenster keine Event-Liste). **Fast kein Backend:** `thresholdTokens` ist über `POST /api/compact/config` schon schreibbar; der Event-Stream existiert (EventLog). **Einziger optionaler `:core`-Touch:** 1 Feld `CompactRunSummary.correlationId` für den autoritativen per-Sequenz-Scope (§B.3; sonst Client-Ableitung).

---

## §A — Editierbare Schwelle

### §A.1 Platzierung & Gating

Die read-only Schwellen-`LabelValueRow` (`CompactPanel.kt:110-115`) wird **konditional**:
- **Operator (`state.editable`)** → editierbares Zahlenfeld + „Setzen"-Button (ersetzt die read-only Zeile).
- **Member** → **read-only Zeile bleibt unverändert** (kein Fake-editierbares Feld). Der bestehende Gate-Hinweis (`workspace_operator_only`, `CompactPanel.kt:103`) deckt „nur Operator" bereits ab.

Reuse des bestehenden `if (state.editable)`-Splits (identisch zum Control §1.2). Kein neues Gating, Reuse `workspace_operator_only`.

### §A.2 Input, Einheit/Format, Validierung

**Kein validiertes Zahlenfeld existiert bisher** (nur der `AuthGate`-Reset-Code `KeyboardType.Number`+Digit-Filter, `AuthGate.kt:463-473`, ohne `toIntOrNull`/Range). CYP-327 führt das Muster **einmal, sauber** ein:

- **Feld:** `OutlinedTextField`, `KeyboardType.Number`, `singleLine`. `onValueChange = { it.filter(Char::isDigit).take(7) }` (1.000.000 = 7 Ziffern) — Reuse des `AuthGate`-Digit-Filters. **Prefill = roher Server-Wert** `status.thresholdTokens.toString()` (NICHT `formatCompactTokens` — muss parsebar sein). Label = Reuse `compact_threshold_label` („Schwelle"). testTag `compact.thresholdInput`, `contentDescription = a11y_compact_threshold_input`.
- **Live-Preview:** neben/unter dem Feld `= ${formatCompactTokens(parsed)}` (Reuse `formatCompactTokens`/CYP-316) → Operator sieht „= 750K" während er die rohe Zahl tippt. Ehrlich: Preview = exakter formatierter Wert. (Kein Key — „=" ist Interpunktion.)
- **Validierung (Client):** `parsed = draft.toIntOrNull()`; **gültig ⇔ `parsed in 1..1_000_000`** (konsistent mit dem `formatCompactTokens`-~1M-Fenster). `isError = draft nicht leer && !gültig`. Inline-`TonedHint(compact_threshold_range_error, HintTone.ERROR, compact.thresholdError)` = „1 bis 1.000.000 Tokens". `isError` am Feld gesetzt.
- **„Setzen"-Button:** `enabled = state.editable && gültig && parsed != status.thresholdTokens` (nur gültig **und geändert** — Reuse `canSave…`-Muster `SettingsViewModel.kt:44-50`). Label `compact_threshold_set` („Setzen"). testTag `compact.thresholdSet`.

### §A.3 Set + Server-Bestätigung (Honesty-Kern)

- **VM `setThreshold(tokens: Int)`** — spiegelt `setAllowed` (`CompactViewModel.kt:65-73`) 1:1: `if (!editable) return`; `setConfig(CompactConfig(allowed = <aktueller Server-allowed>, thresholdTokens = tokens))`; **`onSuccess` → adoptiert die zurückgegebene `CompactStatus`** (Wert bewegt sich auf den **server-bestätigten** Stand); **`onFailure` → Spiegel unverändert** (kein optimistischer Sprung). `allowed` wird wie in `setAllowed` erhalten.
- **„Wert bewegt sich erst nach Bestätigung":** Der **aktive** Schwellwert = Server-Wert. Das Feld ist ein **Entwurf**; die gedraftete Zahl ist **nicht scharf**, bis `Setzen` + Server-Bestätigung. Nach `onSuccess` re-synct das Feld auf den server-bestätigten Wert (Button wird wieder disabled = „unverändert"). Bei Fehler bleibt der Server-Wert stehen; der Entwurf bleibt sichtbar-aber-nicht-angewandt (Button bleibt aktiv = „ungespeichert").
- **Set-Bestätigung (Empfehlung):** transienter `TonedHint(compact_threshold_set_confirm, HintTone.INFO, compact.thresholdConfirm)` = „Schwelle auf %1$s gesetzt" (`%1$s` = `formatCompactTokens`), **self-clearing** (Muster wie CYP-315-Copy-Quittung). **INFO, NICHT `EFFECT_DEFERRED`** (die Schwelle wirkt **sofort** server-seitig — nicht „erst beim nächsten Start", anders als API-Key/Repo), **nie grün**. Feuert **nur** nach Server-`onSuccess` (nie optimistisch). *Minimal-Alternative:* keine Quittung, nur Wert-spiegelt (konsistent mit `setAllowed`, das keinen Hinweis hat) → Frage §F-1.
- **Server-Reject-Mapping:** analog `repoErrorKey` (`SettingsViewModel.kt:152-160`): `"operator_required"/"unauthorized"` → `workspace_operator_only`; sonst → `compact_threshold_range_error`. (Client range-validiert bereits → ein Server-Reject ist i. d. R. Auth.)

### §A.4 Keys / Tags (A)

| Key | DE | EN |
|---|---|---|
| `compact_threshold_set` | Setzen | Set |
| `compact_threshold_range_error` | 1 bis 1.000.000 Tokens | 1 to 1,000,000 tokens |
| `compact_threshold_set_confirm` | Schwelle auf %1$s gesetzt | Threshold set to %1$s |
| `a11y_compact_threshold_input` | Compact-Schwelle in Tokens (1 bis 1.000.000) | Compaction threshold in tokens (1 to 1,000,000) |

Reuse (kein neuer Key): `compact_threshold_label`, `workspace_operator_only`. **4 neue Keys ×2 = 8 Einträge.**
**Neue `CompactTags`:** `THRESHOLD_INPUT="compact.thresholdInput"`, `THRESHOLD_SET="compact.thresholdSet"`, `THRESHOLD_ERROR="compact.thresholdError"`, `THRESHOLD_CONFIRM="compact.thresholdConfirm"`. (`THRESHOLD` bleibt = Member-read-only-Zeile.)

---

## §B — Compact-Event-Liste im Fenster — **per-Sequenz (aktueller/letzter Lauf)**

> **Scope-Entscheidung (Auftraggeber, `1524733788…`): per-Sequenz.** Die Liste zeigt die Events **eines** Compact-Laufs (`correlationId`-scoped: der laufende Lauf, sonst der letzte), **nicht** die globale Historie. Beim nächsten Lauf **re-scopet sie automatisch** auf den neuen. (Das ist genau der CYP-326-§2.4-`correlationId`-Mechanismus, hier als inline Ein-Lauf-View im Compact-Fenster — kein divergentes EventLog-Nesting.)

### §B.1 Platzierung, Header, Scroll

Unter Gate/Status/Schwelle, nach `HorizontalDivider`, **dynamischer Header (Honesty):** `compact_run_current` „Aktueller Lauf" wenn `status.running`, sonst `compact_run_last` „Letzter Lauf" — ein **beendeter** Lauf ist nicht „aktuell"; der Header sagt ehrlich, ob **live** oder **vergangen** (verfeinert die PO-Vorgabe „aktueller Lauf").

**Scroll:** Ein Lauf hat eine **beschränkte** Event-Zahl (~`2·N+2`: 1 `triggered` + je `prepare.sent`/`request.sent`/`completed` pro Agent + 1 `orchestration.done`) — die Liste ist **kurz**, kein unbegrenzter Verlauf. `LazyColumn` mit `heightIn(max = …)`; bei kleiner Zahl schrumpft sie. Das Nested-Scroll-Risiko im bestehenden `Column(verticalScroll)` (`CompactPanel.kt:63-70`) ist **gering**, weil der Inhalt klein/beschränkt ist → **kein Panel-Umbau nötig** (anders als bei globaler Historie).

### §B.2 Rendering — **`EventRow`-Reuse, 0 neue Row-UI**

Jede Zeile = das geteilte `EventRow` (`eventlog/EventRowUi.kt`) — **identische** Glyphen/Severity/Identität/Monospace-Wire wie im EventLog. Die Compact-Event-Severity/Honesty ist in CYP-326 §2.2 + §7.2 festgelegt und wird hier **nur gerendert**:
- `▦` Gruppen-Glyph · Severity-Rail+Glyph · `compact.<typ>`-Wire (un-lokalisiert) · Identität.
- **`compact.completed` neutral (INFO, kein Grün)** · **Timeout / `aborted` = WARN-Amber `▲`** · clean N/N = INFO. Keine Sonderfarbe, kein Hardcode.
- **Seq-geordnet innerhalb des Laufs** → liest als die **geordnete Lauf-Timeline**: `triggered → prepare.sent(×) → request.sent(×) → completed(×) → orchestration.done`.
- Row-Tags via `EventRow`-Parameter, Scope `compact.run.row.<i>` (+ Qualifier/byId), analog `eventTail.row.<i>`.

### §B.3 Daten & Scope (per-Sequenz)

Quelle = der **Event-Stream** (derselbe wie EventLog), gescopt auf **eine `correlationId`** = die des **aktuellen/letzten** Laufs; innerhalb seq-geordnet.

**Scope-Schlüssel (Dev/Backend-Naht):** **Empfehlung** — `CompactRunSummary` (`:core/CompactModel.kt:42-58`) um **`correlationId: String`** erweitern (1 Feld). Dann ist `status.lastRun?.correlationId` der **autoritative** Join-Schlüssel (`lastRun` = der jüngste Lauf, laufend **oder** beendet; `finishedTs == null` = laufend), und der Client filtert `event.correlationId == status.lastRun?.correlationId`. *Zero-Backend-Alternative:* Client leitet den jüngsten Lauf aus dem Stream ab (Compact-Events nach `correlationId` gruppieren, den mit max `seq` nehmen) — mehr Client-Logik, aber kein Feld. **Empfehlung: das Feld** — autoritativer Server↔Events-Join statt Client-Rateschluss = ehrlicher (nie den falschen Lauf zeigen). → Frage §F-3.

`status.lastRun == null` → **kein Lauf** → leer (§B.4). **Re-Scope:** startet ein neuer Lauf, wechselt `status.lastRun` (neue `correlationId`) → die Liste zeigt automatisch den neuen Lauf.

### §B.4 Leere Zustände (ehrlich, zwei distinkt)

Reuse `event_empty`-Styling (`onSurfaceVariant`, `bodySmall`, `padding`, `EventTailPanel.kt:85-92`), zwei **ehrlich unterschiedene** Fälle:
- **Kein Lauf** (`status.lastRun == null`) → `compact_run_empty_norun` „Noch kein Compact-Lauf" — impliziert **nicht**, dass etwas lief. Tag `compact.run.empty`.
- **Lauf ohne (noch) sichtbare Events** (Edge: `lastRun != null`, aber 0 gematchte Stream-Events) → `compact_run_empty_noevents` „Keine Events für diesen Lauf". Tag `compact.run.emptyEvents`.

(Eigener Scope — nicht `eventTail.empty` mitbenutzen.)

### §B.5 Keys / Tags (B)

| Key | DE | EN |
|---|---|---|
| `compact_run_current` | Aktueller Lauf | Current run |
| `compact_run_last` | Letzter Lauf | Last run |
| `compact_run_empty_norun` | Noch kein Compact-Lauf | No compact run yet |
| `compact_run_empty_noevents` | Keine Events für diesen Lauf | No events for this run |

Reuse `a11y_event_row` (komponiert Severity+Typ+Agent+ts) → **kein neuer a11y-Key, kein Typ-Label-Key**. **4 neue Keys ×2 = 8 Einträge.**
**Neue `CompactTags`:** `RUN_EVENTS="compact.run.events"`, `RUN_EMPTY="compact.run.empty"`, `RUN_EMPTY_EVENTS="compact.run.emptyEvents"`, `fun runEventRow(i)="compact.run.row.$i"` (+ Qualifier/byId analog `EventTailTags`).

---

## §C — Honesty-Invarianten (CYP-327)

1. **Schwelle = Server-Spiegel, non-optimistisch.** Aktiv = Server-Wert; das Feld ist ein Entwurf, **nicht scharf** bis `Setzen` + Server-Bestätigung. Wert bewegt sich **nur** auf server-bestätigte `CompactStatus` (spiegelt `setAllowed`); bei Fehler unverändert. Kein Anzeigen der gedrafteten Zahl als „aktiv".
2. **Set-Quittung post-server + Sofort-Wirkung.** Confirm feuert nur nach Server-`onSuccess`; **INFO, nicht `EFFECT_DEFERRED`** (Schwelle wirkt sofort, nicht deferred), **nie grün**.
3. **Validierung ehrlich + hart.** Ungültig (nicht 1..1M / nicht-numerisch) → Client-block + ehrlicher Inline-Fehler; „Setzen" disabled. Bound 1M = `formatCompactTokens`-~1M-Decke.
4. **Operator-gated, kein Fake.** Member behalten die read-only Zeile (kein deaktiviertes/vorgetäuschtes Feld); Reuse `workspace_operator_only`.
5. **Event-Liste = per-Sequenz Ein-Lauf-View, ehrlicher Reuse.** `EventRow` wortgleich (completed neutral, Timeout/`aborted` WARN-Amber). Zeigt **einen** Lauf (`correlationId`-scoped, aktuell/letzt); der Header sagt ehrlich **live vs. vergangen**; ein laufender Lauf ist **wachsend/unvollständig** (kein Vortäuschen von Abschluss — der finale `orchestration.done` schließt ihn). Zwei ehrliche Leer-Zustände (kein-Lauf vs. keine-Events). Die Ein-Lauf-View ist **kein** Vollrekord — das EventLog + `correlationId`-Drilldown bleibt die vollständige Historie (View-auf-einen-Lauf ≠ Gesamt-Log).

---

## §D — Dev-Abhängigkeiten & Shared-Key-Drift

- **VM (A):** `CompactViewModel.setThreshold(tokens)` (spiegelt `setAllowed`); `CompactUiState` um Entwurf-Felder (`thresholdInput: String`, `thresholdError`/-key, `thresholdConfirm`) erweitern; re-sync auf server-bestätigten Wert bei `onSuccess`.
- **Panel (A):** read-only Schwellen-Zeile → konditional (Operator: Input+Preview+Set+Error+Confirm; Member: unverändert).
- **Panel (B):** kein Panel-Umbau (per-Sequenz-Liste ist kurz/beschränkt, §B.1) — `LazyColumn(heightIn(max=…))` unter den Fakten; Event-Flow (auf die Lauf-`correlationId` gescopt) ins Fenster injizieren; `EventRow`-Reuse. VM/State exponiert die Lauf-Events (gescopt via `status.lastRun.correlationId`).
- **Backend/`:core` (per-Sequenz):** **Empfehlung 1 Feld** `CompactRunSummary.correlationId` (`:core`) für den autoritativen Scope-Join (§B.3). Sonst Client-Ableitung (0 Backend). `POST /api/compact/config` akzeptiert `thresholdTokens` schon; Event-Stream existiert.
- **⚠ Shared-Key-Drift:** 8 neue i18n-Keys **atomar DE+EN**, mit dem konsumierenden Modul (App) landen; `CompactTags`-Ergänzungen sind geteilte QA-API — nicht still umbenennen. `CompactRunSummary.correlationId` (falls gewählt) ist `:core` (server+app) → mit beiden Seiten syncen. Timing mit Dev.

---

## §E — Counts / Self-Validation

- **A:** 4 Keys ×2 (=8) + Reuse `compact_threshold_label`/`workspace_operator_only`; 4 Tags. Neuer VM-Call `setThreshold`. Zahlenfeld-Muster (Digit-Filter+`toIntOrNull`+Range 1..1M+Inline-Error) = **einmal neu**, sonst Reuse.
- **B:** 4 Keys ×2 (=8) + Reuse `a11y_event_row`; 3 Container-Tags + `runEventRow(i)`-Scope. **0 neue Row-UI** (EventRow), 0 neue EventTypes; optional **1** `:core`-Feld `CompactRunSummary.correlationId` (per-Sequenz-Scope).
- **Gesamt:** 8 neue Keys (16 Einträge, DE↔EN paritätisch), ~7-8 Tags, 0 neuer Fenstertyp, 0 Endpoint-Change (nur opt. 1 `:core`-Feld), **kein Grün**, nur colorScheme/severity-Rollen (AA).

---

## §F — Offene Fragen

- **1 (Set-Quittung):** transienter INFO-Confirm „Schwelle auf %1$s gesetzt" (Empfehlung) vs. still (nur Wert-spiegelt, konsistent mit `setAllowed`)?
- **2 (Header):** dynamisch „Aktueller Lauf" / „Letzter Lauf" (Empfehlung, ehrlich live-vs-vergangen) vs. ein neutrales statisches „Compact-Lauf" (1 Key weniger)?
- **3 (Scope-Schlüssel):** `CompactRunSummary.correlationId`-Feld (Empfehlung, autoritativer Join) vs. Client-Ableitung des jüngsten Laufs (0 Backend)?
- **4 (Umfang Filter):** Lauf-Events inkl. `COMPACT_TRIGGERED` als Lauf-Opener (Empfehlung) vs. nur die vier `prepare/request/completed/done`?

> **Erledigt (Auftraggeber):** Feature B = **per-Sequenz** (aktueller/letzter Lauf), nicht globale Historie.
