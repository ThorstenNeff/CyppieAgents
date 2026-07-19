# First-Run Onboarding Parität (web-ts) · UX-Spec — nicht-remote Phase-2

**Für:** PO + Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `7422d7e4` (am Objekt gemessen 2026-07-19)
**Modus:** Vorarbeit — bestätigtes nicht-remote Parität-Gap (Phase-2-§6). Reuse-schwer + ein Backend-Seam.
**Tooling-Grenze:** Zustände headless render-test-messbar; Runtime/Pixel = guided-human.

---

## §0 Kernaussage
web-ts hat die Config-**Surfaces** (`SettingsPanel` repo + `ApiKeyPanel`), aber **nicht** den **geführten First-Run** (Intro + Gate + 3-Schritt-Orchestrierung + Repo-Clone-Lifecycle + Unconfigured-Banner). Ein neuer Nutzer kann konfigurieren, wird aber **nicht geführt**. Parität = die **Orchestrierung** neu bauen, die bestehenden Surfaces **reusen** — plus ein Backend-Seam für den Live-Clone-Status.

## §1 CMP-First-Run-Referenz (CYP-629, am Objekt)
- **Intro** (`first_run_intro`): *„Dein Hub ist installiert und läuft — aber noch nicht eingerichtet. Richte den API-Key und das Repository ein, dann kann dein Team arbeiten."*
- **Gate** (`FirstRunGate` + `FirstRunViewModel`): treibt die Experience nach **Config-Status**; seed't `Unknown` während (re)load (honest-unknown, nicht „unconfigured" aus einem Ladefehler).
- **3 Schritte** (`first_run_step_apikey` / `_repo` / `_team`): **API-Key → Repository → Team**.
- **Repo-Clone-Lifecycle** (`FirstRunConfigStatus`, CYP-629 §7.1 — **5 Zustände**): repo not set · **saved** (set-but-never-cloned) · **cloning** (+ `cloning_slow`) · **clone-ok** · **clone-failed** (+ distinkte `_auth` / `_url`-Gründe).
- **Banner + Chip + agent-Gating** (`workspace_unconfigured_banner` / `_chip` / `agent_ctl_unconfigured`): „Hub nicht eingerichtet — Agenten können nicht starten. Einrichten." + agent-start disabled mit „Jetzt einrichten".
- **★ Stubbed bis §7-Backend-Seam:** `FirstRunConfigSource` ist gestubbt, *„real-swapped when `GET /api/config/repo` gains the field"* (der Clone-Status). **Auch CMP wartet auf den Seam** für den Live-Clone-Status.

## §2 web-ts-Ist (am Objekt gemessen)
- **DA (reuse-Ziele):** `SettingsPanel` (repo-config-Form) + `ApiKeyPanel` (maskierter API-Key), CYP-453 *„TWO gate classes: project config (repo + API key)"*; `App.tsx:156` **honest-unset-status** + Prefills; `GET /api/config/repo` liefert `{ configured, url?, branch? }`.
- **FEHLT (das Gap):** Intro · Gate/Orchestrierung · 3-Schritt-Flow · Repo-Clone-Lifecycle-UX · **Unconfigured-Banner/Chip** · agent-start-Gating-mit-„einrichten". *(Grep bestätigt: web-ts rendert **keinen** Unconfigured-Banner — nur Handoff/Overload-Banner.)*

## §3 Was web-ts für Parität braucht
1. **Unconfigured-Banner + Chip + agent-start-Gating** *(klein, buildbar JETZT)*: wenn `!configured` → persistenter Banner „Hub nicht eingerichtet — einrichten" + `Nicht eingerichtet`-Chip + **agent-start disabled** mit „Jetzt einrichten". Reuse den bestehenden `configured`-Status (`App.tsx:156`); Banner-Idiom wie `OverloadBanner`/`HandoffBanner` (persistent, role, reuse).
2. **Geführter First-Run (Intro + Gate + 3 Schritte)** *(buildbar JETZT für API-Key + Repo-URL)*: Intro-Screen → geführte Reihenfolge **API-Key → Repo → Team**, Status je Schritt (offen/erledigt), **reuse `ApiKeyPanel` + repo-config als Schritt-Inhalte**. Die **Orchestrierung** (Gate + Schritt-Reihenfolge + Fortschritt) ist neu. Gate seed't **`Unknown` während load** (honest, nicht „unconfigured" aus Ladefehler — CYP-679/288-Klasse).
3. **Repo-Clone-Lifecycle-UX** (saved→cloning→slow→ok→failed[auth/url]) *(SEAM-BLOCKED)*: **blockiert auf den §7-Backend-Seam** — `GET /api/config/repo` muss den **Clone-Status** exponieren (wie CMP gestubbt). Ohne Seam: nur **„gespeichert"** (kein Live-Clone-Status). Mit Seam: die 5 Zustände honest rendern.

## §4 Honesty-Leitplanken
- **unconfigured ≠ configured** — honest-unset (der bestehende `App.tsx:156`-Status trägt); nie ein falsches „eingerichtet".
- **honest-unset ≠ load-error** — der Gate seed't `Unknown` während load; ein Config-Ladefehler rendert **error+retry** (CYP-288/679), **nicht** „unconfigured" (sonst false „richte ein", obwohl evtl. schon eingerichtet). [[absence-reads-as-all-clear]]-Schwester.
- **clone-failed-Gründe distinkt** (`_auth` vs `_url`) — **nicht** in ein generisches „fehlgeschlagen" einebnen ([[reconcile-not-collapse-distinct-states]]); jeder Grund trägt seine eigene Copy + Fix-Hinweis.
- **cloning-slow ehrlich** — ein langsamer Clone ist ein **advisory Zwischenzustand** (≠ hängend, ≠ fehlgeschlagen); nie als Fehler oder als fertig darstellen.
- **agent-start-Gating ehrlich** — disabled-weil-unconfigured ist ein **present-but-disabled** Zustand mit Grund (aria), nie eine stille Auslassung oder ein fake-aktiver Button.

## §5 Reconcile mit Backend2 (§7-Seam)
- **`GET /api/config/repo` muss den Clone-Status exponieren** (die 5 Lifecycle-Zustände) für §3.3. Bis dahin: §3.1 (Banner/Chip/Gating) + §3.2 (Intro + Gate + 3-Schritt für API-Key + Repo-URL, bis „gespeichert") sind **buildbar**; der Live-Clone-Status ist **seam-blocked** (identisch zu CMPs Stub).

## §6 Übergabe-Flags
- **Buildbar jetzt (Team-2):** §3.1 (Unconfigured-Banner+Chip+agent-Gating) + §3.2 (Intro + Gate + 3-Schritt-Orchestrierung, reuse `ApiKeyPanel`+repo-config).
- **Seam-blocked:** §3.3 (Repo-Clone-Lifecycle-Live-UX) — auf den `GET /api/config/repo`-Clone-Status (wie CMP gestubbt).
- **Reuse:** `ApiKeyPanel`, `SettingsPanel`-repo-config, `App.tsx`-honest-unset-status, Banner-Idiom (`OverloadBanner`/`HandoffBanner`).
- **Shared Keys:** `first_run_*` + `workspace_unconfigured_*` existieren in den CMP-strings — als **shared keys mit Dev5s Impl** landen ([[shared-key-landing]]), nicht vorab.
- **Nächster Schritt:** je §3-Teil ein Screen-Spec (Intro/Gate/Steps, Banner, Clone-Lifecycle nach Seam) — dann build-fertig.
