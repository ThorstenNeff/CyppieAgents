# ACL-Matrix-UI — Kanal × Teilnehmer (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-19** (Epic CYP-2, speist S7) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-28
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/ACL-MATRIX.md`.
> Begleit-Artefakte: `docs/design/acl-matrix-tokens.json`, `docs/design/acl-matrix-keys.md`, `docs/design/acl-matrix-tags.md`.
> **Reuse-First:** baut auf **CYP-14** (Farbcodierung/Identität), **CYP-12** (Status/Disclosure-Wortregeln), **CYP-17** (Pending-/Offline-Muster, `comm_back`) und dem **realen** `AclMatrix`/`CommModel` auf — keine Neuerfindung. **Brand:** CyppieAgents (Anti-Hype). Impl baut Dev (Paar-Ticket **CYP-48**).

Definiert Layout, Zustände und **Disclosure-Honesty** der ACL-Matrix: **wer von wem lesen / wem antworten darf**, zur Laufzeit konfigurierbar, mit dem **durchgesetzten Hub-Zustand** als Wahrheit. Keine Implementierungsvorgabe.

---

## 0. Bezugsrahmen (verifiziert im Code, 2026-06-28)

Alles unten ist gegen develop (`c7f0b24`) gelesen — die Matrix wird gegen einen **stabilen Server-Vertrag** designt.

- **Datenmodell** `core/.../model/CommModel.kt`: `Agent(id,name,role,worktree)`, `Role{PO,WORKER}`, `Channel(id,name,kind,members)`, `ChannelKind{DIRECT,GROUP,HUB}`, **`AclEntry(channelId,agentId,canRead,canWrite)`** — genau **ein** Eintrag pro (Kanal, Agent) (02 §5.3).
- **Entscheidungslogik** `core/.../model/AclMatrix.kt` — die **eine** zentrale Entscheidung, pure Funktion über einen Snapshot:
  - `canRead/canWrite(channelId, agentId)` sind **fail-closed**: **Nicht-Member → false**, **kein Eintrag → false**.
  - **Deny-wins bei Duplikaten:** bei mehreren Einträgen für dieselbe (Kanal,Agent) muss **jeder** das Recht gewähren (`matching.all { … }`).
  - `isMember(channelId, agentId)` ist eine **eigene Achse** neben den Flags.
- **Es gibt KEIN `AclMatrix`-Wire-Typ.** `AclMatrix` ist reine `:core`-Logik. Die Drahtfläche ist:
  - `GET /api/agents` → `List<Agent>`.
  - `GET /api/channels` → `List<Channel>` **ACL-gefiltert** auf den Aufrufer (`readableChannels`); Operator sieht alle.
  - `GET /api/acl?channelId=&agentId=` → `List<AclEntry>`. **Operator: alle Einträge.** Agent: **nur Einträge in Kanälen, in denen er Member ist** (Teilansicht).
  - `PUT /api/acl` → Body **ein** `AclEntry`, gibt ihn zurück. **Operator-only** (sonst 403 `operator_required`, ohne Token 401). Upsert: ersetzt den (Kanal,Agent)-Eintrag.
  - `/ws/comm` → **`AclEvent(entry)`** live — aber **nur an Teilnehmer, die den Kanal lesen dürfen** (`canRead`). Operator liest alle → Operator-UI sieht **alle** `AclEvent`s. Außerdem `ChannelsEvent`.
- **Operator** (`HubState.OPERATOR_ID = "operator"`, 02 §14 / 05 D2): privilegierter Teilnehmer, Member jedes Kanals mit read+write — **kein Bypass**, dieselbe `AclMatrix` greift. `PUT /api/acl` prüft nur `isOperator(token)` (nicht `canRead`) → der Operator kann sich **nicht** aus dem **Editieren** aussperren (kann aber seinen **Live-Feed** blind schalten, §5.7).
- **Fenster-Host:** Die Matrix ist Inhalt **eines** Fensters im Fenster-Manager (CYP-10, `WindowHost`/`content`-Slot). Chrome (Titelleiste/Resize) kommt vom Host.

> ### ⚠ Drei Vertrags-Befunde, die das Design prägen (PO-Entscheide 2026-06-28 eingearbeitet)
> 1. **PO-Aussperr-Durchsetzung = Server (CYP-49), UI = advisory.** Aktuell upsertet `HubState.setAcl` **blind**; `PUT /api/acl` prüft nur Operator-Token. **PO-Entscheid:** Der **Server-Guard wird in CYP-49 gebaut** (fail-closed, mutation-bewiesen) — **das** ist der Durchsetzungspunkt. Die UI-Leitplanke (§6) ist **advisory** (warnen/deaktivieren) und muss den **abgelehnten PUT** des Server-Guards ehrlich behandeln (Rückfall auf Hub-Zustand). Die UI ist **kein** Durchsetzungspunkt.
> 2. **Preset-Reset = N per-Entry-`PUT`s (MVP-Entscheid).** Kein atomares Reset im Vertrag. **PO-Entscheid:** MVP nutzt **N nicht-atomare PUTs** mit **ehrlicher Fortschritts-/Teilausfall-Darstellung** (§7); der atomare Preset-Endpoint ist ein **optionaler Backend-Follow-up (nicht MVP)**, §8.2.
> 3. **Mitgliedschaft ≠ ACL-Flag, kein Membership-Editor im MVP (endorsed).** Eine Zelle ist nur sinnvoll/togglebar, wenn `agentId ∈ channel.members`. Nicht-Member-Zellen sind **N/A** (ein ACL-Flag auf einen Nicht-Member bleibt fail-closed wirkungslos) — ehrlich als „kein Mitglied" zeigen, **nicht** als togglebares Grau (§3, §8.3).

---

## 1. Grundlayout (Matrix Kanal × Agent)

**Zeilen = Kanäle, Spalten = Agenten** (Ticket-Wortlaut „Kanal × Teilnehmer"). Jede Zelle = das Paar (Kanal, Agent) mit **zwei unabhängigen** Schaltern: **R** (`canRead`) und **W** (`canWrite`).

```
                 ▼ Agenten (Spalten, colorSlot-Identitätspunkt + Name)
                 ┌──────────┬──────────┬──────────┬──────────┐
                 │ ● PO     │ ● Front  │ ● Back   │ ◆ Operator│
 ┌───────────────┼──────────┼──────────┼──────────┼──────────┤
 │ ⬡ po-frontend │ R✓ W✓ ⚑ │ R✓ W✓   │   —      │ R✓ W✓    │   ⬡ = ChannelKind-Icon (CYP-14)
 │ ⬡ po-backend  │ R✓ W✓ ⚑ │   —      │ R✓ W✓   │ R✓ W✓    │   ⚑ = PO-kritische Zelle (Leitplanke)
 └───────────────┼──────────┴──────────┴──────────┴──────────┤
                 │  „—" = kein Mitglied (N/A, nicht togglebar) │
                 └────────────────────────────────────────────┘
```

- **Wide (Grid):** volle Matrix; sticky Kopf­zeile (Agenten) + sticky Start-Spalte (Kanäle). Default im Desktop-Fenster.
- **Narrow (Single-Pane, Breakpoint an Panel-, nicht Bildschirmbreite, ~< 600dp Innenbreite):** **pro Kanal eine aufklappbare Karte**, darin je Member-Agent eine Zeile mit R/W. **Zurück** kehrt zur Kanalliste — **Reuse `comm_back`** (CYP-17), kein neuer Key. Begründung: eine echte 2D-Matrix ist auf schmalen Tiles unbedienbar; die Kanal-zentrierte Liste hält dieselbe Semantik.
- **RTL:** start/end statt links/rechts; Kanal-Spalte/Kopf an die Start-Kante gespiegelt; Schalter-Reihenfolge folgt der Leserichtung.

---

## 2. Achsen-Identität (Reuse CYP-14 — Farbe ist nie Berechtigung)

- **Agent-Spaltenkopf:** Identitäts-Punkt `colorSlot(Agent.id)` + Name + **PO-Badge** bei `Role.PO` (`agent_role_po`) + **Operator-Marker** (◆) für `OPERATOR_ID`.
- **Kanal-Zeilenkopf:** `ChannelKind`-Icon (HUB/DIRECT/GROUP — Typ über Icon, nicht Farbe) + `colorSlot(Channel.id)`-Punkt + Name.
- **Kritisch (Disclosure):** Identitäts-/Kanalfarbe markiert **nur Identität**, **nie** ein Recht. Der **R/W-Zustand** wird **nie** allein über Farbe getragen → immer **Schalter-Stellung + Icon + Text-Label** (`acl_read`/`acl_write`, `acl_granted`/`acl_denied`), Farbe sekundär (WCAG 1.4.1).

---

## 3. Zelle — die drei Zustände (verbindlich)

Eine Zelle ist **kein** binärer Toggle. Sie hat drei strukturelle Zustände:

| Zelltyp | Bedingung | Darstellung | Interaktion |
|---|---|---|---|
| **Member-Zelle** | `agentId ∈ channel.members` | zwei Schalter **R** / **W** (aus `AclEntry`), je mit Text-Label | togglebar (nur Operator, §5) |
| **Nicht-Member** | `agentId ∉ channel.members` | „—" + a11y „kein Mitglied" (`acl_non_member`) | **nicht** togglebar (kein Membership-Editor im MVP, §8.3) |
| **Konflikt (deny-wins)** | mehrere Einträge für (Kanal,Agent) | **effektiver** Zustand (alle müssen gewähren) + Warn-Marker (`acl_conflict`) | togglebar; ein `PUT` ersetzt → räumt den Konflikt auf |

- **R und W sind unabhängig.** Das Modell erlaubt `canWrite ohne canRead` (write-only). Die UI **suggeriert keine Kopplung**, zeigt aber bei dieser ungewöhnlichen Stellung einen dezenten Hinweis (`acl_write_only_hint`, advisory), weil „antworten ohne lesen" selten gewollt ist.
- **Effektiver vs. Roh-Zustand:** Da `canRead/canWrite` zusätzlich **Membership** verlangen, ist der angezeigte Zustand der **effektive** (`AclMatrix.canRead/canWrite`). Bei Nicht-Member ist er per Definition false — daher N/A statt „aus".

---

## 4. Sichtbarkeit & Viewer-Identität

- **Operator-Viewer:** sieht die **volle** Matrix (alle Kanäle via `GET /api/channels`, alle Einträge via `GET /api/acl`), darf editieren.
- **Agent-Viewer (oder kein Operator-Token):** **read-only Teilansicht** — `GET /api/acl` liefert nur Einträge der eigenen Kanäle. Das ist **bewusst keine Omission** (anders als das Event-Log-Fenster CYP-41/42, das am Socket fail-closed weggelassen wird): der Vertrag gibt Agenten eine Teilsicht, und CYP-19 fordert explizit „ohne Operator-Token **read-only-Sicht**". → ehrlicher **Teilansicht-Banner** (`acl_partial_view`), keine Vorspiegelung der Vollmatrix.

---

## 5. Editier-Flow & Enforced-vs-Optimistic (das Disclosure-Herzstück)

Die Matrix zeigt **den durchgesetzten Hub-Zustand**, nicht ein lokales Draft. Ein Toggle ist eine **Anfrage**, kein vollzogener Fakt, bis der Server bestätigt.

1. **Toggle** → Zelle geht in **„wird übernommen" (pending)**: gedimmt + Spinner/Text `acl_pending`, Schalter zeigt den **angefragten** Wert, ist aber als **unbestätigt** markiert. **Nicht** als durchgesetzt darstellen.
2. **`PUT /api/acl`** mit dem vollen `AclEntry` (channelId, agentId, canRead, canWrite — beide Flags, der geänderte neu).
3. **Bestätigung = Server-Wahrheit:** der maßgebliche Beweis ist das zurückgespielte **`AclEvent`** auf `/ws/comm` (Source of Truth), nicht nur der 200-Body. Treffen 200 **und** `AclEvent` ein → Zelle wird **enforced** (solide, `acl_enforced`-Marker für a11y).
4. **Fehlerpfade — ehrlich, kein stiller Verlust:**
   - **403 `operator_required`** → revert + `acl_operator_required` („Nur der Operator darf ACL ändern").
   - **401** → revert + `acl_unauthorized`.
   - **Server-Guard-Ablehnung (CYP-49)** → der Server lehnt einen PO-entkoppelnden (oder Operator-selbst-blendenden) `PUT` ab → revert auf Hub-Zustand + `acl_po_protected` („Geschützt: würde den PO aussperren – Änderung abgelehnt"). **Das ist der maßgebliche Schutz**, die UI-Warnung (§6) nur vorgelagert.
   - **Netz/Timeout** → revert auf letzten enforced Wert + `acl_change_failed` („Änderung nicht bestätigt – erneut versuchen"). Nie pending „hängen" lassen.
5. **Live-Fremdänderung:** ein `AclEvent` eines anderen Operators aktualisiert die Zelle **live, ohne Reload** — die Matrix ist ein **Live-Spiegel** des Hub-Zustands. Idempotenz/Letzter-gewinnt über (channelId, agentId) des Eintrags.
6. **Offline/Stale:** bricht `/ws/comm` ab, ist die Matrix **möglicherweise veraltet** → ehrlicher Banner **Reuse `comm_status_offline`** (CYP-17, nennt den Stand-Zeitstempel). Editieren während offline: entweder sperren oder nur mit deutlichem „unbestätigt"-Zustand — **nie** als enforced anzeigen, solange der WS-Spiegel nicht steht.
7. **Operator-Selbst-Blendung (Leitplanke, §6):** Entzieht der Operator **sich selbst** `canRead` auf einem Kanal, hört sein `/ws/comm` auf, diesen Kanal (inkl. dessen `AclEvent`s) zu pushen — der Live-Spiegel erblindet für diese Zeile. (Editieren bleibt möglich, da `PUT` nicht `canRead` prüft.) → eigene Warnung in der Leitplanke.

---

## 6. PO-Aussperr-Leitplanke (UX) — und ihre ehrliche Grenze

**Was „Aussperren" heißt:** Der PO ist die Nabe (Hub-and-Spoke). Wird `canRead` **oder** `canWrite` des **PO** auf einem Spoke `po-<worker>` entzogen, kann der PO diesen Worker nicht mehr lesen/beantworten → die Topologie bricht, der Worker wird über den Hub unerreichbar.

**Leitplanke (UX, mehrstufig):**
- **a) PO-kritische Zellen markiert:** Zellen, in denen der Entzug den PO entkoppeln würde, tragen einen **Leitplanke-Marker** (⚑, `acl_po_critical`) + PO-Badge — strukturell tragend, sichtbar.
- **b) Bestätigung-mit-Konsequenz statt stillem Toggle:** Der Versuch, eine PO-kritische R/W abzuschalten, öffnet einen **Konsequenz-Dialog** (`acl_po_lockout_warning`: „Damit verliert der PO Lese-/Antwortrecht in %1$s — Hub-and-Spoke bricht. Wirklich?") mit Abbrechen als Default.
- **c) Operator-Selbst-Blendung analog:** Entzug der **eigenen** Operator-`canRead` → Warnung `acl_self_blind_warning` („Damit erblindet dein Live-Feed für %1$s; du kannst weiter editieren, siehst aber keine Live-Änderungen mehr.").
- **d) Recovery immer erreichbar:** Das **Preset „Hub-and-Spoke wiederherstellen"** (§7) ist der prominente Notausgang — selbst nach einem Fehlgriff stellt es PO-Read/Write wieder her.

> **⚠ Ehrliche Grenze (Disclosure):** Diese UI-Leitplanke ist **advisory**, **nicht** der Durchsetzungspunkt. Die **echte Durchsetzung** baut **CYP-49** server-seitig (fail-closed): ein PO-entkoppelnder `PUT` wird dort **abgelehnt** (→ §5.4 `acl_po_protected`, Rückfall auf Hub-Zustand). Die UI-Warnung verhindert den Fehlgriff **vorab/komfortabel**; die Garantie liegt am Server. Die UI darf das Aussperren weder als „unmöglich" (vor CYP-49-Merge) noch die UI-Warnung als „den" Schutz darstellen. Zweiter Sicherheitsnetz: das Preset (§7, Recovery).

---

## 7. Preset „Hub-and-Spoke wiederherstellen"

**Kanonischer Soll-Zustand** = exakt was `HubState.hubAndSpoke()` baut: für jeden Spoke `po-<worker>` bekommt **jeder Member** (po, worker, operator) `canRead=true, canWrite=true`.

- **Vorschau vor Anwendung (kein stiller Massen-Change):** Diff anzeigen („diese N Zellen ändern sich: …", `acl_preset_preview`). Erst nach Bestätigung anwenden.
- **Anwendung = N idempotente `PUT /api/acl`** (ein Eintrag je Member-Zelle). **Nicht-atomar** (kein Reset-Endpoint im Vertrag, §0/§8.2).
- **Fortschritt & Teilausfall ehrlich:** Während der Läufe Fortschritt zeigen; Ergebnis **erst nach Bestätigung aller** als „wiederhergestellt" melden:
  - Erfolg → `acl_preset_restored` („Hub-and-Spoke wiederhergestellt").
  - Teilausfall → `acl_preset_partial` („%1$s/%2$s wiederhergestellt — %3$s fehlgeschlagen, erneut versuchen"), die fehlgeschlagenen Zellen markiert. **Nie** „wiederhergestellt" zeigen, solange unvollständig.
- **Scope-Ehrlichkeit:** Das Preset stellt nur **ACL-Flags** wieder her, **nicht** Mitgliedschaft/Kanäle (die sind im MVP nicht editierbar) — daher ist genau der reparierbare Teil abgedeckt. Das ist auch der Beleg aus 03 S7 „nur eine ACL-Belegung".

---

## 8. Zustände & Disclosure-Honesty (verbindlich)

| Zustand | Darstellung | Disclosure-Regel |
|---|---|---|
| **Lädt** | Skeleton der Matrix | nicht als „leer/keine Rechte" zeigen |
| **Keine Kanäle/Agenten** | Empty-State | — |
| **Operator (editierbar)** | Schalter aktiv | nur wenn Operator-Token vorhanden |
| **Read-only (Agent/kein Token)** | **Status-Chips statt Schalter** + `acl_partial_view`-Banner | keine deaktivierten Schalter, die Editierbarkeit vortäuschen; ehrlich Teilansicht |
| **Pending** | gedimmt + `acl_pending`, angefragter Wert „unbestätigt" | **nicht** als durchgesetzt darstellen, bis `AclEvent`/200 |
| **Enforced** | solide + `acl_enforced` (a11y) | spiegelt den realen Hub-Zustand |
| **Offline/Stale** | Banner **`comm_status_offline`** (Reuse) | Matrix **nicht** als aktuell ausgeben, solange WS getrennt |
| **PUT abgelehnt (403/401)** | revert + `acl_operator_required`/`acl_unauthorized` | ehrlich: „nur Operator", nicht still verschlucken |
| **PUT abgelehnt (Server-Guard CYP-49)** | revert auf Hub + `acl_po_protected` | ehrlich: „würde den PO aussperren – abgelehnt"; Server ist der Schutz |
| **Nicht-Member-Zelle** | „—" + `acl_non_member` | kein togglebares Grau; Recht auf Nicht-Member bleibt wirkungslos |
| **Konflikt (Duplikat)** | effektiver (deny-wins) Zustand + `acl_conflict` | nie den „freundlicheren" Einzelwert zeigen |
| **Preset Teilausfall** | `acl_preset_partial` + markierte Fehlzellen | nie „wiederhergestellt" bei Unvollständigkeit |
| **Laufzeit-Token-Entzug** | `aclMatrix.accessRevoked`-Fallback | wenn der Operator-Token zur Laufzeit wegfällt, ehrlich entwerten (nicht stale-editierbar lassen) |

**Kern-Disclosure (die vier Linien):**
1. **Enforced ist Wahrheit:** Der durchgesetzte Hub-Zustand (`AclMatrix` über `AclEvent`) ist maßgeblich; Optimismus ist immer als unbestätigt erkennbar.
2. **Leitplanke ist advisory, Durchsetzung am Server (CYP-49):** Die PO-Aussperr-Verhinderung ist UI-Komfort + Recovery; der **Garant** ist der server-seitige Guard (CYP-49), der den `PUT` ablehnt. Nicht überversprechen.
3. **Recht ≠ Farbe ≠ Mitgliedschaft:** R/W nie nur über Farbe; Identitätsfarbe nie als Recht; Membership als eigene Achse ehrlich (N/A).
4. **Preset ist nicht-atomar:** Teilausfälle sichtbar; „wiederhergestellt" erst, wenn vollständig bestätigt.

---

## 9. Tokens & i18n & testTags

- **Tokens:** überwiegend **Reuse** aus CYP-12 (`state-tokens.json`: granted≈ok, denied/pending-Achtung) + CYP-14 (Identitäts-/Kanalfarben, PO-Badge). Nur **wenige neue** ACL-spezifische Tokens (Schalter granted/denied, pending, PO-kritisch-Marker, Konflikt, read-only-Chip, Preset-Diff) in `docs/design/acl-matrix-tokens.json`.
- **i18n:** `compose.resources`/Underscore — Keys in `docs/design/acl-matrix-keys.md`. **Reuse** `comm_back` (Single-Pane Zurück) und `comm_status_offline` (Stale-Banner) aus CYP-17 — **nicht** dupliziert.
- **testTags:** `docs/design/acl-matrix-tags.md`, Area `aclMatrix` (Test-Contract v0.5 §2; Dev/QA-Vertrag CYP-7).

> **⚠ Shared-Key-Drift:** Keys + Tags landen in `:app:shared` (Resources bzw. `AclMatrixTags`-Objekt) → das konsumierende Modul (CYP-48-Impl + Test-Modul CYP-7) muss **re-syncen**. **Lieferung mit der CYP-48-Umsetzung timen** — vorher nicht blind mergen.

---

## 10. Entscheidungen & verbleibende Dev-Asks (über PO)

**PO-entschieden (2026-06-28) — eingearbeitet:**
1. **Server-Guard gegen PO-Aussperren = CYP-49** (Backend, fail-closed, mutation-bewiesen). Durchsetzungspunkt am Server; UI-Leitplanke advisory + behandelt den abgelehnten PUT (`acl_po_protected`, §5.4/§6).
2. **Preset-Reset = N per-Entry-`PUT`s im MVP** mit ehrlicher Fortschritts-/Teilausfall-Darstellung (§7). **Atomarer Preset-Endpoint** (`POST /api/acl/preset/hub-and-spoke`) = optionaler **Backend-Follow-up, nicht MVP**.
3. **Membership-Editor out-of-scope (endorsed):** kein Endpoint ändert `Channel.members` im MVP → Nicht-Member-Zellen bleiben N/A (§3).

**Verbleibende Dev-Asks:**
4. **Viewer-Identität final** (gleiche Frage wie CYP-17 #2, hier durch das Ticket weitgehend beantwortet: Operator editiert, Agent read-only-Teilansicht §4) — bestätigen, dass die UI den Operator-Token-Pfad nutzt.
5. **`AclEvent` bei Preset-Massenänderung:** N PUTs erzeugen N `AclEvent`s — bestätigen, dass der Client das idempotent (last-wins je (channel,agent)) verarbeitet, kein Flackern. (Mit dem optionalen atomaren Endpoint aus #2 entfiele das.)
6. **Rückgabeform der CYP-49-Ablehnung:** Welcher HTTP-Status + `ApiError.code` signalisiert „PO-protected" (für die ehrliche Unterscheidung von `operator_required`)? Vorschlag: `409`/`403` mit `code = "po_lockout_protected"` → mappt auf `acl_po_protected`. Mit Backend (CYP-49) abstimmen.
