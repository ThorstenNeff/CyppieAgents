# Hub-Ressourcen-/Überlast-UX — Design-Spec (CYP-417 / Slice S-G ResourceGovernor, Backend-Spine §7)

> Status: **Spec-Closure — ratifiziert 2026-07-11 (Q1–Q5 geruled, CYP-417 unter Epic CYP-395)** · docs-only, kein Bau ·
> **Non-Gate Forward-Slice** · Owner: UX/UI
> S-G-Backend baut den ResourceGovernor; **diese Spec ist die UI-Vorlage**. Nahtstellen über den PO (Relay wenn S-G nach CYP-415 dran ist).
> Eingefrorene Companion-Files (Haus-Konvention): `hub-resource-capacity-keys.md` · `-tags.md` · `-tokens.json`.
> Begleit-Konzept: `13-cyppie-hub-architektur.md` (§ „Ressourcenbewusstsein des Hubs").

Der Hub **schätzt** anhand von Speicher/CPU, wie viele Agenten er sicher fahren kann, und **weist einen weiteren
Spawn fail-closed ab**, wenn er die Maschine überlasten würde (produktisierte OOM-Lektion). Kapazität/Abweis kommen
als **content-freies Event** über die bestehende `EventSink`-Naht an die UI. Diese Spec deckt: **(a) Hub-Kapazitäts-Anzeige**
(„3/4 Agenten") und **(b) den Überlast-Abweis** — ehrlich, nicht alarmistisch, maritim + M3.

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **H1 — Kapazität ist eine SCHÄTZUNG, keine harte Garantie.** Der Hub schätzt aus Speicher/CPU; die Obergrenze variiert
  je Maschine und kann sich verschieben. „3/4 Agenten" darf **nicht** als präzise SLA/garantierte Grenze lesen — Framing
  als **geschätzte Kapazität**. Mirror der `contextTokens`-`null≠0`-Disziplin: ist die Kapazität noch **nicht geschätzt/
  unbekannt**, wird **nichts** gezeigt (nie ein erfundenes „0/0" oder „∞").
- **H2 — Der Überlast-ABWEIS ist eine echte fail-closed TATSACHE** (der Spawn ist **nicht** passiert). Das ist garantiert
  und wird klar surfaced. **Zwei getrennte Wahrheiten:** die **Tatsache** („Spawn abgelehnt") vs. der **geschätzte Grund**
  („würde diese Maschine überlasten"). Der Grund ist advisory, die Ablehnung ist hart.
- **H3 — Nicht alarmistisch: WARN, nicht ERROR, nie Grün.** Ein fail-closed Abweis ist das System, das **korrekt** die
  Maschine schützt — also **WARN-Amber** (erreichte Grenze), **nicht** Fehler-Rot (nichts ist abgestürzt) und **nie**
  `tertiary`-Grün (Schutz ist kein Erfolgs-Signal). CYP-300: WARN ist bewusst von `tertiary` entkoppelt (das nachts grün
  wird) — Amber **nur** aus `EventVisuals`.
- **H4 — Anzeige-Gradient: neutral bei Headroom, WARN erst bei/über Kapazität.** Solange Platz ist (1/4, 2/4) ist die
  Zahl **neutral** (Reuse neutraler `Pill`, `primaryContainer` — **nicht** grün=„gut", **nicht** amber=„Alarm"). Erst
  **voll** (N==M, kein Headroom) tönt der Readout **WARN-Amber** (ehrlich „voll"). Der **Abweis** ist WARN-Amber-Banner.
  Kein Zustand ist je grün.
- **H5 — Client zeigt Advisory, Server besitzt die fail-closed Wahrheit.** Die Kapazitäts-Zahl ist eine Vorschau; **nicht**
  hart im Client vordisablen (die Schätzung ist nicht autoritativ — sie könnte einen gültigen Spawn fälschlich blocken
  oder „Platz" zeigen und dann doch ablehnen). Der **Server-Abweis** ist der einzige harte Gate. Client = ehrliche
  Vorwarnung, Server = Tatsache.
- **H6 — Content-frei.** Das Kapazitäts-/Abweis-Event trägt **nur Zähler/Zustand** (aktuell, geschätztes Max), **kein**
  Agenten-Output/Secret. `EventDraft.detail` bleibt minimal (`{current, estimatedMax}`), konsistent mit der content-freien
  Event-Disziplin (wie `capability.degraded`, `log.dropped`).

---

## 2. Scope

- **Phase 1 / dieser Hub:** Kapazität bezieht sich auf **die verbundene Maschine** (im Mehr-Hub-Bild ist es per-Hub).
- **Enthalten:** Kapazitäts-Readout (a), Überlast-Abweis-Banner + Event (b), Farb-/Ton-Disziplin, Copy DE+EN, Tags.
- **Nicht enthalten:** die Schätz-Heuristik selbst (Backend/S-G), rohe Speicher/CPU-Graphen, Multi-Hub-Aggregat.

---

## 3. Reuse-Karte (gegen echten Code gegroundet)

- **EventSink-Naht:** `EventDraft{detail: JsonObject = leer}` = content-freier Write-Contract (`server/.../events/EventSink.kt:20/38`);
  emittiert via `EventProjector.draft(...)` (`:245`) aus dem **Spawn-Chokepoint** `LifecycleManager` (`server/.../boot/LifecycleManager.kt`,
  `spawnOrError :242`). **Additiver** `EventType` (`core/.../model/EventModel.kt:72`, offenes Vokabular, toleranter Decode)
  + `Severity.WARN` (`:59`). Rendert automatisch im Event-Log.
- **Severity→Farbe:** `EventVisuals.kt` — `severityContainer(WARN)` = Amber (`warnContainer` light `#FFE7B0`/`#5A3D00`,
  dark `#4A3A10`/`#FFC857`, `:104`), `severityColor(WARN)` (`:91`), Glyph **`▲`** (`:111`). **Nie `tertiary`, nie `error`.**
- **Neutraler Zähler:** `Pill` (`window/WindowBadge.kt:133`, `primaryContainer`/`onPrimaryContainer`, „neutral — NICHT
  Severity"); Overflow-Muster „9+" (`badge_count_overflow`). **`null≠0`-Disziplin:** `WindowManager.kt:805` (Zahl nur
  wenn non-null, sonst nichts), `AgentTokenUsageEvent(contextTokens: Int? = null)` — Vorbild für „Kapazität unbekannt = nichts".
- **WARN-Banner-Idiom:** `FrameBanner`/`HandoffBanners` (`AgentWindow.kt:434/411`) = voll-breiter WARN-Amber-Streifen mit
  voller a11y (nie grün). **Empfehlung:** dieses Muster auf eine **hub-/workspace-scoped** Composable heben (die
  bestehende ist `private` + per-Agent). **NICHT** `ConnectionBanner` (error-rot, falscher Ton), **NICHT** `TonedHint`
  (hat **keinen** WARN-Ton — `EFFECT_DEFERRED` ist blau).
- **Workspace-Chrome:** `ProjectSwitcherBar` (`project/ProjectSwitcherBar.kt:70`, Top-Bar **über** dem `WindowHost`, hat
  Rollen-Indikator + `trailing`-Slot). Kapazitäts-Readout + Überlast-Banner gehören hierher (workspace-scoped), **nicht**
  in ein `AgentWindow`.
- **testTags/i18n:** `WorkspaceTags` (Area `workspace`, hat `ROLE_INDICATOR`) — neue `workspace.*`-Member passen sauber.
  Prefixlos-dotted. DE-Default `values/strings.xml` + EN `values-en/strings.xml`. `capacity_*`/`overload_*`/`hub_*` sind
  **greenfield** (0 bestehende Keys).

---

## 4. (a) Hub-Kapazitäts-Anzeige

**Platzierung:** im/neben `ProjectSwitcherBar` (workspace-scoped, neben Rollen-Indikator / im `trailing`-Slot). Ein
kompakter Readout, kein Graph.

**Format:** „N/M Agenten" als **neutraler Zähler** (Reuse `Pill`, monospace-Zahl wie CYP-316). M = **geschätztes** Max.

**Zustände (Gradient H4):**
| Zustand | Bedingung | Darstellung |
|---|---|---|
| **unbekannt** | Kapazität noch nicht geschätzt | **nichts** anzeigen (`null≠0`, H1) — kein „0/0", kein Platzhalter-Alarm |
| **Headroom** | N < M | neutraler `Pill` „N/M Agenten" (`primaryContainer`, **nicht** grün, **nicht** amber) |
| **voll** | N == M | Readout tönt **WARN-Amber** (`severityColor(WARN)`) + Label „voll" — ehrlich „kein Headroom", **nicht** Fehler |

**Ehrlichkeit:** „geschätzt" ist Teil der Bedeutung — a11y/Tooltip trägt „(geschätzt)". Kein Fortschrittsbalken, der
Präzision vortäuscht; die „/M" ist eine Schätzung, keine harte Grenze (H1). testTag `workspace.capacity`
(+ Qualifier `.full` im Voll-Zustand), a11y `a11y_hubcap_readout` „Hub-Kapazität: %1$s von %2$s Agenten (geschätzt)".

---

## 5. (b) Überlast-Abweis

Wenn ein Spawn **fail-closed abgewiesen** wird, erscheint ein **hub-scoped WARN-Banner** (Reuse `FrameBanner`-Muster,
workspace-scoped). Zwei-Tier (wie CYP-381 CONTEXT_LOST):

- **Live-Banner (transient, quittierbar):** WARN-Amber-Streifen (`severityContainer(WARN)`, Glyph `▲`), **nicht**
  error-rot, **nie** grün. Copy `hubcap_overload_title` „Ein weiteres Team würde diese Maschine überlasten — Spawn
  abgelehnt." **Ehrlichkeits-Split (H2):** „Spawn abgelehnt" = Tatsache; „würde überlasten" = geschätzter Grund.
  Quittier-Aktion `hubcap_overload_dismiss` „Verstanden". testTag `workspace.overloadBanner` (+ `.dismiss`).
- **Lebensdauer (ratifiziert Q5): persistent-bis-klärt + dismissbar.** Der Banner **bleibt sichtbar, solange die Überlast
  besteht** (Kapazität ≥ Max), **klärt sich selbst**, sobald wieder Headroom da ist, **und** ist jederzeit vom Nutzer
  **quittierbar** („Verstanden"). Kein reiner Auto-Dismiss nach X Sekunden — die Grenze besteht real weiter, also darf der
  Banner nicht vorzeitig verschwinden und Entwarnung vortäuschen.
- **Durable-Record:** das **content-freie WARN-Event** im Event-Log (§6) ist der bleibende Nachweis — der Banner ist die
  Live-Fläche, das Event die Historie. So bleibt „es wurde abgewiesen" nachvollziehbar, auch nachdem der Banner quittiert ist.
- **a11y-Announce:** WARN, **assertiv** (der Nutzer hat gerade versucht, einen Agenten hinzuzufügen → die Ablehnung muss
  ehrlich sofort ankommen), `a11y_hubcap_overload`.

**Interaktion mit „Agent hinzufügen":** **kein** harter Client-Vor-Disable (H5) — die Schätzung ist nicht autoritativ.
Bei **voll** darf ein **weicher advisory Hinweis** am Add-Affordance stehen („voll — ein weiterer Agent könnte abgelehnt
werden"), aber der **Server-Abweis** bleibt der einzige harte Gate. (Offene Entscheidung Q1 §10.)

---

## 6. Event-Feed (content-frei)

Neuer **additiver** `EventType` (z. B. `HUB_CAPACITY`/`SPAWN_REJECTED`) mit `Severity.WARN`, emittiert aus
`LifecycleManager`'s Spawn-Chokepoint via `EventProjector.draft(...)`. `detail` **content-frei**: nur `{current,
estimatedMax}` (Zahlen) — **kein** Agenten-Output, kein Secret; optional die Ziel-`agentId`/Rolle des abgewiesenen Spawns
(Identitäts-Metadatum, kein Inhalt). Rendert automatisch als Event-Zeile mit `▲` + Amber-Rail (`EventRowUi`); Severity-Label
reused `event_severity_warn`. **Kein** neues Rendering nötig — nur der neue Typ + die Banner-Fläche (§5).

---

## 7. Maritim + M3 — Farb-/Ton-Disziplin

- **WARN-Amber** (`severityContainer/severityColor(WARN)`) für Voll-Readout + Abweis-Banner + Event. **Nie `tertiary`/
  `#40D6A0`** (Brand, nachts grün — würde „Schutz" als Erfolg lesen). **Nie `error`-Rot** für eine korrekte Ablehnung
  (nichts ist kaputt).
- **Neutral** (`primaryContainer` Pill / `onSurface`) für Headroom-Zähler — kein grün=„gesund".
- **Farbe nie allein (1.4.1):** Readout trägt Zahl+Label, Banner trägt Glyph `▲`+Text+a11y.
- Dark/Light über `maritimeColorScheme`.

---

## 8. testTag-Kontrakt (Übersicht — maßgeblich: `hub-resource-capacity-tags.md`)

Area `workspace` (bestehend). Diese Übersicht spiegelt den **eingefrorenen** `hub-resource-capacity-tags.md`:
```
workspace.capacity            workspace.overloadBanner
workspace.capacity.full       workspace.overloadBanner.dismiss
```
Event-Zeile reused `eventTail.row.<i>.warn` / `eventBrowse.row.<i>.warn` (Severity-Qualifier) — **kein** neuer Event-Tag.
**Fail-closed-Anker:** Readout **absent** wenn Kapazität unbekannt; `overloadBanner` erscheint **nur** bei echtem
Server-Abweis; keiner der Tags trägt Erfolgs-Grün.

## 9. Copy (Übersicht — maßgeblich: `hub-resource-capacity-keys.md`)

Key-Familie `hubcap_*` / `a11y_hubcap_*` (greenfield, 0 Kollision verifiziert). Diese Tabelle spiegelt den
**eingefrorenen** `hub-resource-capacity-keys.md`:
| Key | DE | EN |
|---|---|---|
| `hubcap_readout` | %1$s/%2$s Agenten | %1$s/%2$s agents |
| `hubcap_full` | voll | full |
| `hubcap_overload_title` | Ein weiteres Team würde diese Maschine überlasten — Spawn abgelehnt. | Another team would overload this machine — spawn rejected. |
| `hubcap_overload_dismiss` | Verstanden | Got it |
| `a11y_hubcap_readout` | Hub-Kapazität: %1$s von %2$s Agenten (geschätzt) | Hub capacity: %1$s of %2$s agents (estimated) |
| `a11y_hubcap_overload` | Überlast-Schutz: Spawn abgelehnt — ein weiteres Team würde die Maschine überlasten. | Overload protection: spawn rejected — another team would overload the machine. |

*(Reuse: `event_severity_warn` für das Event-Severity-Label; `badge_count_overflow` „9+" falls N/M je >9.)*

## 10. Ratifizierte Entscheidungen (Q1–Q5, PO 2026-07-11, CYP-417)

Alle fünf geruled (konventionell, keine Auftraggeber-Eskalation) → **Spec-Closure**; Keys/Tags/Tokens eingefroren.

1. **Q1 — Add-Agent bei „voll" = weich/advisory.** Client **warnt** (weicher Hinweis), der **Server entscheidet hart**
   (H5). Kein Client-Vor-Disable.
2. **Q2 — Voll-Schwelle = `current == estimatedMax` → WARN.** Erst bei voll (kein Headroom), nicht schon „nahe" — nicht
   zu früh alarmieren.
3. **Q3 — Kapazität unbekannt = nichts zeigen** (`null≠0`). Kein „—", kein „0/0".
4. **Q4 — Detailtiefe = nur `{current, estimatedMax}`.** Kein rohes Speicher/CPU, keine Graphen (content-frei, nicht
   alarmistisch).
5. **Q5 — Banner persistent-bis-klärt + dismissbar.** Bleibt sichtbar solange die Überlast besteht, klärt sich bei
   wiederkehrendem Headroom, jederzeit quittierbar; **kein** vorzeitiger Auto-Dismiss (siehe §5).

## 11. Nahtstellen zu Backend/Dev (über den PO)

- **S-1 — Kapazitäts-Contract:** content-freies Event/Feld `{current, estimatedMax}` (beide `Int`, `estimatedMax`
  **nullable** = „noch nicht geschätzt", H1/H3-null≠0). Quelle = S-G-ResourceGovernor-Schätzung.
- **S-2 — Abweis-Event:** neuer additiver `EventType` (`HUB_CAPACITY`/`SPAWN_REJECTED`) `Severity.WARN`, emittiert aus
  dem Spawn-Chokepoint (`LifecycleManager`), `detail` content-frei. **Client rät nicht** — der Abweis-Grund kommt vom Server.
- **S-3 — Banner-Fläche:** `FrameBanner`-Muster auf hub-/workspace-scoped Composable heben (heutige ist per-Agent/private) —
  Dev-Refactor, UX-Ton bleibt WARN-Amber.
- **S-4 — Fail-closed bleibt Server:** der Client zeigt nur Advisory; die harte Ablehnung sitzt im Server (S-G). Kein
  Client-seitiges Kapazitäts-Gate.
- **Drift-Hinweis:** neue `hubcap_*`-Keys + `workspace.*`-Tags landen mit Devs Slice → Re-Sync mit Tester (CYP-7).

## 12. Acceptance-Teeth (für spätere §-QA)

1. **Kapazität=Schätzung (H1):** „geschätzt" in a11y; unbekannt ⇒ **nichts** (kein „0/0"); keine präzise-SLA-Optik.
2. **Abweis = Tatsache ⊕ Grund (H2):** Banner trennt „Spawn abgelehnt" (hart) von „würde überlasten" (geschätzt).
3. **Ton (H3):** Voll-Readout + Banner + Event **WARN-Amber**, **nie** `tertiary`-Grün, **nie** `error`-Rot; Glyph `▲`.
4. **Gradient (H4):** Headroom-Zähler **neutral** (nicht grün), Voll **WARN**; kein Zustand grün.
5. **Client-advisory/Server-Gate (H5):** kein harter Client-Vor-Disable; Abweis kommt vom Server; Add-Agent bleibt
   versuchbar (außer PO ruled Q1 anders).
6. **Content-frei (H6):** Event `detail` nur Zähler/Zustand, kein Agenten-Output/Secret.
7. **Reuse:** WARN aus `EventVisuals`, Zähler aus neutralem `Pill`, Banner aus `FrameBanner`-Muster, Platz =
   `ProjectSwitcherBar` — keine divergenten Einmal-Teile.
8. **DE/EN-Parität** + a11y für jeden `hubcap_*`-Key; Dark/Light über `maritimeColorScheme`.

---

*Spec-Closure erreicht (Q1–Q5 geruled, CYP-417 unter Epic CYP-395): die eingefrorenen Companion-Files
(`hub-resource-capacity-keys.md` / `-tags.md` / `-tokens.json`) sind die UI-Vorlage für den S-G-Bau. Nichts gebaut;
Design-Input für den parallelen Backend-Strang (Nahtstellen über den PO, Relay wenn S-G nach CYP-415 dran ist). §8/§9
sind Übersicht — maßgeblich sind die eingefrorenen Companion-Files.*
