# First-Run Operator-Setup — UX-Spec (self-hosted Hub)

> Owner: UIUX-Designer · Epic **CYP-623** · Story **CYP-629** · Stand 2026-07-16 ·
> Status: **Vorschlag — wartet auf Dev-Gegenlesen + Backend-Seam-Bau (§7)** · **Design/Spec, kein Code.**
> Gegroundet READ-ONLY gg. develop `2f664e33` (Endpunkte/Reuse) + PO-Code-Befund (`ConfigRoutes.kt:44-64`,
> `BootOrchestrator.kt:305`). Eingefrorene Companion-Files (Haus-Konvention): `first-run-setup-keys.md` ·
> `first-run-setup-tags.md` · `first-run-setup-tokens.json` · `first-run-setup-a11y.md` (a11y/Interaktion).

---

## 0. Auftrag & Leitsatz

Ein frisch installierter self-hosted Hub (Linux-`.deb` live-bewiesen, Windows-`.msi` folgt) shippt **null
Secrets**. Nach dem Install bootet der Hub in einen **„läuft, aber nicht eingerichtet"-Zustand** (kein
Repo, kein API-Key). Der Operator muss durch einen **First-Run-Flow** — dessen UX diese Spec definiert.
Mechanismus + Endpunkte liefert CYP-629 (Dev); **die UX gehört hier her.**

**Leitsatz: Orchestrierung, kein Neubau.** First-Run ist eine **geführte Hülle über den bestehenden
Primitiven** — die `SettingsPanel`-Config (Repo + API-Key) und den `AgentManagementPanel`-Roster. Er
erfindet **keine** parallelen Eingabefelder, sondern **ordnet** die vorhandenen und rahmt sie ehrlich.
Net-new ist nur: das **First-Run-Gate** selbst, die **ehrliche Copy** (at-rest-Posture + degraded-Rahmung),
die **Clone-Status-Zustände**, und der **Backend-Seam** (§7), der heute fehlt.

---

## 1. Platzierung & Gate-Bedingung (PO-bestätigt, Q3)

First-Run ist ein **eigenes Gate in der bestehenden Kette**, direkt vor dem vollen Workspace:

```
AuthGate  →  RemoteHubConnectGate  →  ▶ FirstRunGate ◀  →  Workspace
(Identität)   (Hub-Verbindung)         (diese Spec)         (Fenster/Terminals)
```

- **Sichtbar (Gate greift)** solange der Hub **unkonfiguriert oder teil-konfiguriert** ist:
  `apikey.set == false` **ODER** Repo nicht `CLONED_OK` (§7). Bei **beidem fertig** ist das Gate
  **transparent** — direkt in den Workspace, kein Zwischenschritt.
- **Fail-closed:** solange der Konfig-Status **unbekannt** ist (Load fehlgeschlagen, Feld absent bei altem
  Server) rendert das Gate **nicht** „fertig" und lässt **nicht** stillschweigend durch — es zeigt den
  Lade-/Retry-Zustand (Muster `LoadErrorRetry`, Reuse). Unbekannt ≠ konfiguriert.
- **Überspringbar → ehrlich-degradierter Workspace** (§6). Niemand strandet; der Grundsatz „Hub bleibt
  oben, degraded" (CYP-639) bleibt gewahrt.

**Reuse:** dieselbe Gate-Composable-Naht wie `RemoteHubConnectGate` (ein Gate, das seinen Inhalt erst
freigibt, wenn eine Bedingung erfüllt ist). Kein neues Fenster im WindowManager — First-Run ist eine
**Vollflächen-Gate-Fläche**, kein verschiebbares Fenster.

---

## 2. Schritt 1 — Erkennen & Orientieren (KEIN Token-Schritt) (PO-bestätigt, Q1)

**Der Operator ist beim Erreichen des Gates bereits als Operator authentifiziert — upstream, nicht hier.**
Zwei Einstiegskontexte, beide ohne Token-Eingabe im UI:

| Kontext | Operator-Identität | First-Run Schritt 1 |
|---|---|---|
| **Lokal auf dem Hub-Host** (Desktop-Client) | `OPERATOR_TOKEN` in der **Env** (CYP-628-Wizard → ACL-geschützte Service-Account-Env), nie gebaked | erkennen + orientieren |
| **Browser** (Gateway-Weg, CYP-638) | **same-origin Kratos-Login** (`AuthGate`, `UserTier.OPERATOR`) — kein Env, kein Bearer | erkennen + orientieren |

> **★ In KEINEM Kontext ein Bearer-Paste-Feld.** Der Token bleibt in der Env / die Session in Kratos —
> kein sensibler Bearer im UI-Klartext (Shoulder-Surf), kein net-new sensibles Feld. Schritt 1 ist
> **„erkennen + führen", nie „Token abfragen".** (PO-Entscheid Q1 = (A) + nie-Bearer-Paste.)

**Schritt-1-Fläche (Copy, `INFO`-Ton):**
- **Titel** `first_run_title` — „Hub einrichten".
- **Intro** `first_run_intro` — was jetzt zu tun ist (Key + Repo, dann kann das Team arbeiten).
- **Degraded-Rahmung** `first_run_degraded_note` — **„das ist bei einem frischen Hub normal: er läuft
  bereits, kann aber noch keine Agenten starten, bis Key und Repository gesetzt sind."** Kritisch: den
  degraded-Boot-Zustand als **erwartet** rahmen, damit der Operator nicht denkt, es sei kaputt (PO-Punkt).

Kein Erfolgs-Grün, kein Alarm-Rot. Neutral/orientierend.

---

## 3. Schritt 2 — API-Key (Reuse `ApiKeySection` + ehrliche at-rest-Posture)

**Reuse pur:** die bestehende `ApiKeySection` (`SettingsPanel.kt`) mit ihren Keys (`settings_apikey_*`)
und Tags (`settings.apiKey.*`): write-only-Input, maskierte Anzeige (`***<last4>`, nie Klartext zurück),
Reveal un-maskt **nur den aktuell getippten Input**, operator-gated, fail-closed. **Nichts davon wird neu
gebaut.**

**Net-new, hier verankert:**

### 3.1 Ehrliche at-rest-Posture (★ Kern-Ehrlichkeit, `INFO`-Ton)
Über/unter dem Key-Feld eine **ehrliche Posture-Zeile** `first_run_apikey_posture`:

> **„Der API-Key wird server-seitig gespeichert und überall nur maskiert angezeigt (`***<letzte 4>`) —
> noch nicht verschlüsselt at-rest (Verschlüsselung folgt in einem späteren Update). Behandle den
> Hub-Host als vertrauenswürdig."**

- **Suggeriert keine Sicherheit, die nicht da ist** (der Key liegt plaintext in einer owner-only
  `0600`-Datei — Auftraggeber-Entscheidung, Option B), **alarmiert aber auch nicht.** Ton getragen von
  den **Worten**, nicht von Farbe → `INFO`, nicht Alarm-Rot, nicht Erfolgs-Grün.
- **Anker (kein Copy-Drift):** eure eigene CYP-199-Formulierung („*not encrypted at rest yet — say so*" /
  „*treat the hub host as trusted*"). Die at-rest-Verschlüsselung ist die spätere Slice **CYP-220**
  (Tracking im Spec, **nicht** in der user-facing Copy — siehe §9-Flag).

### 3.2 First-Run-Bestätigung ersetzt den „Restart"-Effekt-Hint (Ehrlichkeits-Nuance)
Die laufende Settings-`ApiKeySection` zeigt nach dem Speichern den amber **`EFFECT_DEFERRED`**-Hint
„gespeichert ≠ aktiv — starte die betroffenen Agenten neu" (`settings_apikey_effect_hint`). **Dieser Hint
ist im First-Run falsch:** es laufen **noch keine Agenten**, die man neu starten könnte. Der Key greift
schlicht **beim ersten Start** der (noch nicht gestarteten) Agenten — **kein Deferral-Gap, kein Restart.**

→ Im First-Run **den `EFFECT_DEFERRED`-Restart-Hint unterdrücken** und stattdessen eine **neutrale
`INFO`-Bestätigung** `first_run_apikey_saved` zeigen: **„Gespeichert. Der Key wird beim ersten Start
deiner Agenten verwendet."** Ehrlich (kein „schon aktiv"), aber ohne die irreführende Restart-Aufforderung.

> Die laufende Settings-Panel-Variante bleibt **unverändert** (dort laufen Agenten → Restart-Hint korrekt).
> Nur der First-Run-Kontext tauscht den Hint. Saubere Kontext-Trennung, kein Churn am Bestehenden.

---

## 4. Schritt 3 — Repository (Reuse `RepoSection` + Clone-Status-Zustände)

**Reuse:** die bestehende `RepoSection` (URL + Branch, `settings_repo_*` / `settings.repo.*`),
operator-gated, `PUT /api/config/repo` mit URL-Format-Validierung (`settings_repo_url_invalid` bei 400).

**Net-new: der Clone-Lebenszyklus.** Heute validiert `PUT` nur das **Format** und versucht **keinen
Clone** (`ConfigRoutes.kt:60-64`); der echte Clone passiert später beim Boot/Provision und ist **log-only**
(`BootOrchestrator.kt:305`) — **die GUI ist blind.** Das schließt diese Spec über den Backend-Seam (§7).

### 4.1 Bestätigung nach dem Setzen (`INFO`)
Nach erfolgreichem `PUT` (Format ok): **`first_run_repo_saved`** — **„Repository gesetzt. Der Hub klont
es jetzt."** (Auch hier: **nicht** der laufende `settings_repo_effect_hint` „gilt für neue worktrees /
nächsten Boot" — im First-Run klont der Hub **jetzt**, nicht „nächsten Boot". §7 fordert den prompten
Clone-Trigger.)

### 4.2 Clone-Status-Zustände (verdrahtet gegen §7-Seam)
Der Client **beobachtet** `cloneStatus` (§7) und rendert **genau einen** Zustand:

| `cloneStatus` | Zustand | Copy | Ton | Tag |
|---|---|---|---|---|
| `CONFIGURED_NEVER_CLONED` / `CLONING` | Klont | `first_run_repo_cloning` „Repository wird geklont …" | `INFO` (Polite) | `firstRun.repo.cloning` |
| `CLONED_OK` | Geklont | `first_run_repo_clone_ok` „Repository geklont. Dein Team kann arbeiten." | `INFO` (neutral, **kein** Grün) | `firstRun.repo.cloneOk` |
| `CLONE_FAILED` (`reason=URL_UNREACHABLE`) | Fehlgeschlagen | `first_run_repo_clone_failed_url` | **`ERROR`** (Assertive) | `firstRun.repo.cloneFailed` |
| `CLONE_FAILED` (`reason=AUTH`) | Fehlgeschlagen | `first_run_repo_clone_failed_auth` | **`ERROR`** (Assertive) | `firstRun.repo.cloneFailed` |
| `CLONE_FAILED` (`reason=UNKNOWN`/absent) | Fehlgeschlagen | `first_run_repo_clone_failed` (generisch) | **`ERROR`** (Assertive) | `firstRun.repo.cloneFailed` |
| absent/unbekannt (alter Server) | **nicht** „ok" | `first_run_repo_cloning` (fail-closed: nie „fertig") | `INFO` | `firstRun.repo.cloning` |

**Zwei-Tier-Ehrlichkeit (Muster CYP-587/CYP-576):**
- Der **Clone-Fehler selbst** ist ein echter, vom Operator verursachter Fehlschlag (bad URL / kein
  Host-Zugriff) → **`ERROR`-Ton, actionable, korrigierbar.** Das ist kein „advisory", es ist wirklich
  fehlgeschlagen und muss gefixt werden.
- Der **umgebende Hub-Zustand** bleibt „oben, degraded" → das ist die §6-Banner-Rahmung (erwartet), nicht
  Alarm. Zwei getrennte Ebenen: der Fehlschlag ist rot, der Hub-läuft-Kontext ist neutral.

### 4.3 Korrigierbarer Pfad (kein neuer CTA)
Der **Fix reused den bestehenden Save-Knopf:** Operator editiert die URL / behebt die Host-Credentials →
**erneut Speichern** → `PUT` → §7-Retry → `cloneStatus` transitioniert neu. **Kein separater „Retry"-CTA**
nötig; die Copy `_clone_failed_*` sagt explizit „… und setze es erneut". Reuse-first.

### 4.4 Lange Clones — ein ehrlicher Zustand darf nicht wie ein Hänger aussehen (PO-Fund)

Ein Clone kann **Minuten** dauern (großes Repo). Ein statisches „Repository wird geklont…", das **5 Minuten**
steht, **liest sich als Hänger → wird als Defekt gelesen** — dann hätten wir Ehrlichkeit gebaut und Vertrauen
verloren. `CLONING` liefert **keinen Fortschritt in %** (§7 ist ein Enum, kein Progress) → wir können keinen
Balken faken. Ehrliche Anti-Hänger-Behandlung **ohne** erfundenen Fortschritt, drei Teile:

1. **Lebenszeichen statt statischem Label:** der Clone-Zustand rendert einen **animierten indeterminaten
   Indikator** (Spinner/indeterminate — sichtbar *lebendig*), nie ein eingefrorenes „…". (Reuse des
   bestehenden Prepare-/Lade-Spinner-Musters.)
2. **Erwartung progressiv setzen:** nach einer **Schwelle** (~15 s, damit ein schneller Clone **nicht**
   über-gewarnt wird) erscheint eine ruhige Zusatzzeile `first_run_repo_cloning_slow` — **„Klont noch — bei
   großen Repositories kann das einige Minuten dauern."** (`INFO`, Live-Region **Polite, einmal** — kein
   wiederholtes Announce). So kippt „dauert lange" von „kaputt?" zu „erwartet".
3. **Agency statt Spinner-Falle:** der Nutzer darf den Clone **im Hintergrund weiterlaufen lassen** und den
   Gate verlassen (Reuse `first_run_skip`) — der Clone läuft server-seitig weiter; im degradierten Workspace
   spiegelt der Chip/Banner dann **ehrlich „Repository wird geklont"** (Reuse `first_run_repo_cloning`, **nicht**
   „nicht eingerichtet" — es *ist* gesetzt und in Arbeit), und der Agent-Start bleibt `GATED` mit dem
   **cloning-spezifischen** Grund statt „fehlt". Niemand sitzt einen 5-Minuten-Spinner ab.

**Kein Client-Timeout→`ERROR` bei `CLONING`:** ein langer Clone ist **kein** Fehlschlag — nur der Server-
`CLONE_FAILED` bedeutet Fehler (§7). Der Client erfindet keinen Fehlschlag (fail-closed in die ehrliche
Richtung), gibt dem Nutzer aber die **Ausstiegs-Agency** (3), statt ihn festzuhalten. (Anti-Dead-Hang-Doktrin,
konsistent mit CYP-576-OIDC „Continuing…" und dem Recovery-Codes-Hang.)

---

## 5. Schritt 4 — Team/Roster (Reuse `AgentManagementPanel`)

**Reuse pur, net-new = null.** Der Roster existiert bereits: `AgentManagementPanel` mit dem
`agentMgmt.empty`-Onboarding-Leerzustand (CYP-228), dem Add-Dialog (`agentMgmt.add.*` / `agent_add_*`,
`POST /api/agents` → `NewAgentSpec`), und der bereits ehrlichen `agent_add_spawn_hint`-Copy („Created. The
agent is not started yet — start it via the lifecycle controls."). **Default ist bereits 1 PO.**

**Net-new nur die Rahmung** (`INFO`): `first_run_team_intro` — **„Dein Team startet mit einem PO. Füge
jetzt weitere Agenten hinzu — oder später im Workspace."** Team-Aufbau ist im First-Run **optional** (der
Operator kann direkt mit dem PO starten und später erweitern). Der Add-Flow selbst ist der bestehende,
unverändert eingebettet.

---

## 6. Schritt 5 — Abschluss / Hand-off & Überspringen

### 6.1 Fertig
Wenn **`apikey.set == true` UND Repo `CLONED_OK`**: Abschluss-Fläche —
- `first_run_complete_title` „Einrichtung abgeschlossen" + `first_run_complete_body` „Key und Repository
  sind gesetzt. Dein Hub ist arbeitsbereit." (`INFO`, neutral).
- CTA `first_run_open_workspace` „Workspace öffnen" → Gate transparent, in den Workspace.

### 6.2 Überspringen / später (kein Stranden)
Jederzeit CTA `first_run_skip` „Später einrichten" → **ehrlich-degradierter Workspace**:
- `first_run_skip_note` (`INFO`) — **„Du kannst das jederzeit in den Projekt-Einstellungen nachholen. Bis
  dahin läuft der Hub, kann aber keine Agenten starten."**
- Im degradierten Workspace ein persistenter **Unkonfiguriert-Banner** `workspace_unconfigured_banner`
  (`INFO`, **erwartet**, kein Alarm-Rot): **„Hub noch nicht eingerichtet — Agenten können nicht starten.
  In den Projekt-Einstellungen einrichten."** Mit direktem Weg in die Settings (Reuse `window.settings`).
- **Teil-konfiguriert** (Key XOR Repo): der Banner nennt konkret, was fehlt (der Client kennt beide
  Flags); der Operator kann jederzeit ins Gate zurück oder in die Settings.

> **Reuse-Check (Self-Validation):** `workspace_unconfigured_banner` ist net-new — vor dem Bau gegen einen
> evtl. bestehenden Workspace-Degraded/Empty-Zustand verifizieren (heute gibt es laut Grounding **keinen**
> Workspace-Level-Unkonfiguriert-Banner; Config lag nur im vergrabenen Settings-Fenster). Bei Fund → dessen
> Key reusen statt neu anlegen.

### 6.3 Skip-Pfad-Kanten — wo „ehrlich degradiert" trägt oder auffliegt (PO-Auftrag)

Der Skip ist kein Sackgassen-Ausgang, sondern ein **ehrlich degradierter Betriebszustand.** Drei Kanten
entscheiden, ob das trägt:

**(a) Was sieht der Nutzer *nach* dem Skip?**
Ein voll navigierbarer Workspace (Fenster/Panels bedienbar — der Hub läuft ja) **plus** ein Unkonfiguriert-Hinweis.
Der Banner ist **spezifisch**, nicht generisch:
- **beide fehlen / eines fehlt:** der Banner nennt konkret, was fehlt — über die **bestehenden Schritt-Labels
  als „fehlt:"-Chips** (Reuse `first_run_step_apikey` / `first_run_step_repo`; keine grammatik-fragilen neuen
  Sätze). Der Client kennt beide Flags → zeigt genau die offenen.
- **Repo `CLONE_FAILED`:** der Banner darf **nicht** „Repository fehlt" sagen (es ist gesetzt, nur nicht
  geklont) — er trägt die **Clone-Fehler-Copy** (`first_run_repo_clone_failed*`, Reuse), damit der Nutzer die
  *Realität* sieht, nicht die Konfiguration (genau die CYP-639-Verwechslung, die §7 schließt).
- Der Banner trägt die CTA **„Einrichtung fortsetzen"** `workspace_setup_resume` → (b).

**★ Nag-Falle vermieden (PO-Fund, Selbstkorrektur): der Banner ist NICHT strikt „nicht-wegklickbar".**
Ein permanent-unklickbarer Banner für einen Nutzer, der **bewusst geskippt** hat und 3 Stunden arbeitet, wäre
**Nörgeln durch die Hintertür** — er widerspräche dem eigenen (b)-Grundsatz „Skip respektieren heißt Skip
respektieren". Auflösung über die **Lastverteilung der Ehrlichkeit**:
- Die **eigentliche Ehrlichkeits-Durchsetzung sitzt am Punkt der Handlung** — der `GATED`-Start-Block (c)
  bringt die Wahrheit **genau dann**, wenn der Nutzer wirklich einen Agenten starten will. Der ambiente
  Banner muss also **nicht** ein Dauer-Nag sein, um das Produkt ehrlich zu halten.
- Deshalb: der Banner ist **einklappbar** (`workspace.unconfiguredCollapse`) → kollabiert zu einem **leisen,
  passiven Indikator-Chip** `workspace_unconfigured_chip` („Nicht eingerichtet") im Workspace-Chrome —
  **nicht ganz weg** (der unfertige Zustand ist ein realer Dauerfakt → nie zu Null verstecken = keine
  Ehrlichkeits-Auslassung), aber **passiv statt fordernd.**
- **Kadenz:** voller Banner **einmal pro Session-Start** (klar, spezifisch); Einklappen wird **für die Session
  respektiert** (klappt nicht von selbst wieder auf, nörgelt nicht); Chip-Tap → Banner/Resume auf Abruf. Bei
  **Relaunch** (noch unkonfiguriert) einmal wieder voll — **dieselbe Kadenz wie das Gate** (eine ehrliche
  Erinnerung pro Session-Start, danach passiv). Ein 3-Stunden-Skipper sieht: 1× Banner → einklappen → leiser
  Chip. Ehrlichkeit erhalten, Nag entfernt.

**(b) Kommt er zurück ins Gate — und wann?**
Drei Wege, bewusst getrennt (Ehrlichkeit ohne Nörgeln):
- **Auf Abruf (jederzeit):** die Banner-CTA `workspace_setup_resume` **öffnet das FirstRunGate erneut** und
  landet auf dem **ersten offenen Schritt** (nicht wieder Schritt 1, wenn Key schon steht). Der geführte Weg
  bleibt erreichbar, nicht nur der vergrabene Settings-Weg.
- **Bei nächstem Start (automatisch):** das Gate ist **zustandslos abgeleitet** (`apikey.set=false` ODER Repo
  ≠ `CLONED_OK`) → beim nächsten App-Start **erscheint es wieder** (ehrliche wiederkehrende Erinnerung an
  einen real unfertigen Zustand), wieder überspringbar.
- **Innerhalb der Session:** der Skip wird **respektiert** — das Gate **poppt nicht von selbst wieder auf**
  (kein Nörgeln); der Nutzer holt es über die CTA zurück. Balance: persistenter Banner + Relaunch-Gate
  (Ehrlichkeit) vs. respektierter Skip in der Session (kein Trap/kein Nag).
- **Settings bleibt der direkte Alternativweg** (`window.settings`) für den Power-User, der die Felder direkt
  editieren will — gleichwertig, nicht der einzige Weg.
- Sobald **Key + Repo `CLONED_OK`**: Gate erscheint **nie** mehr, Banner verschwindet.

**(c) Er überspringt und startet *doch* einen Agenten — sagt das Produkt ehrlich, *warum* es nicht geht?**
**Das ist der Lackmustest.** Ein Agent braucht Key (um `claude` zu fahren) **und** Repo/Worktree (um zu
arbeiten). Ohne die zwei **darf der Start nicht still scheitern oder ewig spinnen.**
- **Pre-emptiv + ehrlich (`GATED`-Ton):** solange unkonfiguriert ist das per-Agent **Start-Control
  deaktiviert mit sichtbarem Grund** — `agent_ctl_unconfigured` (Reuse-konsistent zur bestehenden
  `agent_ctl_*`-Familie): **„Agent kann nicht starten, solange der Hub nicht eingerichtet ist (API-Key +
  Repository). Jetzt einrichten."** mit direktem Weg in die Einrichtung. `GATED` (nicht `ERROR`) ist hier
  korrekt: **es ist nichts fehlgeschlagen** — die Aktion ist an eine unerfüllte Vorbedingung *gekoppelt*. Der
  Grund steht **vor** dem Klick, nicht als Überraschung danach. Das ist „ehrlich degradiert" am Punkt der
  Handlung — die schwerste und wichtigste Stelle.
- **Fail-closed bei Race:** ändert sich der Konfig-Status unter der Hand (gerade entzogen), muss ein trotzdem
  durchgerutschter Start server-seitig mit **demselben ehrlichen Grund** scheitern — **dieselbe Copy
  `agent_ctl_unconfigured` in die bestehende `agent_ctl_err_*`-Fehlerfläche** (nie ein generisches
  `agent_ctl_err_generic` „Start fehlgeschlagen" ohne das *Warum*). Eine Copy, zwei Flächen (disabled-Reason
  + Race-Fail).

> **Nahtstellen-Flag (⟂ AgentView/Lifecycle):** (c) berührt das **per-Agent-Start-Control** (`agent_ctl_*` /
> `AgentViewTags`), **nicht** nur First-Run — daher als **Empfehlung + Copy-Seam** gespect, nicht unilateral
> umgebaut. **Reuse gg. Code verifiziert:** die Lifecycle-Fehlerfamilie **`agent_ctl_err_*`** (`_spawn_failed`,
> `_operator_required`, `_unreachable`, `_generic`, …) + Controls `agent_ctl_start/stop/restart` existieren
> bereits (`strings.xml` @ `2f664e33`) — der neue Grund `agent_ctl_unconfigured` **reiht sich ein** (kein
> Fremdkörper). Exakte Verankerung am Control (disabled-Reason-Slot + Tag) mit Dev koordinieren (über PO;
> `AgentViewTags`-Start-Control-Naming vor Bau gegenlesen). Der **Ton (`GATED`, nicht `ERROR`) und die
> Ehrlichkeits-Anforderung** (Grund sichtbar vor der Handlung, kein stiller Fail) sind der nicht-verhandelbare
> Design-Kern.

---

## 7. ★ Backend-Seam (consumer-driven — was die UX braucht, Backend baut)

**Heute existiert KEIN Signal** (PO-Code-Befund): `GET /api/config/repo` hat kein `cloneStatus`; `PUT`
klont nicht; CYP-639 ist log-only („der Operator fixt es via GUI" — **aber die GUI kann es nicht sehen**).
Die First-Run-UX ist **nicht ehrlich verdrahtbar**, ohne dieses Signal. Diese Spec **definiert, was die UX
braucht**; der PO routet den Bau an Backend.

### 7.1 Status-Feld auf `GET /api/config/repo` (`RepoConfigView`)
```
cloneStatus:  CloneStatus            // NEU, nicht-nullable
cloneReason:  CloneFailReason?       // NEU, nur bei CLONE_FAILED gesetzt

enum CloneStatus     { NOT_CONFIGURED, CONFIGURED_NEVER_CLONED, CLONING, CLONE_FAILED, CLONED_OK }
enum CloneFailReason { URL_UNREACHABLE, AUTH, UNKNOWN }
```
- **fail-closed (Pflicht):** fehlt das Feld (alter Server) oder ist der Status unbekannt, decodiert der
  Client zu **`CONFIGURED_NEVER_CLONED`** (falls `configured`) bzw. **`NOT_CONFIGURED`** — **nie**
  `CLONED_OK`. Die UX rendert dann „klont/unbekannt", **nie** „fertig". Unbekannt ≠ ok.
- **unterscheidbar** (PO-Anforderung): `not-configured` / `configured-but-never-cloned` / `clone-failed(reason)`
  / `cloned-ok` — die vier Zustände, plus Reason für die actionable Copy (URL vs Auth vs generisch).

### 7.2 Clone JETZT anstoßen (nicht auf nächsten Boot warten)
Das Setzen des Repos im First-Run muss einen **prompten Clone-Versuch** auslösen (Server-seitig nach `PUT`,
oder ein dedizierter Trigger) — **nicht** auf den nächsten Boot deferren. Sonst kann der korrigierbare Loop
(§4.3) nicht schließen: der Operator sähe endlos „klont" oder nichts. Der Versuch speist `cloneStatus`.

### 7.3 Beobachtung (Client-Wiring)
- **MVP:** Client **pollt** `GET /api/config/repo` nach dem `PUT` bis zu einem **terminalen** Status
  (`CLONED_OK` | `CLONE_FAILED`). Einfach, reuse der bestehenden `ConfigHttpRepository`, kein neuer
  Endpunkt. Poll-Intervall/-Timeout: Dev-Detail (großzügig; ein Clone kann dauern).
- **Optional später:** ein `clone`-Event auf dem bestehenden Event-WS (Push statt Poll) — additiv, nicht
  MVP-blockierend.
- **Retry** = bestehender `PUT` (§4.3), Server versucht erneut zu klonen, Status transitioniert.

### 7.4 Warum consumer-driven
Analog zum Gateway (CYP-638): die **UX definiert den Kontrakt**, den sie ehrlich verdrahten kann; Backend
implementiert. Ohne §7 bleibt CYP-639 der beschriebene **Halb-Fix** (log-only, GUI-blind).

---

## 8. Zustandsübersicht (Konfig-Status → Gate/Workspace)

| Konfig-Status | Bedingung | First-Run zeigt |
|---|---|---|
| **Unkonfiguriert** | `apikey.set=false` UND Repo `NOT_CONFIGURED` | volles Gate ab Schritt 1 (Orientierung → Key → Repo → Team) |
| **Teil-konfiguriert** | genau eines von {Key gesetzt, Repo `CLONED_OK`} | Gate bleibt, offener Schritt hervorgehoben; Rest abgehakt |
| **Repo klont** | Repo gesetzt, `CLONING`/`CONFIGURED_NEVER_CLONED` | `firstRun.repo.cloning` (Polite), Abschluss wartet auf `CLONED_OK` |
| **Repo-Clone fehlgeschlagen** | `CLONE_FAILED(reason)` | `firstRun.repo.cloneFailed` (`ERROR`, actionable, korrigierbar §4.3) |
| **Fertig** | `apikey.set=true` UND Repo `CLONED_OK` | Abschluss-Fläche → „Workspace öffnen"; danach Gate transparent |
| **Übersprungen / unbekannt** | Operator skippt, oder Status unbekannt | degradierter Workspace + `workspace_unconfigured_banner` (fail-closed: nie „fertig") |

Pro Schritt zusätzlich die bestehenden Feld-Zustände (idle / saving / saved / error) aus der reused
`SettingsPanel`-Logik — unverändert.

---

## 9. Honesty-Doktrin — angewandt

- **`INFO`** (neutral advisory, kein Alarm/kein Grün): Orientierung, Degraded-Rahmung, at-rest-Posture,
  First-Run-Bestätigungen (Key gespeichert / Repo gesetzt / geklont), Team-Intro, Skip-Note, Unkonfiguriert-Banner.
- **`ERROR`** (echter Fehlschlag, actionable): Clone-Fehler.
- **`GATED`** (Aktion an unerfüllte Vorbedingung gekoppelt, nichts fehlgeschlagen): der Start-blockiert-Grund
  am per-Agent-Control im **degradierten Workspace** (§6.3c) — **nicht** im First-Run-Gate selbst.
- **KEIN `EFFECT_DEFERRED`** im First-Run — **bewusst**: das Deferral existiert hier nicht (keine laufenden
  Agenten zum Neustarten; Repo klont jetzt, nicht „nächsten Boot"). Der amber Restart-/Next-Boot-Hint der
  laufenden Settings-Panel wäre eine **Überzeichnung** → ersetzt durch neutrale `INFO`-Bestätigung. Die
  laufende Settings-Variante behält ihren `EFFECT_DEFERRED`-Hint (dort korrekt).
- **Kein `GATED` im First-Run-Gate** — der Operator ist upstream authentifiziert (§2); es gibt keinen
  Operator-Gate-Hint im Gate (er IST Operator). (`GATED` erscheint erst im degradierten Workspace, §6.3c.)
- **Kein content-tragendes/sensibles Klartext-Leak:** kein Key-Rohwert (nur `***<last4>`), kein Token im UI.
- **WCAG 1.4.1:** jeder Zustand ist distinktes **Text** + Ton + a11y-Label; Farbe nie alleiniger Träger.
  Live-Regions: `cloneStatus`-Transitionen **Polite**, Clone-Fehler **Assertive** (§4.2, siehe -tags.md).
- **Maritim/M3:** alle Töne über die Semantik-Tokens (`first-run-setup-tokens.json`), keine Roh-Hex im
  Component; WARN-Amber (`state.waiting`) ist eine **reservierte Status-Hue** und wird hier **nicht** als
  Chrome missbraucht.

### 9-Flag (1 Auftraggeber-Entscheid)
Die at-rest-Posture-Copy (§3.1) sagt **„Verschlüsselung folgt in einem späteren Update"** — **nicht**
„folgt mit CYP-220". Begründung: ein **interner Jira-Key gehört nicht in user-facing Copy** (auch wenn der
Operator technisch ist). Das Tracking auf **CYP-220** steht hier im Spec + im Key-Kommentar. **Wenn du den
Ticket-Key explizit in der UI willst, sag's — dann ziehe ich `(CYP-220)` in den String.** (Substanz ist
identisch zu deiner approved Formulierung; nur die Ticket-Nummer ist aus dem sichtbaren String gezogen.)

---

## 10. Reuse-Bilanz (anti-divergent)

| Baustein | Reuse / Net-new |
|---|---|
| API-Key-Feld, Maskierung, Reveal, Save | **Reuse** `ApiKeySection` + `settings_apikey_*` + `settings.apiKey.*` |
| Repo-URL/Branch-Feld, Format-Validierung, Save | **Reuse** `RepoSection` + `settings_repo_*` + `settings.repo.*` |
| Roster / Agent-Add | **Reuse** `AgentManagementPanel` + `agentMgmt.*` + `agent_add_*` + `agentMgmt.empty` |
| Toned-Hint-Komponente | **Reuse** `TonedHint` / `HintTone` (eigener Tag je Aufruf) |
| Gate-Naht | **Reuse** Muster `RemoteHubConnectGate` |
| Lade-/Retry | **Reuse** `LoadErrorRetry` |
| Maritim-Tokens | **Reuse** `MaritimeTheme` via Semantik-Tokens |
| First-Run-Gate-Hülle, Stepper, Orientierung/Degraded-Copy | **Net-new** (`firstRun.*`, `first_run_*`) |
| at-rest-Posture-Copy | **Net-new** `first_run_apikey_posture` (Anker CYP-199) |
| First-Run-Bestätigungen (statt Effekt-Hints) | **Net-new** `first_run_apikey_saved` / `first_run_repo_saved` |
| Clone-Status-Zustände | **Net-new** `firstRun.repo.*` + `first_run_repo_clone*` |
| Backend-`cloneStatus`-Seam | **Net-new Kontrakt** (§7) — Backend baut |

---

## 11. Nahtstellen (→ Dev / Backend / UIUX2, über PO)

- **Dev (Client):** FirstRunGate-Hülle + Stepper; Einbettung der reused `RepoSection`/`ApiKeySection`/
  Roster; First-Run-Kontext-Schalter (Effekt-Hint→`INFO`-Bestätigung, §3.2/§4.1); `cloneStatus`-Poll (§7.3);
  Skip→Degraded-Workspace + Banner + Resume-CTA (§6.3a/b).
- **Dev (⟂ AgentView/Lifecycle):** der Start-blockiert-Grund `agent_ctl_unconfigured` (`GATED`) am
  per-Agent-Start-Control + Race-Fail-Fläche (§6.3c) — Naming/Verankerung gg. `agent_ctl_*`/`AgentViewTags`
  gegenlesen. Kein unilateraler Umbau; Ton + Ehrlichkeit sind der Kern.
- **Backend:** der §7-Seam (`cloneStatus`/`cloneReason` auf `RepoConfigView`, prompter Clone-Trigger,
  fail-closed). **Consumer-driven, PO routet.**
- **a11y/Interaktion (PO-zugewiesen 2026-07-16 — MEINE Fläche, First-Run = Team-1-Scope):** vollständig
  spezifiziert in der Companion **`first-run-setup-a11y.md`**. Die 5 Punkte: (1) Gate-/Schritt-Fokus; (2)
  Live-Region-Politeness (Clone-Fehler Assertive, Rest Polite, `agent_ctl_unconfigured` Polite, Chip stumm);
  (**★3**) der `GATED`-Grund **programmatisch am Control** (Merge-Gruppe + `stateDescription`, nicht daneben) —
  die a11y-kritischste Stelle; (4) Nag-Fix-Einklapp-Fokus; (5) CTA-Fokusordnung (Skip/Resume nie erster
  Tab-Stop). Gegroundet an realen Repo-Idiomen; behaviorale a11y-QA → Tester (CYP-7).

---

## 12. Self-Validation

- **Gegroundet** gg. develop `2f664e33`: Endpunkte (`GET/PUT /api/config/{repo,apikey}`, `POST /api/agents`),
  Reuse-Composables (`SettingsPanel`/`AgentManagementPanel`), Gate-Kette (`AuthGate`→`RemoteHubConnectGate`),
  + PO-Code-Befund (`ConfigRoutes.kt:44-64`, `BootOrchestrator.kt:305`) für die §7-Lücke.
- **Reuse-first:** Config- + Roster-Primitive 1:1 eingebettet; net-new strikt auf Gate-Hülle, ehrliche Copy,
  Clone-Zustände, Seam begrenzt (§10-Bilanz).
- **Honesty:** at-rest-Posture ehrlich + nicht alarmierend (Anker CYP-199); `EFFECT_DEFERRED` bewusst
  vermieden (kein Deferral im First-Run); Clone-Fehler `ERROR`/actionable vs Hub-degraded `INFO`/erwartet
  (Zwei-Tier); fail-closed überall (unbekannt ≠ ok).
- **PO-Entscheide verankert:** Q1=(A)/nie-Bearer-Paste (§2), Q3=Gate/überspringbar (§1/§6), Q2=Seam (§7),
  Q2-Ratifikation (fail-closed + prompter Clone + 4 Zustände).
- **§9-Flag ENTSCHIEDEN (PO 2026-07-16):** `(CYP-220)` bleibt **aus** der user-facing Copy — Tracking nur
  in Spec + Key-Kommentar. Copy unverändert („späteres Update").
- **Skip-Pfad-Kanten (§6.3, PO-ungated-Auftrag) beantwortet:** nach-Skip-Sicht (spezifischer Banner),
  Rückkehr ins Gate (auf-Abruf via CTA / bei Relaunch / Skip-in-Session respektiert / Settings-Alt), und der
  Lackmustest „Start trotz unkonfiguriert" (pre-emptiv `GATED` + ehrlicher Grund vor Klick, kein stiller Fail;
  ⟂ AgentView-Seam).
- **Nag-Falle selbst-korrigiert (PO-Fund §6.3a):** strikt-nicht-wegklickbarer Banner wäre Back-Door-Nörgeln
  gg. den eigenen Skip-Respekt-Grundsatz → Banner **einklappbar → leiser Chip** (nie zu Null); die eigentliche
  Ehrlichkeit trägt der Punkt-der-Handlung-`GATED`-Block, nicht der ambiente Banner.
- Companion-Files eingefroren: `first-run-setup-keys.md` · `first-run-setup-tags.md` ·
  `first-run-setup-tokens.json`. Kein Bau, docs-only auf `feature/CYP-629-first-run-setup-spec`.
