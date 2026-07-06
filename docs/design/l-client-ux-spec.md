# Design-Spec — L-Client-UX: Spawn-Flow + Background-/LRU-Indikator (CYP-262)

> Owner: UIUX-Designer · Story **CYP-262** (unter Epic-Arc CYP-247 „L") · Stand: 2026-07-06 · Status: **v1.1 (Teil-2-Naht-Bindung finalisiert)** — Teil 1 PO-ratifiziert; **Teil 2 auf die echte Naht gefaltet, wartet PO-Fold-Ratifikation.**
> **⚠ v1.1-Änderung ggü. v1.0:** Teil-2-Backend-Dep war seam-agnostisch (§5 „PO liefert Kontrakt nach .4b"). **Jetzt gebunden:** der
> Server-Kontrakt steht (`backend/plans/CYP-249-switch-runtime-contract.md`, off CYP-255 .4b/Push 2). Teil 2 bindet an das PO-autorisierte
> additive **`runtimeState`-Enum-Feld (`HOT|BACKGROUND|SUSPENDED`) auf `ProjectsView` (`GET /api/projects`)**, server-abgeleitet, Backend baut
> es mit **Push 3**. Mein provisorischer Zustandsname **ACTIVE → HOT** (Server-Enum). **D4 Bar-Summe = PO-entschieden Menü-only** (§10a bleibt
> toggle-ready, NICHT gebaut). Rest unverändert. Copy/Tags/Töne waren kontrakt-agnostisch → **0 neue Keys/Tags durch die Bindung.**
> **Design-first — Ratifikation durch PO VOR Dev-Bau.** Konsumenten: **Teil 1 ↔ CYP-256** (247.5 Spawn/CRUD),
> **Teil 2 ↔ CYP-255** (247.4 Teardown-Policy ① Background-live + LRU-K=3) auf **CYP-249-Kontrakt**, Hygiene-Naht **CYP-249** (per-Projekt-VM).
> **Grounded gegen** `origin/develop 44b1b1b` (`agentview/AgentWindow.kt` `StatusIndicator`/`ReconnectingChip`/Lifecycle-Controls/Errors,
> `AgentLifecycleApi` `AgentLifecycleState`, `AgentViewTags`, `project/ProjectSwitcherBar`+`ProjectTags`, `ui/TonedHint`,
> `strings.xml`, `core/…/model/ProjectModel.kt` `ProjectsView{activeProjectId, projects:List<Project>}` + `Project{id,name}`).
> **Reine `commonMain`-UI** — Teil 1 client-only; **Teil 2 = 1 Backend-Dep** (`ProjectsView.runtimeState`, §5, Push 3).

---

## §0 — Ziel in zwei Sätzen

**Teil 1:** Der Übergang **„leeres Projekt → erster laufender Agent"** braucht ehrliches **Spawn-Feedback** — der CYP-250-Empty-State-CTA
führt zu Add, mit L.5 spawnt der Agent **real**; Fenster erscheint, Terminal verbindet. **Teil 2:** Sobald verlassene Projekte
per Policy **im Hintergrund weiterlaufen** (① Background-live) bzw. per **LRU-K=3** suspendiert werden, muss die UI diese Zustände
**ehrlich und ohne Fehler-Anschein** zeigen.

---

## §1 — Was existiert (verifiziert @ `44b1b1b`) — Reuse-Inventar

**Agent-Lifecycle (CYP-73 / CYP-204):**
- `AgentLifecycleState { RUNNING, STOPPED, ERROR, UNKNOWN }` (`AgentLifecycleApi`). `UNKNOWN` = client-only (vor `/ws/lifecycle`-Snapshot).
- `StatusIndicator` (`AgentWindow.kt`): **Punkt + Text-Label** (Farbe nie alleiniger Träger, WCAG 1.4.1); Tag `agent.<id>.status`; a11y `a11y_agent_status`. Strings `agent_status_{running,stopped,error,unknown}` (Läuft/Gestoppt/Fehler/Unbekannt).
- `ReconnectingChip`: **nur wenn `connection != LIVE`** (tertiär), Tag `agent.<id>.reconnecting`, String `agent_reconnecting` „Verbindung wird wiederhergestellt…".
- Lifecycle-Controls: Start/Stop/Neustart (operator-gated) `agent_ctl_{start,stop,restart}`; Start `enabled = canControl && state != RUNNING`.
- Lifecycle-Errors: `agent.<id>.lifecycleError`, honest Server-Gründe — u. a. `agent_ctl_err_spawn_failed` „Start fehlgeschlagen".
- **⚠ KEIN transitionaler „Starting/Spawning"-Zustand** — heute **binär** STOPPED↔RUNNING; State flippt erst beim `/ws/lifecycle`-Event.

**Projekt-Switcher (CYP-91/92/233):**
- `ProjectSwitcherBar`: aktive Zeile „● Aktives Projekt: %1$s" (`projectSwitcher.active`), „Projekte ▾" (`projectSwitcher.menu`),
  Scope-Hinweis, ▾-Menü mit Projekt-Zeilen `projectSwitcher.item.<id>` (+ `.active`), Manage-Eintrag.
- **⚠ KEIN per-Projekt Running/Background/Suspended-Indikator** — Tags decken CRUD + Aktiv-Markierung.

**Kein Client-Session-/Background-/LRU-State (grep bestätigt 0):** kein `SessionState`/`ProjectSessionState`/`background`/`suspended`/`lru`.
→ **Teil 2 bindet an einen NEUEN Server-Zustand** (Backend-Dep, §5).

**Reuse-Komponente für Statushinweise:** `TonedHint(text, tone, tag)` mit `HintTone.INFO` (neutral, „i"-Glyph, **kein** Fehler-Ton).
`WindowBadge` ist fenster-, nicht projekt-scoped → **nicht** reused.

---

## §2 — Teil 1: Spawn-Flow „leer → erster laufender Agent" (↔ CYP-256)

### Die ehrliche Sequenz

1. **Anlegen (≠ Start):** CYP-250-Empty-State-CTA → Add-Dialog → create. Der Agent erscheint als **Fenster im Zustand
   `STOPPED`** (`StatusIndicator` „Gestoppt"). Der Empty-State **self-cleart** (CYP-250, `managedAgents` nicht mehr leer).
   *Ehrlich:* angelegt ≠ läuft — genau die „Anlegen ≠ Start"-Grenze aus CYP-228/250.
2. **Start (Spawn):** Operator klickt `agent.<id>.startBtn`. **→ D1: transientes „Startet…"** auf dem `StatusIndicator`
   (in-progress-Ton, **nicht** „Läuft"). *Ehrlich:* „Start angefragt, warte auf Bestätigung" — es wird **nie** „Läuft"
   vorgetäuscht, bevor der Server `RUNNING` meldet.
3. **Läuft + verbindet:** `/ws/lifecycle`-`RUNNING`-Event → `StatusIndicator` „Läuft" (primär). Der Stream/das Terminal
   verbindet: `AgentWsClient` `CONNECTING→LIVE`; solange `!= LIVE` zeigt der **`ReconnectingChip`** ehrlich „Verbindung wird
   wiederhergestellt…"; auf `LIVE` verschwindet er. **Beides reused.**
4. **Spawn-Fehler:** schlägt der Start fehl (`spawn_failed`) → „Startet…" löst → Zustand bleibt **`STOPPED`** + `LifecycleErrorRow`
   „Start fehlgeschlagen" (`agent_ctl_err_spawn_failed`). **Reused.** Nie ein Fake-`RUNNING`.

### D1 — Transientes „Startet…" (client-only, Spawn-Feedback)

Da es **keinen** Server-`STARTING`-Zustand gibt, ist das ehrliche Spawn-Feedback **client-lokal**: ein `startPending`-Flag im
Lifecycle-VM (gesetzt beim Start-Action, gelöscht beim nächsten terminalen Event `RUNNING`/`STOPPED`/`ERROR`). Der `StatusIndicator`
rendert währenddessen **„Startet…"** (neuer Key `agent_status_starting`) im **in-progress-Ton (tertiär, wie der ReconnectingChip)** —
**0 neue Farbe**. Auf demselben `agent.<id>.status`-Knoten → **0 neuer Tag**.

> *Ehrlichkeits-Kern:* „Startet…" spiegelt **die Anfrage in Flight**, nicht den Prozess-Zustand. Es resolvet **immer** (Erfolg→„Läuft",
> Fehler→„Gestoppt"+Grund) — bleibt nie hängen. Reflektiert nie „läuft", bevor `RUNNING` da ist. **Client-only, keine Backend-Dep.**
> **Forward:** führt der Server später einen echten `STARTING`/`SPAWNING`-Zustand ein, bindet der Client additiv daran (derselbe Slot).

### D2 — Reuse, keine Divergenz

Zustands-Anzeige (`StatusIndicator`), Terminal-Connect (`ReconnectingChip`), Controls, Fehler (`spawn_failed`) sind **alle bestehend**.
Teil 1 fügt **nur** das transiente „Startet…" (1 Key + 1 client-Flag) hinzu. Keine neuen Tags, keine neuen Farben.

---

## §3 — Teil 2: Background-/LRU-Suspend-Indikator (↔ CYP-255)

### Die drei Projekt-Session-Zustände = `ProjectsView.runtimeState` (server-autoritativ, Client spiegelt)

Server-Enum aus dem CYP-249-Kontrakt: **`HOT | BACKGROUND | SUSPENDED`** (Client bindet 1:1; kein client-abgeleiteter Zustand).

| `runtimeState` | Server-Prädikat (CYP-249 §1) | UI-Indikator | Copy | Ton |
|---|---|---|---|---|
| **HOT** | das EINE aktive Projekt (`runtimeRegistry.active()`) | **keiner** — bestehende ●-Markierung `projectSwitcher.active` genügt | „● Aktives Projekt" (bestehend) | — |
| **BACKGROUND** | besucht, nicht aktiv, innerhalb Cap K=3 — Runtime + **Agenten laufen live** | Session-Indikator `TonedHint(INFO)` | **„Läuft im Hintergrund"** (`project_session_background`) | **INFO** |
| **SUSPENDED** | jenseits K=3 — via persist-kill-resume abgebaut, Daten intakt, Prozesse weg bis Re-Entry | Session-Indikator `TonedHint(INFO)` | **„Suspendiert — Resume beim Öffnen"** (`project_session_suspended`) | **INFO** |

> **Push-3-Timing (CYP-249 §3):** `SUSPENDED` wird erst mit dem Teardown real; bis dahin beobachtet der Client nur `HOT`/`BACKGROUND`.
> Der Indikator handhabt **alle drei** ab Tag 1 (kontrakt-vollständig) — kein Nachrüsten nötig, wenn Push 3 die Eviction einschaltet.

### D3 — Platzierung: per-Projekt-Zeile im ▾-Menü, `TonedHint(INFO)`

Jede Projekt-Zeile `projectSwitcher.item.<id>` im ▾-Dropdown trägt einen **INFO-Session-Indikator** (neuer Tag
`projectSwitcher.item.<id>.session`) — Text + „i"-Glyph via `TonedHint(HintTone.INFO)`, **Farbe nie alleiniger Träger**. `HOT`
bleibt die bestehende ●-Markierung (kein zusätzlicher Indikator). a11y: neuer Key `a11y_project_session` („Projekt-Sitzung: %1$s").

### D4 — Ressourcen-Ehrlichkeit: Background sichtbar, nicht versteckt · **PO-entschieden: Menü-only**

**Kern-Ehrlichkeit:** BACKGROUND heißt **echte laufende Agenten** (verbrauchen Compute/API-Tokens) — der Nutzer soll das **wissen**.
**PO-Entscheid (2026-07-06): Menü-only** — der ehrliche Kern ist der per-Projekt-Indikator im ▾-Menü (D3); der **Bar-Level-Summenhinweis
wird NICHT gebaut**. Das Design dafür bleibt **toggle-ready in §10a** (falls der Auftraggeber später Fleet-Awareness ohne Menü-Öffnen will;
zählt dann NUR `BACKGROUND` = laufend/verbraucht, nie `SUSPENDED` = pausiert/verbraucht nichts). **Für den T2-Bau: kein Bar-Summenhinweis.**

### D5 — Kein Fehler-Anschein, keine LRU-Mechanik-Leaks

- **INFO-Ton, nie ERROR/WARN** für BACKGROUND **und** SUSPENDED — beide sind **normale** Zustände, kein Fehler.
- **BACKGROUND ≠ SUSPENDED** klar getrennt: „läuft" (Ressourcen aktiv) vs. „pausiert, resumt" (keine Ressourcen, kein Datenverlust).
- **Kein K=3/LRU-Jargon** an den Nutzer — nur der ehrliche Zustand + die Rückversicherung „Resume beim Öffnen". Der Nutzer muss die
  Cache-Mechanik nicht verstehen; „suspendiert, kommt beim Öffnen zurück" genügt und beruhigt.

---

## §5 — Backend-Dep (Teil 2) — **finalisierte Naht-Bindung** (`ProjectsView.runtimeState`, CYP-249-Kontrakt)

Der per-Projekt-Session-Zustand ist **kein** Client-Konstrukt (grep=0 client-seitig) — nur der Server (Switch-Orchestrierung .4b) kennt
„hot/background/suspended". Der Kontrakt steht jetzt: **`backend/plans/CYP-249-switch-runtime-contract.md`** (off CYP-255 .4b/Push 2).

**Die Naht (PO-autorisiert, Backend baut mit Push 3):** ein additives, server-abgeleitetes Feld

```
GET /api/projects → ProjectsView { activeProjectId, projects: List<Project> }
   Project.runtimeState: RuntimeState = HOT | BACKGROUND | SUSPENDED   // additiv, server-derived
```

- **Enum `RuntimeState { HOT, BACKGROUND, SUSPENDED }` gehört ins `:core`** (eine Definition, Server + Client kompilieren dieselbe) —
  konsistent mit dem bestehenden `ProjectModel.kt`. Server leitet es aus `runtimeRegistry` ab (active / `of(pid)!=null` / else suspended,
  CYP-249 §1). **Additiv mit Default** (`= HOT` oder nullable), damit die Deserialisierung alt/neu robust bleibt.
- **Der Client spiegelt EIN autoritatives Feld** — `Project.runtimeState` — und leitet den Zustand **nicht** parallel aus `activeProjectId`
  ab (Doppelquelle = Drift-Risiko). `HOT` ⇒ kein Session-Indikator (bestehende ●); `BACKGROUND`/`SUSPENDED` ⇒ `TonedHint(INFO)` (§3-Copy).
- **Update-Kanal:** `GET /api/projects` beim Öffnen des ▾-Menüs / nach `POST /api/projects/switch` neu lesen (der Switch ändert die States
  aller Projekte). Ein `/ws`-Delta ist **nicht** nötig fürs MVP (das Menü ist Pull-getrieben); falls Backend eins liefert, bindet der Client additiv.

> **Fail-safe (Push-3-Rollout):** fehlt `runtimeState` (Pre-Push-3-Server / alter Client) → Feld defaultet auf `HOT` ⇒ **kein** Indikator.
> Der Client **rät nie** einen Hintergrund-/Suspend-Zustand ohne das Server-Feld (Invariante §9-11). Kein Fehlalarm während des Rollouts.

> **Re-Entry ist normal, kein Fehler (CYP-249 §4):** ein `SUSPENDED`-Projekt wird beim Öffnen per `switch` + `--resume` frisch gespawnt;
> der Client reconnectet frisch und replayt ab Cursor (CYP-198 history-then-live / CYP-204). Der dabei sichtbare `ReconnectingChip` ist der
> **normale** Re-Entry-Pfad — **kein** Error-Ton. Das deckt sich mit „Suspendiert — Resume beim Öffnen" (SUSPENDED-Copy).

> **Falls der Server je einen `ERROR`/failed-Runtime-Zustand liefert** (CYP-249 nennt keinen — 409 `project_not_runnable` ist ein
> Switch-Ergebnis, kein Dauerzustand): → error-toned, eigener Forward; **nicht** in diesem Scope.

---

## §7 — Ehrlichkeit (mein Kern)

**Teil 1:** nie „Läuft" vor Server-`RUNNING` (das Transiente ist „Startet…", nicht „Läuft"); STOPPED-Fenster zeigt ehrlich „nicht laufend"
(Anlegen ≠ Start); Spawn-Fehler → ehrlicher `spawn_failed`, bleibt STOPPED (nie Fake-Running); Terminal-Inhalt nie „live" behauptet, bevor
der Stream `LIVE` ist (ReconnectingChip). „Startet…" resolvet immer, bleibt nie hängen.
**Teil 2:** BACKGROUND = **echt laufend** → sichtbar/entdeckbar (Ressourcen-Ehrlichkeit), nicht versteckt; SUSPENDED = pausiert + resumt,
**kein** Datenverlust, **kein** Fehler; INFO-Ton für beide (kein Fault-Anschein); „läuft im Hintergrund" ≠ „suspendiert" klar getrennt;
kein LRU-Jargon.

---

## §8 — Umfang & Abgrenzung

- **Im Scope (Teil 1, client-only):** transientes „Startet…"-Spawn-Feedback + die ehrliche Add→Start→Running→Connect-Sequenz, rein aus
  Reuse (StatusIndicator/ReconnectingChip/Controls/Errors) + 1 Key + 1 client-Flag.
- **Im Scope (Teil 2, Design):** per-Projekt-Session-Indikator (`HOT`/`BACKGROUND`/`SUSPENDED`) im ▾-Menü, `TonedHint(INFO)`, Copy + Tags,
  gebunden an `ProjectsView.runtimeState`. **D4 Bar-Summe: NICHT im Bau-Scope** (PO Menü-only; toggle-ready §10a).
- **Backend-Dep (Teil 2):** additives server-abgeleitetes `Project.runtimeState`-Enum-Feld auf `ProjectsView` (`GET /api/projects`),
  Backend baut mit Push 3 (CYP-249-Kontrakt, §5). `RuntimeState`-Enum in `:core`.
- **Nicht im Scope:** die Spawn-/Teardown-/LRU-Server-Mechanik selbst (CYP-255/256); ein echter Server-`STARTING`-Zustand (Forward);
  Socket-Hygiene (CYP-249).

---

## §9 — Invarianten (= meine UX-QA-Abnahme, 11)

**Teil 1 — Spawn-Flow:**
1. **Nie Fake-Running:** vor dem Server-`RUNNING`-Event zeigt der Status **nie** „Läuft" — nur „Gestoppt" bzw. transient „Startet…".
2. **„Startet…" transient + resolvend:** erscheint nur bei in-flight Start; löst **immer** in „Läuft" (Erfolg) oder „Gestoppt"+Grund
   (Fehler); bleibt nie hängen.
3. **Anlegen ≠ Start:** ein frisch angelegter Agent erscheint als `STOPPED`-Fenster (ehrlich nicht laufend); Empty-State (CYP-250) self-cleart.
4. **Spawn-Fehler ehrlich:** `spawn_failed` → Zustand bleibt `STOPPED` + „Start fehlgeschlagen" (reused), nie Fake-Running.
5. **Terminal-Connect ehrlich:** „connecting" via bestehenden `ReconnectingChip` (nur `!= LIVE`); Inhalt nie „live" behauptet vor `LIVE`.
6. **Reuse-Reinheit Teil 1:** 0 neue Tags, 0 neue Farben; nur 1 Key (`agent_status_starting`) + 1 client-`startPending`-Flag.

**Teil 2 — Background/LRU:**
7. **Kein Fehler-Anschein:** BACKGROUND **und** SUSPENDED in **INFO**-Ton (kein ERROR/WARN); beide lesen als normale Zustände.
8. **Background = ehrlich laufend + entdeckbar:** „Läuft im Hintergrund" macht echte Ressourcen-Nutzung sichtbar (nicht versteckt).
9. **Suspended ehrlich:** „Suspendiert — Resume beim Öffnen" — pausiert, resumt, **kein** Datenverlust; kein LRU/K=3-Jargon.
10. **BACKGROUND ≠ SUSPENDED unterscheidbar:** die zwei Zustände sind in Copy **und** a11y klar getrennt; **`HOT` trägt keinen
    Session-Indikator** (nur die bestehende ●-Markierung `projectSwitcher.active`).
11. **Autoritative Quelle = `ProjectsView.runtimeState`:** der Indikator bindet **1:1** an das server-abgeleitete `Project.runtimeState`
    (`HOT|BACKGROUND|SUSPENDED`, §5), **nicht** an client-geratenes „ist wohl im Hintergrund" und **nicht** parallel aus `activeProjectId`.
    **Fail-safe:** fehlt das Feld (Pre-Push-3 / Default `HOT`) → **kein** Indikator; nie ein erfundener/geratener Zustand.

---

## §10 — §-Asks / Forwards (nicht blockierend, PO-Call)

- **D4 Bar-Level-Summenhinweis** — **PO-ratifiziert (2026-07-06): Default = nur Menü-Indikator (D3), minimal-first.** Das Bar-Summen-
  Design steht als **toggle-ready Addendum §10a** bereit — falls der (kosten-bewusste) Auftraggeber immer-sichtbare Hintergrund-Fleet-
  Awareness will, ist es ein kleiner Toggle-on (keine Re-Spezifikation nötig).
- **Server-`STARTING`-Zustand** (falls .5 einen echten liefert) → „Startet…" bindet additiv daran statt client-`startPending`.
- **Session-`ERROR`-Zustand** (falls der Server einen surfaced) → error-toned, eigener Forward.

---

## §10a — D4 Addendum: Bar-Level Background-Summe (toggle-ready, Menü-only ist Default)

> **Status:** vollständig spezifiziert, **aus**geschaltet per Default (PO-ratifiziert Menü-only). Ein-Schalten = dieses Addendum bauen;
> keine Änderung an D1–D5. Motiv: **Kosten-/Ressourcen-Ehrlichkeit** für den Auftraggeber — Hintergrund-Agenten verbrauchen
> Compute/API-Tokens, auch ungesehen; ein immer-sichtbarer Zähler macht das entdeckbar, ohne das ▾-Menü zu öffnen.

- **Trigger — zählt NUR BACKGROUND, nie SUSPENDED:** sichtbar, wenn `count(BACKGROUND) ≥ 1`. **Ehrlichkeits-Kern:** SUSPENDED-Projekte
  verbrauchen **nichts** (pausiert) → sie gehören **nicht** in eine Ressourcen-/Kosten-Summe. „N laufen im Hintergrund" = N **laufende**
  (BACKGROUND) Projekte, exkl. des aktiven und exkl. suspendierter. Bei `count == 0` → **absent** (fail-closed, kein „0 im Hintergrund").
- **Platzierung:** eine `TonedHint(HintTone.INFO)`-Zeile in der `ProjectSwitcherBar`, unter der Scope-Hinweis-Zeile (nicht über der
  aktiven-Projekt-Zeile — die bleibt primär). Tag `projectSwitcher.backgroundSummary`.
- **Copy:** `project_session_background_summary` „%1$s weitere Projekte laufen im Hintergrund" / „%1$s more projects running in the
  background" (positional `%1$s` = BACKGROUND-Count). a11y = der sichtbare Text (INFO).
- **Interaktion (optional):** Tap öffnet das ▾-Menü (wo die per-Projekt-Zustände stehen) — kein neuer Surface. Reuse `projectSwitcher.menu`.
- **Konsistenz:** derselbe INFO-Ton/„i"-Glyph wie der per-Projekt-Menü-Indikator (D3) — ein Vokabular, kein zweiter Stil.
- **Counts (nur falls getoggelt):** +1 Key (`project_session_background_summary`), +1 Tag (`projectSwitcher.backgroundSummary`), 0 Farben.
  Beide bereits in `-keys.md`/`-tags.md` als **optional** geführt → beim Toggle-on nur von „optional" auf „aktiv" heben.

---

## §11 — Hand-off

- **Neue Keys:** **Teil 1: 1** (`agent_status_starting`) · **Teil 2: 3** (`project_session_background`, `project_session_suspended`,
  `a11y_project_session`) **+ optional 1** (`project_session_background_summary`, D4). DE+EN. Details `-keys.md`.
- **Neue Tags:** **Teil 1: 0** (reuse `agent.<id>.status`) · **Teil 2: 1** (`projectSwitcher.item.<id>.session`) **+ optional 1**
  (`projectSwitcher.backgroundSummary`, D4). ⚠ CYP-7-Sync. `-tags.md`.
- **Neue Tokens/Farben:** **0** (reuse M3 + `TonedHint(INFO)` + StatusIndicator-Töne). `-tokens.json`.
- **⚠ Backend-Dep (Teil 2) — FINALISIERT:** additives `Project.runtimeState: RuntimeState { HOT, BACKGROUND, SUSPENDED }` auf `ProjectsView`
  (`GET /api/projects`), server-abgeleitet, `:core`-Enum, Backend baut mit **Push 3** (CYP-249-Kontrakt §5). Client spiegelt 1:1, fail-safe
  auf Feld-Absenz = kein Indikator. **⚠ Shared-DTO-Drift:** `:core`-Enum + Feld → Server **und** Client re-syncen (mit dem Push-3-Slice timen).
  Teil 1 hat **keine** Dep.
- **Konsumenten:** Dev CYP-256 (Teil 1) + CYP-255/Backend (Teil 2, `ProjectsView.runtimeState`) + CYP-249 (Hygiene). Danach **UX-QA durch
  UIUX** gegen §9 (11 Invarianten). **Bau-Reihenfolge:** Teil 1 → Dev nach CYP-250-Merge (keine Dep); Teil 2 → nach Push 3 (Feld existiert).
