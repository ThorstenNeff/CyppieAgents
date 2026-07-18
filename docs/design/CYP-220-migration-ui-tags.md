# CYP-220 — Migrations-Screen — testTags

> Owner: UIUX-Designer · Companion zu `CYP-220-migration-ui-spec.md` / `-keys.md` · Stand 2026-07-18 ·
> **Design-Pass, kein Bau.**
> Schema (Test-Contract v0.5 §2, identisch in jedem `*Tags.kt`-Header, z.B. `SettingsTags.kt:5-7`):
> **prefixless `<area>[.<scopeId>].<element>[.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase,
> KEINE Punkte, KEINE Unterstriche).** Neue Datei: `settings/MigrationTags.kt` (oder eigenes Paket
> `migration/`), mit dem Standard-Header.
> **Geteilte API mit QA (CYP-7) — additiv, nie stumm umbenennen, über den PO koordinieren.**

---

## ⚠ 0. Schema-Kollision, die vor dem Bau entschieden werden muss

Die echten `storeKey`-Werte sind **snake_case** — gemessen an den Aufrufstellen
(`grep 'storeMigrating("…")'`, `boot/MigrationGate.kt` u.a.):

```
agent_events · agent_override · channel_share · delivery · event_log
project_config · remote_token · report · session
```

Das Tag-Schema erlaubt im Segment aber nur `[A-Za-z0-9-]+` — **ein `_` ist nicht zulässig.**
`migration.store.remote_token` wäre also **schemawidrig**.

**Festlegung (Design-Entscheidung, in meiner Bahn):** die UI bildet den `storeKey` für den **Tag** auf
**camelCase** ab, 1:1 und verlustfrei:

| storeKey (Backend, Wahrheit) | Tag-Segment |
|---|---|
| `agent_events` | `agentEvents` |
| `agent_override` | `agentOverride` |
| `channel_share` | `channelShare` |
| `delivery` | `delivery` |
| `event_log` | `eventLog` |
| `project_config` | `projectConfig` |
| `remote_token` | `remoteToken` |
| `report` | `report` |
| `session` | `session` |

```kotlin
/** storeKey (snake_case, Backend-Wahrheit) → Tag-Segment (camelCase, Schema-konform). Verlustfrei. */
private fun seg(storeKey: String): String =
    storeKey.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercase) }.joinToString("")
```

> **Wichtig für QA (CYP-7):** der **Anzeigewert und die Backend-Identität bleiben `snake_case`** — nur das
> **Tag-Segment** ist camelCase. Die Abbildung ist eindeutig umkehrbar (kein `storeKey` enthält
> Großbuchstaben oder Bindestriche). Wer den Tag aus dem `storeKey` baut, muss `seg()` benutzen —
> **nicht** den Rohwert einsetzen, sonst bricht der Contract-Check.

---

## 1. Sektion & Gate (§1)

| Konstante | Wert |
|---|---|
| `SECTION` | `migration.section` |
| `GATE_HINT` | `migration.gateHint` |
| `PROJECT_LABEL` | `migration.projectLabel` |

## 2. Umfang (§2)

| Konstante / Funktion | Wert |
|---|---|
| `GROUP_MIGRATABLE` | `migration.group.migratable` |
| `GROUP_UNAVAILABLE` | `migration.group.unavailable` |
| `fun store(k)` | `migration.store.<seg(k)>` |
| `fun storeState(k)` | `migration.store.<seg(k)>.state` |
| `fun storeMigrate(k)` | `migration.store.<seg(k)>.migrate` |
| `fun unavailable(k)` | `migration.unavailable.<seg(k)>` |
| `fun unavailableReason(k)` | `migration.unavailable.<seg(k)>.reason` |

> Der **Zustands-Qualifier** hängt an `storeState`: `.local` / `.bound` / `.migrating` / `.readonly` —
> gespiegelt aus `BindingState` (`db/StoreBinding.kt:13`) plus `local` für die **Abwesenheit** einer
> Bindung. QA assertet den Zustand über den Qualifier, **nie** über Farbe (WCAG 1.4.1).

## 3. Ziel-DSN (§3)

| Konstante | Wert |
|---|---|
| `DSN_SECTION` | `migration.dsn.section` |
| `DSN_LABEL` | `migration.dsn.label` |
| `DSN_HOST` | `migration.dsn.host` |
| `DSN_PORT` | `migration.dsn.port` |
| `DSN_DATABASE` | `migration.dsn.database` |
| `DSN_USER` | `migration.dsn.user` |
| `DSN_SSLMODE` | `migration.dsn.sslMode` |
| `DSN_SSL_WARNING` | `migration.dsn.sslWarning` |
| `DSN_PASSWORD` | `migration.dsn.password` |
| `DSN_PASSWORD_MASKED` | `migration.dsn.passwordMasked` |
| `DSN_PASSWORD_REVEAL` | `migration.dsn.passwordReveal` |
| `DSN_ORIGIN` | `migration.dsn.origin` |

## 4. Start & Fenster (§4)

| Konstante | Wert |
|---|---|
| `START` | `migration.start` |
| `CONFIRM_DIALOG` | `migration.confirmDialog` |
| `CONFIRM_FREEZE` | `migration.confirmDialog.freeze` |
| `CONFIRM_SOURCE_KEPT` | `migration.confirmDialog.sourceKept` |
| `CONFIRM_START` | `migration.confirmDialog.confirm` |
| `CONFIRM_CANCEL` | `migration.confirmDialog.cancel` |
| `WINDOW_ACTIVE` | `migration.windowActive` |
| `WRITE_REJECTED` | `migration.writeRejected` |

> ⚠ **Dialog-Pflicht:** `Modifier.enableTestTagsAsResourceId().testTag(CONFIRM_DIALOG)` — der Dialog
> rendert in einem eigenen Compose-Fenster und erbt das Root-Flag **nicht** (`acl/AclPanel.kt:445-448`).
> Ohne das sind **alle** Tags in §4/§6.2 für Maestro unsichtbar.

## 5. Phasen (§5)

| Konstante / Funktion | Wert |
|---|---|
| `PHASES` | `migration.phases` |
| `fun phase(p)` | `migration.phase.<p>` |
| `fun phaseValue(p)` | `migration.phase.<p>.value` |
| `SLOW` | `migration.slow` |

`<p>` ∈ `window` · `copy` · `verify` · `rebind` — abgeleitet aus `MigrationPhase`
(`db/MigrationAudit.kt:11`), kleingeschrieben, ohne `WINDOW_OPEN`→ nur `window`.

**Zustands-Qualifier auf `phase(p)`:** `.pending` · `.running` · `.done` · `.failed` · `.skipped`.

> ⭐ **Der Qualifier ist bei „done" der EINZIGE maschinenlesbare Marker**, weil „fertig" bewusst **keinen**
> Glyph trägt (Spec §5.2 — der Belegwert ersetzt ihn). QA darf „fertig" deshalb **nicht** über ein Zeichen
> assertieren, sondern über `migration.phase.copy.done` **und** den Wert in
> `migration.phase.copy.value`. Genau dafür existiert `phaseValue`.

## 6. Ergebnis, Rollback, Decommission (§6–§7)

| Konstante | Wert |
|---|---|
| `RECEIPT` | `migration.receipt` |
| `RECEIPT_SOURCE_KEPT` | `migration.receipt.sourceKept` |
| `ERROR` | `migration.error` |
| `ROLLBACK` | `migration.rollback` |
| `ROLLBACK_EXPLAIN` | `migration.rollbackExplain` |
| `DECOMMISSION` | `migration.decommission` |
| `DECOMMISSION_DIALOG` | `migration.decommissionDialog` |
| `DECOMMISSION_CONFIRM` | `migration.decommissionDialog.confirm` |
| `DECOMMISSION_CANCEL` | `migration.decommissionDialog.cancel` |

## 7. Historie (§8)

| Konstante / Funktion | Wert |
|---|---|
| `HISTORY` | `migration.history` |
| `HISTORY_EMPTY` | `migration.history.empty` |
| `HISTORY_UNAVAILABLE` | `migration.history.unavailable` |
| `fun historyRow(i)` | `migration.history.<i>` |

> **`HISTORY_EMPTY` und `HISTORY_UNAVAILABLE` sind zwei verschiedene Tags — bewusst.** Ein QA-Test, der nur
> „Liste leer" prüft, kann die stille Falle (Spec §8: `MigrationAudit.NONE` als Default) nicht fangen.
> Präzedenz für den ehrlichen Leerzustand: `CompactTags.EVENTS_EMPTY`.

---

## 8. Vorgeschlagene Zähne (QA-Anker, je mit rot-Bedingung)

| # | Zahn | Mutation ⇒ MUSS rot |
|---|---|---|
| 1 | Nicht-Migrierbares ist **sichtbar**: für jeden nicht-migrierbaren Store existiert `migration.unavailable.<seg>` **und** `.reason` | Zeile wird ausgeblendet statt gezeigt |
| 2 | „fertig" ist **maschinenlesbar ohne Glyph**: `migration.phase.copy.done` vorhanden **und** `migration.phase.copy.value` nicht leer | `done` gesetzt, aber Wert fehlt (⇒ Erfolg ohne Beleg) |
| 3 | Kein erfundener Wert: liefert das Backend keine Zeilenzahl, **existiert `phaseValue` nicht** (statt „0") | „0" wird gerendert |
| 4 | Leerzustands-Trennung: bei nicht verdrahtetem Sink erscheint `history.unavailable`, **nicht** `history.empty` | beide Fälle rendern denselben Tag |
| 5 | Schreibsperre ist angesagt: während `MIGRATING` existiert `migration.windowActive` | Zeile verschwindet nach Dialog-Schluss |

---

## 9. Self-Validation

- **Tag-Inventar: 47 Einträge** = 3 (§1) + 7 (§2) + 12 (§3) + 8 (§4) + 4 (§5) + 9 (§6) + 4 (§7).
  Davon **39 `const`** und **8 parametrisierte `fun`**: `store`, `storeState`, `storeMigrate`,
  `unavailable`, `unavailableReason` (§2, 5) · `phase`, `phaseValue` (§5, 2) · `historyRow` (§7, 1).
  Parametrisierte Tags sind `fun`, nie `const` — Haus-Form (`ProjectTags.kt:38-42`, `CompactTags.kt:83`).
- **Schema-Konformität geprüft:** jedes Segment `[A-Za-z0-9-]+`, camelCase, keine Punkte **im** Wert.
  **Die einzige Verletzung war strukturell** (snake_case `storeKey`) und ist mit `seg()` gelöst (§0) —
  gefunden durch Abgleich der echten Werte gegen das Schema, nicht angenommen.
- **Reuse belegt:** `EventRow` (`eventlog/EventRowUi.kt:75`) für Historie-Zeilen · Leerzustands-Präzedenz
  `CompactTags.EVENTS_EMPTY` · Dialog-Tag-Form `projectMgmt.deleteDialog.confirm/.cancel`
  (`ProjectTags.kt:59-68`) 1:1 gespiegelt. **Kein neues Muster erfunden.**
- **Reuse-Referenzen gegen echten Code verifiziert** (Haus-Regel: `*Tags.kt` ist die Wahrheit, nicht die
  Doku): `SettingsTags.kt:16-18,25-26`, `CompactTags.kt:22,35,38,83`, `ProjectTags.kt:38-42,59-68`,
  `FirstRunTags.kt:265-268`.
- **Kollision:** Area-Präfix `migration.` gg. alle 24 bestehenden `*Tags.kt` @ `da6437de` = **frei**.
- **Dialog-Fallstrick explizit vermerkt** (§4) — er hat in drei bestehenden Dateien einen Kommentar, wird
  aber leicht vergessen und macht sonst zwei ganze Abschnitte untestbar.
- Jeder Tag ist in `-spec.md` verankert; jeder sichtbare/a11y-Text in `-keys.md`.
