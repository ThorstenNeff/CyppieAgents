# Multi-User: MEMBER-vs-OPERATOR-Erfahrung — UX/UI-Spec (CYP-80 / S18)

> Owner: UIUX-Designer · Story **CYP-80** (S18 — Multi-User, aktiviert) · Stand 2026-07-02
> Status: **FINAL (2026-07-02) — alle Entscheidungen gefolded:** Access-Modell = C (Hybrid); MEMBER = read-only-observer (B); Backends **BE1-Read-Ceiling-Matrix** in §4/§5 geerdet (Comm/Config-masked/Event-Log ja · Terminals/Projekt-Switch nein). Nur zwei **forward**-Items bleiben (kein CYP-80-Scope): Promote/Nachfolge (§-Ask 3), Terminal-Read-mit-Redaction (§-Ask 5). Docs-only, Referenz — Dev baut die Client-Slice dagegen. **PO merged, nicht selbst mergen.**
> Grounded gegen **develop `8cf393a`** (Operator-Gate + Auth-Session real verifiziert, §8). Kein neuer Login/Register-Flow — der End-User-Auth-Bogen (CYP-176/177/185) steht.
> Begleit-Artefakte: `member-operator-tokens.json`, `-keys.md`, `-tags.md`.
> Kontext: mehrere verifizierte Nutzer teilen einen Workspace. **Erster verifizierter = OPERATOR, alle weiteren = MEMBER** (Backend-bestimmt). Diese Spec entwirft **nur** die Post-Login-Tier-Differenzierung der bestehenden Desktop-Oberfläche.

---

## 0. Was hier (nicht) entworfen wird

**Im Scope (docs-only):**
1. **MEMBER vs OPERATOR am Haupt-Screen** (§4): welche Mutations-Flächen ein MEMBER **weg/read-only/disabled** sieht.
2. **Rollen-Sichtbarkeit** (§3): eigener Rollen-Indikator + Operator-Identität; operator-only Mitglieder-Roster.
3. **MEMBER-Read/Participate-Umfang** (§5): Event-Log/Comm/Terminals — abgestimmt auf Backends Capability-Matrix.
4. **testTags** für die tier-abhängigen Controls (§tags) + Enumerations-/Security-Nähte (§3.3).

**Außerhalb des Scope:**
- **Kein neuer Login/Register/Verify-Flow** — steht (CYP-176/177/185).
- **Volle Mandanten-/Multi-Tenancy** (mehrere Workspaces, Team-Abrechnung, Einladungen) — breiterer deferred Rest.
- **Das Access-Permission-**Modell** selbst** (Rolle ersetzt Operator-Token vs koexistiert vs hybrid) — **Backend rahmt (A/B/C + Security), finaler Call beim Auftraggeber.** Diese Spec baut **nicht darauf blockiert** (§6 markiert das Bedingte).
- **Operator-Nachfolge / Promote-Demote** (was, wenn der OPERATOR geht?) — §-Ask 3 (forward, nur falls Backends Matrix es aufnimmt).

---

## 1. Leitprinzip: MEMBER = die schon gebaute `editable=false`-Erfahrung, dauerhaft + ehrlich benannt

Der zentrale Befund (gegen `8cf393a` verifiziert): **jede operator-gatete Fläche liest heute genau EINEN Boolean.**
`editable = cfg.operatorToken != null` (bzw. `canControl` für Lifecycle) fließt in **jedes** Mutations-Panel. MEMBER
vs OPERATOR ist damit **kein neues Gating-Mechanismus, sondern das Umlegen der Quelle dieses Booleans** — von „Operator-
Token vorhanden" auf „meine Rolle == OPERATOR". Alles Downstream bleibt.

**Drei Gate-Muster existieren bereits — ich staffle MEMBER/OPERATOR genau darauf** (PO-gebucht 2026-07-02):

| Muster | schon gebaut bei | MEMBER-Anwendung |
|---|---|---|
| **Strukturelle Auslassung** (Fläche gar nicht angeboten) | Event-Log-Fenster (nur mit Operator-Token) | ganze operator-only *Flächen* (Settings/API-Key, ACL-Config, Agent-CRUD, Roster) |
| **Disabled + GATED-Hinweis** (`enabled=editable` + `TonedHint(GATED)`) | Settings, Agent-Mgmt, Projekt-Mgmt, Lifecycle | *inline* Mutations-Affordanzen in geteilten Flächen (z. B. Lifecycle an einem sichtbaren Agenten) |
| **Read-only-Ersatz** (interaktiv → statisch) | ACL-Switch→Text (`acl_partial_view`), Comm-Composer→`comm_readonly_hint` | geteilte Flächen, die ein MEMBER lesen darf, aber nicht mutieren |

**„Alles hidden" ist verworfen** (mit PO): es bräche das bestehende `editable`/GATED-Muster und die Entdeckbarkeit/
Ehrlichkeit. Stattdessen **granularitäts-gestaffelt** (§4) + ein **persistenter Rollen-Indikator** (§3), damit ein MEMBER
**immer weiß**, dass/warum etwas gated ist.

**Achsen-Trennung (verbindlich, wie Identität≠Provider≠Connector≠Fidelity):** die **User-Tier** (OPERATOR/MEMBER, NEU) ist
**nicht** die **Agent-Rolle** (`agent_role_po`/`_worker`/`_product_lead`). Ein Agent ist „PO/Worker/Product-Lead"; ein
**Mensch** ist „Operator/Mitglied". Die UI vermischt beide nie (eigene Keys, eigene Area `workspace`).

---

## 2. Tier-Modell & Session-Naht (die halbe Spec, unbedingt)

**Backend bestimmt die Tier** (erster verifizierter = OPERATOR). Die **Naht existiert schon**: `HttpAuthRepository`
liest `{authenticated, role?, verified}` (PO 2026-07-01) → `SessionState`. Heute ist `SessionState.Verified` ein
leeres `data object`; die Tier reitet auf dem bereits vorgesehenen **`role?`**-Feld.

**Design-Form (Wire offen, Fold später — wie CYP-119→120):**
- `SessionState.Verified(tier: UserTier)` mit `enum UserTier { OPERATOR, MEMBER }` (Feldname `role`/`tier` = Backend-Fold).
- Downstream: `editable`/`canControl` werden aus der Tier getrieben statt aus `operatorToken != null` — **eine** Ableitung,
  an **einer** Stelle (`AgentShell`-VM-Konstruktion, §8.1): `val isOperator = session.tier == OPERATOR` → in **alle**
  bestehenden `editable = …`-Zeilen. **Kein Panel ändert sich** — nur die Quelle des Booleans.
- **Fail-closed:** unbekannte/fehlende Tier ⇒ **MEMBER** (die restriktivere Tier), nie OPERATOR. Ein Session-Fehler
  gewährt **nie** Operator-Rechte (analog Auth fail-closed → Unauthenticated).

> **Wie sich `editable` speist, hängt am Access-Modell (A/B/C, §6)** — aber **dass** MEMBER = `editable=false` und
> OPERATOR = `editable=true` ist, ist unbedingt. Diese Spec baut die Tier-Erfahrung fertig; nur das Schicksal etwaiger
> Token-Affordanzen bleibt bedingt.

---

## 3. Rollen-Sichtbarkeit (unbedingt)

### 3.1 Persistenter Rollen-Indikator (jeder Nutzer sieht seine eigene Rolle)

Ein **neutraler Rollen-Indikator** in der **Top-Leiste** (im/neben dem bestehenden `ProjectSwitcherBar`, der ohnehin
über dem `WindowHost` immer sichtbar ist — CYP-176-Mount): 
- OPERATOR: „Du bist Operator" (`workspace_role_indicator_operator`), Tag `workspace.roleIndicator`.
- MEMBER: „Du bist Mitglied · Operator: %1$s" (`workspace_role_indicator_member` + `workspace_operator_is`), Tags
  `workspace.roleIndicator` + `workspace.operatorName`.

Stil = **neutrales Identitäts-Label** (Reuse des ProjectSwitcher-Aktiv-Marker-Musters: `●`-Form-Marker + Label,
`onSurfaceVariant`/`titleSmall`) — **kein** Hue/Badge/Severity, **kein** „Admin"-Prestige. Farbe nie alleiniger Träger:
die Rolle steht als **Text** (WCAG 1.4.1). `a11y_workspace_role` als contentDescription.

**Ehrlichkeit:** der Indikator ist die ehrliche Erklärung, **warum** Controls gated sind — ein MEMBER rätselt nie, ob
etwas kaputt ist. Er nennt den **Operator** (damit der MEMBER weiß, an wen er sich wenden muss), aber **nur** dessen
Anzeigenamen — kein E-Mail-/Kontakt-Leak über das Nötige hinaus (§3.3).

### 3.2 Mitglieder-Roster (nur OPERATOR)

Der OPERATOR sieht **wer sonst im Workspace ist + deren Tier**: ein **operator-only** „Mitglieder"-Surface
(`workspace_members_title`, Tag `workspace.members`) — je Zeile Anzeigename + Tier-Label (`workspace_tier_operator`/
`workspace_tier_member`), eigener Eintrag markiert (`workspace_member_you`). Tags `workspace.member.<id>` +
`workspace.member.<id>.role`. **Angeboten wie die Event-Log-Fenster** (strukturelle Auslassung für MEMBER, kein totes
„kein Zugriff"-Fenster).

**Promote/Demote = NICHT in dieser Spec** (§-Ask 3): falls Backends Matrix Operator-Übertragung/Beförderung aufnimmt,
kämen `workspace.member.<id>.promote`/`.demote` als disabled+GATED-Controls dazu (forward-prep, hier nicht materialisiert).

### 3.3 Enumerations-/Security-Naht (mein Kern-Mandat)

- **Ein MEMBER enumeriert den Roster NICHT.** Er sieht seine **eigene** Rolle + den **einen** Operator-Namen — **nicht**
  die volle Mitgliederliste, nicht andere Mitglieder-Identitäten/-E-Mails. Der Roster ist operator-only (strukturell
  ausgelassen, nicht nur disabled — sonst leakt die Existenz/Anzahl an Mitgliedern).
- **Keine Rolleninfo leaken, die ein MEMBER nicht sehen soll:** das Backend liefert einem MEMBER-Client **gar nicht**
  erst den Roster (Naht-Bedingung an Backend — die UI-Auslassung ist die halbe Miete; die API darf den Roster einem
  MEMBER-Token nicht herausgeben, analog der Enumeration-Sicherheit aus CYP-176 §7).
- **Operator-Name ≠ Kontakt-Dump:** nur Anzeigename, kein E-Mail/kein Token/kein Endpoint.
- **Fail-closed:** kommt keine Tier an ⇒ MEMBER-Sicht (kein Roster, keine Mutation).

### 3.4 Secret-Naht: MEMBER-Config-Read ist masked-only (Reveal operator-only)

**Verbindlich (BE1 + Test-Guardrails i/iv, PO 2026-07-02):** ein MEMBER darf Config **lesen**, sieht aber **nur die
maskierten last-4**, **nie den Rohschlüssel**. Der **Reveal/Un-Mask-Toggle ist OPERATOR-only** (hängt schon an `editable`
→ unter C ist `editable=false` für MEMBER ⇒ Toggle disabled) — und **kein Endpoint** gibt einem MEMBER-Token den Raw-Key
(Backend). **Explizit verankert, damit „read-only Config" nicht versehentlich den Reveal freigibt:** die read-only-
Config-Ansicht rendert `API_KEY_MASKED` (last-4) + den `workspace_operator_only`-Hinweis, **nie** das entmaskierte Feld.
Das ist die eine Stelle, wo „darf lesen" **nicht** „darf alles lesen" heißt — Secret bleibt hinter der Operator-Grenze.

---

## 4. Per-Surface-Tier-Matrix (der Kern — unbedingt, reused)

Jede heute operator-gatete Fläche (verifiziert §8) → OPERATOR- vs MEMBER-Verhalten, **auf dem bestehenden Muster**:

Jede heute operator-gatete Fläche → OPERATOR- vs MEMBER-Verhalten, gefolded gegen **Backends BE1-Read-Ceiling-Matrix
(2026-07-02)**:

| Fläche (Datei) | OPERATOR | MEMBER (read-only-observer) | Muster / BE1-Mechanismus |
|---|---|---|---|
| **Settings/Config** — Repo + API-Key (`SettingsPanel`) | voll | **read-only, maskiert** — `{set,masked}`-GET (last-4); **Reveal/Raw = OPERATOR-only** (§3.4) | read-only-Ersatz + `API_KEY_MASKED` (schon gebaut) |
| **Agent-Verwaltung** CRUD (`AgentManagementPanel`) | voll | **CRUD-Panel nicht angeboten** | strukturelle Auslassung |
| **ACL-Matrix** (`AclPanel`) | Switches | **read-only-Ersatz** (Switch→„gewährt/verweigert"-Text), Partial-View-Banner | `acl_partial_view` (schon gebaut) |
| **Projekt-Verwaltung** CRUD (`ProjectManagementPanel`) | voll | **nicht angeboten** | strukturelle Auslassung |
| **Projekt-Sicht/Switcher** (`ProjectSwitcherBar`) | ja | **NEIN — nicht angeboten** (fail-closed, BE1: switch = globale State-Mutation; Projekt-Liste leakt Tenant-Struktur) | strukturelle Auslassung |
| **Cross-Projekt-Autorisierung** (`CrossProjectControls`) | voll | **nicht angeboten** | conditional render (schon gebaut) |
| **Agent-Lifecycle** Start/Stop/Restart (`AgentWindow`) | voll (`canControl`) | **nicht member-facing** — die Agent-Stream-Fenster (wo Lifecycle sitzt) sind für MEMBER nicht angeboten (↓ Terminals) | strukturelle Auslassung |
| **Agentenfenster/Terminal/Stream** (`AgentWindow`/AgentView) | Stream lesen + „Nachricht an Agent" | **NEIN — nicht angeboten** (fail-closed, BE1: Agent-stdout NICHT secret-free garantiert / kann Keys echoen, keine Redaction) | strukturelle Auslassung |
| **Event-Log** Browse + Live-Tail (`AgentShell`) | angeboten | **read-only angeboten** (BE1: metadata-only, nie Bodies/Secrets by construction → secret-free) — MEMBERs Fenster auf Agenten-Aktivität | strukturelle Auslassung → read-only aufgehoben |
| **Comm** (`CommPanel`) | schreiben (per ACL) | **Timeline read-only** (ACL-`canRead`-gefiltert nach eigener Identität, **fail-closed leer by default**, OPERATOR grantet per-Channel — nie blanket); **Senden nein** | read-only-Ersatz (`comm_readonly_hint`) |
| **Mitglieder-Roster** (NEU, §3.2) | sieht Roster | **nicht angeboten** (Enumerations-Naht) | strukturelle Auslassung |

> **MEMBERs Beobachtungs-Fläche ist damit: Comm (read, ACL-gefiltert) + Event-Log (metadata, secret-free) + Config
> (maskiert).** Agenten-Aktivität beobachtet ein MEMBER über das **secret-free Event-Log**, **nicht** über den rohen
> Agent-stdout — das ist die bewusste, ehrliche Grenze (Raw-Stream kann Secrets echoen; erst mit Redaction-Garantie
> revisiten, §9 §-Ask 5).

**Der GATED-Hinweis (disabled-Fälle) wird rollen-ehrlich** (§6-bedingt für den Wortlaut): heute „nur mit Operator-Token
änderbar"; im Rollen-Modell **„Nur der Operator kann das ändern"** (`workspace_operator_only`) — sonst suggeriert der
Text einem MEMBER, er müsse „nur einen Token eingeben".

---

## 5. MEMBER-Read/Participate-Umfang — FINAL (Backends BE1-Read-Ceiling-Matrix gefolded, 2026-07-02)

MEMBER = **read-only-observer**: **kein Mutieren, kein Senden/Teilnehmen** — nur Lesen; die zulässigen Lese-Flächen sind
durch Backends **BE1-Matrix** autoritativ gesetzt (Read-Ceiling geerdet):

| Fähigkeit | MEMBER (read-only-observer) | BE1-Mechanismus |
|---|---|---|
| **Comm-Timeline lesen** | **ja** | ACL-`canRead`-gefiltert nach eigener Identität, **fail-closed leer by default**, OPERATOR grantet per-Channel — nie blanket |
| **Comm senden** | **nein** | `comm_readonly_hint` (read-only-Ersatz) |
| **Config lesen** | **ja, masked-only** | `{set,masked}`-GET (last-4); **Reveal/Raw = OPERATOR-only**, kein Endpoint gibt MEMBER den Rohschlüssel (§3.4) |
| **Event-Log lesen** | **ja** | metadata-only (nie Bodies/Secrets by construction) → secret-free |
| **Agenten-Stream / Terminal lesen** | **NEIN (fail-closed)** | Agent-stdout nicht secret-free garantiert (kann Keys echoen), keine Redaction → keine Raw-Streams; Revisit **nur** mit Redaction-Garantie (§-Ask 5) |
| **„Nachricht an Agent" senden** | **nein** | Observer; Stream-Fenster ohnehin nicht angeboten |
| **Projekt-Sicht / -Switch** | **NEIN (fail-closed)** | switch = globale State-Mutation (OPERATOR); Projekt-Liste leakt Tenant-Struktur |
| **Jegliche Mutation** (CRUD/ACL/Config-Write/Lifecycle) | **nein** (OPERATOR) | §4 |

**Naht:** wo Senden/Teilnehmen/Read entfällt, rendert die UI das **vorhandene** read-only-Muster (`comm_readonly_hint`
bzw. `workspace_operator_only`) oder lässt die Fläche strukturell aus — **kein neues Surface**. **Fail-closed:** nicht
explizit gewährte Lese-Fläche ⇒ **nicht** angeboten. Das Read-Ceiling ist damit **geerdet**, keine offene Zeile mehr.

---

## 6. Access-Modell — ENTSCHIEDEN: C (Hybrid), 2026-07-02 (Auftraggeber)

**Auftraggeber-Entscheid: Option C (Hybrid)** — **Rolle ist Default, Operator-Token ist Break-Glass/Override.** Dank des
Befunds (kein Token-Eingabefeld in der UI) ist das client-seitig **ein Boolean-Quellen-Tausch + Wortlaut, kein Umbau**:
`editable = (tier==OPERATOR) || operatorTokenPresent` (Token als Break-Glass), und die 5 GATED-Hinweise werden für den
**MEMBER** rollen-ehrlich (`workspace_operator_only` „Nur der Operator kann das ändern") statt token-zentrisch. Der
Token-Pfad bleibt als operator-/bootstrap-seitiges Break-Glass bestehen, ist aber **nicht member-facing** (ein MEMBER
sieht nie „Token eingeben"). `workspace_operator_only` ist damit **nicht mehr bedingt** — es ist der member-facing
Standard-Hinweis (keys.md aktualisiert).

**Historie (Optionen, die zur Wahl standen):**

- **Befund (`8cf393a`):** es gibt **kein Operator-Token-Eingabefeld in der UI** — der Token wird via Env/Runtime
  injiziert (`ShellConfig`). Der sichtbare token-zentrierte Rest ist der **Wortlaut** der GATED-Hinweise („…mit Operator-
  Token änderbar", 5 Keys: `settings_operator_required`, `agent_mgmt_operator_required`, `project_mgmt_operator_required`,
  `crossproject_operator_required`, `acl_operator_required`).
- **Option A — Rolle ersetzt Token:** `editable = tier==OPERATOR`; die 5 GATED-Wortlaute werden **rollen-ehrlich**
  umformuliert („Nur der Operator kann das ändern", `workspace_operator_only`). Token-Pfad höchstens als Bootstrap
  (erster Operator), nicht mehr UI-benannt. **(Sauberster MEMBER-Read; meine Präferenz.)**
- **Option B — Token koexistiert:** Tier **und** Token gewähren `editable` (OR). Dann müssen **beide** Wortlaute
  kontextabhängig existieren (Rolle *und* Token) — mehr Erklärungslast.
- **✅ Option C — Hybrid (GEWÄHLT):** Rolle ist Default, Token ist Break-Glass/Override. Member-facing Wortlaut = rollen-
  ehrlich (`workspace_operator_only`); Token-Break-Glass bleibt operator-/bootstrap-seitig, nicht member-facing.

**Gefolded (2026-07-02):** §2 (`editable = tier==OPERATOR || operatorTokenPresent`, Token=Break-Glass), §4 GATED-Wortlaut
(member-facing `workspace_operator_only`), keys.md (`workspace_operator_only` von bedingt → unbedingt). Kein `⟨A/B/C⟩`
mehr offen. **Test-Alignment bestätigt (PO):** meine zwei Ehrlichkeits-Anker == Testers Security-Guardrails (Roster
operator-only + MEMBER-sieht-nur-eigene-Rolle; fail-closed unbekannte-Tier⇒MEMBER = Test-Default).

---

## 7. a11y

- **Rollen-Indikator:** Text-Rolle (`heading()` optional), `contentDescription = a11y_workspace_role` („Deine Rolle: %1$s").
- **GATED-Controls:** tragen den bestehenden GATED-`TonedHint` (Glyph `·` + Text, WCAG 1.4.1) — unverändert, nur Wortlaut
  rollen-ehrlich (§6). Disabled-Buttons behalten ihr sichtbares Label (Screenreader liest „deaktiviert").
- **Roster (Operator):** Liste mit `a11y_workspace_members`; je Zeile Name + Tier als Text (nie Farbe allein).
- **Kein neues a11y-Muster nötig** (anders als CYP-176-liveRegion) — Reuse durchgängig.

---

## 8. Reuse (gegen develop `8cf393a` real verifiziert)

### 8.1 Die eine Naht-Änderung
`AgentShell` konstruiert heute jede VM mit `editable = cfg.operatorToken != null` (bzw. `canControl`). **Änderung (Dev,
minimal):** `val isOperator = session.tier == UserTier.OPERATOR` (aus `SessionState.Verified`, das `role?` schon trägt) →
diese eine Ableitung in **alle** `editable = …`/`canControl = …`-Zeilen. **Kein Panel-Code ändert sich.**

### 8.2 Reuse-Tabelle

| Bedarf | Reuse-Quelle (verifiziert `8cf393a`) | Neu? |
|---|---|---|
| Der eine Gate-Boolean | `AgentShell` `editable = operatorToken != null` → `tier==OPERATOR` | Quelle getauscht |
| Session trägt Tier | `AuthRepository.SessionState` + `HttpAuthRepository` `{…, role?, …}` | Feld schon vorgesehen |
| GATED-Hinweis | `ui/TonedHint.kt` `HintTone.GATED` | Reuse (Wortlaut §6) |
| Read-only-Ersatz | `AclPanel` Switch→Text, `CommPanel` Composer→`comm_readonly_hint` | Reuse |
| Strukturelle Auslassung | `AgentShell` Event-Log-Fenster nur mit Operator | Reuse |
| Rollen-Indikator-Stil | `ProjectSwitcherBar` Aktiv-Marker (● + Label, neutral) | Reuse-Muster |
| Disabled-Controls | `enabled = editable`/`canControl` überall | Reuse |
| Roster-Fläche | Fenster-/Panel-Muster (wie Event-Log operator-only) | neue Fläche, altes Muster |

**Netto: kein neues Gating-Mechanismus, kein neuer Ton, kein neues a11y-Muster.** Eine Boolean-Quelle + ein Rollen-
Indikator + ein operator-only Roster.

---

## 9. §-Ask-Resolutions (offen — PO/Backend/Auftraggeber)

- **§-Ask 1 — MEMBER-Capability-Matrix (§5). ✅ RESOLVED (Backends BE1-Matrix gefolded 2026-07-02):** read-only-observer;
  Read-Ceiling = Comm(ACL) + Config(masked) + Event-Log(metadata) **ja**, Terminals + Projekt-Switch **nein**. §4/§5 final.
- **§-Ask 2 — Projekt-Switch & Event-Log für MEMBER. ✅ RESOLVED (BE1):** Event-Log **ja** (metadata, secret-free);
  Projekt-Switch/-Sicht **nein** (fail-closed: switch=Mutation, Liste leakt Tenant-Struktur).
- **§-Ask 3 — Operator-Nachfolge / Promote-Demote (§3.2).** Was, wenn der OPERATOR geht? Übertragbar? Beförderung? **Nicht
  in CYP-80** außer Backends Matrix nimmt es auf → dann liefere ich die `workspace.member.<id>.promote/.demote`-Controls
  (disabled+GATED) als additiven Folge-Slice.
- **§-Ask 4 — Access-Modell A/B/C (§6). ✅ RESOLVED (Auftraggeber 2026-07-02): C (Hybrid)** — Rolle Default + Token
  Break-Glass. Gefolded in §2/§4/§6 + keys.md (`workspace_operator_only` unbedingt). Client = Boolean-Quellen-Tausch +
  Wortlaut, kein Umbau.
- **§-Ask 5 — MEMBER-Terminal-Read mit Redaction (forward, NICHT CYP-80).** Heute fail-closed **nein** (Agent-stdout kann
  Secrets echoen, keine Redaction). Falls je eine **Redaction-Garantie** am Stream-Sink existiert, kann MEMBER-Read der
  Terminals revisitet werden (additiver Folge-Slice) — dann liefere ich die read-only-Stream-Zeile. Bis dahin: Event-Log
  (metadata, secret-free) ist MEMBERs Beobachtungs-Kanal auf Agenten-Aktivität.

---

## 10. Selbst-Validierung & DS-Notizen

- **Keys:** `member-operator-keys.md` — **10 Keys** (8 `workspace_` + 2 `a11y_workspace_`, inkl. `workspace_operator_only`
  **jetzt unbedingt** nach dem C-Entscheid); DE+EN-Parität; 0 Kollision gg. `strings.xml` @ `8cf393a`.
- **Tags:** `member-operator-tags.md` — Area `workspace` NEU, 0 Kollision gg. die bestehenden `*Tags.kt`.
- **Token:** `member-operator-tokens.json` — **0 neue Tokens** (reiner Reuse: neutrales Label + GATED-Ton + read-only-Ersatz).
- **Disclosure-Invarianten (load-bearing, dürfen bei Impl nicht degradieren):** (1) MEMBER enumeriert den Roster nicht
  (operator-only, strukturell ausgelassen); (2) Rollen-Indikator ehrlich (MEMBER weiß Tier + Operator-Name, kein Kontakt-
  Dump); (3) fail-closed: unbekannte Tier ⇒ MEMBER; (4) User-Tier ≠ Agent-Rolle (nie vermischt); (5) kein Panel gaukelt
  einem MEMBER Mutierbarkeit vor (read-only-Ersatz statt toter Switch); (6) **MEMBER liest keine Raw-Agent-Streams/
  Terminals** (stdout nicht redaction-garantiert → fail-closed; Beobachtung via secret-free Event-Log); (7)
  **MEMBER-Config-Read = masked-only, Reveal operator-only** (nie Rohschlüssel, §3.4). Diese prüfe ich in der UX-QA nach Dev-Impl.
- **DS-Notiz:** der Rollen-Indikator ist ein neutrales Identitäts-Label (kein Prestige-Badge) — bewusst anti-hype; falls
  je ein „Rollen-Chip"-DS-Element gewünscht ist, wäre das ein separater Parität-Kandidat, kein CYP-80-Scope.
