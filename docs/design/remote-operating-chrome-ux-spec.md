# Remote-Operating-Surface-Chrome — UX-Spec / Wiring-Naht (M2, Epic CYP-427, CYP-449 §4-Seams #4/#6/#8)

> Status: **Spec-Closure — Dev-verdrahtbar** · docs-only, **kein Bau** · Owner: UX/UI · Stand 2026-07-13 · Auftraggeber-autorisiert
> (M2-Slice, PO `1526364905…`, 10h-Deadline). Begleit: `remote-operating-chrome-keys.md` (Copy) + `remote-operating-chrome-tags.md`
> (testTag-Vertrag + Guards). Gegroundet READ-ONLY gg. develop `5fbd9e9c` (nichts angefasst).
> Finalisiert die drei In-Betrieb-Chrome-Nähte, die `remote-operating-surface-ux-spec.md` §4 als **frozen-spec/NOT-BUILT**
> markiert — jetzt **auf den gebauten Stand gegroundet + Dev-verdrahtbar**.

---

## 0. Headline (Grounding-Korrektur) — was wirklich gebaut ist

Die CYP-449-Frozen-Spec (§4/§10) schrieb gegen einen älteren/strengeren Stand. **Der gebaute Stand @ `5fbd9e9c`:**

- **Seam #1 Banner ist GEBAUT + gemountet** — `RemoteContextBanner(hubName)` (`workspace/RemoteContextBanner.kt:39`),
  gemountet in `AgentShell.kt:373-377` via `ProjectSwitcherBar`-Slot `remoteContextBanner` (`:90`/`:256`), gespeist von
  `AgentShell.remoteContext: String?` (`:285`), abgeleitet in `RemoteHubConnectGate.remoteContextHubName(state)` (`:56-59`,
  present-iff `RemoteConnState.CONNECTED`). Tag `WorkspaceTags.REMOTE_CONTEXT`. **Aber** nur die WARN-partial-Form
  (`workspace_remote_context_partial`) — **keine** affirmative Tunnel-Form, **kein** E2E-gepinnt-Indikator.
- **Seam #2 State existiert, kein In-Betrieb-Konsument** — `RemoteConnState.RECONNECTING` (`RemoteSessionState.kt:14`),
  `inFlightUncertain` (`:62`, gesetzt bei Drop `RemoteHubSession.kt:74 // H4`, geclippt bei Reconnect `:177`). `RECONNECTING`
  hat **einen** Konsumenten — aber im **Pre-Connect** `RemoteConnectingView` (`HubConnectSelection.kt:295`), **nicht** im
  Operating-Shell. `inFlightUncertain` hat **null** Composable-Leser (nur Unit-Tests).
- **Seam #3 Control gebaut + gemountet — am falschen Ort** — `RemoteRevokeControl` (`connect/RemoteRevokeControl.kt:48`)
  gemountet in `HubConnectSelection.kt:315` (CONNECTED-Row des **Connect-Flows**, `onEndSession=viewModel::backToHubList`),
  **nicht** im Operating-Shell/`AgentShell`. Sein eigener KDoc (`:42-45`) sagt: Live-Mount gehört **neben** das Kontext-Banner
  (`WorkspaceTags.REMOTE_CONTEXT`).

**Anti-Duplikat-Regel dieser Slice:** ich stelle **kein** zweites Banner/keine zweite Fläche daneben. Alle drei Nähte hängen
an der **einen** Banner-Region (top-of-workspace) + reusen die gebauten Composables/Tags/Copy. CYP-429s nie-gebaute
`remote.context.*`/`remote_context_operating`/`remote_e2e_indicator` werden **nicht** wiederbelebt.

---

## 1. Kern-Ehrlichkeit (tragende Entscheidungen)

- **HA — Eine Banner-Region, transport-/state-getriebene Zustandsmaschine** (nicht klick-getrieben). Lokal→absent;
  CONNECTED→Kontext (partial/affirmativ); RECONNECTING→Relay-Drop; LOST→Sitzung-beendet (out-of-scope). Node-Swap, kein Stapeln.
- **HB — „Über verschlüsselten Tunnel" nur wenn wahr (Seam #1, capability-gegated).** Die affirmative Form erscheint **nur**
  bei echtem „Daten laufen über den Tunnel"-Signal, **nie** allein auf CONNECTED. Fehlt es (heute Stub), **bleibt WARN-partial**.
- **HC — Gepinnt nur wenn echt gepinnt (Seam #1).** Der Indikator (`remote_connect_trust_pinned`) ist present-iff realer
  Fingerprint-Pin; provisorisch → absent. Nie vorgetäuschter Pin.
- **HD — In-flight ehrlich ungewiss (Seam #2, H4).** Bei Drop wird laufende Arbeit **explizit unbestätigt** markiert
  (`workspace_relay_uncertain`), **nie** still als erledigt. Der `inFlightUncertain`-Konsum ist die eigentliche Gap-Fill-Arbeit.
- **HE — Drop ≠ kaputt (Seam #2).** Reconnect = WARN-Amber, nie Rot; terminaler Verlust (LOST) ist ein separater Pfad.
- **HF — Revoke: garantiert vs. advisory (Seam #3).** Confirm = **garantierter** sofortiger lokaler Teardown DIESER Verbindung;
  Scope-Note = **advisory** „kein globales Revoke". Nie überzeichnet. Neutral, kein Scare-Rot (wie gebaut).
- **HG — Neutral ≠ Grün.** Affirmativ = neutral `●`; Drop/Ungewiss = WARN `▲`; kein Zustand trägt Erfolgs-Grün.

---

## 2. Seam #1 — Kontext-Banner: affirmative Tunnel-Graduierung

**Was Dev verdrahtet:** dieselbe `RemoteContextBanner`-Region rendert je nach Zustand eine der zwei CONNECTED-Formen.

- **Binding-Signal (net-new, Dev):** ein reales Capability-Flag „Workspace-Daten laufen über den Tunnel" (nenne es z. B.
  `dataOverTunnel: Boolean`, gespeist aus dem echten Remote-Transport / CR3-Capability). Heute existiert es **nicht**
  (`RemoteHubTransport` = fail-loud Stub; `remoteContextHubName` gated nur auf CONNECTED) → das Flag ist die **Naht**, an der
  M2 „real" wird. **Bis das Flag echt `true` liefert, bleibt B2 (WARN-partial) — das ist korrekt, nicht ein Bug.**
- **Render:**
  - `dataOverTunnel == false` → **B2** (unverändert gebaut): `workspace_remote_context_partial`, WARN `▲`.
  - `dataOverTunnel == true` → **B3**: `workspace_remote_context_tunnel` (net-new), Ton **neutral `●`/`primary`** (nicht grün,
    nicht amber). Zusätzlich, present-iff echt gepinnt: der Indikator `remote_connect_trust_pinned` als eigener Sub-Node
    (`workspace.remoteContext.pinned`).
- **Gepinnt-Quelle:** `HubTrust`-Resolution = realer Pin (Fingerprint gepinnt via CYP-480/CYP-482T1, in develop gemergt) →
  Indikator present; provisorischer Trust (`remote_connect_trust_provisional`-Pfad) → Indikator **absent** (HC).
- **`%1$s` = `hub.name`** (nicht `hubId`) — wie `_partial`, konsistent mit `HubRow`.
- **AC:** B2→B3 ist ein **Copy-/Ton-Wechsel derselben Node** (`workspace.remoteContext`), kein neuer Node; `.pinned` erscheint
  nur mit B3 **und** echtem Pin. Kein optimistischer Flip auf CONNECTED (G1). Absent lokal.

## 3. Seam #2 — In-Betrieb-Relay-Drop-Fläche (RECONNECTING + inFlightUncertain)

**Was Dev verdrahtet:** ein Konsument von `RemoteSessionState` **im Operating-Shell** (die Banner-Region, nicht der
Pre-Connect-View), der bei `conn == RECONNECTING` **eine** globale Fläche rendert.

- **Reconnect-Banner** (`workspace.relayDrop`, Copy `remote_connect_relay_dropped` reused): present-iff
  `conn == RECONNECTING`. WARN-Amber `▲`. **Eine** Fläche, **nicht** N per-Fenster-Chips (H4). Ersetzt in der Region das
  CONNECTED-Kontext-Banner (Node-Swap, G5).
- **Ungewissheits-Warnung** (`workspace.relayDrop.uncertain`, Copy `workspace_relay_uncertain` net-new): present-iff
  `inFlightUncertain == true` — als Sub-Zeile unter dem Reconnect-Banner. **Das ist der `inFlightUncertain`-Konsum**, den
  es heute **nirgends** gibt. Sagt ehrlich „laufende Aktionen unbestätigt — beim Reconnect geprüft, was ankam". WARN.
- **Clear:** bei Reconnect (`conn→CONNECTED`, `inFlightUncertain=false`, `RemoteHubSession.kt:177`) verschwinden beide Nodes;
  die Region kehrt zu B2/B3 zurück. Bei terminalem `LOST` → Sitzung-beendet-Pfad (bestehend, **out-of-scope**).
- **Reuse-Verhalten (unverändert, transport-agnostisch):** die per-Aktion-Ehrlichkeit der Surfaces bleibt (Composer
  „sendet…", ACL-Echo-Watchdog, non-optimistische CRUD) — der globale Ungewissheits-Banner trägt das **Warum**; **kein**
  per-Aktion-„ungewiss"-Marker im MVP (CYP-449 Q1 = global; hier bestätigt). Idempotenz via message-id beim Resend.
- **AC:** Drop-Fläche present-iff RECONNECTING; Ungewissheit present-iff `inFlightUncertain`; beide WARN, nie Rot; genau eine
  Banner-Zeile je Zustand (G5); nichts still als erledigt (HD).

## 4. Seam #3 — RemoteRevokeControl live-Mount (0 net-new Copy/Tags)

**Was Dev verdrahtet:** `RemoteRevokeControl` **im Operating-Shell** mounten, in der **Kontext-Banner-Zeile**
(`WorkspaceTags.REMOTE_CONTEXT`-Nachbarschaft, wie sein eigener KDoc `:42-45` fordert) — **nicht** (nur) in der Connect-Flow-Row.

- **Mount-Ort:** in/neben der top-of-workspace Banner-Region, present-iff remote CONNECTED (B2/B3). Trailing in der
  Kontext-Zeile (die „Fern-Sitzung beenden"-Kontrolle gehört zur aktiven-Session-Chrome).
- **Signatur (gebaut):** `RemoteRevokeControl(onEndSession: () -> Unit, modifier, ttl: String? = null)`.
  - `onEndSession` → **`HubConnectViewModel.backToHubList()` / `RemoteHubSession.close()`** (garantierter lokaler Teardown
    DIESER Verbindung). Der Control setzt intern `phase=ENDED` **und** ruft `onEndSession()` im selben Confirm — das ist
    **kein** Optimismus: der Teardown ist ein garantierter lokaler Akt (kein Server-Round-Trip, der scheitern könnte). ✔ HF.
  - `ttl` = **seam-gated**: nur setzen, wenn der Backend eine Operator-Session-TTL liefert (CYP-459); sonst `null` → der
    ≤TTL-Hinweis ist **absent** (nie erfundene Ablaufzeit, `null≠0`). Bis die TTL-Naht landet: `ttl=null` (Hinweis absent).
- **Copy/Tags:** **alle gebaut** (`remote_revoke_*`, `RemoteRevokeTags.*`) — **0 net-new**. Der frozen Vertrag bleibt: `END`
  (Trigger), Dialog (`CONFIRM`/`SCOPE_NOTE`/`TTL_HINT`), `ENDED`.
- **Teardown-Kohärenz:** nach `onEndSession()` → `backToHubList()` setzt `remoteContext`-Ableitung auf `null`/`false` →
  Banner-Region **clear** (kein stale-Banner, CYP-527 G4). Die drei Nähte hängen so kohärent zusammen: Revoke beendet die
  Session → Kontext-Banner + Revoke-Control verschwinden gemeinsam.
- **AC:** present-iff remote-aktiv; absent lokal/nach-Teardown; `onEndSession` verdrahtet an close()/backToHubList();
  Scope-Note ehrlich („nur diese Verbindung"); `ttl`-Hinweis nur mit echter TTL.

## 5. Region-Zustandsmaschine (Zusammenfassung, eine Fläche)

```
                       ┌───────────────────────────── top-of-workspace Banner-Region ─────────────────────────────┐
 LOCAL transport   →   │  (absent, fail-closed)                                                                    │
 REMOTE CONNECTED  →   │  B2  workspace.remoteContext + _partial      ▲ WARN   [Revoke: remote.revoke.end]        │
   + dataOverTunnel →  │  B3  workspace.remoteContext + _tunnel  ●    (+ .pinned iff real-pin)  [Revoke]           │
 REMOTE RECONNECTING → │  B4  workspace.relayDrop + relay_dropped     ▲ WARN  (+ .uncertain iff inFlightUncertain) │
 REMOTE LOST       →   │  (out-of-scope: Sitzung-beendet-Pfad, bestehend)                                          │
                       └───────────────────────────────────────────────────────────────────────────────────────┘
```
Genau eine Zeile je Zustand (G5). Revoke-Control (Seam #3) ist präsent in B2/B3 (Session aktiv), absent in B4/LOST/lokal.

## 6. Seam-Map an Dev (was fehlt, wo)

| Seam | Datei/Anker (gebaut) | Net-new Dev-Arbeit |
|---|---|---|
| **#1** | `RemoteContextBanner.kt:39` · `RemoteHubConnectGate.remoteContextHubName:56` · `AgentShell.kt:285/373` | Capability-Flag `dataOverTunnel` durchreichen; B3-Render (`_tunnel`, neutral `●`) + `.pinned`-Sub-Node (present-iff echt gepinnt) |
| **#2** | `RemoteSessionState.kt:14/62` · `RemoteHubSession.kt:74/177` (Producer) | In-Shell-Konsument von `conn==RECONNECTING` + `inFlightUncertain`; Nodes `workspace.relayDrop`(+`.uncertain`) in der Banner-Region |
| **#3** | `RemoteRevokeControl.kt:48` · `HubConnectSelection.kt:315` (falscher Mount) | Mount in der Operating-Shell-Kontext-Zeile; `onEndSession`→`backToHubList()`/`close()`; `ttl` seam-gated |

*(Alle drei reiten mit dem echten Remote-Transport-Wiring — solange `RemoteHubTransport` Stub ist, bleibt die Region B1/absent;
das ist ehrlich, kein Defekt.)*

## 7. Acceptance-Teeth (für §-QA, wenn die Nähte landen)

1. **B3 capability-gegated (HB/G1):** affirmative Tunnel-Form **nur** bei echtem `dataOverTunnel`-Signal; auf reinem
   CONNECTED-ohne-Daten bleibt B2. Kein optimistischer Flip.
2. **Gepinnt ehrlich (HC/G2):** `.pinned` present-iff realer Pin; provisorisch → absent; nie „Identität gepinnt" vorgetäuscht.
3. **In-flight ehrlich ungewiss (HD/G4):** `workspace.relayDrop.uncertain` present-iff `inFlightUncertain`; clear bei Reconnect;
   nichts still als erledigt.
4. **Drop ≠ Rot (HE):** `workspace.relayDrop` WARN-Amber, nie `errorContainer`; LOST ist ein anderer Pfad.
5. **Region-Exklusiv (HA/G5):** genau eine Banner-Zeile je Zustand; kein Doppel-Banner (Kontext + Drop gleichzeitig).
6. **Revoke garantiert vs. advisory (HF/G6):** Teardown garantiert-lokal + non-optimistisch (kein vorgetäuschter Server-Erfolg);
   Scope-Note advisory; `ttl`-Hinweis nur mit echter TTL; present-iff remote-aktiv; Region clear nach Teardown.
7. **Neutral ≠ Grün (HG):** kein Zustand trägt Erfolgs-Grün; affirmativ neutral `●`, Drop/Ungewiss WARN `▲`; WCAG 1.4.1
   (Glyph separat, Farbe nie alleiniger Träger).
8. **Anti-Duplikat:** genau **eine** Banner-Region; `RemoteContextBanner`/`WorkspaceTags.REMOTE_CONTEXT` erweitert, kein
   zweites Banner; `remote.context.*`/`remote_context_operating`/`remote_e2e_indicator` **nicht** angelegt.

## 8. Net-New-Delta
- **Keys: 4** (`workspace_remote_context_tunnel` + a11y · `workspace_relay_uncertain` + a11y). Reuse: `remote_connect_trust_pinned`,
  `remote_connect_relay_dropped`, `remote_revoke_*`, `agent_cancel`.
- **Tags: 3** (`workspace.remoteContext.pinned` · `workspace.relayDrop` · `workspace.relayDrop.uncertain`). Reuse:
  `WorkspaceTags.REMOTE_CONTEXT`, `RemoteRevokeTags.*`.
- **Seam #3: 0 net-new** — reine Mount-Naht.
- **Geteilte CYP-7-API:** die 3 Tag-Werte über den PO mit Tester + DS einfrieren.

---

*Spec-Closure — Dev-verdrahtbar. Gegroundet gg. develop `5fbd9e9c` (read-only, nichts angefasst). Anti-Duplikat: erweitert die
eine gebaute Banner-Region (CYP-527), belebt CYP-429s nie-gebaute `remote.context.*` nicht wieder. Nichts gebaut — die drei
Nähte reiten mit dem echten Remote-Transport-Wiring. Alle Nähte + der frozen Tag-Contract über den PO.*
