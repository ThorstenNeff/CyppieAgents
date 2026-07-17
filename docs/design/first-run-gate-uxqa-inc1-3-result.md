# CYP-629 First-Run Gate — UX-QA Result (Inc1–3)

> Owner: UIUX-Designer · UX-QA-Pass · Stand 2026-07-17 · **kein Bau, READ-ONLY** gg. **`origin/develop`
> `45c5ceb4`** (Inc1–3 gemergt; Dev baut Inc4). Spec: `first-run-setup-{ux-spec,keys,tags,a11y}.md`.
> **Wichtig:** das lokale Working-Tree stand auf `f2273267` (prä-Merge) — **alle Zitate sind aus dem
> `origin/develop`-Ref gelesen** (`git show origin/develop:…`), nicht aus dem Working-Tree.

## 0. Verdikt

**🟢 GO auf das gebaute Inc1–3 — spec-treu und ehrlich.** Beide PO-Fokusfragen (Clone-Zustände unterscheidbar /
Slow-Zeile als Lebenszeichen) bestehen **auf Code-Ebene.** **Aber ein Ehrlichkeits-Vorbehalt, den der PO kennen
muss:** die Fläche ist **absichtlich inert** (`enabled=false` per Default, **nicht** in `App.kt` verdrahtet) — es
gibt **heute keinen Live-Render, den ein echter Mensch sieht.** Die „für einen echten Menschen"-Frage ist damit
**Code-Ebene beantwortet, nicht Live** (§1). Das ist **kein Defekt** — es ist die dokumentierte Opt-in-off-
Disziplin, die auf **meinen §7-Backend-Seam + Deploy-GO** wartet.

## 1. ★ Render-Realität (Top, aber KEIN Defekt): die Fläche ist absichtlich inert

Firsthand gegen `origin/develop` verifiziert:
- **Kein `FirstRunGate`-Aufruf in `App.kt`** — `App.kt` komponiert `AuthGate → RemoteHubConnectGate → AgentShell`
  direkt; **null Produktions-Call-Sites** (nur Tests referenzieren `FirstRunGate`).
- **`enabled=false` per Default** (`FirstRunGate.kt`): „OFF (default): construct nothing — byte-identical to a
  build without the first-run flow." KDoc wörtlich: *„with `enabled` false (**the default until the §7 Backend
  seam ships + a deploy GO**) the gate is inert."* — spiegelt die `RemoteHubConnectGate`-Opt-in-off-Disziplin.

**⟹ Konsequenz für diese QA:** ein **Live-Human-Render existiert nicht** — die vier Clone-Zustände / die Slow-Zeile
sind **im Code** geprüft (Fidelity + Honesty), nicht am laufenden Bild. **Die echte „für einen Menschen"-Live-QA
ist ein SEPARATES Gate** und wartet auf: (a) **§7-Backend-`cloneStatus`-Seam gebaut + geroutet** (die Abhängigkeit,
die du an Backend routen wolltest — die Zustände werden aus genau diesem Seam gepollt), **und** (b) Gate
`enabled` + in `App.kt` verdrahtet + Deploy-GO. **Ehrliche Einordnung:** „Inc1–3 gemergt" ≠ „live sichtbar" — by
design. Kein Fehler von Dev; das Gate wartet korrekt fail-closed auf seine eigene Abhängigkeit.

> **Doc-Drift (Minor):** der `FirstRunGate.kt`-KDoc behauptet die Platzierung
> `AuthGate → RemoteHubConnectGate → ▶ FirstRunGate ◀ → Workspace` als Ist — sie ist **noch nicht** in `App.kt`
> vollzogen (sie ist der Soll-Zustand nach dem Enable). Beim Enable-Schritt (Inc4/Deploy) die Verdrahtung
> tatsächlich einsetzen; bis dahin liest der KDoc leicht voraus.

## 2. ★ PO-Fokusfrage 1 — sind die vier Clone-Zustände unterscheidbar? (Code-Ebene: JA, 1 Watchpoint)

`CloneStatusDisplay` (`FirstRunSteps.kt`), `cloneDisplay()` kollabiert 5 Roh-Werte → 3 Slots + null:

| Zustand | Copy | Ton/Glyph/Farbe | Extra-Signal | Tag | Unterscheidbarkeit |
|---|---|---|---|---|---|
| **NOT_CONFIGURED** | — (nichts) | — | — | — | korrekt still (Repo noch nicht gesetzt; State trägt das leere Repo-Feld + Stepper) |
| **CLONING** | `first_run_repo_cloning` | INFO · „i" · secondary | **live 18dp Spinner** | `firstRun.repo.cloning` (Polite) | ✅ Spinner = starkes Lebenszeichen |
| **CLONED_OK** | `first_run_repo_clone_ok` | INFO · „i" · secondary | **kein** Spinner, **kein** Grün | `firstRun.repo.cloneOk` (Polite) | ⚠ nur Copy + Spinner-Abwesenheit (s.u.) |
| **CLONE_FAILED** | `_url`/`_auth`/generisch je `reason` | **ERROR · „✕" · error** | — | `firstRun.repo.cloneFailed` (Assertive) | ✅ stark distinkt |

- **CLONING vs CLONE_FAILED:** stark unterscheidbar (INFO „i" secondary + Spinner **vs** ERROR „✕" rot, Assertive). ✅
- **NOT_CONFIGURED:** rendert nichts — **korrekt** (vor dem Setzen gibt es keinen Clone; der Zustand wird vom leeren
  Repo-Feld + der offenen „Repository"-Stepper-Chip getragen, nicht von einer Clone-Zeile). ✅
- ⚠ **Watchpoint (Med) — CLONING ↔ CLONED_OK:** **beide** INFO, **gleicher** Glyph „i", **gleiche** secondary-
  Farbe. Der **einzige** Unterschied ist (a) die Copy und (b) **Spinner da / Spinner weg.** Das ist **bewusst**
  („no green", Honesty — Grün würde Erfolg überzeichnen). Mein QA-Urteil: **ausreichend** — der Spinner ist ein
  starkes Live-Signal, sein Verschwinden + die abgesetzte Copy („geklont. Dein Team kann arbeiten.") liest als
  fertig. **Aber es ist die dünnste Naht:** das **einzige** Erfolgssignal ist Copy + Spinner-Abwesenheit, **kein
  positives** Erfolgs-Affordance. **Für die Live-QA der Watch:** wenn echte Nutzer den „fertig"-Moment verpassen,
  ist der ehrliche Hebel ein **nicht-farbliches** distinktes „fertig"-Signal (z. B. ein eigener neutraler Glyph für
  OK statt „i") — **nie Grün.** Heute kein Defekt, spec-treu.

## 3. ★ PO-Fokusfrage 2 — liest die Slow-Zeile als Lebenszeichen, nicht als Fehler? (JA)

`FirstRunSteps.kt`, CLONING-Zweig:
- **Schwelle 15 s** (`CLONE_SLOW_THRESHOLD_MS = 15_000`), `LaunchedEffect(Unit){ delay(…); slow=true }` — client-
  seitiger Render-Timer, an die CLONING-Composable-Lebenszeit gebunden (schneller Clone <15 s → nie über-gewarnt). ✅
- **Lebenszeichen bleibt:** der **18dp `CircularProgressIndicator` läuft weiter** über der Slow-Zeile (der animierte
  Cue); die Slow-Zeile selbst ist statischer INFO-Text — **aber** der Spinner trägt die Liveness. ✅
- **Copy** `first_run_repo_cloning_slow` „Klont noch — bei großen Repositories kann das einige Minuten dauern."
  INFO, `firstRun.repo.cloningSlow`, **Polite, einmal** (flippt false→true genau einmal). ✅
- ★ **KEIN Client-Timeout→ERROR:** firsthand bestätigt — Kommentar *„NOT a failure/timeout — the poll never invents
  CLONE_FAILED"*; die Poll-Schleife (`FirstRunViewModel.reload()`) stoppt nur bei **terminalem Server-Status**
  (`isTerminalCloneStatus` = CLONED_OK | CLONE_FAILED). Der Client erfindet **nie** einen Fehlschlag. ✅
  (Anti-Dead-Hang-Doktrin, konsistent CYP-576-OIDC / Recovery-Hang.)

**⟹ Beide Fragen: PASS auf Code-Ebene.** Die Live-Bestätigung (echtes Bild, echter Mensch) folgt, sobald §1 (a)+(b)
erfüllt sind.

## 4. Honesty-Achsen (spec-treu gebaut)

- **at-rest-Posture** `first_run_apikey_posture` — **INFO, verbatim** (die einzige Stelle, an der das Produkt „not
  encrypted at rest" offenlegt). ✅
- **Saved-Bestätigungen NEUTRAL INFO, nicht amber `EFFECT_DEFERRED`** (meine §3.2-Nuance): `first_run_apikey_saved`
  + `first_run_repo_saved` sind `HintTone.INFO`/Polite; der laufende Restart-/Next-Boot-Effekt-Hint ist via
  `firstRunContext = true` **unterdrückt.** Kommentar: „Neutral INFO confirmation instead of the suppressed restart
  hint." ✅ (Kern-Honesty: keine laufenden Agenten ⟹ kein amber „greift beim nächsten Start".)
- **CLONED_OK kein Grün** (Kommentar + Ton secondary). ✅
- **CLONE_FAILED = echter, korrigierbarer Fehler** → ERROR/Assertive, reason-actionable, Fix = **derselbe** Save
  (kein neuer Retry-CTA). ✅
- **unknown ≠ unconfigured:** Status unbekannt → **LOADING**, nie „Schritt 1" (Constraint ①); „done" erscheint nur
  weil **MODE == TRANSPARENT**, nie mid-clone (Constraint ③); fail-closed (unbekannt passiert nie nach TRANSPARENT). ✅

**Honesty-Rückgrat intakt.**

## 5. Nebenbefunde

- **[Info · Tester CYP-7] CLONE_FAILED: EIN Tag für 3 Reasons** (`firstRun.repo.cloneFailed`) — **spec-treu** (mein
  §4.2 hatte einen Tag); für den **Menschen** über die Copy unterscheidbar (URL / Host-Zugriff / generisch), für den
  **Test** nur über Text assertbar, nicht per Tag. Kein Defekt; Koordinations-Notiz für den Tester.
- **[Low · Orphan] `first_run_skip_note` definiert, aber nirgends referenziert** (`values*/strings.xml` vorhanden,
  kein Kotlin-Import). Es ist die Skip-Bestätigungs-Copy — **landet vermutlich mit dem Inc4-Skip-Pfad**; falls nicht,
  Stray. Beim Inc4-Bau verdrahten oder streichen. (An PO, nicht direkt Dev.)

## 6. Inc4-Deferred (bestätigt, KEINE Defekte — Code-Kommentare belegen)

Explizit **nicht** in Inc1–3, per `FirstRunGate.kt` / `FirstRunSteps.kt`-Kommentaren („the degraded banner/resume
is Inc4"):
- **Degradierter Skip-Pfad:** `workspace_unconfigured_banner` / `_chip` / `workspace_setup_resume` /
  `agent_ctl_unconfigured` — **Keys, Tags, Composables existieren nicht.** (6 der 27 Spec-Keys = genau diese.)
- ★ **Meine Prio-#3-a11y (GATED-Grund programmatisch AM Control):** **nicht gebaut** — **kein**
  `stateDescription`/`semantics(mergeDescendants)`/`contentDescription` irgendwo im `firstrun`-Paket; `HintTone.GATED`
  existiert, wird aber nicht genutzt. ⟹ die **behaviorale a11y-QA mit dem Tester (CYP-7)** — SR hört den GATED-Grund
  beim Fokus auf „Start" — ist **ebenfalls Inc4-pending**, nicht heute prüfbar.
- **Gebaute a11y (Inc1–3):** `heading()` (Titel/Complete) + `liveRegion` **korrekt**: CLONING/Slow/OK = Polite,
  CLONE_FAILED = Assertive, degraded-Note = Polite. ✅ (§2-Politeness-Tabelle stimmt, soweit gebaut.)

## 7. Prioritäten (für PO-Routing)

1. **[Klärung, nicht Defekt]** Fläche absichtlich inert (`enabled=false`, nicht in `App.kt`) → **Live-Human-QA erst
   möglich, wenn §7-Backend-Seam gebaut+geroutet + Gate enabled/verdrahtet.** Die zwei Fokusfragen sind **Code-Ebene
   PASS**; die Live-Ebene ist ein separates Gate. **→ Route §7-`cloneStatus`-Seam an Backend** (dieselbe Abhängigkeit
   wie geplant) — das schaltet den First-Run überhaupt erst live.
2. **[Med · Watchpoint Live]** CLONING↔CLONED_OK nur über Copy + Spinner-Abwesenheit distinkt (bewusst „no green");
   in der Live-QA beobachten, nicht-farbliches „fertig"-Signal als ehrlicher Hebel falls Nutzer den Moment verpassen.
3. **[Low]** `first_run_skip_note` Orphan → mit Inc4-Skip verdrahten oder streichen.
4. **[Info]** CLONE_FAILED-Tag-Kollaps (3 Reasons, 1 Tag) → Tester assertet Reason per Text (spec-treu).
5. **[Pending Inc4]** degradierter Pfad + Prio-#3-GATED-a11y + behaviorale a11y-QA mit Tester — sobald gebaut.

## 8. Self-Validation

- **Firsthand gegen `origin/develop 45c5ceb4` verifiziert** (nicht Working-Tree `f2273267`): die Kern-Aussage
  „nicht verdrahtet + `enabled=false`" per `git show origin/develop:App.kt` (kein FirstRun) + KDoc gelesen — **nicht**
  aus dem Recon übernommen, weil die Aussage folgenschwer ist (PO glaubte „live").
- **Beide Fokusfragen gegen echten Code beantwortet** (Spinner/Ton/Tag/Timer/Poll), nicht token-plausibel.
- **Ehrlich zur Ebene:** ich sage explizit „Code-Ebene PASS, Live nicht möglich (inert)", statt eine Live-QA zu
  behaupten, die die Fläche nicht hergibt. Kein falsches Grün.
- **Inc4-Deferred als deferred, nicht als Defekt** markiert (Code-Kommentare belegen); meine eigene Prio-#3-a11y
  ehrlich als **nicht gebaut / pending** geführt, nicht schöngeredet.
- **Reuse/Konsistenz:** Befund-Format wie frühere QA-Results (B1/Edge-Critic); prioritisierte Liste + konkrete
  Handlung je Punkt.
- **Kein Bau, docs-only** auf `docs/CYP-629-first-run-uxqa`, off develop `45c5ceb4`. Dev-Branches nicht angefasst.
