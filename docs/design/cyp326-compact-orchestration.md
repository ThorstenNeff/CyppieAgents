# CYP-326 — Compact-Orchestration · UI/UX Design-Spec

> **Story:** CYP-326 — Compact-Orchestration (Design-Pass, **kein Bau**).
> **Autor:** UX/UI-Designer · **Branch:** `feature/CYP-326-compact-orchestration-spec` (off `origin/develop @ 80b0a06`, docs-only).
> **Zweck:** Design-Contract für zwei Flächen — (1) ein globales **„Compact allowed"-Gate-Fenster**, (2) die **Event-Log-Darstellung** der vier Compact-Orchestrierungs-Events. Läuft parallel zu Backends Mechanik-Spike; wird vom PO für die Auftraggeber-Ratifikation gebündelt.
> **Reuse-first:** Jede Entscheidung ist an bestehenden Code verankert (file:line unten). Wo etwas **nicht** existiert, ist es als solches markiert (Anti-Divergenz).

---

## §0 — Verhalten (Kontext, wie vom PO beschrieben)

PO-Context > **500K Tokens** **UND** Gate an → „Bereite dich vor" an den PO → je **1 min** versetzt an die Worker → **+10 min** Compact-Runde → **Abschluss**, wenn alle compactet sind; sonst **10-min-Timeout → ehrliche X/N-Meldung**. Das Gate ist **global** (nicht per-Agent), **Default = aus**, und gatet die **gesamte** Sequenz.

**Signatur-Prinzip dieser Spec (Disclosure-Honesty, §3):** Compacten verdichtet den Arbeits-Context jedes Agenten irreversibel und läuft nach dem Schwellen-Übertritt **ohne weiteren Bestätigungsschritt** ab. Die UI muss das ehrlich benennen, den Zustand als **Server-Spiegel** zeigen (nie optimistisch), und einen Timeout **nie** als Erfolg darstellen.

---

## §1 — Fläche 1: Globales „Compact allowed"-Gate-Fenster

### §1.1 Wo es lebt — **neuer Fenstertyp**, NICHT die Projekt-Einstellungen

**Verifizierter Ist-Zustand:** Es gibt **kein** globales/operator-Einstellungsfenster. `SettingsPanel` ist **projekt-scoped** — Titel `settings_title` = „Projekt-Einstellungen" (`AgentShell.kt:138` `SETTINGS_WINDOW_ID`, VM re-keyed pro Projekt `AgentShell.kt:506`). Es enthält nur Repo-Config + API-Key, **beide pro Projekt**.

**Entscheidung:** Das Gate ist **global/team-weit**. Es in „Projekt-Einstellungen" zu legen wäre **Scope-Lüge** (Nutzer läse es als per-Projekt). Darum: **neuer, eigener Fenstertyp** `compact` — Titel **„Compact-Orchestrierung"** / „Compact orchestration".

**Wiring (Fenstertyp = 4 Hand-Stellen, kein Enum — `AgentShell.kt`):**
1. neue `private const val COMPACT_WINDOW_ID = "compact"` (bei `AgentShell.kt:136-143`).
2. ein Eintrag in der `buildList` der System-Fenster (`AgentShell.kt:338-358`).
3. ein `when (window.id)`-Zweig `COMPACT_WINDOW_ID -> CompactPanel(compactVm)` (Dispatch `AgentShell.kt:717-791`, analog `SETTINGS_WINDOW_ID -> SettingsPanel(...)` :734).
4. Klassifikation in `contentWindowIds` als **Lese-Surface** (160dp-Floor, wie ACL/Event-Log/Settings — `AgentShell.kt:410-419`), **nicht** als Composer-Surface.

Fenster-testTag ergibt sich frei aus `WindowTestTags.window("compact")` = `window.compact` (`window/WindowTestTags.kt:32-52`).

> **Zukunfts-Notiz (nicht v1):** Dieses Fenster ist der natürliche Keim für ein späteres „Team-Betrieb/Operations"-Fenster (globale Operator-Toggles). v1 bleibt **auf Compact fokussiert**.

### §1.2 Sichtbarkeit & Operator-Gating — **sichtbar für alle, Steuerung nur Operator** (Empfehlung)

Zwei bestehende Muster: **Weglassen** (Event-Log/Roster sind für Nicht-Operatoren gar nicht in der Fensterliste, `AgentShell.kt:351-357`) vs. **Deaktivieren-mit-Hinweis** (ACL/Settings bleiben sichtbar, Steuerung disabled + Gate-Hinweis).

**Empfehlung: Deaktivieren-mit-Hinweis (sichtbar für alle).** Der Effekt der Auto-Compaction trifft **jedes Team-Mitglied** — Mitglieder haben ein ehrliches Recht zu sehen, *dass* Auto-Compaction scharf ist und *ab wann*. Darum Fenster sichtbar, **Steuerung** aber operator-gated:
- `isOperator = isOperatorAccess(tier, cfg.operatorToken)` (`AgentShell.kt:224`, `WorkspaceAccess.kt:14-15`) wird als `editable`-Flag in die `CompactViewModel` gereicht (wie `SettingsViewModel(editable = isOperator)`, `AgentShell.kt:507`).
- Nicht-Operator: **kein deaktivierter Switch, sondern ein read-only Status-Chip** — exakt das ACL-`GrantControl`-Honesty-Muster („kein Switch, der Editierbarkeit vortäuscht", `AclPanel.kt:387-410`) — plus `TonedHint(stringResource(Res.string.workspace_operator_only), HintTone.GATED, CompactTags.GATE_HINT)` (Reuse-Key + -Muster, `SettingsPanel.kt:122-124`).
- Operator: aktives Control; VM-Methode früh-returnt `if (!s.editable) return` (Defense-in-Depth, Server 403t zusätzlich).

**Alternative (für Auftraggeber-Call):** **Weglassen** (operator-only, konsistent mit Event-Log) — falls Ops-Flächen bewusst vor Mitgliedern verborgen bleiben sollen. Ich empfehle **sichtbar**, weil der Effekt team-weit ist. → offene Frage §6-A.

### §1.3 Layout (maritim/M3, top-down im Panel)

```
┌ Compact-Orchestrierung ───────────────────────────┐
│                                                    │
│  [✓] Compact für das Team erlauben                 │   ← Control (Operator) / Chip (Member)
│                                                    │
│  ⓘ  Ist dies aktiv und der PO-Context 500K         │   ← TonedHint(INFO) — Disclosure (§3)
│      überschreitet, wird das ganze Team            │
│      automatisch compactet: zuerst der PO, dann    │
│      die Worker je 1 min versetzt. Compacten       │
│      verdichtet den Arbeits-Context jedes Agenten. │
│                                                    │
│  · Nur der Operator kann das ändern                │   ← GATED-Hinweis (nur Nicht-Operator)
│  ─────────────────────────────────────────────    │
│  Schwelle    500K Tokens (PO-Context)              │   ← read-only Fakt
│  Status      Bereit – wartet auf Schwelle          │   ← Server-Spiegel, liveRegion
│  Zuletzt     3/5 Agenten compactet, 2 Timeout      │   ← ehrliche X/N (WARN wenn Timeout)
│                                                    │
└────────────────────────────────────────────────────┘
```

**Control (§1.2):** M3 `Checkbox` (PO-Wortlaut). Der Check-Haken ist ein Form-Signal → Zustand nicht nur farblich (WCAG 1.4.1 inhärent erfüllt). *Alternative:* `Switch` + `thumbContent`-Glyph ✓/✕ (ACL-Haus-Muster `AclPanel.kt:394-401`), falls ein An/Aus-Affordance bevorzugt wird. Label `compact_allow_label`. **Default = unchecked.** testTag `CompactTags.ALLOW_TOGGLE`, `contentDescription = a11y_compact_allow`.

**Disclosure-Hint (Pflicht):** `TonedHint(compact_allow_hint, HintTone.INFO, CompactTags.ALLOW_HINT)` — **immer sichtbar** (nicht nur wenn an). Trägt die ehrliche Konsequenz (§3-1).

**Zustandsanzeige (Server-owned Spiegel, §3-3):** Label/Wert-Zeilen (`onSurfaceVariant`-Label, `onSurface`-Wert):
- **Schwelle:** Label `compact_threshold_label` + Wert. Zahl über **`formatCompactTokens(500_000)` = „500K"** (Reuse aus CYP-316 — konsistente kompakte Token-Formatierung, kein Duplikat). Wert server-geliefert, nicht hartkodiert.
- **Status:** `compact_status_label` + einer von vier **exklusiven** Werten, gespiegelt vom Server:
  - Gate aus → `compact_status_off` „Deaktiviert".
  - Gate an, keine Runde → `compact_status_idle` „Bereit – wartet auf Schwelle".
  - Runde läuft → `compact_status_running` „Läuft – Runde seit %1$s" (`%1$s` = Startzeit).
  - **Unbekannt** (Server-State nicht aufgelöst) → **weder idle noch off zeigen** — Zeile weglassen oder `—` (Absenz ehrlich, analog CYP-316 `null≠0` / CYP-315 `failed≠remote`). **Nie „idle" defaulten.**
  - `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` auf die Status-Zeile → Übergang idle→läuft→fertig wird angesagt, auch wenn Fokus weg ist.
- **Zuletzt (letzter Lauf):** exklusiv:
  - voll → `compact_last_run_ok` „Zuletzt: %1$d/%2$d Agenten compactet" (Farbe `onSurfaceVariant`, **neutral, kein Grün**).
  - Timeout → `compact_last_run_timeout` „Zuletzt: %1$d/%2$d compactet, %3$d nach Timeout ausstehend" (Farbe = **shared `severityColor(WARN)`**, `EventVisuals.kt:72-106` — Amber, kein Hardcode). Timeout **nie** als Erfolg (§3-4).

### §1.4 Copy / Resource-Keys (DE + EN, atomar)

| Key | DE | EN |
|---|---|---|
| `compact_window_title` | Compact-Orchestrierung | Compact orchestration |
| `compact_allow_label` | Compact für das Team erlauben | Allow team compaction |
| `compact_allow_hint` | Ist dies aktiv und der PO-Context 500K Tokens überschreitet, wird das gesamte Team automatisch compactet: zuerst der PO, dann die Worker je 1 Minute versetzt. Compacten verdichtet den Arbeits-Context jedes Agenten. | When this is on and the PO context exceeds 500K tokens, the whole team is compacted automatically: the PO first, then the workers staggered one minute apart. Compaction condenses each agent's working context. |
| `compact_threshold_label` | Schwelle | Threshold |
| `compact_status_label` | Status | Status |
| `compact_status_off` | Deaktiviert | Disabled |
| `compact_status_idle` | Bereit – wartet auf Schwelle | Armed – waiting for threshold |
| `compact_status_running` | Läuft – Runde seit %1$s | Running – round since %1$s |
| `compact_last_run_ok` | Zuletzt: %1$d/%2$d Agenten compactet | Last: %1$d/%2$d agents compacted |
| `compact_last_run_timeout` | Zuletzt: %1$d/%2$d compactet, %3$d nach Timeout ausstehend | Last: %1$d/%2$d compacted, %3$d pending after timeout |
| `a11y_compact_allow` | Compact für das Team erlauben (globaler Schalter) | Allow team compaction (global switch) |

**Reuse (kein neuer Key):** Gate-Hinweis nutzt bestehenden **`workspace_operator_only`** (DE:475 „Nur der Operator kann das ändern").
**Zählung:** 11 neue Keys × 2 Sprachen = 22 Einträge, DE↔EN paritätisch. Kein grüner SUCCESS-Ton.

### §1.5 a11y & WCAG

- Alle Farben = **colorScheme-Rollen** (`onSurface`/`onSurfaceVariant`/`secondary`) + shared `severityColor(WARN)` — **kein Hardcode**; alle bereits AA-belegt (WARN Amber `#9A6400` Light 5.0:1 / `#FFC857` Night; INFO/onSurface ≫ 4.5:1).
- Checkbox: `contentDescription = a11y_compact_allow`; Zustand nicht nur farblich (Haken-Form).
- Status-Zeile: `liveRegion = Polite`.
- Disclosure-Hint: Text-Träger (`i`-Glyph + Copy), erreichbar.

### §1.6 testTags — neues `object CompactTags` (Konvention `compact.<element>`)

| Konstante | Tag |
|---|---|
| `ALLOW_TOGGLE` | `compact.allowToggle` |
| `ALLOW_HINT` | `compact.allowHint` |
| `GATE_HINT` | `compact.gateHint` |
| `THRESHOLD` | `compact.threshold` |
| `STATUS` | `compact.status` |
| `LAST_RUN` | `compact.lastRun` |

Prefixlos, camelCase-nach-Punkt — konsistent mit `settings.*`/`acl.*`/`eventTail.*`. Fenster-Rahmen-Tag `window.compact` ergibt sich automatisch.

---

## §2 — Fläche 2: Event-Log-Darstellung der Compact-Events

### §2.1 EventTypes — **1 Reuse + 3 neue** (`:core` `EventModel.kt`)

`enum class EventType(val wire)` (`EventModel.kt:72-137`) ist das kontrollierte Vokabular; die **Compaction-Familie existiert bereits** (`COMPACT_TRIGGERED("compact.triggered")`, `COMPACT_COMPLETED("compact.completed")`) und hat schon den Gruppen-Glyph `▦`.

| PO-Event | EventType | Status |
|---|---|---|
| `compact.completed` (Agent, ts) | `COMPACT_COMPLETED` | **existiert — reuse, nicht duplizieren** |
| `compact.prepare.sent` (Empfänger, ts) | `COMPACT_PREPARE_SENT("compact.prepare.sent")` | **neu** |
| `compact.request.sent` (Empfänger, ts) | `COMPACT_REQUEST_SENT("compact.request.sent")` | **neu** |
| `compact.orchestration.done` (X/N) | `COMPACT_ORCHESTRATION_DONE("compact.orchestration.done")` | **neu** |

Pro neuem Konstanten-Eintrag: **einen `▦`-Zweig** in `EventVisuals.groupGlyph()` ergänzen (an die bestehende Compaction-Zeile `EventVisuals.kt:117-136`). Decode ist tolerant (`fromWire`, `EventModel.kt:139-159`) — bis die Konstanten landen, fallen die neuen Wires auf `UNKNOWN`+`rawType` zurück, kein Crash (aber dann Glyph `ⓘ` statt `▦`, darum die Konstanten zeitnah landen).

> **Optionaler Anker:** Der Schwellen-Übertritt, der die Runde eröffnet, kann als bestehendes `COMPACT_TRIGGERED` emittiert werden → natürliches Startevent des Laufs (trägt die `correlationId`, §2.4). Nice-to-have, nicht Teil der vier.

### §2.2 Severity-Contract pro Typ — **die Honesty-Achse** (Backend-Contract)

`enum class Severity { DEBUG, INFO, WARN, ERROR }` (`EventModel.kt:59-64`) — **kein SUCCESS, kein Grün** (bewusst & load-bearing: `TonedHint.kt:53`, `SettingsPanel.kt:60`, `EventVisuals.kt:66-67`). Der Server setzt `Event.severity`; die UI rendert nur.

| Event | Severity | Begründung |
|---|---|---|
| `compact.prepare.sent` | **INFO** | normaler Lifecycle-Send |
| `compact.request.sent` | **INFO** | normaler Lifecycle-Send |
| `compact.completed` | **INFO** | per-Agent-Abschluss — **bewusst neutral, kein Grün** |
| `compact.orchestration.done` — **N/N voll** | **INFO** | Runde vollständig; neutral (kein Grün) |
| `compact.orchestration.done` — **X/N Timeout** | **WARN** | **Timeout ist kein Erfolg** → Amber-Rail `▲`, ehrliche X/N |

**Kern (§3-4):** Die **konditionale Severity** von `orchestration.done` (INFO voll / WARN Timeout) ist ein **Backend-Contract** — der Server entscheidet anhand des echten Ergebnisses, die UI erfindet die Farbe nicht. So trägt die eine Farb-Achse (Rail) im flachen Live-Tail sofort das Pass/Partial-Signal.

### §2.3 Rendering — **Reuse `EventRow`, keine neue Row-UI**

Jedes Compact-Event ist eine gewöhnliche `EventRow` (`EventRowUi.kt:75`, Triage-Zellen `:124-143`) mit den drei kollisionsfreien Achsen (KDoc `:67-73`):
- **Severity** (die eine Farb-Achse): Rail-Farbe `event.severity.railColor(dark)` + Glyph `event.severity.glyph()` (`EventVisuals.kt:45-59, 109-114`) — INFO `ⓘ` / WARN `▲`.
- **Typ** (monochromer Scan-Glyph, kein Hue): `event.type.groupGlyph()` = **`▦`** + `event.typeText()` = der **rohe gepunktete Wire** in Monospace (`compact.prepare.sent` etc.). **Typ-Text bleibt un-lokalisiert** (aggregierbar/greppbar, Haus-Konvention `EventVisuals.kt:28-29`) → **keine Typ-Label-Keys nötig.**
- **Identität**: Avatar + Name via `SenderPalette` aus `event.agentId`.

Live-Tail-Zeile: `▦ compact.request.sent` (Monospace) · INFO-Rail · Empfänger-Avatar · ts. Reflow ≤560dp auf 2 Zeilen automatisch.

### §2.4 Gruppierung als Sequenz — **`correlationId` + bestehendes Run-Drilldown, KEIN inline-Threading**

**Kritischer Ist-Zustand:** Es gibt **kein** inline-Gruppieren/Verschachteln im Log — Live-Tail (`EventTailPanel.kt:94`) und Browse sind **flach, `seq`-geordnet** (`Event.seq`, `EventModel.kt:32`). Eine „Sequenz" existiert nur als **Drilldown-Filter über `correlationId`** in `EventBrowsePanel` („show the whole run", `:88-89, :297, :301-303, :366-370` `DETAIL_SHOW_RUN`, aktiv nur wenn `correlationId != null`).

**Entscheidung (Anti-Divergenz):** **KEIN** neues inline-Nesting erfinden. Stattdessen **Reuse**:
1. Der Server stempelt **allen** Events eines Compact-Laufs **dieselbe `correlationId`** (die Orchestrierungs-Lauf-ID; `Event.correlationId` „per work-run", `EventModel.kt:41`).
2. Damit gruppiert das **bestehende** „show the whole run"-Drilldown die vier Typen kostenlos zu **einer Sequenz** — ohne neue UI.
3. Im flachen Live-Tail liest die Sequenz als **`▦`-Cluster** in Zeitordnung (die vom Codebase intendierte Typ-Gruppierung-per-Glyph).

So ist „als eine Sequenz dargestellt" erfüllt **durch Reuse**, nicht durch einen divergenten Sonderweg. → Falls der Auftraggeber echtes **inline**-Threading will, ist das **net-new** und ein eigener Story-Scope (offene Frage §6-B).

### §2.5 `orchestration.done` X/N-Sichtbarkeit

Das ehrliche Ergebnis (z. B. „3/5, 2 Timeout") lebt in `Event.detail: JsonObject` (content-freie Metadaten). Im flachen Tail trägt die **Severity** (WARN vs INFO) das Pass/Partial-Signal; die **exakte X/N** erscheint in der **Browse-Detail-Ansicht** (Reuse `detail`-Rendering).

**Eine lokalisierte Detail-Zusammenfassung** (damit X/N in der Detail-Ansicht legibel ist, statt roher JSON):

| Key | DE | EN |
|---|---|---|
| `event_compact_done_summary` | %1$d/%2$d Agenten compactet, %3$d Timeout | %1$d/%2$d agents compacted, %3$d timed out |

(1 Key × 2 Sprachen; Konvention `event_<area>_<detail>`, `EventRowUi`/strings.xml-Stil.) Row-Typ-Text bleibt der Wire; nur die Detail-Zusammenfassung ist lokalisiert.

### §2.6 Identität, a11y, testTags — alles Reuse

- **Identität (`agentId`)**: `prepare.sent`/`request.sent` tragen den **Empfänger** als `agentId`; `compact.completed` den **compacteten Agenten**; `orchestration.done` den **Orchestrator** (PO) oder eine Team-Scope-Identität (Backend-Call). X/N in `detail`.
- **a11y**: `a11y_event_row` (`EventRowUi.kt:92`, DE:74) komponiert bereits Severity+Typ+Agent+ts → **greift automatisch** für die neuen Typen, **kein neuer a11y-Key**.
- **testTags**: Rows sind schon getaggt `eventTail.row.<i>.<qualifier>` mit `qualifier ∈ {info,warn,...}` (`EventLogTags.kt:56-83`) → Compact-Events reusen das automatisch (info/warn) — **keine neuen Event-Tags**.

---

## §3 — Disclosure-Honesty-Invarianten (verpflichtend, meine Kern-Achse)

1. **Sensible Aktion, ehrlich benannt.** Das Gate autorisiert **automatische, team-weite** Context-Compaction — eine **irreversible Verdichtung** jedes Agenten-Contexts, nach dem Schwellen-Übertritt **ohne weiteren Bestätigungsschritt**. `compact_allow_hint` benennt das klar; **Default = aus**; Steuerung operator-gated. **Nicht** als harmlose Bequemlichkeit worden.
2. **Keine überzogene Garantie.** Copy verspricht **keine** verlustfreie Compaction und **keine** garantierte Vollständigkeit. „verdichtet" statt „ohne Verlust". Abschluss wird als **X/N mit Timeout** berichtet.
3. **Zustand = Server-Spiegel, nie optimistisch.** `allowed`, Schwelle, laufend/idle, letzter Lauf X/N kommen aus dem **server-owned** Orchestrierungs-State. Die UI zeigt **nie** „läuft"/„fertig"/„erlaubt" vor dem Server. **Unbekannt → ehrlich abwesend**, nicht auf idle/off defaulten (Linie CYP-312 never-optimistic · CYP-315 failed≠remote · CYP-316 null≠0).
4. **Timeout ≠ Erfolg.** `orchestration.done` bei Timeout = **WARN** + explizit „X/N compactet, Rest ausstehend". **Nie** als erledigt/grün. Kein grüner SUCCESS-Ton existiert und keiner wird eingeführt.

---

## §4 — Backend/Dev-Abhängigkeiten & Shared-Key-Drift

**Contracts, die Dev/Backend liefern muss (ich spec sie, baue sie nicht):**
- **`:core` `EventModel.kt`:** 3 neue `EventType`-Konstanten (§2.1) + je ein `▦`-Zweig in `EventVisuals.groupGlyph()`.
- **Server-Severity (§2.2):** Events mit der spezifizierten Severity emittieren; `orchestration.done` **konditional** (INFO voll / WARN Timeout).
- **`correlationId` (§2.4):** alle Events eines Laufs teilen dieselbe → aktiviert das bestehende Run-Drilldown.
- **Server-owned `CompactOrchestrationState`** fürs Fenster: `{ allowed: Boolean, thresholdTokens: Int, phase: idle|running, runningSince: Long?, lastRun: { done: Int, total: Int, timedOut: Int }? }`; Operator-`PUT` flippt `allowed` (Server 403t Nicht-Operator).
- **Operator-Gating (§1.2):** Reuse `isOperator`; Toggle-PUT operator-only.
- **Fenster-Wiring (§1.1):** const-ID + `buildList` + `when`-Zweig + `contentWindowIds` in `AgentShell.kt`.

**⚠ Shared-Key-Drift (Pflicht-Flag):** Die neuen EventTypes leben in **`:core`** (compiliert in `:server` **und** `:app:shared`) — landen sie asynchron, bricht ein Shared-/Parity-Check. Die 11+1 i18n-Keys (`:app:shared`) müssen **atomar DE+EN** und **zeitgleich mit dem konsumierenden Modul** landen. **Timing mit Dev abstimmen.**

---

## §5 — Counts / Self-Validation

- **Fläche 1:** 11 neue i18n-Keys (×2 = 22 Einträge, DE↔EN paritätisch) + Reuse `workspace_operator_only`; 6 testTags (`CompactTags`) + auto `window.compact`; 1 neuer Fenstertyp; 0 App-Theme-Farben (nur colorScheme/severity-Rollen).
- **Fläche 2:** 1 EventType-Reuse + 3 neue; 3 neue `▦`-Glyph-Zweige; **0 neue Row-UI, 0 neue Event-Tags, 0 neue a11y-Keys, 0 neue Typ-Label-Keys** (Wire un-lokalisiert); **1** Detail-Zusammenfassungs-Key (×2). Severity-Contract 5-zeilig.
- **Kein grüner SUCCESS** über beide Flächen. Alle Farben colorScheme/severity, AA-belegt.

---

## §6 — Offene Fragen für die Auftraggeber-Ratifikation

- **A (Sichtbarkeit):** Gate-Fenster **sichtbar für alle** (Steuerung operator-gated, meine Empfehlung — Effekt ist team-weit) **vs. operator-only weglassen** (konsistent mit Event-Log)?
- **B (Gruppierung):** `correlationId`-Run-Drilldown als Sequenz (Reuse, meine Empfehlung) **vs. net-new inline-Threading** im Log (eigener Story-Scope)?
- **C (Schwelle):** 500K **fix** vs. operator-konfigurierbar (dann eine zusätzliche Zahl-Eingabe im Fenster; Server liefert `thresholdTokens` ohnehin)?
- **D (Control):** `Checkbox` (PO-Wortlaut) vs. `Switch`+Thumb-Glyph (ACL-Haus-Muster) — beide 1.4.1-konform.
