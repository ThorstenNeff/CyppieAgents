# First-Run §3.3 — Repo-Clone-Lifecycle · web-ts Screen-Spec

**Für:** Dev5 · **Grounded auf:** Backend2s `:core`-Contract (adoptiert Dev5s gebautes Client-Enum verbatim, 2026-07-19) · **Von:** UIUX2 (Team-2) · **Begleitet:** `firstrun-3-2-guided-setup-screen-spec.md` (§3.2)
**Scope:** der Repo-Schritt-**Clone-Lifecycle** — das, was §3.2s Repo-Schritt von „gespeichert" auf „done" hebt. Baut, sobald der Seam (`cloneStatus` in `GET /api/config/repo`) steht.
**Tooling-Grenze:** Zustände headless render-test-messbar; das Live-Poll-/Timing-*Feel* = guided-human.

---

## §0 Contract (reconciled 2026-07-19 auf die finale `:core`-Shape)
Backend2 hat Dev5s **schon gebautes** Client-Enum gelesen und `:core` adoptiert es **verbatim** — ich spitze §3.3 darauf (mein `88b26ebf` matchte die vorläufige Shape):
- **`CloneStatus{ NOT_CONFIGURED, CONFIGURED_NEVER_CLONED, CLONING, CLONE_FAILED, CLONED_OK }`**
- **`CloneFailReason{ URL_UNREACHABLE, AUTH, UNKNOWN }`** (bei `CLONE_FAILED`) — **3-wertig.**
- **`cloneStatus == null / absent` ⇒ UNKNOWN** (lädt / Feld noch nicht da / Ladefehler) — nie false-OK.

**★ Zwei ratifizierte Honesty-Verfeinerungen (beide honester als meine Erst-Shape):**
1. **`SLOW` als server-Phase GEDROPPT** — der Server kann „slow" **nicht ehrlich** von „läuft" trennen (die ~15s-Schwelle ist willkürlich) → ein server-`SLOW` wäre eine **geratene Tatsache** ([[measured-vs-derived]] / forecast≠observed: die Quelle behauptet nie einen Zustand, den sie nicht kennt). **„dauert-länger" = CLIENT-elapsed-time** (der Client **misst** die `CLONING`-Dauer — Beobachtung, kein Guess), ein **Attribut auf `CLONING`**, keine Phase.
2. **`CloneFailReason.UNKNOWN` (3. Reason) ist honester** — nie AUTH-vs-URL **fabrizieren**, wenn der Server den Grund nicht kennt; `UNKNOWN` = fail-closed „fehlgeschlagen — Grund nicht ermittelbar" ([[reconcile-not-collapse-distinct-states]]: distinkte Gründe **inklusive** eines ehrlichen „unbekannt", statt in einen der zwei zu raten).

## §1 States (je: Bedeutung · Copy · Honesty)
| `CloneStatus` | Bedeutung | Marker/Copy (Vorschlag) | Honesty |
|---|---|---|---|
| **UNKNOWN** (`null`/absent) | Status unbestimmt (lädt / Feld fehlt / Ladefehler) | neutraler „Status unbekannt", **kein** OK | **fail-closed: nie false-OK** |
| **NOT_CONFIGURED** | kein Repo gesetzt | (= §3.2-Repo-Schritt **offen**) | ≠ „gespeichert", ≠ done |
| **CONFIGURED_NEVER_CLONED** | Config akzeptiert, Clone noch nicht/ausstehend | „gespeichert — Klonen steht aus" | **≠ CLONED_OK / „funktioniert"** |
| **CLONING** | Clone läuft | „Repository wird geklont…" (progress) | Zwischenzustand, kein done |
| ↳ *client-elapsed „dauert-länger"* | `CLONING` > client-Schwelle (~15s) | „…dauert länger als üblich" | **Attribut auf CLONING, KEINE Phase** — client-**gemessene** elapsed-time; **≠ hängend, ≠ fehlgeschlagen, ≠ done** |
| **CLONE_FAILED · URL_UNREACHABLE** | URL/Repo nicht erreichbar | „Repository nicht erreichbar — URL prüfen" + **Retry** | distinkt |
| **CLONE_FAILED · AUTH** | Zugang abgelehnt | „Zugang abgelehnt — Token/SSH-Schlüssel prüfen" + **Retry** | distinkt |
| **CLONE_FAILED · UNKNOWN** | Grund nicht ermittelbar | „Klonen fehlgeschlagen — Grund nicht ermittelbar" + **Retry** | **ehrliches „unbekannt", nie in AUTH/URL geraten** |
| **CLONED_OK** | Geklont | „Repository geklont ✓" | = das done, das §3.2-Repo-done + Gate-TRANSPARENT freigibt |

## §2 Honesty-Zähne (diskriminierend)
1. **★ UNKNOWN (`null`) ≠ CLONED_OK** — ein `null`/absent `cloneStatus` rendert **nie** als OK/„geklont"/done (fail-closed); der Config-Ladefehler = error+retry, nicht „geklont" ([[absence-reads-as-all-clear]]). *(Mutation: null→OK → RED.)*
2. **CONFIGURED_NEVER_CLONED ≠ CLONED_OK** — „gespeichert" (URL akzeptiert) ist **nicht** „geklont"; nie „das Repo funktioniert" (der §3.2-Punkt, hier terminal aufgelöst). *(Mutation: saved→„done" → RED.)*
3. **★ „dauert-länger" = client-elapsed, kein Server-State** — aus der **client-gemessenen** `CLONING`-Dauer (Beobachtung), **nie** aus einem server-`SLOW` (existiert nicht). Advisory, **nie** „hängt"/„fehlgeschlagen"/„fertig" ([[over-alarm-is-also-dishonest]]); bleibt `CLONING`, ein Attribut, keine Phase. *(Mutation: →error-Ton ODER →ok/failed ODER als eigene Phase → RED.)*
4. **★ CLONE_FAILED-Gründe distinkt (URL_UNREACHABLE vs AUTH vs UNKNOWN)** — je **eigene** Copy + Fix; `UNKNOWN` bleibt **ehrlich „unbekannt", nie in AUTH/URL geraten** ([[reconcile-not-collapse-distinct-states]]). *(Mutation: alle drei → eine Copy, ODER UNKNOWN→AUTH/URL geraten → RED.)*
5. **State = server-`cloneStatus`, non-optimistisch** — der Marker kommt aus dem gepollten/gepushten `cloneStatus`, **nie** client-geraten; kein optimistisches „OK" vor dem Server-Signal. (Ausnahme, die KEINE ist: die „dauert-länger"-elapsed-time ist client-**gemessen**, keine geratene Phase.)

## §3 Progression + Polling (CMP CYP-629 §7.3)
- **CONFIGURED_NEVER_CLONED → CLONING (+ client-elapsed „dauert-länger" bei ~15s) → CLONED_OK** *oder* **CLONE_FAILED[URL_UNREACHABLE|AUTH|UNKNOWN]**. **Terminal = CLONED_OK oder CLONE_FAILED.**
- **Poll (WS-Nudge gedroppt → nur Poll)** von `cloneStatus` (`GET /api/config/repo`). **Kadenz:** **~2s** im aktiven Fenster; nach der Long-Running-Schwelle **ausdünnen auf ~30s**. **Stopp NUR bei terminal** (`CLONED_OK`/`CLONE_FAILED`) — **ausdünnen ≠ aufgeben** (das eventuelle Ende muss noch gefangen werden). **Component-lifecycle-bounded** (Skip/Unmount stoppt, Rückkehr re-pollt) → **kein Forever-Loop**.
- **Elapsed-Schwellen (client-gemessen, auf `CLONING`, KEINE server-States):** **~15s** → advisory „dauert länger als üblich" (`first_run_repo_cloning_slow`); **~60s** → Poll ausdünnen (~30s) + **Elapsed sichtbar machen** „dauert schon {min} min" (`first_run_repo_cloning_long`). **Beide bleiben `CLONING`.** — Copy-Wahl: die **Dauer** zeigen (ehrlicher, nützlicher Fakt), **nicht** ein Forever-„klont noch"-Spinner (behaupteter Fortschritt) und **nicht** „Status unbestimmt/kaputt" (Alarm).
- **★ NIE „failed" aus Elapsed-Time** — ein **hängender** Clone ist client-seitig **ununterscheidbar** von einem **langsamen**; „failed" wäre eine **erfundene Diagnose** (SLOW-drop-Linie: der Client rät nie einen Zustand aus der Zeit; unknown bleibt unknown). Long-running = ehrliches **„läuft noch, Dauer unbekannt"**, nie failed/hung.
- **CLONE_FAILED → actionable Fix + Retry:** der Grund nennt den Fix (URL / Token) — bei `UNKNOWN` ehrlich „Grund nicht ermittelbar" + Retry. Retry re-triggert (zurück zu CLONING). Non-optimistisch: Zustand flippt auf den nächsten server-`cloneStatus`, nicht auf den Retry-Klick.

## §4 Naht zu §3.2 (die Vollendung)
- **`CLONED_OK` hebt §3.2s Repo-Schritt von „gespeichert" auf „done"** → mit API-Key-set → Gate **TRANSPARENT**. §3.3 **vollendet** §3.2s Repo-Schritt (der ohne den Seam bei „gespeichert" hing).
- Der **Repo-Schritt-Marker in §3.2 zeigt jetzt den Live-`cloneStatus`** (CONFIGURED_NEVER_CLONED → CLONING → CLONED_OK/CLONE_FAILED) statt nur „gespeichert".
- **★ Skip-Persistenz (ratifiziert 2026-07-19):** solange kein CLONED_OK ist TRANSPARENT unerreichbar → der Skip wird **client-lokal (per-Browser) erinnert**, damit man nicht bei jedem Laden im Gate landet. **Ehrlich, weil die Wahrheit NICHT im Gate lebt:** der **Gate = Führung** (wegklickbar = UI-Präferenz, kein Fakt — anders als CYP-705s server-`seen`); die **Wahrheit = das nicht-ausblendbare §3.1-Banner + agent-start-Gating** (server-getrieben, immer sichtbar solange `!configured`/`!CLONED_OK`). **Bedingungen:** (a) Skip unterdrückt Banner/Gating **nie**; (b) Resume-Pfad zurück in den Gate (das Banner-„Einrichten"); (c) sobald server-`CLONED_OK`/`configured` → Banner UND Gate klären sich.

## §5 Layout + testTags
- Der **Repo-Schritt (§3.2-Stepper)** trägt den `cloneStatus`-Marker; **CLONE_FAILED** zeigt Grund + Fix + Retry **inline**.
- „dauert-länger" = derselbe progress-Marker wie CLONING + ein advisory-Zusatz, **nicht** ein Farb-/Ton-Wechsel zu WARN/error.
- testTags: `firstrun.repo.cloneStatus.{notConfigured|configuredNeverCloned|cloning|failed|ok}` · `firstrun.repo.cloneSlowHint` (client-elapsed, kein Phase-Tag) · `firstrun.repo.cloneFailReason.{urlUnreachable|auth|unknown}` · `firstrun.repo.cloneRetry` · `firstrun.repo.cloneUnknown`.

## §6 Übergabe-Flags
- **Grounded auf die finale `:core`-Shape** (`CloneStatus` + `CloneFailReason`, 3-wertig; SLOW = client-elapsed). Build-ready, sobald der Seam (`cloneStatus` in `GET /api/config/repo`) steht.
- **Vollendet §3.2** (CLONED_OK → §3.2-Repo-done → Gate-TRANSPARENT erreichbar); die **Skip-Persistenz** (§4) überbrückt die Zeit bis dahin ehrlich.
- **Reuse:** der §3.2-Repo-Schritt-Marker; das honest-Zustands-Idiom.
- **Copy** (shared keys, mit Dev5s Impl landen — [[shared-key-landing]]):
  - `first_run_repo_cloning` „Repository wird geklont…" / "Cloning repository…"
  - `first_run_repo_cloning_slow` (~15s) „Das dauert länger als üblich — große Repositories brauchen Zeit." / "This is taking longer than usual — large repositories take time."
  - `first_run_repo_cloning_long` (~60s) „Klont noch — dauert schon {min} min. Große Repositories können lange brauchen; du kannst warten oder später zurückkommen." / "Still cloning — {min} min so far. Large repositories can take a while; you can wait or come back later."
  - plus die `CloneStatus`-/`CloneFailReason`-Copys (§1); weitere DE/EN auf Zuruf.
