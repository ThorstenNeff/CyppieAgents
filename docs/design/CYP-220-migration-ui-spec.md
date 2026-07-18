# CYP-220 — Migrations-Screen (Operator) — UX-Spec

> Owner: UIUX-Designer · Auftrag PO 2026-07-18 · Companions: `CYP-220-migration-ui-keys.md` / `-tags.md` ·
> Basis: `CYP-220-migration-mechanics-design.md` (ratifiziert, §4a) + `CYP-220-store-inventory.md` ·
> Stand develop `da6437de` · **Design-Pass zur Ratifikation, kein Bau.**
>
> **★ Grenze (PL, unverändert):** Dieses Dokument legt **nicht** fest, *welche* Stores wandern, **nicht** die
> Form des Datenmodells und **nicht**, was BYODB verspricht. Es legt fest, **dass die UI über den Umfang
> nicht lügen kann** — form-neutral gegenüber der offenen §6.4-Weiche (PO bestätigt 2026-07-18).
> `hub_secret` / `role_assignments` bleiben Auftraggeber-Entscheid; §2.2 zeigt sie **als Zeile mit Grund**,
> ohne die Entscheidung vorwegzunehmen.

---

## 0. Was dieser Screen ehrlich sein muss (die vier Zähne der Fläche)

Vor jedem Layout: eine Migration hat vier Eigenschaften, die eine UI **aktiv aussprechen** muss, weil das
Weglassen jeweils eine falsche Aussage macht.

| # | Wahrheit | Ohne Aussprache liest der Operator … |
|---|---|---|
| **H1** | Migriert wird **ein Store**, nicht „die Datenbank" | „meine Daten liegen jetzt in meiner DB" |
| **H2** | Während des Fensters **schlägt jedes Schreiben auf diesen Store fehl** (409) | ein Fehler mitten im Betrieb wirkt wie ein Defekt |
| **H3** | Rollback **löscht die Quelle nie** — Löschen ist ein **separater** Schritt | Angst vorm Rollback (oder umgekehrt: Verlustannahme) |
| **H4** | Verify prüft **Zeilenzahl UND Prüfsumme** — das ist der Beweis | „fertig" ohne Belegwert |

H2 ist der wichtigste und im Auftrag nicht genannt: `MigrationGate` wirft während `MIGRATING` bei **jeder**
Mutation `store_migrating` → HTTP 409 (`boot/MigrationGate.kt:27-31`). Ein Operator, der das nicht vorher
weiß, erlebt die Migration als Störung. **Also: vor dem Start ansagen, während des Fensters sichtbar halten.**

---

## 1. Ort, Gate, Geltung

**Ort:** eigener Abschnitt in der bestehenden `SettingsPanel`-Fläche (`settings/SettingsPanel.kt:65`) —
Sektion nach dem Haus-Muster `Column(fillMaxWidth, testTag(SECTION_*), spacedBy(8.dp))` + `SectionHeading`.
**Kein neues Fenster.** Begründung: der Screen ist projekt-gebunden (§1.2) und die Settings-Fläche ist bereits
projekt-gekeyt (`AgentShell.kt:693`, `"$SETTINGS_WINDOW_ID-$activeProjectId"`).

**Gate — bewusst die strengere Variante:** `showRoster`-Form, also **echte Tier-Prüfung**
(`tier == UserTier.OPERATOR`, `workspace/WorkspaceAccess.kt:24`), **nicht** `isOperatorAccess`
(`:14`, das ein Break-Glass-Token einschließt). Grund: die Backend-Route ist tier-gegated; ein
Break-Glass-Token bekäme 403, und eine Fläche, die *bedienbar aussieht* und dann 403 liefert, ist eine
Lüge über die eigene Wirksamkeit.
**Darstellung im Nicht-Operator-Fall:** Sektion **präsent, Controls disabled**, plus
`TonedHint(workspace_operator_only, HintTone.GATED, migration.gateHint)` — Haus-Muster
(`SettingsPanel.kt:130-132`). Das Gate bleibt **beobachtbar**; es verschwindet nicht.

**§1.2 Geltung = (Store × Projekt).** Bindungen sind per `(storeKey, projectId)`
(`db/StoreBinding.kt:21-27`). Der Screen zeigt darum **immer** das aktive Projekt im Sektions-Header und
migriert nie „global". Ein Store kann in Projekt A gebunden und in B lokal sein — das ist gültiger Zustand,
kein Fehler, und die Fläche darf ihn nicht als Inkonsistenz zeichnen.

---

## 2. Abschnitt A — Umfang (die Anti-Lügen-Fläche)

**Eine Liste, zwei Gruppen, nie eine Gesamtzahl.** Es gibt **keinen** Fortschritt „über alles", keinen
„3 von 12 migriert"-Zähler, keine Prozentzahl über den Bestand. Jede Zeile ist ein Store.

### 2.1 Migrierbar (`migration.store.<storeKey>`)

Je Zeile: **Store-Name** (Klartext, kein `storeKey`-Rohwert) · **Zustand** · **Aktion**.

| Zustand | Anzeige | Ton |
|---|---|---|
| lokal (unbound) | `migration_state_local` „Lokal (SQLite)" | `onSurfaceVariant` |
| gebunden ACTIVE | `migration_state_bound` „In %1$s" (DSN-Label) | `onSurfaceVariant` |
| MIGRATING | `migration_state_migrating` + Spinner | INFO + Polite |
| READ_ONLY | `migration_state_readonly` | `severityColor(Severity.WARN)` |

> `BindingState` hat genau `ACTIVE, MIGRATING, READ_ONLY` (`StoreBinding.kt:13`) — die UI erfindet keinen
> fünften Zustand. „lokal" ist **kein** `BindingState`, sondern das **Fehlen** einer Bindung (ebd. `:16-18`)
> und muss deshalb als eigener, benannter Zustand gezeigt werden, nicht als leere Zelle.

### 2.2 Nicht migrierbar — **gezeigt, nicht weggelassen** (`migration.unavailable.<storeKey>`)

Dieselbe Liste, zweite Gruppe, unter der Überschrift `migration_group_unavailable`. Je Zeile Store-Name +
**Grund**, `HintTone.GATED` (`·`), Controls **abwesend** (nicht disabled — es gibt keine Aktion).

| Grund-Key | Wann | Text (DE) |
|---|---|---|
| `migration_reason_no_export` | Store hat kein `MigrationSource` (§2.2 Mechanik-Pass: 15 von 16) | „Kein Export-Pfad — dieser Store kann derzeit nicht kopiert werden." |
| `migration_reason_no_target` | keine Pg-Implementierung (z.B. `messages`) | „Kein Postgres-Ziel — dieser Store bleibt lokal." |
| `migration_reason_infra` | Bootstrap-Store, muss auf unserer Infra bleiben | „Bleibt auf der Plattform — verweist auf die Datenbanken der anderen Stores." |
| `migration_reason_pending_decision` | Umfang noch nicht entschieden (§6.4) | „Noch nicht freigegeben." |

⭐ **Der Kern der Anti-Lügen-Fläche:** `DsnRegistry` + `BindingRegistry` sind
Bootstrap-Stores, die **strukturell nicht** in eine Kunden-DB können (`db/DsnRegistry.kt:47-49`,
`StoreBinding.kt:30-32`) → `migration_reason_infra`. Und `hub_secret` / `role_assignments` erscheinen
**hier**, mit `migration_reason_pending_decision` — die Fläche sagt damit „noch nicht freigegeben"
und **nicht** „geht nicht" und **nicht** „geht". Das ist die form-neutrale Formulierung, die die
Auftraggeber-Weiche offen lässt und trotzdem nicht schweigt.

> **Warum Zeigen statt Ausblenden:** eine Liste mit 10 migrierbaren Stores und nichts sonst **behauptet
> durch ihr Layout**, das sei der Bestand. Der Bestand sind 44 (`CYP-220-store-inventory.md §0`). Abwesenheit
> ist hier eine Aussage — also muss sie eine wahre sein.

### 2.3 Was die Fläche NICHT anzeigt
Keine Gesamt-Prozentzahl · kein „vollständig migriert"-Zustand · kein Aggregat-Badge. Es gibt keinen
Zustand „fertig", weil es keinen definierten Gesamt-Umfang gibt, solange §6.4 offen ist — und einen zu
zeigen, wäre exakt die Vorwegnahme, die mir untersagt ist.

---

## 3. Abschnitt B — Ziel-DSN (Eingabe)

Felder **exakt** nach `DsnDescriptor` (`db/DsnRegistry.kt:24-33`) — keine erfundenen, keine fehlenden:
`label` · `host` · `port` · `database` · `user` · `sslMode` (Default `require`) · **Passwort**.

**Passwort = schreib-nur, Haus-Muster `ApiKeySection` (`SettingsPanel.kt:165-243`):**
- Eingabe `OutlinedTextField` + `PasswordVisualTransformation`, **nie** aus gespeichertem Zustand vorbelegt.
- Reveal-Toggle enthüllt **nur die aktuelle Eingabe**, nie das Gespeicherte (`:204-217`).
- „Hinterlegt"-Zustand: `migration_dsn_password_set` mit **server-maskiertem** `***last4` — die
  Maskierung kommt vom Server, der Client kürzt **nie** selbst (`values/strings.xml:191`).
- **Strukturelle Garantie, die die UI ausnutzen darf:** das Passwort ist AAD-gebunden verschlüsselt und
  wird **nie** serialisiert/geloggt (`DsnRegistry.kt:42-49`). Es gibt also gar keinen Rückgabe-Pfad — der
  maskierte Zustand ist nicht Höflichkeit, sondern der einzig mögliche.

**`sslMode`:** Default `require` vorbelegt. Wird er herabgesetzt, erscheint
`migration_dsn_ssl_warning` in `severityColor(Severity.WARN)` + Glyph `▲` (`eventlog/EventVisuals.kt:113`).
Kein Block — der Operator darf, er soll es nur nicht versehentlich tun.

**`tierOrigin`:** `AIVEN_MANAGED` vs `BYO` (`DsnRegistry.kt:17`) wird als Klartext-Zeile geführt
(`migration_dsn_origin_managed` / `_byo`). Ehrlichkeitsgrund: bei `BYO` liegt der Betrieb der Datenbank
beim Kunden — das ändert, wer bei einem Ausfall zuständig ist, und gehört sichtbar an die DSN, nicht in ein
Handbuch.

**Anzeigbar, weil credential-frei:** `jdbcUrl()` (`DsnRegistry.kt:36`) darf als Bestätigungszeile gezeigt
werden. Der `maskedHost` aus `/api/admin/db/metrics` (`tier/AdminDbMetrics.kt:14`) ist die bestehende
Konvention für die Kurzform in Listen — **reuse, keine zweite Maskierungsform.**

---

## 4. Abschnitt C — Start & das Fenster (H2)

> ⛔ **VORBEDINGUNG — §4.1 und §4.2 setzen CYP-714 voraus** (PO-Entscheid 2026-07-18, in Jira als
> **blockierend** für die CYP-220-UI verlinkt; Herkunft: CYP-712-Fund A2).
>
> **Ohne den read-seam-Fix ist die hier angesagte Schreibsperre für Alt-Bindungen nicht belastbar.**
> Belegt: `StoreBinding.state` hat den Default `= BindingState.ACTIVE` (`db/StoreBinding.kt:26`) und der
> Registry-Load ist ein direktes `decodeFromString<Map<String, StoreBinding>>` (`:60`) — eine vor dem
> `state`-Feld geschriebene Zeile lädt damit **stumm als `ACTIVE`**, und `MIGRATING` greift nie.
>
> **Warum das die UI blockiert und nicht nur die Montage:** die Copy in §4.1 **sagt zu**, dass
> Schreibvorgänge abgelehnt werden. Greift die Sperre nicht, sagt der Screen „abgelehnt", während
> Schreibvorgänge durchgehen — und **genau diese gehen beim Rebind still verloren** (die Kopie ist bereits
> durch). Das ist der Verlust, gegen den `MigrationGate` überhaupt existiert. Eine Disclosure, die
> **aktiv beruhigt, wo nicht beruhigt werden darf**, ist die schlimmere Klasse: der Operator passt
> *wegen* der Zusage nicht selbst auf. **Die UI landet nicht vor CYP-714.**

### 4.1 Vor dem Start: der Bestätigungsdialog
Haus-Muster `DeleteDialog` (`project/ProjectManagementPanel.kt:251`), **aber nicht destruktiv gefärbt** —
eine Migration ist kein Löschen; der Confirm-Button ist ein normaler `Button`, **nicht** `error`-gefärbt.
Destruktive Färbung ist für §6 (Decommission) reserviert.

Dialog-Inhalt, in dieser Reihenfolge:
1. **Titel** benennt Store **und** Ziel: `migration_confirm_title` „%1$s nach %2$s migrieren?"
2. **H2 als Hauptaussage**, `HintTone.EFFECT_DEFERRED` (`!`, gefüllter Banner — die einzige Tonstufe mit
   Banner, `ui/TonedHint.kt:43,49-55`): `migration_confirm_freeze`
   „Während der Migration werden **Schreibvorgänge auf diesen Store abgelehnt** (Lesen läuft weiter). Andere
   Stores sind nicht betroffen."
3. **H3 als Beruhigung, aber wahr**: `migration_confirm_source_kept`
   „Die bisherigen Daten bleiben erhalten — auch bei Abbruch. Gelöscht wird nichts."
4. Fehlerslot.

⚠ **Dialog-Pflicht (Plattform):** `Modifier.enableTestTagsAsResourceId().testTag(...)` am Dialog —
er rendert in einem **eigenen** Compose-Fenster und erbt das Root-Flag nicht (`acl/AclPanel.kt:445-448`,
`connect/RemoteRevokeControl.kt:73`). **Ohne das sieht Maestro die Tags nicht.**

### 4.2 Während des Fensters
Solange `BindingState.MIGRATING` gilt, trägt die **Store-Zeile** persistent
`migration_state_migrating` + Spinner, und die Sektion trägt eine stehende
`HintTone.EFFECT_DEFERRED`-Zeile `migration_window_active` — „Schreibvorgänge auf %1$s werden gerade
abgelehnt." Nicht nur im Dialog: der Dialog ist weg, das Fenster ist noch offen.

**Der 409 im Rest der App:** wenn irgendwo sonst eine Mutation mit `store_migrating` scheitert, ist die
Meldung **kein generischer Fehler**, sondern `migration_write_rejected` — „Wird gerade migriert. Bitte nach
dem Umschalten erneut versuchen." Ton **`EFFECT_DEFERRED`, nicht `ERROR`**: nichts ist kaputt, die Aktion
ist **aufgeschoben**, und der Rückweg ist echt. (Das ist genau die Tonstufe, für die
`EFFECT_DEFERRED` gebaut wurde.) ⟂ **Naht zu Backend:** der 409 trägt den typisierten Code
`store_migrating` (`MigrationGate.kt:30`) — der Client muss auf **den Code** matchen, nicht auf den
Meldungstext.

---

## 5. Abschnitt D — Phasen-Fortschritt (neu, und der Glyph-Knoten)

**Es gibt im Repo keinen Per-Schritt-Zustandsrenderer.** `StepChip` ist reiner Umschalter, und sein
Kommentar sagt ausdrücklich, dass ein „done"-Marker **aufgeschoben** wurde, weil er „einen verifizierten,
nicht-ASCII-tofu-anfälligen Glyph" braucht (`firstrun/FirstRunSteps.kt:111-112`). Diesen Knoten löse ich
hier — **ohne einen neuen Glyph einzuführen.**

### 5.1 Die vier Phasen (aus dem echten Enum, nicht erfunden)
`MigrationPhase` = `WINDOW_OPEN, COPY, VERIFY, REBIND, ROLLBACK, DECOMMISSION`
(`db/MigrationAudit.kt:11`). Der Fortschritt zeigt die **vier Vorwärts-Phasen**; `ROLLBACK` ist ein
Ausgang (§7), `DECOMMISSION` ein separater Schritt (§6).

| Reihe | Key | Bedeutung für den Operator |
|---|---|---|
| 1 | `migration_phase_window` | Schreiben gesperrt, Lesen läuft |
| 2 | `migration_phase_copy` | Daten werden kopiert |
| 3 | `migration_phase_verify` | Zeilenzahl + Prüfsumme werden geprüft |
| 4 | `migration_phase_rebind` | Umschalten auf das Ziel |

### 5.2 Zustandsdarstellung — die Lösung ohne neuen Glyph

| Zustand | Marker | Farbe/Ton | Begründung |
|---|---|---|---|
| ausstehend | `·` | `onSurfaceVariant` | **verifiziert** (= `hintGlyph(GATED)`, `TonedHint.kt:83`) |
| läuft | **Spinner** 18.dp + Polite-Live-Region | INFO | Haus-Form (`FirstRunSteps.kt:174`); **kein** Glyph nötig |
| **fertig** | **kein Glyph — der Belegwert** | `onSurfaceVariant` | ⭐ s.u. |
| fehlgeschlagen | `✕` | `error` | **verifiziert** (= `hintGlyph(ERROR)`, `TonedHint.kt:81`) |
| nicht erreicht | kein Marker, Label gedimmt | `onSurfaceVariant` | Abwesenheit ist hier wahr |

⭐ **„Fertig" trägt keinen Haken, sondern sein Ergebnis.** Statt eines unverifizierten `✓` zeigt die fertige
Phase **den Wert, der sie belegt** — COPY: „2.341 Zeilen", VERIFY: „2.341 / 2.341 · Prüfsumme identisch",
REBIND: den Ziel-DSN-Label. Drei Gewinne auf einmal:
1. **Kein neuer Glyph**, also kein Tofu-Risiko auf Desktop-JVM (CYP-54) — der aufgeschobene Knoten aus
   `FirstRunSteps.kt:111` wird umgangen statt geraten.
2. **Kein Grün.** Erfolg ist in diesem Produkt nie grün (`ui/TonedHint.kt`, `CompactPanel.kt:233`) — hier
   ist er schlicht **eine Zahl**.
3. **Der Beweis ersetzt das Symbol** (H4). Ein Haken behauptet Erfolg; „2.341 / 2.341 · Prüfsumme identisch"
   **zeigt** ihn.

⚠ **Zahlen nie erfinden:** liefert das Backend für eine Phase keine Werte, bleibt die Zeile **wertlos**
(Label + Marker), nicht „0". Präzedenz: `ProjectManagementPanel.kt:269-270` lässt Zählwerte ungerendert,
bis der Server sie liefert. **`null ≠ 0`.**

⚠ **Kein determinierter Fortschrittsbalken.** Es gibt im ganzen Repo keinen, und die Kommentare lehnen
erfundene Prozentwerte ausdrücklich ab (`FirstRunSteps.kt:174`). Zeilen-Fortschritt **innerhalb** von COPY
gibt es nur, wenn das Backend ihn liefert — dann als **Text X/N** (Präzedenz `AclPanel.kt:426`,
`CompactPanel.kt:228-231`), nie als Balken. ⟂ **Naht:** `StoreMigrator.migrate` ist heute **synchron ohne
Zwischen-Callback** (`db/StoreMigrator.kt:45-88`) — Zeilen-Fortschritt existiert also **nicht**. Bis das
Backend ihn anbietet, ist COPY **spinner-only**. Das ist ehrlich und ohne Zusatzarbeit.

### 5.3 Lange Läufe
Nach `MIGRATION_SLOW_THRESHOLD_MS` (Vorschlag 15_000, gleiche Größe wie `CLONE_SLOW_THRESHOLD_MS`,
`FirstRunSteps.kt:60`) erscheint eine ruhige Zeile `migration_slow` — „Läuft noch. Große Stores können
mehrere Minuten dauern." **Ausdrücklich kein Timeout, kein Abbruch-Angebot**, exakt die Semantik der
Clone-Zeile (`FirstRunSteps.kt:184`), die in der CYP-629-QA als Lebenszeichen bestanden hat.

---

## 6. Abschnitt E — Ergebnis (der Beleg) & Decommission

### 6.1 Der Beleg
Nach `ok = true` steht eine **Quittungszeile** aus `MigrationReceipt` (`StoreMigrator.kt:16-24`):
`migration_receipt_ok` — „%1$s Zeilen kopiert und geprüft (Prüfsumme identisch). Jetzt gebunden an %2$s."
Ton **INFO, nicht grün**, Polite.

**Zusatz, der H3 hält:** direkt darunter, `onSurfaceVariant`:
`migration_receipt_source_kept` — „Die bisherigen Daten liegen unverändert weiter am alten Ort."

### 6.2 Decommission — der **einzige** destruktive Ort
Das Löschen der Quelle ist im Migrator bewusst **nicht** enthalten (`StoreMigrator.kt:38-39`). Also ist es
hier eine **eigene, später ausführbare** Aktion, nicht das Ende des Migrations-Flows:
- Sichtbar **erst** wenn die Bindung `ACTIVE` **und** die Quelle noch vorhanden ist.
- Eigener Dialog, **jetzt** destruktiv gefärbt (`containerColor = error`, `ProjectManagementPanel.kt:256-259`).
- Text `migration_decommission_warning`: „Löscht die bisherigen Daten am alten Ort. **Danach ist kein
  Zurück mehr möglich.**" — die einzige Stelle im Flow, an der „unumkehrbar" steht, und sie ist wahr.

> **Warum getrennt:** solange die Quelle liegt, ist Rollback ein Handgriff. Verschmölze man Decommission mit
> dem Erfolgsfall, nähme man dem Operator genau die Sicherheit, mit der die Mechanik geworben hat.

---

## 7. Abschnitt F — Fehler, Abbruch, Rollback

`StoreMigrator` fängt **jeden** `Throwable`, macht `unbind()` und wirft weiter (`:82-87`). Für die UI heißt
das: **es gibt keinen „halb migrierten" Zustand** — nach einem Fehler ist der Store zurück auf der Quelle.

| Fall | Anzeige | Ton |
|---|---|---|
| Verify fehlgeschlagen | `migration_error_verify` „Prüfung fehlgeschlagen: %1$s von %2$s Zeilen angekommen. Nichts umgestellt." | `ERROR` (`✕`), Assertive |
| Prüfsummen-Abweichung bei gleicher Zeilenzahl | `migration_error_checksum` „Zeilenzahl stimmt, Inhalt nicht. Nichts umgestellt." | `ERROR`, Assertive |
| Verbindung/DSN | `migration_error_connect` „Ziel nicht erreichbar. Nichts umgestellt." | `ERROR`, Assertive |
| Unbekannt | `migration_error_unknown` + servergelieferter, sekret-freier Text | `ERROR`, Assertive |

**Jede** Fehlermeldung endet auf „**Nichts umgestellt.**" — das ist die Aussage, die der Operator zuerst
braucht, und sie ist durch `unbind()` im `catch` gedeckt. `MigrationAuditEntry.error` ist ausdrücklich
sekret-frei (`MigrationAudit.kt:16-18`), darf also unverändert gezeigt werden.

**Rollback als Aktion** (aus `READ_ONLY`/hängendem Fenster): Button `migration_rollback`, **nicht**
destruktiv gefärbt, mit `migration_rollback_explain` — „Setzt auf die bisherigen Daten zurück. Es geht
nichts verloren." H3, an der Stelle, an der die Angst entsteht.

---

## 8. Historie / Nachweis — und die stille Falle

Eine Verlaufsliste aus `MigrationAuditEntry` (`MigrationAudit.kt:22-35`; Felder alle sekret-frei:
`ts, storeKey, phase, result, sourceRows, targetRows, checksumMatch, error, actor, toDsnId`).
Reuse `EventRow` (`eventlog/EventRowUi.kt:75`) + Leerzustand nach `CompactTags.EVENTS_EMPTY`-Präzedenz.

⚠⚠ **Fund, der eine eigene Zeile verlangt:** `StoreMigrator` nimmt den Audit-Sink als **Default-Parameter**
`audit: MigrationAudit = MigrationAudit.NONE` (`StoreMigrator.kt:43`), und `NONE.entries()` gibt
`emptyList()` (`MigrationAudit.kt:44-47`). Wird der Migrator ohne `FileMigrationAudit` montiert, ist die
Historie **leer — ununterscheidbar von „es gab keine Migration".**

Das ist dieselbe Fehlerform wie der stille Operator-Pin (CYP-576 §0.1): fail-safe, aber **wortlos** — und
die UI würde die Stille erben und als Tatsache ausgeben.

**Deshalb zwei getrennte Leerzustände, und der Unterschied muss vom Server kommen:**

| Zustand | Key | Ton |
|---|---|---|
| Sink läuft, nichts passiert | `migration_history_empty` „Noch keine Migration." | `onSurfaceVariant` |
| Sink **nicht** verdrahtet | `migration_history_unavailable` „Kein Migrations-Protokoll verfügbar — Verlauf wird nicht aufgezeichnet." | `severityColor(Severity.WARN)` + `▲` |

⟂ **BE-1 (blockierend für §8):** der Server muss **melden, ob der Audit-Sink durabel ist** (ein Flag, nicht
ableitbar aus einer leeren Liste). Ohne dieses Flag kann die UI die beiden Fälle nicht trennen — und dann
ist die ehrliche Wahl, **§8 gar nicht zu bauen**, statt eine leere Liste zu zeigen, die „nichts passiert"
behauptet. Lieber keine Historie als eine, die schweigt.

---

## 9. a11y

- Phasen-Container `liveRegion = Polite`; **Fehler `Assertive`** (Präzedenz `FirstRunSteps.kt:196-206`).
- ⚠ **Der nackte Spinner ist SR-stumm** (`connect/RemoteOperatorAuthSteps.kt:229`): jede laufende Phase
  trägt eine `contentDescription` **auf der Zeile** (`a11y_migration_phase_running`), nicht nur die
  optische Bewegung.
- Phasenzeilen: `stateDescription` = der Zustand als Wort (ausstehend/läuft/fertig/fehlgeschlagen) —
  weil „fertig" bewusst **keinen** Glyph trägt (§5.2), darf der Zustand für SR **nicht** aus dem Marker
  abgeleitet werden; er muss explizit gesetzt sein. **Sonst wäre die glyphlose Lösung eine a11y-Regression.**
- `SectionHeading` trägt `semantics { heading() }` (Haus-Muster `SettingsPanel.kt:246`).
- Kein Farb-Alleinträger (WCAG 1.4.1): jeder Zustand hat Glyph **oder** Wort **oder** Zahl.

---

## 10. ⟂ Nahtstellen an Backend (Montage läuft parallel)

| ID | Braucht die UI | Warum |
|---|---|---|
| **BE-1** | Flag „Audit-Sink durabel?" | §8 — sonst ist der Leerzustand mehrdeutig. **Blockierend für §8.** |
| **BE-2** | Store-Liste mit `canMigrate` + Grund-Code | §2.2 — die UI darf die Gründe **nicht** selbst herleiten |
| **BE-3** | 409 mit Code `store_migrating` bis zum Client durchgereicht | §4.2 — Match auf Code, nicht auf Text |
| **BE-4** | Klartext-Namen je `storeKey` | §2.1 — kein Rohschlüssel in der UI |
| **BE-5** | (optional) Zeilen-Fortschritt in COPY | §5.2 — ohne ihn bleibt COPY spinner-only, das ist ok |
| **BE-6** | Decommission als **eigener** Endpunkt | §6.2 — nicht Teil von `migrate` |
| **BE-7** | **CYP-714** (read-seam) — Alt-Bindungen laden nicht mehr stumm als `ACTIVE` | §4 — **blockierend für die gesamte UI**, s. Vorbedingung |
| **BE-8** | Erkennbarkeit: ist eine Bindung aus der Vor-`state`-Ära? | §4 — s. §11.4; sonst sieht der stille Fall aus wie der gesunde |

---

## 11. Offene Fragen (nicht von mir entschieden)

1. **§6.4-Weiche (Auftraggeber):** Deckungsumfang. Die Fläche ist form-neutral gebaut, **aber** die
   Zeilen für `hub_secret`/`role_assignments` brauchen den Grund-Code: `pending_decision` (heute) → nach der
   Weiche entweder migrierbar oder `no_target`/`infra`. **Ein Key-Wechsel, kein Redesign.**
2. **Slow-Schwelle** 15 s übernommen — falls Migrationen typischerweise deutlich länger laufen, gehört die
   Zahl gemessen, nicht geraten. **Messfrage an Backend, keine Design-Frage.**
3. **Mehrere Migrationen gleichzeitig?** Der Migrator ist per Aufruf synchron; ob die Fläche mehrere
   parallele Fenster zulässt, ist eine Betriebsentscheidung. **Default-Vorschlag: eine zur Zeit**, weil
   mehrere gleichzeitige Schreib-Sperren für den Nutzer nicht mehr als ein Ereignis lesbar sind.
4. **Reicht CYP-714 aus UX-Sicht?** Dass Alt-Zeilen künftig **richtig laden**, ist notwendig — aber
   solange nicht **erkennbar** ist, ob eine Bindung noch aus der Vor-`state`-Ära stammt, hat der Screen
   dasselbe Problem wie beim Audit-Sink (§8) und beim Operator-Pin (CYP-576 §0.1): **der stille Fall sieht
   aus wie der gesunde.** Als BE-8 aufgenommen; Entscheid liegt bei Backend/PL.

> **Muster-Notiz (drei Fälle an einem Tag, gleiche Form):** stiller Operator-Pin · nicht verdrahteter
> Audit-Sink · Alt-Bindung ohne `state`. Jedes Mal ist das System **fail-safe, aber wortlos**, und der
> defekte Zustand ist vom gesunden **nicht unterscheidbar** — die UI erbt die Stille und gibt sie als
> Tatsache aus. Die Gegenmaßnahme ist jedes Mal dieselbe: **den degradierten Zustand unterscheidbar
> machen**, nicht nur korrigieren. Als Muster an den PL gemeldet, nicht als drei Einzelfälle.

---

## 12. Was dieses Dokument NICHT tut

Kein Bau · keine Festlegung, welche Stores wandern · keine Datenmodell-Form · keine Aussage über das
BYODB-Kundenversprechen · keine Entscheidung zu `hub_secret`/`role_assignments` (nur ihre **Sichtbarkeit
mit Grund**) · keine Backend-Endpunkt-Spezifikation (nur die geforderten Nahtstellen, §10).

---

## 13. Self-Validation

- **Jede UI-Behauptung gegen echten Code belegt** (`datei:zeile`): Gate-Prädikate, `TonedHint`-Töne und
  Glyphen, Dialog-Muster, Masked-Secret-Pfad, Spinner-Konvention, Tag-Schema, Farbrollen.
- **Reuse-first, gemessen:** `TonedHint`/`HintTone` · `severityColor(Severity.WARN)` + `▲` (weil es
  **kein** `warnContainer` in der Colour-Scheme gibt — verifiziert) · `EventRow` · `ApiKeySection`-Masken-
  Muster · `DeleteDialog`-Form · `SectionHeading` · `maskedHost`. **Neu nur, was nachweislich fehlt:** der
  Phasen-Zustandsrenderer (§5).
- **Der aufgeschobene Glyph-Knoten ist gelöst, nicht geraten** (`FirstRunSteps.kt:111-112`): „fertig" trägt
  **keinen** neuen Glyph, sondern seinen Belegwert. Verwendet werden ausschließlich die **verifizierten**
  Zeichen `·` `✕` `▲`.
- **`null ≠ 0` an drei Stellen gehalten:** Phasenwerte (§5.2), Zeilen-Fortschritt (§5.2), Historie-
  Leerzustand (§8).
- **Ein eigener Fund, der nicht im Auftrag stand:** die Schreib-Sperre (H2) — vom Auftrag nicht genannt,
  aber die spürbarste Nutzerwirkung; sie bekommt Dialog-Rang, eine stehende Zeile **und** die App-weite
  409-Copy.
- **Ein zweiter Fund mit Konsequenz statt Kosmetik:** der Default-`MigrationAudit.NONE` macht eine leere
  Historie mehrdeutig → §8 wird **lieber nicht gebaut** als schweigend (BE-1 blockierend).
- **Grenze gehalten:** §2.2 zeigt `hub_secret`/`role_assignments` **mit Grund** und ohne Vorwegnahme; §11.1
  benennt den Key-Wechsel, den die Weiche auslöst. Keine Entscheidung getroffen.
- Jeder sichtbare/a11y-Text ist in `-keys.md`, jeder Tag in `-tags.md` verankert; Zählungen dort validiert.
