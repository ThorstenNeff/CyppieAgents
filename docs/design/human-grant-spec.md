# Operator Grant-UI — Human `canWrite` pro Channel (CYP-189)

> Owner: UIUX-Designer · Story **CYP-189** (Human-Send-Ergonomie; Epic: eigenes Kommunikationssystem / ACL) · Stand: 2026-07-02
> Status: **Vorschlag → Referenz für Dev-Impl** · Begleitdateien: `human-grant-keys.md`, `human-grant-tags.md`, `human-grant-tokens.json`
> **Grounded gg. develop `3832bb4`** (echter Code gelesen, nicht nur v0.1-Entwurf): `acl/AclPanel.kt`, `acl/AclViewModel.kt`,
> `acl/AclRepository.kt`, `acl/AclMatrixTags.kt`, `workspace/WorkspaceRosterPanel.kt`, `core/model/WorkspaceMember.kt`,
> `core/model/CommModel.kt` (`AclEntry`), `AgentShell.kt`.
> Backend-Naht bereits gebaut (PO): **CYP-188** Human-Send (`requireCommWriter` + `canWrite` am `postAsAgent`-Chokepoint,
> `from=identityId`, uniform-403) + **Grant-Härtung** (Grant auf Ghost-`channelId` → **404**, `projectId` = server-single-source).

---

## 0. Was diese Story ist — und was nicht

Human-Send **funktioniert schon** (Backend CYP-188): der Operator grantet einem Menschen `canWrite` auf einem **existierenden**
Channel via `PUT /api/acl` → der Mensch darf senden. Heute geht das **nur über die API**. Diese Story gibt dem Operator eine
**ergonomische UI** dafür — **kein neues Channel-Modell, keine neue Route, keine neue Entität**: reine UI über den **bestehenden
ACL-Grant**.

**Kern-Einsicht (gg. `3832bb4` verifiziert):** Die ACL-Matrix modelliert Subjekte als `AclEntry.agentId: String` (arbiträr,
keine Typ-Constraints) — ein Menschen-`identityId` passt **unverändert** in denselben Slot. `PUT /api/acl` nimmt exakt
`AclEntry(channelId, agentId=<identityId>, canRead, canWrite)`. Die Matrix rendert Member-Zellen bereits über `AclCellView`
(Read/Write-`GrantControl`, Pending/Enforced/Conflict-Disclosure). **Menschen sind damit nur ein weiteres Subjekt in der
bestehenden Matrix** — maximaler Reuse, minimale Erfindung.

**In Scope:** die bestehende **ACL-Matrix/Panel** um **Human-Members** (aus dem Roster `GET /api/workspace/members`, BE3a) als
grantbare Subjekte erweitern; per-Channel-`canWrite`-Toggle je `identityId`; klare Unterscheidung **Human vs Agent** und
**granted vs not**; Grant/Revoke-Affordanz; `displayName` wo vorhanden.

**Nicht in Scope (bewusst, PO):**
- **Human/Group-Channels** (eigene Entität) → separater Epic.
- **Proaktives Composer-Disable** auf der Menschen-Seite (#69/optional) — der Composer des Menschen wird **nicht** vorab
  deaktiviert, wenn `canWrite` fehlt; der Send läuft heute in ein Backend-**uniform-403** (CYP-188). Siehe §7 Disclosure-Grenze.
- **Kein neuer Endpoint** — Toggle → `PUT /api/acl` (dieselbe Route wie für Agenten).

---

## 1. Wo es lebt (Reuse, keine neue Fläche)

Ein **einziges** Fenster wird berührt: die bestehende **ACL-Matrix** (`ACL_WINDOW_ID` → `AclPanel(aclVm)`, `AgentShell.kt:485`).
Kein neues Surface, kein neuer WindowHost-Eintrag, keine neue Area.

**Operator-Gate ererbt (single-source):** `AclViewModel(..., editable = isOperator)` mit
`val isOperator = isOperatorAccess(tier, cfg.operatorToken)` (`AgentShell.kt:176`, CYP-186/CYP-80). Die Grant-UI ist damit
**automatisch operator-only** — dieselbe Gate-Quelle, die schon jede Mutation in der Matrix trägt. **Kein neues Gating.**

**Subjekt-Achse (Spalten wide / Subjekt-Zellen narrow) wird erweitert:** heute treibt `state.agents` die Spalten
(`AclPanel.kt:134`). Neu: die Matrix führt **zwei Subjekt-Gruppen** —
1. **Agenten** (bestehend, unverändert: Name + PO-Badge + `◆`-Operator-Marker),
2. **Menschen** (neu: `state.members` aus dem Roster, je `identityId` eine Spalte/Subjekt-Zelle).

Der Client lädt dazu zusätzlich den Roster (`GET /api/workspace/members`, exakt die BE3a-Naht, die `WorkspaceRosterPanel`
schon nutzt) und merged die Menschen in die Subjekt-Achse. Alles andere (Kanal-Zeilen, `AclCellView`, Pending/Enforced-Echo,
Preset, PO-Leitplanke) bleibt **unverändert**.

---

## 2. Subjekt-Gruppen: Human vs Agent (Unterscheidung 1)

Die Matrix gruppiert die Subjekte sichtbar und testbar in zwei Bänder:

| Gruppe | Kopf | Marker je Spalte/Subjekt | Quelle |
|---|---|---|---|
| **Agenten** | `acl_agents_group` „Agenten" | Name (+ `PO`-Badge, + `◆` für `operator`) — **unverändert** | `state.agents` (`GET /api/agents`) |
| **Menschen** | `acl_humans_group` „Menschen" | `memberLabel` + Marker `acl_human` „Mensch" | `state.members` (`GET /api/workspace/members`) |

- **Gruppen-Kopf** trennt die Bänder visuell (Divider/Überschrift), Tag `aclMatrix.agentsGroup` / `aclMatrix.humansGroup`.
- **Label je Mensch** = **Reuse** `memberLabel(m) = displayName?.takeIf{isNotBlank} ?: shortId(identityId.take(8))`
  (identisch zum Roster, `WorkspaceRosterPanel.kt:78`). Heute ist `displayName` null (BE3a pending) → 8-stellige Kurz-ID; nie
  roh/lang, nie E-Mail/Token. Sobald die displayName-Anreicherung landet, zieht das Label automatisch nach.
- **Human-Marker** (`acl_human`) hängt an jeder Menschen-Spalte/-Subjektzeile — **besonders** in der Narrow-Card-Ansicht, wo es
  keine Spaltengruppierung gibt, macht der inline-Marker die Achse pro Zeile klar. **Farbe nie alleiniger Träger** (Text-Marker
  + a11y-Label `a11y_acl_human_subject`, nicht Hue-only).
- **Achsen-Trennung (load-bearing, konsistent mit CYP-80):** „Mensch vs Agent" ist die **Subjekt-Art**, **nicht** die
  User-Tier (`OPERATOR`/`MEMBER`, das ist die Rolle *eines* Menschen) und **nicht** die Agent-Rolle
  (`agent_role_po/worker/product_lead`). Drei verschiedene Achsen, nie vermischt. Der Roster-Tier-Text (Operator/Mitglied)
  taucht in der ACL-Matrix **nicht** auf — hier zählt nur „ist grantbares Subjekt".

---

## 3. Grant-Zelle je (Channel × Mensch) — Reuse von `AclCellView`

Jede Zelle (Kanal-Zeile × Menschen-Spalte) rendert **dieselbe** `AclCellView` wie für Agenten:

- **Operator (`editable`):** zwei `GrantControl`-Switches — **Read** (`canRead`) und **Write** (`canWrite`) — mit Thumb `✓`/`✕`,
  Tags `aclMatrix.cell.<channelId>.<identityId>.read` / `.write`. Toggle → optimistisches Upsert + `pending` + async
  `PUT /api/acl (AclEntry(channelId, identityId, canRead, canWrite))` (identischer Pfad wie Agenten, `AclViewModel.applyToggle`).
- **granted vs not (Unterscheidung 2):** der Switch-Zustand **ist** die Grant/Revoke-Affordanz — an = `acl_granted` „gewährt",
  aus = `acl_denied` „gesperrt". `acl_denied` ist **neutral** (kein Fehler; ein entzogenes Recht ist ein gültiger Zustand).
- **Pending ≠ Enforced (Disclosure, Reuse):** nach dem Toggle zeigt die Zelle `acl_pending` „wird übernommen…"
  (`.pending`); erst nach dem **`AclEvent`-Echo** (Source of Truth, **nicht** schon nach `PUT`-200) wechselt sie auf
  `.enforced`. Bei ausbleibendem Echo greift der bestehende `PENDING_TIMEOUT_MS = 8_000` → `acl_change_failed`. **Die UI
  spiegelt den Hub-Zustand, nicht die Absicht** (AC „UI spiegelt Hub-Zustand" ✓).
- **Read-back = Hub-Mirror:** existierende Menschen-`AclEntry`s (agentId=identityId) aus `GET /api/acl` rendern beim Laden als
  **granted** — die Matrix zeigt den **aktuellen** Grant-Stand, nicht ein leeres Blatt.
- **Write-ohne-Read-Hinweis (Reuse):** `canWrite && !canRead` → `acl_write_only_hint` „Antworten ohne Lesen – ungewöhnlich".
  Für Menschen ist das besonders relevant: ohne `canRead` sendet der Mensch **blind** in einen Kanal, den er nicht sieht
  (Comm-Read = ACL-`canRead` per-Channel, CYP-80-Read-Ceiling). Der bestehende Hinweis deckt das ehrlich ab.

### 3.1 Struktureller Unterschied zur Agenten-Achse (wichtig)

Für **Agenten** rendert die Matrix `„—"` (`NON_MEMBER`, **keine** Toggles), wenn der Agent **nicht** in `Channel.members` ist.
Für **Menschen gilt das NICHT:** der ganze Sinn der Story ist, einem Menschen `canWrite` auf einem Channel zu geben, dessen
`members` er **nicht** ist („Grant auf existierenden Channel"). CYP-188 prüft `canWrite` am Chokepoint, **nicht**
Channel-Membership → ein Menschen-Grant ist ohne Membership-Eintrag gültig.

**→ Menschen-Zellen sind auf JEDER Kanal-Zeile grantbar** (voller Read/Write-Toggle), **ohne** Membership-`NON_MEMBER`-
Suppression. Die Membership-basierte `„—"`-Auslassung ist eine **Agenten-Achsen-Eigenschaft** und gatet Menschen-Grant-Zellen
nicht. (Das ist der einzige strukturelle Unterschied; er ist **required by feature**, nicht optional.)

---

## 4. Enumerations-/Sicherheits-Naht (kritisch, mein Kern)

Der Human-Roster ist **operator-only** (BE3a: `GET /api/workspace/members` → **403 fail-closed** für Nicht-Operator;
`WorkspaceRosterPanel` ist client-seitig nur für OPERATOR gemountet, CYP-186 Roster-Fold). **Die ACL-Matrix darf diesen Roster
nicht durch die Hintertür leaken.**

**Invariante E (Enumeration):** die **Menschen-Subjekt-Gruppe rendert NUR, wenn `editable`/Operator** ist. In der
`partialView`/Nicht-Operator-Sicht (ein MEMBER sieht die Matrix read-only für seine Kanäle) sind die **Menschen-Spalten/-Zellen
strukturell ABWESEND** — nicht disabled, nicht ausgegraut, **gar nicht im Baum** (dieselbe strukturelle Auslassung wie das
operator-only-Roster-Fenster). Sonst würde ein MEMBER über die ACL-Matrix die Menschen-Liste enumerieren, die ihm der Roster
verweigert.

**Doppelte fail-closed-Absicherung (Reuse-Muster):**
1. `state.members` wird nur geladen/gemerged, wenn `editable` (kein Roster-Fetch als Nicht-Operator).
2. Selbst wenn Daten anlägen: die Menschen-Gruppe ist an `editable` konditioniert → MEMBER-Baum enthält die Knoten nie.
3. Backend bleibt Source of Truth (403 auf `GET /api/workspace/members` für MEMBER; 403 auf `PUT /api/acl` für Nicht-Operator).
   Der Client-Guard ist **Defense-in-Depth**, nicht die einzige Grenze.

**Keine Secrets:** Menschen-Label = neutraler `displayName ?? shortId`; keine E-Mail, kein Token, kein Endpoint. `identityId`
nur als Tag-Scope/Wire-Feld, nie roh im sichtbaren Text.

---

## 5. Server-Naht: Ghost-Channel (404) & projectId (single-source)

Backend-Härtung ist gebaut (PO); die UI muss sie **ehrlich** bedienen:

- **Ghost-`channelId` → 404:** togglet der Operator eine Zelle, deren Channel zwischen Laden und `PUT` verschwand, antwortet der
  Server **404**. Der bestehende `AclViewModel` mappt 401/403/409 auf Banner; **404 ist neu**. Behandlung: **Revert** des
  optimistischen Toggles + **ehrlicher, nicht-retrybarer** Hinweis `acl_channel_gone` „Kanal existiert nicht mehr – Ansicht
  aktualisieren" (bewusst **distinct** von `acl_change_failed` „erneut versuchen" — ein Ghost-Channel ist nicht durch Retry
  heilbar, sondern durch Neuladen). Kein „durchgesetzt"-Signal, keine erfundene Zelle.
- **`projectId` = server-single-source:** `AclEntry.projectId` ist additiv/defaulted (`DEFAULT_PROJECT_ID`). Die Grant-UI
  **berechnet/sendet keinen** per-Mensch-`projectId` — der Server scoped autoritativ aus Session/Channel. Der bestehende
  PUT-Pfad (`AclEntry(channelId, agentId, canRead, canWrite)` ohne explizites `projectId`) bleibt unverändert; **keine neue
  projectId-Logik** in der UI. (Reduziert Fläche, verhindert Client-seitiges Tenant-Raten.)

---

## 6. Adaptive / RTL / a11y

- **Wide-Grid:** Menschen-Spalten reihen sich nach den Agenten-Spalten (Reuse `AGENT_COL_WIDTH`), unter dem
  `acl_humans_group`-Kopf. Horizontaler Scroll bei vielen Subjekten ist der bestehende Matrix-Mechanismus.
- **Narrow-Card (`< PANE_COLLAPSE_WIDTH`, CYP-156):** je gewähltem Channel listet die Karte Agenten-Zellen + Menschen-Zellen;
  jede Menschen-Zeile trägt den inline-`acl_human`-Marker (Gruppierung fehlt hier → per-Zeile-Marker load-bearing).
- **RTL:** reiner `Row`/`Column`-Reuse (richtungsneutral); keine gespiegelten Literale neu eingeführt.
- **a11y:** `a11y_acl_human_subject` „Mensch %1$s" macht die Human-Achse für Screen-Reader hörbar (nicht Marker/Farbe-only).
  Zell-a11y reused `a11y_acl_cell` „%1$s in Kanal %2$s: Lesen %3$s, Antworten %4$s" + `a11y_acl_toggle_write`/`_read`
  (%1$s = Menschen-Label). Pending reused `a11y_acl_pending`.

---

## 7. Disclosure-Ehrlichkeit — Grenzen & Invarianten

1. **UI spiegelt Hub-Zustand (nicht Absicht):** Grant/Revoke gilt erst als durchgesetzt nach `AclEvent`-Echo (Reuse
   Pending≠Enforced). Kein Toggle wird schon nach PUT-200 als „aktiv" gelesen.
2. **`acl_denied`/Revoke ist neutral:** Entzug eines Menschen-Grants ist ein gültiger, folgenloser Zustand (kein Fehler-Ton).
   Menschen sind **nicht** PO — es gibt **kein** `poCritical` auf Menschen-Spalten, keine Lockout-Leitplanke; Revoke ist immer
   sicher (entfernt einen nicht-strukturellen Teilnehmer, bricht Hub-and-Spoke nicht).
3. **Ehrliche 404-Grenze:** Ghost-Channel → `acl_channel_gone` (nicht-retrybar), nie stilles „erledigt".
4. **Composer-Grenze (#69, out of scope):** Das Granten/Entziehen von `canWrite` ändert, ob der **Send** des Menschen
   akzeptiert wird (Backend uniform-403, CYP-188). Der **Composer des Menschen wird NICHT proaktiv deaktiviert**, wenn `canWrite`
   fehlt — ein Mensch ohne Grant sieht heute weiter ein Eingabefeld und läuft beim Senden in ein 403. Die **Operator-Grant-UI
   überstellt das nicht**: sie spiegelt **nur** den ACL-Hub-Zustand, nicht das Live-Verhalten des Composers auf der anderen
   Seite. Die Composer-Ehrlichkeit ist **#69** (separates, deferred Item) — hier bewusst nicht impliziert.
5. **Enumeration:** Menschen-Subjekte nur operator-sichtbar (Invariante E, §4) — die Matrix leakt den operator-only-Roster nie
   an MEMBER.
6. **Keine Overstatement:** Der Grant sagt ehrlich, was er ist — `acl_read` „Lesen" / `acl_write` „Antworten" in *diesen*
   Channel. Kein „Vollzugriff", kein Prestige, kein Superlativ (Anti-Hype). Ein Menschen-Grant auf einen Agenten-
   Koordinationskanal ist eine bewusste, operator-kontrollierte Human-in-the-Loop-Fähigkeit, neutral dargestellt.

---

## 8. Counts / Reuse (Selbst-Validierung → siehe Begleitdateien)

- **Neue i18n-Keys: 5** (`acl_agents_group`, `acl_humans_group`, `acl_human`, `acl_channel_gone`, `a11y_acl_human_subject`) —
  DE+EN-Parität, 0 Kollision (`human-grant-keys.md`). Alles andere **Reuse** (`acl_granted/denied/read/write/pending/enforced/
  change_failed/write_only_hint`, `a11y_acl_cell/toggle_*/pending`, `memberLabel`).
- **Neue Tags: 3** (`aclMatrix.agentsGroup`, `aclMatrix.humansGroup`, `colHeader.<identityId>.human`) — Zell-/Toggle-Tags
  **reused** (`aclMatrix.cell.<channelId>.<identityId>.read/.write` + Qualifier); `AclMatrixTags`-Objekt erweitert, kein Rename
  (`human-grant-tags.md`).
- **Neue Tokens: 0** — reiner Reuse (`AGENT_COL_WIDTH`, TonedHint-Töne, neutrale `onSurfaceVariant`/`labelSmall`;
  `human-grant-tokens.json`).
- **Kein neuer Endpoint, kein neues DTO, kein neues Surface, kein neues Gate.**

---

## 9. §-Asks (an PO/Backend — nicht-blockierend, Spec baut auf den Defaults)

1. **Voll-Zelle (Read+Write) für Menschen vs. Write-only** — **Empfehlung: Voll-Zelle (Reuse `AclCellView`).** Begründung:
   `canRead` ist die **Channel-Sichtbarkeits**-Seite des Grants (Comm-Read = ACL-`canRead`, CYP-80); ein Mensch mit `canWrite`
   ohne `canRead` sendet blind (bereits als `acl_write_only_hint` „ungewöhnlich" markiert). Voll-Zelle = maximaler Reuse; eine
   Write-only-Sonderzelle wäre **mehr** Erfindung. Falls der Auftraggeber Write-only will: die Read-Spalte je Menschen-Zelle
   ausblenden (die Story-Kern-`canWrite`-Toggle bleibt). **Default in dieser Spec: Voll-Zelle.**
2. **Grant auf allen Channels (keine Membership-Suppression)** — Bestätigung, dass ein Menschen-`canWrite`-Grant auf einem
   Channel gültig ist, dessen `members` der Mensch **nicht** ist (CYP-188 prüft `canWrite`, nicht Membership → ja). §3.1 baut
   darauf; ein `„—"`-Membership-Gate für Menschen würde das Feature brechen.
3. **404-Copy `acl_channel_gone`** — Bestätigung der ehrlichen, **nicht-retrybaren** Ghost-Channel-Formulierung (distinct von
   `acl_change_failed`). Entspricht der gebauten Grant-Härtung (Grant auf Ghost-`channelId` → 404).
4. **Self/Operator-Zeile** — der Roster listet auch den Operator (die eigene Identität). Ein Self-Grant ist ein No-op (Operator
   hat via Tier/Token ohnehin Zugriff). **Empfehlung: die eigene Operator-Identität aus der grantbaren Menschen-Liste
   auslassen** (kein Self-Toggle) — oder harmlos mitzeigen. Minor; Default in dieser Spec: **eigene Identität auslassen.**
5. **#69 Composer-Ehrlichkeit** — bleibt separates/deferred Item (out of scope, §7.4). Diese Spec impliziert **nicht**, dass
   der Menschen-Composer den Grant live spiegelt; sie ist ausschließlich die **Operator-Grant-Seite**.

---

## 10. Disclosure-Invarianten (Abnahme-Checkliste für die UX-QA nach Dev-Impl)

1. **Operator-only:** Menschen-Subjekte rendern **nur** bei `editable`/Operator; in `partialView` strukturell abwesent
   (Invariante E — kein Roster-Leak an MEMBER).
2. **Human/Agent klar unterscheidbar:** Gruppen-Köpfe `acl_agents_group`/`acl_humans_group` + per-Menschen-Marker `acl_human`
   + a11y `a11y_acl_human_subject`; nie nur über Farbe.
3. **granted/denied neutral:** Switch = Grant/Revoke; `acl_granted`/`acl_denied`; Revoke folgenlos, kein Fehler-Ton, kein
   `poCritical` auf Menschen.
4. **Pending ≠ Enforced:** Grant gilt erst nach `AclEvent`-Echo als durchgesetzt (nicht nach PUT-200); Timeout →
   `acl_change_failed`.
5. **Ghost-Channel ehrlich:** 404 → `acl_channel_gone` (nicht-retrybar, kein stilles „erledigt").
6. **Menschen auf allen Channels grantbar:** keine Membership-`NON_MEMBER`-Suppression für Menschen-Zellen (§3.1).
7. **Keine Secrets / projectId-single-source:** Label = `displayName ?? shortId`; UI sendet keinen `projectId` (Server scoped);
   kein Token/E-Mail/Endpoint sichtbar.
8. **Composer-Grenze:** die Grant-UI impliziert kein Live-Composer-Verhalten auf der Menschen-Seite (#69 separat).

---

*Nur Design/Spec/Verifikation. Keine Implementierung. Key/Tag-Landing mit dem konsumierenden Dev-Slice timen
(Shared-Key-Drift, CYP-7 — PO koordiniert).*
