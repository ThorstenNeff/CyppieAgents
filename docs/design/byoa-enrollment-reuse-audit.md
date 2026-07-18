# BYOA Self-Service-Enrollment — Reuse-Audit (Vorarbeit zum Design-Pass)

> Owner: UIUX-Designer · **kein Ticket** (PO-Anti-Idle-Vorarbeit 2026-07-18) · Stand 2026-07-18 ·
> **Design-Pass-Vorarbeit, kein Bau, READ-ONLY** gg. `origin/develop` `4f0a6c93`.
> **Scope (PO-eingegrenzt):** der künftige **EXTERNE BYOA-Fremdnutzer**, **post-M1 (M2)**, kein Zeitdruck.
> In M1 enrollt sich **niemand** selbst — po2 stellt Remote-Tokens für Team-2s **eigene** Agenten out-of-band aus.
> **Zweck dieses Dokuments:** verhindern, dass ein BYOA-Enroll der **dritte** Enroll-Flow wird. Erst
> inventarisieren, was schon existiert, dann komponieren. Der Audit hält unter **jeder** Antwort auf die drei
> offenen Scoping-Fragen (§5).

---

## 0. Verdikt zuerst — die Kosten-Asymmetrie ist gemessen, nicht geraten

> **Flow (a) „Token-in-der-Hand" ist überwiegend KOMPOSITION bereits ausgelieferter Teile.
> Flow (b) „Request → Approve" ist praktisch NET-NEW — client- UND serverseitig.**

Das ist der entscheidende Input für die Scoping-Entscheidung (1)/(2): es ist **kein** „zwei ähnlich teure
Varianten"-Entscheid, sondern ein **Größenordnungs-Unterschied**. Grund: für (b) gibt es **nirgends im Produkt**
einen Pending/Approval-Zustand (§3) — kein Antrags-Record, keine Freigabe-Queue, keine Cross-Actor-Benachrichtigung,
weder in `app/` noch in `server/`.

---

## 1. ★ Die starken Reuse-Anker (schon ausgeliefert, self-service-fähig)

| Anker | Was es heute schon tut | Warum es für BYOA-Enroll trägt |
|---|---|---|
| **`SetPassphrase` + `EnrollPhase`** (CYP-542, `connect/HubConnectUiState.kt:87-93`, `connect/RemoteOperatorAuthSteps.kt`) | Nutzer trifft `DeviceNotEnrolled` → wählt Passphrase (Diceware-1-Klick **oder** getippt) → Flow **verbindet automatisch neu**. **Kein Operator beteiligt.** | ⭐ **Der stärkste Anker:** „Der Nutzer erzeugt sein Geräte-Credential **im Flow**" **ist bereits self-service und ausgeliefert.** Ein BYOA-Enroll erbt diesen Schritt unverändert. |
| **`RevealCodes` + `RecoveryCodesReveal`** (`net/hub/operator/ui/RecoveryCodesReveal.kt:38`, GE2-Ack-Gate) | Einmalige Anzeige des Credentials + **Bestätigungs-Gate** („gespeichert?") | Der „**hier ist dein Zugang — bestätige, dass du ihn verwahrt hast**"-Beat, inkl. Anti-Verlust-Disziplin. 1:1 wiederverwendbar. |
| **`OobFingerprintConfirmScreen`** (`connect/OobFingerprintConfirmScreen.kt:46`, `+ TrustAbortedView:111`) | Gebauter **Approve/Reject-Entscheidungs-Screen** (OOB-Fingerprint) | Als **visuelles** Muster für einen Freigabe-Schritt reuse-fähig. ⚠ **Semantik ist Ein-Akteur** (derselbe Mensch bestätigt seinen eigenen Connect) — trägt **nicht** die Cross-Actor-Logik von (b), nur das Bild. |
| **`OperatorAuthDialog` + Taxonomie** (`net/hub/operator/ui/OperatorAuthDialog.kt:33`, `OperatorAuthTaxonomy.kt:10`) | `Pin/Biometric/Enroll`-Schritte + Fehler-Taxonomie (`WrongPin(attemptsLeft)`, `LockedOut(retryAfter)`, `NeedsEnroll`, `HubRejected`, …) | Fertige, **ehrliche Fehler-Leiter** für den Credential-Teil — nichts neu erfinden. |
| **`AuthUiState.AuthedUnverified`** (`auth/AuthViewModel.kt:216`, `RegisterResult.Pending`) | Der **einzige** gebaute „du hast eingereicht, jetzt warte"-Screen im Produkt (E-Mail-Verifikation) | Falls (2) einen Wartezustand braucht: das ist der **visuelle/tonale** Präzedenzfall. ⚠ Kein Mensch im Loop — nur Mail-Verifikation. |
| **Dormantes `Register` / `RegisterPhase`** (`connect/HubConnectUiState.kt:12`, `HubConnectViewModel.kt:82`) | Vollständig gebaut, **unerreichbar in M1**; Kommentar wörtlich: *„Register/Seq-A stay in the code (unreachable in M1; **retained for BYOA-later**)"* | Dormantes Gerüst, **explizit für BYOA aufgehoben**. ⚠ **Ehrliche Einschränkung:** die ursprüngliche Semantik war **Hub**-Registrierung, nicht Nutzer-Enroll (`hubconnect-screens-copy-delta.md`: *„`register` ist VESTIGIAL — Hubs **self-admitten**; die GUI ist List + Select"*). Ob es auf Nutzer-Enroll passt, ist **zu prüfen**, nicht anzunehmen. |

**Bestehende Spec-Flächen, die der Enroll komponiert** (i18n-Präfix / Tag-Area, nichts davon neu anlegen):
`device-enroll-ux-spec.md` (CYP-525: PIN/Passphrase → PoP → Recovery-Codes → connected · `remote_pop_enroll_*`,
`remote_recovery_*` / `remote.authStep.*`, `remote.recovery.*`) · `CYP-542-B1-operator-uv-design.md`
(`remote_pop_pin_*`, `remote_pop_passphrase_*`) · `remote-trust-recovery-ux-spec.md` (CYP-480: OOB-Trust, Recovery,
Revocation · `remote_connect_trust_*`) · `remote-operator-ux-spec.md` (CYP-429 · `remote_connect_*`,
`remote_trust_*`) · `remote-connect-screen-sb-ux-spec.md` (CYP-482, **„0 net-new", reine Komposition — das
Vorbild für diesen Pass**) · `hubconnect-screens-copy-delta.md` (`hubconnect_*` / `hubConnect.*`).

## 2. ★ Der Credential-Befund: die BYOA-Credential-Klasse EXISTIERT schon — ohne jede UI

- **`ParticipantTokenStore`** (`server/.../auth/ParticipantTokenStore.kt:9`) ist wörtlich *„the
  **participant-scoped token class** (the ratified BYO-machine credential, spec §2.2): a token issued to an
  **EXTERNAL, non-browser** frontend consumer (Go/Godot/CLI)"* — Tier **`READ` by construction**.
- **`POST/GET/DELETE /api/participant-tokens`** (`routing/ParticipantTokenRoutes.kt`) — **`AuthRole.OPERATOR`**-
  gegated; *„the mint is the **ONLY** place a participant credential is issued"*, inkl. Reserved-Subject-
  Kollisionsschutz (`:52`).
- **UI dazu: null.** Kein einziger Verweis auf `/api/participant-tokens` irgendwo in `app/`.

> ⟹ **Die Frage ist wahrscheinlich nicht „welches Credential erfinden wir", sondern „wer darf die bestehende
> Klasse minten, und über welche Fläche".** Das verschiebt den Design-Pass von „neues Credential-Konzept" zu
> „Issuance-Pfad + Fläche für eine **ratifizierte** Klasse" — deutlich kleiner und risikoärmer.

**Heutige Ausgabe (zum Kontrast, alles operator-gegated + manuell):** `POST /api/agents` mintet einen
Per-Agent-Remote-Token, **einmalig** in der Response (`boot/AgentManagement.kt:191`), persistiert in
`RemoteTokenStore` (*„operator-pre-provisioned"*). Die UI **zeigt ihn bewusst nicht** (
`agentmgmt/AgentManagementHttpRepository.kt:67-69`: *„we intentionally do not surface it here"*;
`AgentManagementPanel.kt:358`: *„no field fakes a token input"*). Der Betreiber-Weg ist `curl` →
kopieren → `HUB_TOKEN`-Env (`docs/OPERATOR-remote-agent.md:13-30`, *„Lost the token? Delete + recreate the agent."*).
**Es gibt heute keinen Self-Service-Credential-Pfad — Punkt.**

## 3. ★ Die kritische Abwesenheit: kein Pending/Approval-Zustand, nirgends

Grep über `app/` + `server/` + `core/` nach `pendingEnroll|approvalQueue|awaitingApproval|pending_grant|
requestAccess|joinRequest|enrollRequest` → **null Treffer.** Kein Antrags-Record, keine Freigabe-Queue, keine
Cross-Actor-Benachrichtigung.

Vier Zustände **sehen** wie „pending" aus und sind es **nicht** (alle **Ein-Akteur**):

| Sieht aus wie | Ist in Wahrheit |
|---|---|
| `OobConfirmState.Awaiting(hubId, fingerprint)` + `approve()`/`reject()` (`net/hub/trust/OobFingerprintConfirmer.kt:39-50`) | **Derselbe** Nutzer bestätigt seinen **eigenen** Connect, in-process/suspending — keine Queue, kein zweiter Akteur |
| `EnrollConfirmState.Revealing(codes)` (`connect/EnrollConfirmCoordinator.kt:17`) | Selbst-Ack „hab die Codes gespeichert", **explizit nicht-durabel** (*„Within-flow only (no durable ack, no codes at-rest)"*) |
| `acl_pending` (`acl/AclViewModel.kt:55`, `AclPanel.kt:365`) | **In-flight PUT**-Latenz (optimistisches UI), nicht Freigabe — *„Pending ≠ Enforced (AclEvent-Echo = Source of Truth)"* |
| `AuthUiState.AuthedUnverified` (`auth/AuthViewModel.kt:216`) | **E-Mail**-Verifikation, kein Mensch im Loop |

> ⚑ **Konsequenz für (1)/(2):** wählt der Auftraggeber **(b) Request→Approve**, ist das **net-new in beiden
> Schichten** — Antrags-Record + Operator-Freigabe-Fläche + Cross-Actor-Signal existieren **nicht** und müssten
> serverseitig **erst geschaffen** werden. Reuse gäbe es nur fürs **Bild** (Approve/Reject-Screen) und den **Ton**
> (`AuthedUnverified`), **nicht** für die Zustandslogik. Wählt er **(a) Token-in-der-Hand**, ist der Flow
> überwiegend Komposition von §1 + §2.

## 4. Identitäts-Modell (Randbedingungen, die der Enroll nicht brechen darf)

- **`agentId`** wird **beim Anlegen vom Operator gesetzt** (`boot/AgentManagement.kt:161`), **nie** vom Client zur
  Auth-Zeit (`routing/Auth.kt:23`: `agentFor` ist die **einzige** Lookup-Bahn; *„identity stays `token→agentId`
  (no client-supplied agentId)"*). Ein Self-Service-Enroll darf diese Invariante **nicht** aufweichen.
- **`role`** wird beim Anlegen gesetzt und **nie überschrieben** (CYP-313).
- **Participant-Principal** ist mit `participant:`-Präfix namespaced → **kann nie** mit `agentId`/`operator`
  kollidieren. (Guter Baustein: ein externer BYOA-Nutzer kann als Participant existieren, ohne die Agenten-Identität
  zu berühren.)
- ⚠ **Ehrliche Korrektur an meiner eigenen Fragestellung:** **D2 („Projekt-Token = Team") ist in diesem Repo nicht
  belegt** — nur Querverweise. Im Code überlebt das Konzept als **server-gestempelte `projectId`**
  (`core/.../model/EventModel.kt:37`: *„formerly `teamId`"*; `docs/prd/06-…:174`). **Ich sollte mich beim
  Flow-Entwurf also nicht auf D2 als code-gegroundete Tatsache stützen** — die Team-Zugehörigkeit ist heute
  `projectId`, vom Server gestempelt.
- **`operator-drives-admission`** (zentrale-UI-Admit) steht als *„the **future BYOA ticket**, not MVP"*
  (`controlplane/HubAdmissionClient.kt:124`) — **ungebaut**, aber es ist der benannte Zielpfad. Vermutlich der
  Anschlusspunkt für (b), falls (b) gewählt wird.

## 5. Was dieser Audit für die drei offenen Fragen bedeutet

| Frage | Was der Audit beisteuert (entscheidet sie **nicht**) |
|---|---|
| **(1) Token-in-Hand vs Request→Approve** | **Größenordnungs-Unterschied gemessen** (§0/§3): (a) ≈ Komposition, (b) ≈ net-new in beiden Schichten. Sollte in die Entscheidung einfließen. |
| **(2) Operator im Loop / Pending-Zustand** | Es gibt **keinen** Pending-Zustand zum Wiederverwenden (§3). Falls „ja": net-new Zustandslogik + Queue; Ton/Bild-Präzedenz = `AuthedUnverified` + `OobFingerprintConfirmScreen`. **Honesty-Achse bleibt Pflicht: „enrolled" ≠ „autorisiert".** |
| **(3) Was fällt raus / bestehender Trust-Pfad?** | **Die Credential-Klasse existiert bereits** (`participant-tokens`, ratifiziert, READ-Tier) — **ohne UI** (§2). Und der self-service **Geräte**-Credential-Schritt ist **schon gebaut** (`SetPassphrase`/`EnrollPhase`, §1). ⟹ starker Hinweis, dass (3) **Komposition** ist, nicht net-new — **zu ratifizieren durch Auftraggeber/PL**, nicht von mir. |

## 6. Self-Validation

- **Reuse-first belegt, nicht behauptet:** jeder Anker mit `file:line` + Zitat gegen `origin/develop 4f0a6c93`;
  die „0 net-new"-Komposition (CYP-482 S-B) ist als Vorbild benannt.
- **Absenzen explizit als Befund geführt** (kein Pending/Approval-Zustand; keine UI für `participant-tokens`; kein
  Self-Service-Credential-Pfad) — Abwesenheit ist hier die entscheidungsrelevante Information.
- **Ein-Akteur vs Cross-Actor sauber getrennt (§3):** die vier „sieht aus wie pending"-Zustände werden **nicht** als
  Reuse-Anker verkauft — genau die Verwechslung, die (b) billig aussehen ließe.
- **Eigene Fragestellung korrigiert (§4):** D2 ist im Repo **nicht** belegt; ich stütze den Entwurf nicht auf eine
  Annahme, die ich selbst eingebracht hatte.
- **Dormantes `Register` ehrlich eingeordnet:** „für BYOA-later aufgehoben" **und** „ursprünglich Hub-Registrierung,
  Passung zu prüfen" — kein voreiliger Anker.
- **Entscheidet nichts:** Trust-/Issuance-Modell bleibt Auftraggeber/PL; dieser Audit liefert nur Kosten- und
  Reuse-Fakten für (1)–(3).
- **Kein Bau, docs-only** auf `docs/byoa-enrollment-reuse-audit`, off develop `4f0a6c93`.
