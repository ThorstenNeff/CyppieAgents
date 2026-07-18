# CYP-220 — Migrations-Mechanik & Test-Fläche (Design-Pass)

> Owner: UIUX-Designer · Auftrag: PO 2026-07-18 (PL-Vorlauf BYODB) · Basis: `CYP-220-store-inventory.md` ·
> Stand develop `f7916cdf` · **Plan, kein Bau.**
>
> **★ Grenze, verbindlich (PL-Nachschärfung 2026-07-18, enger als CYP-443):**
> **FREI** — Lücke kartieren, Migrations-Mechanik entwerfen, `StoreRouter`-Seam skizzieren, Reihenfolge
> vorschlagen. **NICHT FREI** — die **FORM des Datenmodells** festlegen oder entscheiden, **was BYODB einem
> Kunden verspricht**. Dieses Dokument beantwortet beides **nicht**, auch nicht nebenbei: die Stellen, an
> denen es dorthin liefe, sind als **HALT-Punkte (§6)** markiert und brechen dort ab.

---

## 0. Der Befund, der alles andere umsortiert — und eine Korrektur an mir selbst

**Meine Store-Inventur §4.3 sagte: „`StoreRouter` ist der einzige gemeinsame Seam, durch den jeder geroutete
Store läuft." Das ist falsch. Gemessen:**

```
grep -rln "StoreRouter" server/src --include=*.kt
  → main/…/boot/StoreRouter.kt          (die Klasse selbst)
  → test/…/boot/StoreRouterEnforcementTest.kt   (ihr Test)
```

`StoreRouter` wird **in der Produktion nirgends konstruiert**. Kein `BootOrchestrator`, kein
`PlatformWiring`. Dasselbe gilt für den gesamten Apparat darum herum — `PgStoreRouting`, `MigrationGate`,
alle `MigrationGated*`, `StoreMigrator`, `BindingRegistry`: sie referenzieren **einander und ihre Tests**,
sonst nichts. Einzige produktive Ausnahme außerhalb des Clusters: `AdminDbMetrics`/`AdminMetricsRoutes`
lesen die `DsnRegistry` (`routing/AdminMetricsRoutes.kt`).

**Die richtige Formulierung ist deshalb:**

> Es existiert eine **vollständige, unit-getestete BYODB-Schicht — sie ist nicht montiert.**

Das dreht den Auftrag um. Die Aufgabe ist **nicht**, eine Migrations-Mechanik zu entwerfen — die ist gebaut
und sie ist gut (§2). Die Aufgabe ist, **die vorhandene Naht zu montieren**, die zwei strukturellen Blocker
aufzulösen, und eine Test-Fläche zu bauen, die **Montiertheit** beweist statt Existenz.

Und der Prod-Boot konstruiert `Sqlite*` nicht „an den Interfaces vorbei, obwohl es einen Router gibt" —
er konstruiert sie direkt, **weil kein Router da ist, an dem er vorbeikäme.** Auch das korrigiert die
Härte meiner §4.1-Formulierung: der Befund bleibt (der Prod-Pfad kennt keinen Seam), die Ursache ist eine
andere.

---

## 1. Die Lücke, vollständig kartiert

Vier Schichten, von außen nach innen. Jede ist für sich beweisbar — das ist später die Reihenfolge (§3).

| # | Schicht | Zustand heute | Beleg |
|---|---|---|---|
| **L1** | **Montage** — hält irgendwer einen `StoreRouter`? | **Nein.** Nur die Klasse + ihr Test. | §0 |
| **L2** | **Bindung** — kann ein Operator überhaupt eine DSN/Bindung anlegen? | **Nein.** Es existiert **keine** Admin-Route für Bindings/DSN (nur die lesende Metrics-Route). | `grep "/api/db\|bindings\|/dsn" routing/*.kt` = leer |
| **L3** | **Quell-Seite** — kann aus den heutigen SQLite-Stores kopiert werden? | **Fast nirgends.** 16 `Sqlite*`-Klassen, davon implementiert **eine** `MigrationSource`/`Target` (`SqliteProjectRegistry.kt`). | §2.2 |
| **L4** | **Vererbung** — ist der durable Store vom In-Memory-Store lösbar? | **Ein echter Fall auf dem Prod-Pfad.** | §4 |

**L1 + L2 zusammen ergeben eine für die Montage sehr günstige Eigenschaft:** ohne Bindungen ist der Router
**per Konstruktion inert** — `StoreRouter.kt:18-22` sagt es selbst: leere `bindings` → `activeDataSource`
immer null → jeder Accessor gibt seinen File-Fallback zurück → **null Verhaltensänderung**. Dazu die
Fail-Safe: null `cipher` (kein `CYPPIE_MASTER_KEY`) ⇒ ein Secret-Store wird **nie** in eine Nutzer-DB
geroutet, unabhängig von jeder Bindung.

**Konsequenz für den Plan:** L1 kann **vor** L2 landen und ist dann ein bewiesenes No-op. Genau so soll es
laufen — die Naht montieren, solange sie nichts tut, statt Montage und Scharfschaltung in einem Schritt.

---

## 2. Migrations-Mechanik: was da ist (nicht neu bauen) und was fehlt

### 2.1 Was gebaut ist — und tragfähig aussieht

`db/StoreMigrator.kt:41-88` implementiert genau den Ablauf, den man entwerfen würde:

```
bind(target, MIGRATING)          ← Fenster auf: Schreiben gesperrt, Lesen weiter aus A
  → source.exportRows()          ← Kopie A → B, roh, store-agnostisch
  → target.importRows(rows)
  → verify: Zeilenzahl UND SHA-256-Prüfsumme über die geordneten Zeilen
  → setState(ACTIVE)             ← der EINE atomare Flip, der B live macht
catch → unbind()                 ← Rollback auf A; A wird hier NIE gelöscht
```

Vier Eigenschaften, die ich ausdrücklich **nicht** anfasse, weil sie richtig sind:
- **Verify ist zweifach** (Zahl *und* Prüfsumme) — `StoreMigrator.kt:70-71`, mit der korrekten Begründung
  im Code: „count alone can miss corruption".
- **Prüfsumme ist längen-präfigiert** (`:94`) → Bijektion zur Zeilenliste, keine Verschmelzungs-Kollision.
- **Rollback verwirft die Quelle nie** (`:84`, Decommission ist ein separater expliziter Schritt).
- **Audit ist inhaltsfrei** — Zähler + IDs, keine Werte, kein DSN-Passwort (`:33-34`).

Das Schreibfenster ist ebenfalls gebaut: `boot/MigrationGate.kt` gibt während `MIGRATING` statt des
Live-Stores ein Gate heraus — Lesen geht nach A durch, **jede** Mutation wirft typisiert
`store_migrating` → HTTP 409, fail-closed (`:27-31`). Das ist die richtige Antwort auf die eigentliche
Gefahr (ein Schreibvorgang nach der Kopie ginge sonst still verloren).

> **Design-Urteil: die Mechanik ist nicht der Engpass.** Wer hier neu entwirft, baut Vorhandenes nach.

### 2.2 Was fehlt — und es ist die Quell-Seite

`MigrationSource`/`MigrationTarget` (`StoreMigrator.kt:6-13`) sind der Vertrag der Mechanik. Implementiert
wird er heute fast nur von den **Ziel**-Klassen:

- **13 Implementierer**, davon 10 `Pg*` (Ziele) + `ProjectRegistry` + `SqliteProjectRegistry` + `ProjectAgentStore`.
- **Von 16 `Sqlite*`-Klassen implementiert genau eine** (`SqliteProjectRegistry`) den Vertrag.

**Das heißt: für fast jeden Store gibt es heute kein `exportRows()` auf der Seite, von der man wegzöge.**
Die Mechanik kann A→B kopieren — aber A kann nicht liefern. Das ist die konkrete, benennbare Lücke der
Migrations-Mechanik, und sie ist mechanisch (ein Export-Pfad je Store), nicht konzeptionell.

> ⚠ **Achtung, hier beginnt eine Grenze:** *wie* eine Zeile kanonisch aussieht, ist Kodierungs-Detail des
> jeweiligen Stores (`db/MigrationRowCodec.kt` existiert dafür) — das ist frei. *Welche Entitäten es
> überhaupt gibt und wie sie geschnitten sind*, ist **Datenmodell-Form → §6.**

---

## 3. Montage-Plan: wie ein Store hinter einen austauschbaren Seam kommt

Fünf Schritte. **Jeder ist für sich landbar und für sich beweisbar**; keiner setzt den nächsten voraus.
Reihenfolge ist Vorschlag, nicht Entscheidung.

### M1 — Den Router montieren, solange er nichts tut
`BootOrchestrator`/`PlatformWiring` konstruieren **einen** `StoreRouter` und geben ihn weiter; die heutige
direkte `Sqlite*`-Konstruktion wandert **unverändert** in die `fileFallback`-Lambdas, die der Router ohnehin
verlangt (`StoreRouter.kt:38-60`). Kein Store ändert sein Verhalten, weil ohne Bindung jeder Accessor genau
diesen Fallback zurückgibt.
**Beweis-Ziel:** Verhalten byte-gleich zu vorher (§5, Zahn 1).

### M2 — Consumer vom Handle auf den Accessor umstellen
Der Router ist **per-Op** gedacht, nicht boot-gecacht (`StoreRouter.kt:11-16`): der Consumer hält den
Router und ruft den Accessor **bei jedem Zugriff**, damit ein späterer `MIGRATING`-Flip sofort sichtbar
ist. Wer heute ein Store-Handle im Boot einsammelt und behält, umgeht das Fenster — genau der Bypass, den
der Code als „structurally impossible here" beschreibt, **sobald man ihn benutzt.**
**Beweis-Ziel:** ein nach dem Boot gesetzter `MIGRATING`-Zustand wirkt ohne Neustart (§5, Zahn 2).

### M3 — Quell-Export je Store (die Lücke aus §2.2)
Pro Store ein `MigrationSource.exportRows()` auf der SQLite/File-Seite, kanonisch geordnet (die Prüfsumme
ist ordnungsabhängig). **Store für Store landbar** — jeder Store, der es hat, wird migrierbar, ohne auf die
anderen zu warten.
**Beweis-Ziel:** Round-Trip export→import→export ist prüfsummen-gleich (§5, Zahn 3).

### M4 — Die Bindungs-Verwaltung (L2)
Erst hier wird überhaupt etwas scharf: eine Admin-Fläche, über die ein Operator eine DSN hinterlegt und
einen Store bindet. **Bewusst ans Ende gestellt** — vorher ist jede Fehlbedienung folgenlos, weil nichts
gebunden werden kann.
⚠ Diese Fläche hat eine **UX-Seite** (Zustände `unbound / MIGRATING / ACTIVE / rolled back`, das 409-Retry,
Quittungen). Die spezifiziere ich, **wenn** sie beauftragt wird — sie hängt inhaltlich an §6.

### M5 — Decommission (getrennt, explizit, nie automatisch)
Das Löschen der Quelle A ist im Migrator bewusst **nicht** enthalten (`StoreMigrator.kt:38-39`). Das bleibt
so: ein eigener, ausdrücklicher Schritt. Ein automatisches Aufräumen nähme dem Rollback die Grundlage.

---

## 4. Die In-Memory-Vererbungs-Basis auflösen

Gemessen ist der Fall **kleiner und schärfer**, als meine Inventur ihn zeichnete:

| Klasse | Erbt von | Auf Prod-Pfad? |
|---|---|---|
| `SqliteReportStore` (`report/SqliteReportStore.kt:23`) | `InMemoryReportStore` | **Ja** (`BootOrchestrator.kt:936`) |
| `FileReportStore` (`report/ReportStore.kt:113`) | `InMemoryReportStore` | Fallback |
| `JsonFileMessageStore` (`comm/MessageStore.kt:54`) | `InMemoryMessageStore` | Legacy-Fallback |
| `JsonFileSessionStore` (`connector/SessionStore.kt:82`) | `InMemorySessionStore` | Legacy-Fallback |
| `SqliteMessageStore` (`comm/SqliteMessageStore.kt:21`) | — implementiert `MessageStore` **direkt** | Ja |

**Korrektur an meiner Inventur:** ich schrieb „die durablen Impls erben von den In-Memory-Klassen" als
Regel. Tatsächlich ist `SqliteMessageStore` **sauber** — er implementiert das Interface direkt. Der
belastende Fall auf dem Prod-Pfad ist **`SqliteReportStore`**; die übrigen drei sind Fallback-/Legacy-Pfade.

**Warum es überhaupt stört:** ein Store, dessen durable Variante die In-Memory-Klasse *erweitert*, hat den
In-Memory-Index als **Teil seiner Identität**. Man kann ihn nicht gegen eine Pg-Variante tauschen, ohne
gleichzeitig zu entscheiden, was mit dem geerbten Zustand passiert — und ein `MigrationGated*`-Wrapper, der
nur das Interface kennt, kann diesen Zustand weder sehen noch einfrieren.

**Mechanik zur Auflösung (Komposition statt Vererbung, ohne Verhaltensänderung):**
1. `InMemoryReportStore` bleibt, verliert aber `open` und wird **Feld** statt Basisklasse.
2. `SqliteReportStore` implementiert `ReportStore` **direkt** und **delegiert** an eine private
   In-Memory-Instanz (Kotlin `by`-Delegation, wo die Signatur es hergibt) — dieselbe Cache-plus-Flush-
   Semantik, aber der Cache ist jetzt **Implementierungsdetail statt Vererbungs-Vertrag**.
3. `SqliteMessageStore` ist die **Vorlage im eigenen Haus** — genau diese Form ist dort schon gebaut.

> **Reuse-Argument:** das ist kein neues Muster, sondern die Angleichung des Ausreißers an die im Repo
> bereits vorhandene, saubere Form.

---

## 5. Test-Fläche: Zähne, die **Montiertheit** beweisen, nicht Existenz

Die heutige Test-Lage ist der Kern des Problems: `StoreRouterEnforcementTest`, `MigrationWindowTest`,
`StoreMigratorTest`, die `Pg*Test`s sind **grün** — und die Schicht ist trotzdem nicht montiert. Grüne
Tests über einer nicht montierten Naht sind genau die Art Beweis, die Sicherheit vortäuscht.

**Regel für jeden Zahn unten (Haus-Konvention aus CYP-657): der Zahn assertet das *reale Ergebnis*, nicht
eine Zwischenvariable — und jeder Zahn nennt die Mutation, unter der er rot werden MUSS.** Ein Zahn ohne
rot-Bedingung ist kein Zahn.

| # | Zahn | Assertion (Outcome) | Mutation ⇒ MUSS rot |
|---|---|---|---|
| **1** | **Montage ist ein No-op** | Nach M1: über den Boot-Pfad bezogene Stores sind bei leerer `BindingRegistry` **dieselben Instanztypen** wie vor M1 (der File/SQLite-Fallback), und ein Boot-Smoke liefert identische Ergebnisse. | Ein Accessor liefert bei leerer Registry etwas anderes als den Fallback |
| **2** | **Der Flip wirkt ohne Neustart** | Store beziehen → nach dem Bezug `MIGRATING` setzen → **die nächste Mutation** wirft `store_migrating`/409, ohne Boot. | Ein Consumer cacht das Handle über den Flip hinweg ⇒ Schreiben geht durch ⇒ rot |
| **3** | **Export ist verlustfrei** | Je Store mit M3: `export → import → export` ist **prüfsummen-gleich** (dieselbe Funktion wie `StoreMigrator.checksum`), inkl. leerer Store und Sonderzeichen. | Eine Zeile fällt weg, wird umsortiert oder verliert ein Feld ⇒ Prüfsumme kippt ⇒ rot |
| **4** | **Verify fängt echte Korruption** | Ziel manipulieren, sodass die **Zeilenzahl stimmt, der Inhalt nicht** → Migration bricht ab, Bindung ist **nicht** ACTIVE, Quelle intakt. | Verify prüft nur Zeilenzahl ⇒ grün ⇒ rot |
| **5** | **Rollback verliert nichts** | Migration nach der Kopie fehlschlagen lassen → Store liest weiter aus A, **alle** Zeilen da, Bindung unbound. | Rollback löscht/leert A ⇒ rot |
| **6** | **Secret-Fail-Safe hält** | Ohne `CYPPIE_MASTER_KEY`: ein Secret-Store bleibt File **auch bei aktiver Bindung** auf eine Nutzer-DB. | Ein Secret-Store landet ungeschützt auf Pg ⇒ rot |
| **7** | **Vererbung ist aufgelöst** | Nach §4: `SqliteReportStore` ist **kein** Subtyp von `InMemoryReportStore`, Verhalten (Schreiben→Lesen→Neustart→Lesen) unverändert. | Die Vererbung kehrt zurück ⇒ rot |

**Zahn 1 ist der wichtigste und der ungewöhnlichste:** er beweist, dass eine Änderung **nichts** getan
hat. Ohne ihn ist M1 nicht risikoarm, sondern nur unbeobachtet.

**Lücke, die ich benenne statt sie zu schließen:** Zähne 3–6 brauchen eine echte Postgres-Instanz. Ob die
im CI läuft (Container) oder die Pg-Zähne ein manuelles Gate bleiben, ist eine **Betriebs-Entscheidung**,
die ich nicht treffe — aber ohne sie sind 3–6 papiern. Das gehört auf den Tisch, nicht in eine Fußnote.

---

## 6. HALT-Punkte — hier hört dieses Dokument auf (Auftraggeber-Weiche)

Der Plan oben ist bis M4 **form-neutral**: er verschiebt keine Entität und schneidet keine Tabelle neu.
Ab hier wäre jede weitere Zeile eine Entscheidung, die mir nicht zusteht. Also: **als Fragen.**

### 6.1 ★ `hub_secret` — eine Vertrauensgrenze, keine Speicherfrage

`SecretStore` → **nur** `SqliteSecretStore` (`crypto/SqliteSecretStore.kt:25`), Tabelle `hub_secret`
(`:142`), AEAD-verschlüsselt unter `EnvKeysetMasterKeyCustody`, in `.cyppie/hub-secrets.db`. Inhalt: die
**Privatschlüssel des Hubs**.

Die Mechanik *könnte* das routen — und der Code hat vorsorglich die Fail-Safe eingebaut, dass ohne Master-Key
**nie** in eine Nutzer-DB geroutet wird (`StoreRouter.kt:19-22, 39, 44`). Aber:

> **Gehört der Hub-Privatschlüssel in eine Datenbank, die dem Kunden gehört — auch verschlüsselt?**
> Der Kunde hat dann das Chiffrat und die Backups davon; wir haben den Schlüssel. Das ist keine
> Speicher-Entscheidung, sondern die Frage, **wo die Vertrauensgrenze des Produkts verläuft.**
> **HALT. Auftraggeber.** Ich kartiere nur die Stelle: sie ist der einzige Store, dessen Routing eine
> eigene Fail-Safe im Code hat — jemand hat die Brisanz beim Bau bereits gesehen.

### 6.2 ★ `role_assignments` — wer darf, entschieden in fremder DB

`RoleStore` → `SqliteRoleStore` (`auth/RoleStore.kt:101,119`), kein Pg-Pfad, keine Flyway-Migration. Inhalt:
**wer welche Rolle hat** — die Grundlage jeder Autorisierungsentscheidung.

> **Wenn die Rollentabelle in der Kunden-DB liegt, kann der Kunde-DB-Administrator sich selbst Rollen
> geben — per `UPDATE`, an jeder Applikationsprüfung vorbei.** Ist das (a) in Ordnung, weil es *seine*
> Instanz ist, oder (b) genau die Grenze, die nicht wandern darf? **HALT. Auftraggeber.**

### 6.3 Form-Divergenz (aus der Inventur, unverändert offen)
`registry` (SQLite, 1 Tabelle) vs `project` + `project_active` (Postgres, 2 Tabellen). Ein Migrationspfad
zwischen beiden muss wissen, **welche Form die verbindliche ist** — das ist Datenmodell-Form. **HALT.**

### 6.4 Deckungsumfang = das Versprechen
Heißt „bring your own DB" *alle* 44 Stores, die 10 gerouteten, oder eine benannte Teilmenge? Solange das
offen ist, kann M4 keine ehrliche Oberfläche bekommen: eine Bindungs-UI, die 10 Stores anbietet, während
`messages` und `hub_secret` lokal bleiben, **behauptet mit ihrer bloßen Existenz** eine Vollständigkeit,
die nicht besteht. Das ist der Punkt, an dem die Produktzusage die UX bestimmt — nicht umgekehrt.
**HALT. Auftraggeber.**

### 6.5 Henne/Ei (Kartierung, vermutlich keine Weiche)
`DsnRegistry` + `BindingRegistry` konfigurieren die Kunden-DB und können darum nicht in ihr liegen (JSON,
0600, atomic-move). Das ist vermutlich schlicht so und keine Entscheidung — gehört aber in jede ehrliche
Beschreibung des Deckungsumfangs, sonst liest sich „alles in deiner DB" falsch.

---

## 7. Was dieses Dokument NICHT tut

Keine Ziel-Tabellenform · keine Entscheidung, welche Stores wandern **sollen** · keine Aussage über das
Kundenversprechen · keine Festlegung zu `hub_secret`/`role_assignments` unter BYODB · keine
UI-Spezifikation der Bindungs-Fläche (§M4, erst nach §6.4) · kein Bau.

---

## 8. Self-Validation

- **Zwei Korrekturen an meiner eigenen, bereits gelieferten Inventur** — beide gemessen, beide hier
  benannt statt stillschweigend überschrieben:
  ① `StoreRouter` ist **nicht** der Prod-Seam, sondern **gar nicht montiert** (§0) — meine Inventur §4.3
  war falsch. ② Die Vererbungs-Regel gilt **nicht** pauschal: `SqliteMessageStore` implementiert sauber
  direkt; der Prod-Fall ist `SqliteReportStore` (§4).
- **Jede Bestandsaussage belegt** mit `datei:zeile` oder dem ausgeführten `grep`; die Nicht-Existenz von
  L1/L2 ist durch **leere** Suchergebnisse belegt und als solche ausgewiesen (Abwesenheits-Beweis,
  entsprechend markiert).
- **Mechanik nicht nachgebaut:** §2.1 stellt ausdrücklich fest, dass der `StoreMigrator` tragfähig ist, und
  fasst ihn nicht an. Der Entwurf setzt an der **gemessenen** Lücke an (Quell-Export, §2.2).
- **Jeder Zahn hat eine rot-Bedingung** (§5) und assertet ein Outcome, keine Zwischenvariable.
- **Eine Test-Lücke offen benannt statt kaschiert:** Zähne 3–6 brauchen eine echte Pg-Instanz; die
  CI-Frage ist als Betriebsentscheidung ausgewiesen (§5).
- **Grenze gehalten und an der schärfsten Stelle sichtbar gemacht:** §6.1/§6.2 brechen **mitten im
  Gedanken** ab und geben an den Auftraggeber ab, statt die naheliegende Antwort zu geben. §7 sagt
  ausdrücklich, was nicht getan wurde.
- **Kein Ticket-Anlegen, kein Bau, keine fremde Lane berührt.**
