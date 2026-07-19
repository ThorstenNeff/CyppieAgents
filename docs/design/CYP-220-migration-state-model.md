# CYP-220 — Migrations-Flow: Zustandsmodell (Design-Pass-Finalisierung)

> Owner: UIUX-Designer · Auftrag PO 2026-07-19 (Ratifikations-Vorlauf) · Ergänzt die **gemergte**
> `CYP-220-migration-ui-spec.md` (develop `96fdef6c`) · Stand develop `4361f0d5` · **Design-Pass, kein Bau.**
>
> **★ Grenze unverändert:** kein Datenmodell-Form-Entscheid, keine Aussage über das BYODB-Versprechen.
> §3.3 markiert die Stelle, an der „Migration nötig" genau dorthin liefe — und bricht dort ab.

---

## 0. Lane-Bestätigung + eine Korrektur der Ausgangslage

**Lane bestätigt:** CYP-220 Zustandsmodell für den BYO-DB-Migrations-Flow, safe-but-silent-Regel angewandt.

**⚠ Eine Prämisse stimmt nicht mehr: „der Bau wartet auf die Ratifikation".** Der Bau **ist da** — auf
develop liegen 10 Dateien unter `app/shared/src/commonMain/kotlin/com/tneff/cyppieagents/migration/`
(`MigrationModel/ViewModel/Section/DsnSection/PhaseProgress/Dialogs/HistorySection/Outcome/Api/Tags`) plus
2 Testdateien. **Und er ist gut:** er trägt `ReadOnlyReason` (CYP-720) und `HistoryAvailability` (BE-1)
bereits als eigene Typen — beides Punkte, die ich gestern als Nahtstellen gemeldet hatte.

Das ändert diesen Design-Pass von „Modell für einen künftigen Bau" zu **„Modell gegen einen vorhandenen
Bau, inklusive eines Befunds darin"** — der Befund unten ist deshalb kein Papier-Risiko, sondern **heute
im Code**.

---

## 1. ⚠ Der Befund: drei Wahrheiten, eine Fläche (safe-but-silent, live)

### 1.1 Was der Code tut

```kotlin
// MigrationViewModel.load()
val stores  = runCatching { api.stores(projectId) }.getOrNull()
val history = runCatching { api.history(projectId) }.getOrNull()
_state.update { MigrationUiState(loading = false, stores = stores, history = history) }
```
```kotlin
// MigrationSection
if (stores != null) {
    if (stores.migratable.isNotEmpty())  { … }
    if (stores.unavailable.isNotEmpty()) { … }
}
```

### 1.2 Was der Operator sieht

| Wahrheit | Zustand im Code | Gerenderte Fläche |
|---|---|---|
| Abfrage **läuft** | `loading=true, stores=null` | **nichts** (kein Spinner) |
| Abfrage **fehlgeschlagen** | `loading=false, stores=null` | **nichts** |
| Inventar **wirklich leer** | `stores≠null`, beide Listen leer | **nichts** |

**Drei grundverschiedene Wahrheiten, eine identische leere Sektion.** Der Operator liest daraus die
plausibelste: *„es gibt nichts zu migrieren."* Bei Fall 2 ist das falsch — die Wahrheit ist *„wir konnten
es nicht feststellen"*. Das KDoc nennt Fall 2 selbst „the honest empty (loaded, no data)" — genau da
liegt der Denkfehler: **eine fehlgeschlagene Feststellung ist keine Feststellung von Leere.**

Nebenbefund: `CircularProgressIndicator`, `Severity` und `severityColor` sind in `MigrationSection.kt`
**importiert, aber im Lade-/Fehlerpfad nicht benutzt** — der Zustand war offenbar vorgesehen und ist
herausgefallen.

### 1.3 Die zwei Prüf-Fragen, angewandt (Regel §4a)

| Frage | Befund |
|---|---|
| **① Hat der Fehlschlag-Zweig eine Ausgabe?** | **Nein.** `runCatching { … }.getOrNull()` verschluckt den Fehler restlos — kein Log, kein Zustand, keine Fläche. |
| **② Kann ein Default Abwesenheit in Anwesenheit übersetzen?** | **Ja.** `getOrNull()` macht aus *Fehlschlag* ein `null`, und `null` rendert wie *„nichts vorhanden"* — ein legitimer, beruhigender Wert. |

**Beide Fragen feuern.** Das ist derselbe Fingerabdruck wie beim stillen Operator-Pin und beim
`MigrationAudit.NONE` — und diesmal auf **meiner** Fläche, im bereits gebauten Code.

> **Warum das hier besonders zählt:** die ganze Sektion ist als **Anti-Lügen-Fläche** entworfen (Spec §2).
> Eine Fläche, die „nichts zu migrieren" zeigt, obwohl sie es nicht weiß, macht **genau die Aussage über
> den Umfang**, die zu vermeiden ihr einziger Zweck ist.

---

## 2. Das Zustandsmodell: zwei Achsen, nicht eine Liste

Die vorgeschlagene Reihe `unknown / checking / needed / in-progress / done / failed` mischt **zwei
unabhängige Achsen**. Als eine flache Enum gebaut, entstünden unmögliche und mehrdeutige Kombinationen
(was heißt „unknown" für einen Store, der gerade migriert?). Getrennt sind beide Achsen klein und
vollständig — und die untere existiert bereits im Bau, muss also **nicht** neu erfunden werden.

### 2.1 Achse A — **Feststellung** (NEU; das ist die Lücke aus §1)

Gilt für die **Sektion**, nicht für den einzelnen Store.

| Zustand | Bedeutung | Fläche | Ton |
|---|---|---|---|
| `CHECKING` | Abfrage läuft | Spinner 18.dp + Polite-Zeile | INFO |
| `KNOWN` | Inventar liegt vor | die zwei Gruppen (Spec §2) | — |
| `UNAVAILABLE` | Feststellung **fehlgeschlagen** | eigene Zeile + Wiederholen | **ERROR** (`✕`), Assertive |

⭐ **`UNAVAILABLE` ist der Zustand, den es heute nicht gibt, und der ganze Punkt dieses Passes.** Er darf
**nie** mit „Inventar leer" zusammenfallen.

⭐⭐ **Und das ist keine neue Regel — es ist eine Haus-Regel, die diese Fläche nicht geerbt hat.**
`LoadErrorRetry` (`ui/LoadErrorRetry.kt:37`) ist die etablierte Komponente für genau diesen Fall und wird
**bereits produktiv** verwendet: `AgentSettingsPanel.kt:259`, `AgentManagementPanel.kt:169`,
`CommPanel.kt:151,232`; `FirstRunGate.kt:107` nennt sie als vorgesehenen Reuse. Der Kommentar an
`AgentSettingsPanel.kt:252` schreibt die Doktrin wörtlich aus:

> *„Fail-closed: a failed live read shows `LoadErrorRetry` (**never an empty field — failed ≠ empty**, §10-4)"*

Das ist **CYP-288**, längst ratifiziert. Die Migrations-Sektion ist eine **neue Fläche, die diese Regel
nicht übernommen hat** — der Fix ist damit kein Entwurf, sondern **Angleichung an den Hausstandard**:
eine bestehende Komponente an einer Stelle einsetzen, an der sie fehlt. Billig, präzedenzgedeckt, und es
macht Zahn 1 zu einem Regressionstest gegen eine **bereits geltende** Regel.

**Und der leere, aber bekannte Fall braucht ebenfalls eine eigene Zeile:** `KNOWN` + beide Listen leer ⇒
`migration_stores_empty` — „Für dieses Projekt sind keine Stores gelistet." Heute rendert dieser Fall
**nichts**, ist also von `UNAVAILABLE` optisch nicht zu unterscheiden. Ehrliche Leere **sagt**, dass sie
leer ist.

### 2.2 Achse B — **Store-Zustand** (existiert bereits, unverändert übernehmen)

`StoreBindingState = LOCAL · BOUND · MIGRATING · READ_ONLY` — gespiegelt aus `BindingState` plus `LOCAL`
für die **Abwesenheit** einer Bindung. Dazu `ReadOnlyReason = MIGRATION_WINDOW · LEGACY_UNEVALUATED`
(CYP-720). **Kein Änderungsbedarf** — das ist bereits die ehrliche Form.

⭐ **Eine Lücke auf dieser Achse bleibt aber und gehört ins Modell:** `READ_ONLY` **ohne** Reason.
Das `MigrationModel`-KDoc sagt bereits richtig, ein solcher Store werde „honestly as unknown" gerendert —
**der Zustand braucht damit einen Namen und eine Zeile**, sonst wiederholt sich §1 eine Ebene tiefer:

| `state` | `readOnlyReason` | Anzeige |
|---|---|---|
| `READ_ONLY` | `MIGRATION_WINDOW` | „Wird migriert — Schreiben gesperrt" (warten) |
| `READ_ONLY` | `LEGACY_UNEVALUATED` | „Gesperrt — muss überprüft werden" (**handeln**) |
| `READ_ONLY` | `null` | **„Gesperrt — Grund unbekannt"** ⚠ nie stillschweigend als Migration lesen |

### 2.2b Achse B — Umsetzungs-Tabelle `READ_ONLY` (Dev-Hand-off CYP-727)

**Stand im Bau (`MigrationSection.StoreState`, develop):** `READ_ONLY` rendert **eine** Zeile —
`▲` + `migration_state_readonly`, beides in `severityColor(Severity.WARN)` — und der Grund fährt **nur**
in der `stateDescription` mit, als **roher Enum-Name** (`store.readOnlyReason?.name ?: "unknown"`).
Dev hat das im Code selbst als offen markiert („needs a dedicated key + BE-8"). Die drei Zeilen unten
lösen es auf.

| `readOnlyReason` | sichtbares Label | Tag-Qualifier | Farbe / Glyph | `stateDescription` (a11y) | Zusatzzeile |
|---|---|---|---|---|---|
| `MIGRATION_WINDOW` | `migration_state_readonly_migrating` | `.readonly.migrating` | **`secondary`, kein `▲`** (s.u.) | `a11y_migration_state_readonly_migrating` | — |
| `LEGACY_UNEVALUATED` | `migration_state_readonly_legacy` | `.readonly.legacy` | `severityColor(WARN)` + `▲` | `a11y_migration_state_readonly_legacy` | **`migration_legacy_action`** (Handlungssatz) |
| `null` | `migration_state_readonly_unknown` | `.readonly.unknown` | `severityColor(WARN)` + `▲` | `a11y_migration_state_readonly_unknown` | — |

**Drei Festlegungen, je mit Grund:**

1. ⭐ **`MIGRATION_WINDOW` bekommt KEIN Amber.** Ein Store, der gerade planmäßig migriert, ist **kein
   Warnzustand** — er tut genau das, was der Operator angestoßen hat. `▲` + WARN-Amber würde einen
   **erwarteten** Vorgang als Störung zeichnen und damit die Amber-Bedeutung abstumpfen (dann sieht
   `LEGACY_UNEVALUATED` aus wie „auch nur Migration"). Also `secondary` wie der `MIGRATING`-Zustand,
   mit dem er faktisch identisch ist. **WARN bleibt den beiden Fällen vorbehalten, in denen etwas
   nicht stimmt oder unklar ist.**
2. **`null` ist WARN, nicht neutral.** „Wir wissen nicht, warum dieser Store gesperrt ist" ist ein
   Zustand, der jemanden interessieren muss — er darf nicht ruhig aussehen (safe-but-silent).
3. ⚠ **Der rohe Enum-Name muss aus der a11y-Ausgabe raus.** Heute hört ein Screenreader-Nutzer
   „Nur lesend (LEGACY_UNEVALUATED)" — ein Bezeichner aus dem Code, vorgelesen. Das verletzt dieselbe
   Regel wie ein roher `storeKey` in der UI (Spec BE-4: Klartext, nie Rohschlüssel). Ersatz: die drei
   `a11y_*`-Keys oben.

**Ansage-Dringlichkeit:** alle drei `Polite` — es ist der **Anfangszustand einer gerade geöffneten
Fläche**, nicht das Ergebnis einer abgeschickten Aktion (`docs/A11Y-ANNOUNCEMENTS.md §1`).

**Aktions-Button je Zeile:** in allen drei Fällen **disabled** — der Store ist gesperrt. (Heute korrekt:
`canMigrate` verlangt `LOCAL`.) Für `LEGACY_UNEVALUATED` ist die **Handlungszeile** der Träger der
Information, nicht ein Button: *was* zu tun ist, hängt an CYP-720/Backend und wird hier **nicht** erfunden.

> ⭐ **Wichtig fürs Schneiden: Achse B ist HEUTE vollständig baubar.** Der Stub liefert bereits
> `LEGACY_UNEVALUATED` (`MigrationApi.kt:102-103`, `channel_share`), und `ReadOnlyReason` steht im Modell.
> **CYP-720 gated nur, ob echte Daten den Grund tragen — nicht die drei UI-Pfade.** Dev kann alle drei
> Zeilen inkl. Zähne gegen den Stub bauen und testen; die Live-Daten schalten sie später scharf.

### 2.3 Lauf-Zustand (dritte, kurzlebige Achse — existiert)

`PhaseState = PENDING · RUNNING · DONE · FAILED · SKIPPED` je Phase, plus `MigrationError` für den Lauf.
Deckt `in-progress` / `done` / `failed` der vorgeschlagenen Liste vollständig ab. **Kein Änderungsbedarf.**

---

## 3. Die vorgeschlagene Liste, abgebildet

| Vorschlag | Abbildung | Status |
|---|---|---|
| `unknown` | **A: `UNAVAILABLE`** (+ B: `READ_ONLY` ohne Reason) | **NEU — die Lücke** |
| `checking` | **A: `CHECKING`** | **NEU** (heute rendert Laden nichts) |
| `needed` | **nur** B: `READ_ONLY` + `LEGACY_UNEVALUATED` | ⚠ **eingeschränkt, s. §3.3** |
| `in-progress` | B: `MIGRATING` · C: `RUNNING` | vorhanden |
| `done` | B: `BOUND` · C: `DONE` | vorhanden, **s. §3.2** |
| `failed` | C: `FAILED` / `MigrationError` | vorhanden |

### 3.2 „done" nur **pro Store**, nie als Fläche
`BOUND` heißt „dieser Store liegt auf diesem Ziel". Ein Sektions-`done` („alles migriert") ist
ausgeschlossen — es behauptete einen **Gesamt-Umfang**, den §6.4 offen lässt (Spec §2.3). Die Achsen
trennen das sauber: „done" lebt auf B/C, nie auf A.

### 3.3 ⛔ „needed" — hier bricht der Design-Pass ab

„Migration **nötig**" ist in genau **einem** Fall aus dem Code ableitbar: `LEGACY_UNEVALUATED` — eine
Bindung, die CYP-714 fail-closed eingefroren hat und die **tatsächlich** eine Handlung verlangt (sonst
steht der Store still, s. gestrige Priorisierung von CYP-720). Diese Zeile ist wahr und gehört gebaut.

**Jede weitere Lesart von „needed" wäre eine Produktaussage, keine Statusanzeige.** „Dieser Store
*sollte* nach Postgres" setzt voraus, dass Postgres der Zielzustand ist — und ob BYODB alle Stände
umfasst, welche Teilmenge, und was dem Kunden zugesagt wird, ist die **offene §6.4-Weiche**
(Auftraggeber). Eine UI, die „Migration nötig" an einem lokalen SQLite-Store zeigt, hätte diese Weiche
**beantwortet**, indem sie lokal als defizitär markiert.

> **HALT.** Ich baue „needed" **ausschließlich** als `LEGACY_UNEVALUATED`-Zeile. Soll es darüber hinaus
> „empfohlen zu migrieren" geben, ist das ein Auftraggeber-Entscheid — als Frage an den PO, nicht hier
> eingebacken.

---

## 4. Copy-Delta (Keys, Ergänzung zu `-keys.md`)

| Key | DE | EN |
|---|---|---|
| `migration_checking` | Bestand wird ermittelt … | Checking inventory … |
| `migration_unavailable_title` | Bestand konnte nicht ermittelt werden. | Could not determine the inventory. |
| `migration_unavailable_hint` | Es ist unklar, welche Stores migrierbar sind — **nicht**, dass es keine gibt. | It is unclear which stores can be migrated — **not** that there are none. |
| `migration_retry` | Erneut versuchen | Try again |
| `migration_stores_empty` | Für dieses Projekt sind keine Stores gelistet. | No stores are listed for this project. |
| `migration_state_readonly_migrating` | Wird migriert — Schreiben gesperrt | Migrating — writes frozen |
| `migration_state_readonly_legacy` | Gesperrt — muss überprüft werden | Frozen — needs review |
| `migration_state_readonly_unknown` | Gesperrt — Grund unbekannt | Frozen — reason unknown |
| `migration_legacy_action` | Vorsorglich gesperrt (Bindung aus einer früheren Version). Schreibvorgänge bleiben blockiert, bis ein Operator sie prüft. | Frozen as a precaution (binding from an earlier version). Writes stay blocked until an operator reviews it. |

> **Wortlaut final (CYP-730, 2026-07-19).** Reihenfolge ist **Folge → Ursache → Handlung**, mit Absicht:
> die Sperre wirkt **jetzt** und **migrations-unabhängig** (CYP-714 friert fail-closed ein), also muss
> „Schreibvorgänge bleiben blockiert" vorn stehen — es erklärt zugleich das 409, das dem Operator
> anderswo begegnet. **Verworfen wurde „… vor dem Migrieren prüfen"**: das rahmt die Sperre als
> Migrations-Vorbedingung, worauf ein Operator ohne Migrationsabsicht folgerichtig „betrifft mich nicht"
> schließt — und der Store bliebe unbestimmt schreibgesperrt. Genau die CYP-720-Lücke (nicht „lügt die
> UI", sondern „erfährt er, dass er handeln muss"). **„bis ein Operator sie prüft"** nennt die Handlung,
> **ohne ein Verfahren zu erfinden** — was konkret zu tun ist, hängt an CYP-720/Backend.

**a11y:** `a11y_migration_checking` · `a11y_migration_unavailable` · die drei `a11y_migration_state_readonly_{migrating,legacy,unknown}` (§2.2b — sie **ersetzen** den heute vorgelesenen rohen Enum-Namen).

| a11y-Key | DE | EN |
|---|---|---|
| `a11y_migration_state_readonly_migrating` | Wird migriert, Schreiben gesperrt | Migrating, writes frozen |
| `a11y_migration_state_readonly_legacy` | Gesperrt, muss von einem Operator überprüft werden | Frozen, needs operator review |
| `a11y_migration_state_readonly_unknown` | Gesperrt, Grund unbekannt | Frozen, reason unknown |

⚠ **Ansage-Dringlichkeit:** `migration_unavailable_*` ist **`Polite`**, nicht Assertive — Anfangszustand
einer gerade geöffneten Fläche (`docs/A11Y-ANNOUNCEMENTS.md §1`; entschieden in CYP-727, `LoadErrorRetry`
trägt die Polite-Region ohnehin zentral). **Korrigiert meine frühere Achse-A-Tabelle**, die hier
Assertive sagte.

⭐ **`migration_unavailable_hint` ist die Kern-Zeile dieses Passes.** Sie sagt ausdrücklich, was der
Zustand **nicht** bedeutet — weil die naheliegende Fehllesart („dann gibt es eben keine") genau die ist,
die der leere Screen heute erzeugt. Eine Zeile, die eine Fehllesart aktiv ausschließt, ist hier billiger
als jede Umgestaltung.

⚠ **`migration_legacy_action` schließt die Lücke, die ich gestern selbst benannt hatte:** in **beiden**
bisherigen Fassungen fehlte der Satz, der dem Operator sagt, **dass er handeln muss**. Er hängt an
CYP-720 (Reason in der Kette).

---

## 5. Tag-Delta (Ergänzung zu `-tags.md`)

| Konstante | Wert |
|---|---|
| `CHECKING` | `migration.checking` |
| `UNAVAILABLE` | `migration.unavailable` |
| `RETRY` | `migration.retry` |
| `STORES_EMPTY` | `migration.storesEmpty` |

Plus Qualifier auf der bestehenden `storeState`-Bahn: `.readonly.migrating` · `.readonly.legacy` ·
`.readonly.unknown`.

⚠ **Namenskollision vermeiden:** `MigrationTags.UNAVAILABLE` (Achse A, Ladefehler) vs. die bestehende
`fun unavailable(k)` (§2.2, nicht-migrierbarer Store) sind **verschiedene Dinge**. Vorschlag zur
Entschärfung: die neue Konstante `INVENTORY_UNAVAILABLE = "migration.inventoryUnavailable"` nennen.
**Das ist eine QA-geteilte API (CYP-7) — Entscheid über den PO.**

---

## 6. Zähne (je mit rot-Bedingung)

| # | Zahn | Mutation ⇒ MUSS rot |
|---|---|---|
| 1 | **Fehlschlag ≠ leer:** schlägt `api.stores` fehl, rendert `migration.inventoryUnavailable`; die Gruppen-Überschriften erscheinen **nicht** | Fehler wird zu `null` verschluckt und die Sektion bleibt leer (**heutiger Zustand ⇒ rot**) |
| 2 | **Leer sagt, dass es leer ist:** `KNOWN` + beide Listen leer ⇒ `migration.storesEmpty` sichtbar | leerer Bestand rendert nichts |
| 3 | **Laden ist sichtbar:** während der Abfrage `migration.checking` + Polite-Region | Ladephase rendert nichts |
| 4 | **READ_ONLY ohne Reason ist eigen:** Reason `null` ⇒ `.readonly.unknown`, **nicht** `.readonly.migrating` | fehlender Reason wird als Migration gelesen |
| 5 | **Handlungs-Zeile existiert:** `LEGACY_UNEVALUATED` ⇒ `migration_legacy_action` sichtbar | Zustand wird angezeigt, ohne zu sagen, dass gehandelt werden muss |
| 6 | **Kein roher Bezeichner in der a11y-Ausgabe:** die `stateDescription` der drei READ_ONLY-Zeilen enthält **keinen** Enum-Namen (`LEGACY_UNEVALUATED` o.ä.) | der rohe Enum-Name wird vorgelesen (**rötet heute**) |
| 7 | **Erwartetes ist nicht amber:** `MIGRATION_WINDOW` rendert **ohne** `▲`/WARN-Farbe | der planmäßige Migrationsfall wird als Warnung gezeichnet |

**Zahn 1 rötet gegen den heutigen Code — er ist ein Regressionstest auf den Befund aus §1**, kein
theoretischer Fall.

---

## 7. Offene Punkte

1. **§6.4 (Auftraggeber)** — unverändert. Berührt hier nur „needed" (§3.3).
2. **Tag-Benennung** `INVENTORY_UNAVAILABLE` — geteilte QA-API, Entscheid über den PO (§5).
3. **⟂CYP-720** — `migration_state_readonly_legacy` + `migration_legacy_action` hängen am Reason in der
   Kette. Ohne ihn bleibt nur `.readonly.unknown` (ehrlich, aber ohne Handlungsangabe).
4. **⟂Backend:** liefert `api.stores` bei Teilerfolg (Stores ja, History nein) heute stillschweigend
   `history=null` — **derselbe Fingerabdruck**, eine Ebene daneben. Sollte die History-Sektion einen
   eigenen `UNAVAILABLE` bekommen, oder reicht `HistoryAvailability`? **Frage an Backend/PO**, von mir
   nicht entschieden.

---

## 8. Self-Validation

- **Befund am gebauten Code verifiziert**, nicht am Papier: `MigrationViewModel.load()` (`getOrNull()`
  zweifach) + `MigrationSection` (`if (stores != null)`, beide `isNotEmpty()`-Zweige) — die drei Fälle
  in §1.2 sind aus diesen Zeilen abgeleitet, nicht vermutet.
- **Beide Prüf-Fragen der ratifizierten Regel angewandt und beide feuern** (§1.3) — inkl. des
  Nebenbefunds, dass Spinner/Severity importiert, aber ungenutzt sind.
- **Prämissen-Korrektur gemeldet statt übergangen:** der Bau existiert bereits (§0); der Auftrag
  „der Bau wartet" stimmt nicht mehr. Das ändert die Art des Passes und musste vorne stehen.
- **Reuse-first, und der stärkste Beleg kam beim Prüfen:** `LoadErrorRetry` ist nicht nur vorhanden,
  sondern an vier Stellen produktiv (`AgentSettingsPanel.kt:259`, `AgentManagementPanel.kt:169`,
  `CommPanel.kt:151,232`), und `AgentSettingsPanel.kt:252` schreibt die Doktrin **„failed ≠ empty"**
  (CYP-288) wörtlich aus. Der Befund ist damit **kein neuer Entwurf, sondern eine nicht geerbte
  Haus-Regel** — das senkt Aufwand und Risiko des Fixes erheblich und war ohne die Prüfung nicht sichtbar.
  Ebenso reused: `TonedHint`/`HintTone` · die bestehende `storeState`-Qualifier-Bahn. **Neu nur Achse A.**
- **Achsen statt flacher Liste:** die vorgeschlagenen 6 Zustände sind vollständig abgebildet (§3), ohne
  unmögliche Kombinationen zu erzeugen; 4 der 6 existierten bereits und werden **nicht** neu gebaut.
- **Grenze gehalten:** §3.3 bricht bei „needed" ab und gibt an den Auftraggeber ab; „done" wird
  ausdrücklich auf Store-Ebene begrenzt, damit keine Umfangs-Aussage entsteht.
- **Kollisionsrisiko selbst gefunden und markiert** (§5, `UNAVAILABLE` doppeldeutig) statt es in den Bau
  laufen zu lassen.
- **Jeder Zahn hat eine rot-Bedingung**; Zahn 1 rötet gegen den heutigen Stand.
