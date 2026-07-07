# Design-Pass — Systemisches Load-Error + Retry-Muster (CYP-288)

> **Status:** DESIGN-PASS (kein Bau) · Owner: UIUX-Designer · PO-getriggert 2026-07-07 (Devs Sweep #4) · Ratifikation durch PO → Dev impl (A1/EventBrowse zuerst).
> **Grounding (develop `359162d`):** `eventlog/EventBrowseViewModel.kt` (+`EventBrowsePanel.kt`), `comm/CommViewModel.kt`, `agentmgmt/AgentManagementViewModel.kt`, `acl/AclViewModel.kt` (+`AclPanel.kt`); Reuse-Anker `AgentShell.ProjectLoadingPlaceholder` (CYP-267 Loading), `ui/TonedHint.kt` (ERROR-Ton), `eventlog/EventVisuals` (⚠-Glyph), bestehende `*_empty`/`*_failed`-Copy.
> **Ehrlichkeit ist der KERN:** ein fehlgeschlagener Load ist **kein leerer** Load. Beide müssen unterscheidbar sein.

---

## §0 — Zwei Sätze

Vier Panels (EventBrowse · ACL · Comm · AgentMgmt) rendern einen **fehlgeschlagenen Load still als „keine Daten"** — ein Ehrlichkeits-Bug (der Nutzer glaubt „leer", obwohl „kaputt"). Dieser Pass definiert **ein** konsistentes, maritimes Error+Retry-Muster (eine geteilte Komponente + ein State-Kontrakt), das den Fehl-Zustand **ehrlich** (Meldung + Retry) und **unterscheidbar vom Empty-Zustand** rendert.

---

## §1 — Root-Cause-Audit (die 4 Panels divergieren — das IST der Bug)

| Panel | VM-Zustand heute | Warum es still-leer rendert |
|---|---|---|
| **EventBrowse** | **hat schon `error: String?`** (`loadPage` setzt `"events_load_failed"` onFailure) | **UI ignoriert `error`**: `if (events.isEmpty() && !loading) event_empty` — der Fehler ist da, wird aber nie gezeigt → **Fix = UI-only** |
| **Comm** | `runCatching{…}.getOrDefault(emptyList())` (channels/agents/history) | **Fehler in leere Liste geschluckt** — kein `error`-Feld → **Fix = State-Modell** |
| **AgentMgmt** | `runCatching{list()}.getOrDefault(emptyList())` | **Fehler geschluckt** (hat nur Per-Aktion-Fehler, kein Listen-Load-Fehler) → **Fix = State-Modell** |
| **ACL** | `runCatching{channels()/agents()/acl()}.getOrDefault(emptyList())` ×3; Panel `if (!loading && (channels.isEmpty()||agents.isEmpty())) acl_empty` | **Fehler geschluckt** → **Fix = State-Modell** |

**Kern-Diagnose:** `getOrDefault(emptyList())` **maskiert das Scheitern als Erfolg-mit-0-Daten**. Das ist die Wurzel — die Ehrlichkeit muss **im VM** beginnen (Fehler tragen, nicht schlucken), die UI folgt.

---

## §2 — Der State-Kontrakt (4 unterscheidbare Zustände, konsistent über alle 4)

Jeder Panel-VM unterscheidet **vier** Zustände — **Empty und Error sind getrennte Felder** (nie dasselbe):

| Zustand | Bedingung | Render |
|---|---|---|
| **Loading** | Erst-Load in-flight | Spinner (Reuse CYP-267-Muster `ProjectLoadingPlaceholder`) |
| **Content** | geladen, ≥1 Datensatz | die Daten |
| **Empty** | **erfolgreich** geladen, 0 Datensätze | Empty-State (bestehende `*_empty`-Copy), neutral, **kein Retry** |
| **Error** | **Load fehlgeschlagen** (`error != null`) | **NEU**: `LoadErrorRetry` (Meldung + Retry) |

**Root-Fix (VM):** `runCatching{…}.getOrDefault(emptyList())` → das Ergebnis, das **das Scheitern trägt** (wie EventBrowse: `onFailure { error = "…_load_failed" }`), damit Empty (Erfolg+0) und Error (Scheitern) getrennt sind. Ein Panel mit mehreren Loads (ACL: channels/agents/entries) ist **Error, wenn irgendein Pflicht-Load scheitert** (fail-honest, nicht best-effort-leer).

---

## §3 — Die geteilte Komponente `LoadErrorRetry` (Anti-Divergenz: EINE, nicht 4)

Eine `commonMain`-Komponente (`ui/LoadErrorRetry.kt`), die **alle 4 Panels** rendern — ein Aussehen, ein Verhalten, eine Copy:

```
LoadErrorRetry(
    message: String = load_failed,   // "Laden fehlgeschlagen"
    onRetry: () -> Unit,             // re-invoked den aktuellen Load des Panels
    tag: String,                     // "<panel>.loadError" (scoped je Panel)
    modifier: Modifier,
)
```

**Aufbau (zentriert, ruhig — nicht alarmistisch):**
- **Glyph `⚠`** in `colorScheme.error` (Reuse des Severity-ERROR-Glyphs; Form-Marker, nie Farbe allein — WCAG 1.4.1).
- **Meldung** in `colorScheme.onSurface` (`bodyMedium`) — **lesbar-neutral, NICHT voll-rot** (der Ton reicht der Glyph/Akzent; „error" ist die Rolle, nicht die Stimmung).
- **Retry-Button** „Erneut versuchen" — `TextButton`/`Button`, **≥48dp Touch-Target**, `tag = "<panel>.loadError.retry"`, `enabled` außer während des Retry-Loads.
- **Maritim:** nur `colorScheme`-Rollen (`error`/`onSurface`/`surface`) → folgt dem Theme automatisch (nach CYP-268 R1/R3), **0 hardcoded Farben**, light + dark.

---

## §4 — Empty ≠ Error (die Unterscheidbarkeit = der Ehrlichkeits-Kern)

| Achse | **Empty** (Erfolg, 0 Daten) | **Error** (Load fehlgeschlagen) |
|---|---|---|
| Trigger | `error == null && data.isEmpty() && !loading` | `error != null` |
| Glyph | keiner / `ⓘ` (informativ) | **`⚠`** (`error`-getönt) |
| Copy | bestehende Empty-Copy (z. B. „Noch keine Ereignisse") | **„Laden fehlgeschlagen"** |
| Text-Rolle | `onSurfaceVariant` (leise) | `onSurface` (klar) |
| Retry | **NEIN** (0 Daten erneut zu laden ändert nichts) | **JA** („Erneut versuchen") |
| a11y | statisch (auf Fokus gelesen) | **liveRegion Polite** (Zustand angesagt) |

**Regel:** Ein fehlgeschlagener Load wird **nie** als Empty gerendert; Error hat **immer** Vorrang vor Empty (§5). Der Empty-State behauptet **nur** „erfolgreich geladen, nichts da" — das ist bei Scheitern eine Lüge, genau der Bug.

---

## §5 — Render-Präzedenz (eine Reihenfolge, in allen 4 Panels)

```
when {
    loading   -> LoadingPlaceholder   // Spinner (CYP-267)
    error != null -> LoadErrorRetry    // Fehler VOR Empty — nie still-leer
    data.isEmpty() -> EmptyState       // bestehende *_empty-Copy
    else      -> Content
}
```

**Retry-Verhalten (kein toter Zustand, wie CYP-267):** `onRetry` → Panel-Load neu; setzt `loading=true`, dann **auf Erfolg** → Content/Empty, **auf erneutes Scheitern** → wieder Error (fail-closed, `loading=false` auf BEIDEN Pfaden — nie ein hängender Spinner).

---

## §6 — Copy (DE + EN) — geteilt/systemisch, ehrlich-neutral

Konsistent mit der bestehenden Konvention (`acl_change_failed` „…fehlgeschlagen – erneut versuchen", `*_error` „…fehlgeschlagen"). **Geteilte Keys, allen 4 Panels gemein** (eine Formulierung, kein 4-fach-Duplikat):

| Key (neu, geteilt) | DE | EN |
|---|---|---|
| `load_failed` | **Laden fehlgeschlagen** | **Loading failed** |
| `load_retry` | **Erneut versuchen** | **Try again** |
| `a11y_load_error` | **Laden fehlgeschlagen** (liveRegion-Ansage) | **Loading failed** |

> **Ton:** klar, aber **nicht alarmistisch** — „fehlgeschlagen" nennt den Fakt, „Erneut versuchen" gibt den Ausweg. Keine Schuld-/Panik-Sprache, keine Server-Internals (kein Stacktrace/Code-Leak). EventBrowse's bestehender `events_load_failed`-Wert → auf den geteilten `load_failed` mappen (oder als Alias entfernen), damit **eine** Quelle.

---

## §7 — a11y

- **liveRegion (Polite):** der Error-Block sagt „Laden fehlgeschlagen" an, wenn er erscheint — Zustand, kein Interrupt → `Polite`, nicht `Assertive` (Assertive ist für echte Fehler-Interrupts der Auth-Flows reserviert). Konsistent mit CYP-267-Loading-[Info]-Forward.
- **Retry ≥ 48dp** Touch-Target, klares Label („Erneut versuchen"), fokussierbar.
- **Glyph nie alleiniger Träger** — die Meldung trägt die Bedeutung (WCAG 1.4.1); `⚠` + `error`-Ton verstärken nur.

---

## §8 — Rollout (A1 zuerst, dann die 3 State-Modell-Fixes)

1. **A1 — EventBrowse (UI-only, kleinster Schnitt):** der VM hat `error` schon → Panel: `error != null` vor der `isEmpty()`-Empty-Zweig setzen, `LoadErrorRetry(onRetry = vm::retry)` rendern; `vm.retry()` = aktuellen Filter neu laden. **Beweist das Muster ohne State-Modell-Risiko.**
2. **Comm · AgentMgmt · ACL:** je den `getOrDefault(emptyList())`-Swallow durch fehler-tragendes Laden ersetzen (`error`-Feld + `retry()`), dann dieselbe `LoadErrorRetry`-Render-Präzedenz. ACL = Error, wenn **irgendein** Pflicht-Load (channels/agents/acl) scheitert.

---

## §9 — Counts · Reuse · Drift

- **Neue geteilte Copy-Keys (3, DE+EN):** `load_failed`, `load_retry`, `a11y_load_error` — **systemisch, von allen 4 Panels reused** (Anti-Divergenz). Kein Per-Panel-Duplikat.
- **Reuse:** `ProjectLoadingPlaceholder`-Muster (CYP-267), `⚠`-Glyph (EventVisuals), Empty-Copy bestehend (`event_empty`/`acl_empty`/…), M3 `colorScheme`-Rollen.
- **Neue testTags (2 je Panel, scoped):** `<panel>.loadError` + `<panel>.loadError.retry` (z. B. `eventBrowse.loadError`, `acl.loadError`, `comm.loadError`, `agentMgmt.loadError`) — **⚠ shared mit QA/CYP-7**: Landing mit dem Panel-Change re-synced, in `docs/design/*-tags.md` spiegeln.
- **Neue Komponente:** `ui/LoadErrorRetry.kt` (eine, geteilt).
- **0 App-Theme-Änderung** (maritim folgt via `colorScheme`); **State-Modell-Änderung** in 3 VMs (der Root-Fix) — kein Layout-/Flow-Umbau der Panels.

---

## §10 — Invarianten (= UX-QA-Abnahme, 8)

1. **Fehlgeschlagener Load → Error-Surface** (Meldung + Retry), **nie** der Empty-State.
2. **Error ≠ Empty unterscheidbar** — distinkter Glyph (`⚠` vs. keiner/`ⓘ`) + Copy („fehlgeschlagen" vs. Empty-Noun); Error hat Retry, Empty nicht.
3. **Konsistent über alle 4 Panels** — EINE `LoadErrorRetry`-Komponente, EINE Copy, EIN Verhalten.
4. **Root-Fix im VM** — kein `getOrDefault(emptyList())`-Swallow mehr; Empty (Erfolg+0) und Error (Scheitern) sind getrennte Felder.
5. **Retry funktioniert, kein toter Zustand** — re-invoked den Load; `loading=false` auf Erfolg UND erneutem Scheitern (fail-closed).
6. **Maritim + Farbe nie alleiniger Träger** — `colorScheme`-Rollen (`error`-Akzent), Glyph+Text tragen die Bedeutung (WCAG 1.4.1), light + dark.
7. **a11y** — Error via liveRegion `Polite` angesagt; Retry ≥ 48dp + labelled.
8. **Ehrlich-neutrale Copy** — „Laden fehlgeschlagen · Erneut versuchen", nicht alarmistisch, keine Internals/Codes geleakt.

---

## §11 — §-Asks / Hand-off

1. **§-Ask (PO):** geteilte generische Copy („Laden fehlgeschlagen") vs. per-Panel-Nomen („Ereignisse konnten nicht geladen werden") — ich **empfehle geteilt-generisch** (der Panel-Kontext sagt schon, WAS; systemische Konsistenz). Dein Call.
2. **§-Ask (PO):** optionale **Detail-Zeile** bei Error (z. B. „Netzwerkfehler") — ich rate **dagegen** für v1 (Internals-Leak-Risiko + Alarm); der generische Fakt + Retry genügt. Später optional aus einem sicheren, kuratierten Fehler-Enum.
3. **Dep (Dev):** die 3 State-Modell-Fixes (Comm/AgentMgmt/ACL) sind der Substanz-Teil; EventBrowse-A1 ist UI-only → als Muster-Beweis zuerst.
4. **Hand-off:** kein Bau — Design-Pass → PO ratifiziert → Dev impl A1 zuerst. **UX-QA:** die 8 §10-Invarianten je Panel (Kern = Error≠Empty unterscheidbar + Root-Fix-im-VM + Retry-kein-toter-Zustand), gerendert light + dark.
