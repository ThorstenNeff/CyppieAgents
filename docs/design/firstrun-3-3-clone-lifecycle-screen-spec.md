# First-Run §3.3 — Repo-Clone-Lifecycle (5 States) · web-ts Screen-Spec

**Für:** Dev5 · **Grounded auf:** Backend2 CYP-736-Contract (gepinnt 2026-07-19) · **Von:** UIUX2 (Team-2) · **Begleitet:** `firstrun-3-2-guided-setup-screen-spec.md` (§3.2)
**Scope:** der Repo-Schritt-**Clone-Lifecycle** — das, was §3.2s Repo-Schritt von „gespeichert" auf „done" hebt. Baut, sobald Backend2 den Seam liefert.
**Tooling-Grenze:** Zustände headless render-test-messbar; das Live-Poll-/Timing-*Feel* = guided-human.

---

## §0 Contract-Match (CYP-736) — bestätigt, KEINE Divergenz zu flaggen
Backend2s gepinnter Contract mappt **exakt** auf meinen §3.2-§7 / Parität-§3.3-Intent:
| Backend2 CYP-736 | mein Intent | 
|---|---|
| `ClonePhase.SAVED` | „gespeichert" (Config akzeptiert, Clone nicht gestartet) |
| `ClonePhase.CLONING` | „wird geklont" |
| `ClonePhase.SLOW` (~15s-Schwelle) | „cloning-slow" (ehrlicher Zwischenzustand) |
| `ClonePhase.OK` | „CLONED_OK" (done — hebt §3.2-Repo auf done) |
| `ClonePhase.FAILED` + `CloneFailureReason{AUTH,URL}` | „failed", auth-vs-url **distinkt** |
| `clonePhase == null` | **UNKNOWN** (nie false-OK) |
**→ exakter Match. Backend2 baut; ich mappe nichts anders.**

## §1 Die 5 States + UNKNOWN (je: Bedeutung · Copy · Honesty)
| State | Bedeutung | Marker/Copy (Vorschlag) | Honesty |
|---|---|---|---|
| **SAVED** | Config akzeptiert, Clone noch nicht gestartet | „gespeichert — Klonen steht aus" | **≠ OK/„funktioniert"** |
| **CLONING** | Clone läuft | „Repository wird geklont…" (progress) | Zwischenzustand, kein done |
| **SLOW** | Clone läuft, > ~15s | „…dauert länger als üblich" | **≠ hängend, ≠ fehlgeschlagen** — advisory-reassure |
| **OK** | Geklont | „Repository geklont ✓" | = das **CLONED_OK**, das §3.2-Repo-done + Gate-TRANSPARENT freigibt |
| **FAILED · AUTH** | Zugang abgelehnt | „Zugang abgelehnt — Token/SSH-Schlüssel prüfen" + **Retry** | **distinkt** von URL |
| **FAILED · URL** | URL/Repo ungültig | „Repository nicht gefunden — URL prüfen" + **Retry** | **distinkt** von AUTH |
| **UNKNOWN** (`null`) | clonePhase unbestimmt (lädt/absent) | neutraler „Status unbekannt", **kein** OK | **fail-closed: nie false-OK** |

## §2 Honesty-Zähne (diskriminierend)
1. **★ UNKNOWN (`null`) ≠ OK** — ein `null` clonePhase rendert **nie** als OK/„geklont"/done (fail-closed); der Config-Ladefehler = error+retry, nicht „geklont" ([[absence-reads-as-all-clear]]). *(Mutation: null→OK/done → RED.)*
2. **SAVED ≠ OK** — „gespeichert" (URL akzeptiert) ist **nicht** „geklont"; nie als „das Repo funktioniert" (der §3.2-Punkt, hier terminal aufgelöst). *(Mutation: SAVED→„done" → RED.)*
3. **★ SLOW ehrlich** — advisory „dauert länger", **nie** „hängt"/„fehlgeschlagen"/„fertig" ([[over-alarm-is-also-dishonest]]: SLOW ist kein Fehler und kein Erfolg, nur ein langsamer Zwischenstand). *(Mutation: SLOW→error-Ton ODER SLOW→ok → RED.)*
4. **★ FAILED-Gründe distinkt (AUTH vs URL)** — je **eigene** Copy + **actionable Fix** (Token/SSH vs URL); **nie** ein generisches „fehlgeschlagen" ([[reconcile-not-collapse-distinct-states]]). *(Mutation: AUTH+URL → eine gemeinsame Copy → RED.)*
5. **State = server-`clonePhase`, non-optimistisch** — der Marker kommt aus dem gepollten/gepushten `clonePhase`, **nie** client-geraten; kein optimistisches „OK" vor dem Server-Signal.

## §3 Progression + Polling (CMP CYP-629 §7.3)
- **SAVED → CLONING → (SLOW bei ~15s) → OK** *oder* **FAILED[AUTH|URL]**. **Terminal = OK oder FAILED.**
- Der Client **pollt** (oder empfängt) `clonePhase` **bis terminal** (Poll **stoppt** bei OK/FAILED — kein endloses Pollen; vgl. das 705-Ledger-Prinzip, Requests bounden).
- **FAILED → actionable Fix + Retry:** der Grund (AUTH/URL) nennt den konkreten Fix; Retry re-triggert den Clone (zurück zu SAVED→CLONING). Non-optimistisch: der Zustand flippt auf den nächsten server-`clonePhase`, nicht auf den Retry-Klick.

## §4 Naht zu §3.2 (die Vollendung)
- **`ClonePhase.OK` IST das CLONED_OK**, das §3.2s Repo-Schritt von „gespeichert" auf **„done"** hebt → mit API-Key-set → Gate **TRANSPARENT**. §3.3 **vollendet** §3.2s Repo-Schritt (der ohne den Seam bei „gespeichert" hing; „TRANSPARENT unerreichbar bis CYP-736" — jetzt erreichbar).
- Der **Repo-Schritt-Marker in §3.2 zeigt jetzt den Live-Clone-State** (SAVED→CLONING→SLOW→OK/FAILED) statt nur „gespeichert".

## §5 Layout + testTags
- Der **Repo-Schritt (§3.2-Stepper)** trägt den Clone-State-Marker; **FAILED** zeigt Grund + Fix + Retry **inline** am Repo-Schritt.
- SLOW = derselbe progress-Marker wie CLONING + ein advisory-Zusatz („dauert länger"), **nicht** ein Farb-/Ton-Wechsel zu WARN/error.
- testTags: `firstrun.repo.clonePhase.{saved|cloning|slow|ok|failed}` · `firstrun.repo.cloneFailure.{auth|url}` · `firstrun.repo.cloneRetry` · `firstrun.repo.cloneUnknown`.

## §6 Übergabe-Flags
- **Grounded auf den CYP-736-Contract (exakter Match, keine Divergenz).** Build-ready, sobald Backend2 den Seam (`clonePhase` + `CloneFailureReason` in `GET /api/config/repo`) liefert.
- **Vollendet §3.2** (OK = §3.2-Repo-done → Gate-TRANSPARENT erreichbar).
- **Reuse:** der §3.2-Repo-Schritt-Marker; das honest-Zustands-Idiom (offen→gespeichert→cloning→slow→ok/failed).
- **Copy = neue `first_run_clone_*`-Keys** (shared, mit Dev5s Impl landen — [[shared-key-landing]]); DE/EN auf Zuruf.
