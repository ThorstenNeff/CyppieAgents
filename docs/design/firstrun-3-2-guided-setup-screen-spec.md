# First-Run §3.2 — Guided Setup (Intro + Gate + 3-Schritt) · web-ts Screen-Spec

**Für:** Dev5 (hält §3.2 bis zu diesem Spec — richtig, nicht raten) · **Von:** UIUX2 (Team-2) · **Baseline:** develop `7422d7e4` (am Objekt) · **Begleitet:** `firstrun-onboarding-parity-ux-spec.md`
**Scope:** §3.2 = der **geführte First-Run** (Intro + Gate + 3-Schritt API-Key→Repo→Team). **§3.1** (Banner+Gating) = Dev5 `CYP-735`. **§3.3** (Live-Clone-5-State) = seam-blocked (`CYP-736`, mein Clone-Spec danach).
**Tooling-Grenze:** Zustände headless render-test-messbar; Runtime/Pixel = guided-human.

---

## §1 Der Gate im Mount-Chain (CMP CYP-629 §1, am Objekt)
Reihenfolge: `AuthGate → [Hub-Connect] → **FirstRunGate** → Workspace`. Der Modus wird **rein aus dem Config-Status abgeleitet** (`firstRunGateMode`):
- **LOADING** (Status unbekannt) → Live-Load-Surface. **NIE als „Schritt 1"** — **unknown ≠ unconfigured** (fail-closed; siehe §6.1).
- **ACTIVE** (unconfigured/partiell) → die Setup-Schritte (§5).
- **TRANSPARENT** (beide Kern-Schritte done) → direkt zur Workspace, der Gate **verschwindet**.

## §2 ★ Blockiert-oder-daneben? (deine Frage) — **blockiert by default, aber SKIPPABLE zur DegradedWorkspace**
- **Default: der Gate blockiert die Workspace** (ACTIVE = Setup **vor** der Workspace) — ein neuer Nutzer landet geführt im Setup, nicht in einer leeren, nicht-startfähigen Workspace.
- **ABER skippable → DegradedWorkspace** (CMP §6.2): die **echte** Workspace **+ persistenter Unconfigured-Banner/Chip [§3.1] + Resume-CTA** zurück in den Gate. Der Banner klärt sich selbst, sobald konfiguriert.
- **Honest-Mitte:** geführt by default (kein „leere Workspace, viel Glück"), **entkommbar** (kein hart-gefangener Zwangs-Wizard), **nie pretend-configured** (der degradierte Zustand trägt sichtbar den Banner + agent-start-Gating). Weder blockierend-gefangen noch daneben-ignoriert.

## §3 ★ Fortschritts-Semantik (deine Frage) — **„erledigt" = SERVER-VALIDIERT, nicht form-submitted** (der load-bearing Honesty-Punkt)
- **API-Key:** done = **der Server hält ihn** (`apiKeyView.set === true`, masked `***last4`). Der Client hat nie den Plaintext; „gespeichert" heißt „server-bestätigt hinterlegt", nicht „lokal getippt".
- **Repo — ZWEI Ebenen, „gespeichert" ≠ „done":**
  - **„gespeichert"** = `GET /api/config/repo` `configured:true` (URL/Branch **akzeptiert**).
  - **„done" = CLONED_OK** (das Repo ist **wirklich geklont**) — das ist der **§3.3-Clone-Lifecycle-Terminal-State (seam-blocked, `CYP-736`)**.
  - **In §3.2 (ohne Seam) erreicht der Repo-Schritt „gespeichert", NICHT „done/geklont".** Die CLONED_OK-Vollendung kommt mit §3.3. **★ „gespeichert" NIE als „das Repo funktioniert" darstellen** — das wäre die false-configured-Lüge (ein akzeptierter URL ≠ ein klonbares Repo).
- **Team:** **informational (Intro), KEIN gating-„done"** — „hier ist dein Team, es arbeitet, sobald eingerichtet". Man braucht **keine** Agenten konfiguriert, um fortzufahren; Team nie als Blocker.
- **Gesamt-Vollendung (TRANSPARENT-Kriterium):** **API-Key-set UND Repo-CLONED_OK** (Team nicht gating). **unknown ≠ done** — LOADING nie als done.
- **Resume (CMP §6.3b):** der Gate landet beim **ersten noch-nicht-done Schritt**, nicht immer Schritt 1.

## §4 Copy (reuse die CMP `first_run_*`-Strings — shared keys mit Dev5s Impl)
- Intro `first_run_intro` · Schritte `first_run_step_apikey` / `_repo` / `_team` · saved-Bestätigungen `first_run_apikey_saved` / `first_run_repo_saved` · complete `first_run_complete_title` / `_body`.
- **Ich liefere DE/EN-Strings auf Zuruf; die Keys landen mit Dev5s Impl** ([[shared-key-landing]]), nicht vorab — sonst driftet der Shared-Check.

## §5 Layout
- **Stepper** API-Key → Repo → Team; je Schritt ein **honest 3-Zustands-Marker**: **offen** / **gespeichert** (advisory Zwischenstand) / **done** (server-validiert). **unknown/lädt ≠ done** ([[absence-reads-as-all-clear]]-Schwester: ein unbestimmter Schritt ist nicht „offen aus einem Ladefehler").
- **Schritt-Inhalte = reuse:** `ApiKeyPanel` (write-only, masked Status) für API-Key · repo-config-Form (`SettingsPanel`-Teil) für Repo · **Team = Intro-Sektion** (kein Formular).
- **LOADING** = Load-Surface (nicht Schritt 1). **TRANSPARENT** = nichts (direkt Workspace). **Skip** → DegradedWorkspace (§2).
- testTags: `firstrun.gate` · `firstrun.step.{apikey|repo|team}` · `firstrun.step.{id}.status` (offen/gespeichert/done) · `firstrun.skip` · `firstrun.resume`.

## §6 Honesty-Zähne (diskriminierend)
1. **★ unknown ≠ configured** — LOADING rendert **nie** als Schritt/„unconfigured"/„done"; ein Config-**Ladefehler** = error+retry, **nicht** „richte ein" (sonst false „einrichten", obwohl evtl. schon konfiguriert — CYP-288/679-Klasse). *(Mutation: LOADING→„Schritt 1" oder →„unconfigured" → RED.)*
2. **★ repo „gespeichert" ≠ „done/geklont"** — zwei Ebenen; „gespeichert" nie als „das Repo funktioniert"/„fertig". *(Mutation: saved→done ohne CLONED_OK → RED.)*
3. **„done" = server-validiert**, nie form-submitted/optimistisch. *(Mutation: Schritt done auf Submit statt Server-Bestätigung → RED.)*
4. **skippable-aber-honest** — Skip → DegradedWorkspace **mit** persistentem Banner/Gating (§3.1), nie eine „saubere" Workspace ohne Hinweis. *(Mutation: Skip → Workspace ohne Banner → RED = pretend-configured.)*
5. **Team nicht gating** — informational; nie „du musst ein Team anlegen" als Fortfahr-Blocker.

## §7 Übergabe-Flags
- **Build-ready jetzt (§3.2):** Intro + Gate (LOADING/ACTIVE/TRANSPARENT) + 3-Schritt-Stepper + Skip→DegradedWorkspace, **reuse `ApiKeyPanel` + repo-config**. „done" = server-validiert; **der Repo-Schritt erreicht in §3.2 „gespeichert" als höchsten State** (CLONED_OK = §3.3).
- **★ Naht zu §3.3 (`CYP-736`) — (d) Sequencing (ratifiziert 2026-07-19):** `TRANSPARENT=CLONED_OK` greift erst, wenn CLONED_OK erreichbar ist. Lösung (Dev5 `b0c11301`): die §3.2-Komponenten landen **additiv**, der **Gate wird MIT CYP-736 montiert** — kein premature-Gate → **kein Jeder-Hub-Wizard, kein Interim-Debt, Spec unverändert**. **Interim pre-CYP-736** (kein Gate): das schon-gebaute **§3.1-Banner + Settings-Config** trägt das Onboarding honest („einrichten" → Settings). Mein §3.3-Clone-Lifecycle-Spec co-landet mit dem Seam.
- **Dokumentierte Fallback-Option (DECOUPLE):** falls die gekoppelte Gate-UX (`TRANSPARENT=CLONED_OK` → configured-Hub im Wizard während/nach dem Clone, Trap bei `CLONE_FAILED`) sich später als schlecht erweist: **Wizard-Gate = `configured`** (Setup-Aktionen) + Clone-Status als **SEPARATE** Fläche (§3.3 + §3.1-Gating `setupBlocked = !configured || cloneStatus===CLONE_FAILED`, block-on-known-failure, UNKNOWN-gezeigt-nicht-geblockt). **Beide ehrlich** (keiner faked cloned) — die Wahl ist Modell-/UX-Präferenz, keine Honesty-Frage. Für jetzt: **(d)**.
- **Shared Keys** (`first_run_*`) landen mit Dev5s Impl.
