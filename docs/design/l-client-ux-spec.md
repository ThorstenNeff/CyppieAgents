# Design-Spec — L-Client-UX: Spawn-Flow + Background-/LRU-Indikator (CYP-262)

> Owner: UIUX-Designer · Story **CYP-262** (unter Epic-Arc CYP-247 „L") · Stand: 2026-07-06 · Status: Vorschlag
> **Design-first — Ratifikation durch PO VOR Dev-Bau.** Konsumenten: **Teil 1 ↔ CYP-256** (247.5 Spawn/CRUD),
> **Teil 2 ↔ CYP-255** (247.4 Teardown-Policy ① Background-live + LRU-K), Hygiene-Naht **CYP-249** (per-Projekt-VM).
> **Grounded gegen** `origin/develop 44b1b1b` (`agentview/AgentWindow.kt` `StatusIndicator`/`ReconnectingChip`/Lifecycle-Controls/Errors,
> `AgentLifecycleApi` `AgentLifecycleState`, `AgentViewTags`, `project/ProjectSwitcherBar`+`ProjectTags`, `ui/TonedHint`,
> `strings.xml`). **Reine `commonMain`-UI** — Teil 1 client-only; **Teil 2 = 1 Backend-Dep** (per-Projekt-Session-State, §5).

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

### Die drei Projekt-Session-Zustände (Policy ① Background-live + LRU-K=3)

| Zustand | Bedeutung | Copy (Vorschlag) | Ton |
|---|---|---|---|
| **ACTIVE** | Vordergrund, du siehst die Agenten | „● Aktives Projekt" (bestehend, `projectSwitcher.active`) | — |
| **BACKGROUND** | verlassen, aber **Agenten laufen weiter** (①), innerhalb K=3 hot | **„Läuft im Hintergrund"** (`project_session_background`) | **INFO** |
| **SUSPENDED** | LRU-evicted (jenseits K=3 hot) → **pausiert**, kommt per Resume beim Öffnen zurück | **„Suspendiert — Resume beim Öffnen"** (`project_session_suspended`) | **INFO** |

### D3 — Platzierung: per-Projekt-Zeile im ▾-Menü, `TonedHint(INFO)`

Jede Projekt-Zeile `projectSwitcher.item.<id>` im ▾-Dropdown trägt einen **INFO-Session-Indikator** (neuer Tag
`projectSwitcher.item.<id>.session`) — Text + „i"-Glyph via `TonedHint(HintTone.INFO)`, **Farbe nie alleiniger Träger**. ACTIVE
bleibt die bestehende ●-Markierung (kein zusätzlicher Indikator). a11y: neuer Key `a11y_project_session` („Projekt-Sitzung: %1$s").

### D4 — Ressourcen-Ehrlichkeit: Background sichtbar, nicht versteckt

**Kern-Ehrlichkeit:** BACKGROUND heißt **echte laufende Agenten** (verbrauchen Compute/API-Tokens) — der Nutzer soll das **wissen**,
auch ohne das Menü zu öffnen. Darum **empfohlen (D4-§-Ask):** ein dezenter **Bar-Level-Summenhinweis**, wenn ≥1 Projekt im Hintergrund
läuft (z. B. `project_session_background_summary` „%1$s weitere Projekte laufen im Hintergrund", Tag `projectSwitcher.backgroundSummary`,
INFO). **Kern = per-Projekt-Indikator im Menü (D3); der Bar-Summenhinweis ist der PO-Call** (berührt die immer-sichtbare Leiste).

### D5 — Kein Fehler-Anschein, keine LRU-Mechanik-Leaks

- **INFO-Ton, nie ERROR/WARN** für BACKGROUND **und** SUSPENDED — beide sind **normale** Zustände, kein Fehler.
- **BACKGROUND ≠ SUSPENDED** klar getrennt: „läuft" (Ressourcen aktiv) vs. „pausiert, resumt" (keine Ressourcen, kein Datenverlust).
- **Kein K=3/LRU-Jargon** an den Nutzer — nur der ehrliche Zustand + die Rückversicherung „Resume beim Öffnen". Der Nutzer muss die
  Cache-Mechanik nicht verstehen; „suspendiert, kommt beim Öffnen zurück" genügt und beruhigt.

---

## §5 — Backend-Dep (Teil 2) — Server-Kontrakt, PO liefert

Der per-Projekt-Session-Zustand **existiert client-seitig nicht** und ist **kein** Client-Konstrukt — nur der Server (Switch-Orchestrierung
.4b) kennt „hot/background/suspended". **Der Client bindet an ein Server-Signal.** Ich designe **gegen die ratifizierte Policy**
(① Background-live + LRU-K=3); der **exakte Kontrakt** (Enum-Namen z. B. `ProjectSessionState{ACTIVE,BACKGROUND,SUSPENDED}`, Feld an der
Projektliste vs. `/ws`-Delta, Update-Kanal) kommt vom PO, **sobald .4b-Switch-Semantik steht** → dann faltet der Client-Impl auf die echte Naht.

> **Design ist naht-bereit:** die Copy/Tags/Töne oben sind kontrakt-agnostisch (3 Zustände). Nur die **Bindung** (welches Feld/Event den
> Zustand trägt) wartet auf .4b. **Kein Blocker fürs Design/die Ratifikation** — nur fürs Dev-Wiring.
> **Falls der Server einen `ERROR`/failed-Session-Zustand liefert** (Policy nennt keinen): → error-toned, eigener Forward; nicht in diesem Scope.

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
- **Im Scope (Teil 2, Design):** per-Projekt-Session-Indikator (ACTIVE/BACKGROUND/SUSPENDED) im ▾-Menü, `TonedHint(INFO)`, Copy + Tags.
- **Backend-Dep (Teil 2):** per-Projekt-Session-State-Signal (Server-Kontrakt .4b, §5).
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
10. **BACKGROUND ≠ SUSPENDED unterscheidbar:** die zwei Zustände sind in Copy **und** a11y klar getrennt; ACTIVE bleibt die ●-Markierung.
11. **Autoritative Quelle:** der Indikator bindet an den **Server**-Session-State (Backend-Dep §5), nicht an client-geratenes „ist wohl im
    Hintergrund"; bis der Kontrakt steht, kein erfundener Client-Zustand.

---

## §10 — §-Asks / Forwards (nicht blockierend, PO-Call)

- **D4 Bar-Level-Summenhinweis** („N Projekte laufen im Hintergrund") — Ressourcen-Ehrlichkeit auf der immer-sichtbaren Leiste;
  berührt die Bar → **PO-Call** (Default: nur per-Projekt-Indikator im Menü). Copy/Tag in `-keys.md`/`-tags.md` als optional markiert.
- **Server-`STARTING`-Zustand** (falls .5 einen echten liefert) → „Startet…" bindet additiv daran statt client-`startPending`.
- **Session-`ERROR`-Zustand** (falls der Server einen surfaced) → error-toned, eigener Forward.

---

## §11 — Hand-off

- **Neue Keys:** **Teil 1: 1** (`agent_status_starting`) · **Teil 2: 3** (`project_session_background`, `project_session_suspended`,
  `a11y_project_session`) **+ optional 1** (`project_session_background_summary`, D4). DE+EN. Details `-keys.md`.
- **Neue Tags:** **Teil 1: 0** (reuse `agent.<id>.status`) · **Teil 2: 1** (`projectSwitcher.item.<id>.session`) **+ optional 1**
  (`projectSwitcher.backgroundSummary`, D4). ⚠ CYP-7-Sync. `-tags.md`.
- **Neue Tokens/Farben:** **0** (reuse M3 + `TonedHint(INFO)` + StatusIndicator-Töne). `-tokens.json`.
- **⚠ Backend-Dep (Teil 2):** per-Projekt-Session-State-Signal — **PO liefert den Server-Kontrakt nach .4b** (§5). Teil 1 hat **keine** Dep.
- **Konsumenten:** Dev CYP-256 (Teil 1) + CYP-255/Backend (Teil 2) + CYP-249 (Hygiene). Danach **UX-QA durch UIUX** gegen §9 (11 Invarianten).
