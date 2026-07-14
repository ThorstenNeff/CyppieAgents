# CYP-571 — Persistiertes Agent-Fenster über Reconnect: Ehrlichkeits-Verifikation (PREP)

> Owner: UIUX-Designer · Task **CYP-571** (Parent CYP-197 „Side-Project-Readiness"; verifiziert das **schon-gebaute** CYP-198/CYP-204) · Stand 2026-07-14
> Status: **PREP** (grounded @ develop `8156a63e`). **EXECUTE post-Verify auf Live-Staging** (guided-human-Teil) + headless-Teil jetzt messbar.
> **Scope (PO1-Ruling): VERIFIZIEREN, dass das HEUTIGE Verhalten ehrlich ist — KEIN Design.** Der ③-honest-**Marker**-Entwurf (seamless-shipped
> vs neuer „—— wiederhergestellt ——"-Divider) ist **post-Dogfood** (Auftraggeber). Findet die Verifikation eine **Unehrlichkeit** → **Bug-Ticket
> (via PO → PO1)**, kein Redesign hier. **Prüfmaßstab = meine Honesty-Invarianten** (nie-optimistisch-grün · replayed≠live/kein-stale-als-live ·
> `ConnectionStatus` ≠ `AgentStatus` · kein stales „RUNNING" aus replayten Events · kein Re-Dating · Dedup).
> **Werkzeug-Grenze (verbindlich):** ich habe **keinen JS-Browser** — Runtime/Pixel/echter-Restart = **guided-human auf Live-Staging** (ich leite,
> Mensch beobachtet mit DevTools, kein Pixel-Claim ohne seine Bestätigung); Netzwerk/Contract via `curl`; Verhaltens-Invarianten headless via
> `runComposeUiTest`/E2E-Test messbar. Zweistufig wie CYP-542-Pre-Gate. Siehe `browser-only`-Disziplin.

---

## 0. Prelim (Objekt-Read @ `8156a63e`) — HYPOTHESE: honest-by-construction
Substanzielle Reconnect-Infra ist schon gebaut; die Objekt-Evidenz deutet auf **ehrlich by construction**:
- **Reconnecting-Chip** (`AgentWindow.kt:618` `ReconnectingChip`, CYP-204): present **nur wenn `connection != LIVE`**, Text + neutral
  `onSurfaceVariant`, **nie `tertiary`/grün** → meine „nie-optimistisch-grün"-Invariante ist **schon im Code**. LIVE → Chip weg (kein Persistent-„verbunden"-Badge, Anti-Hype).
- **Kein Re-Dating:** replayte Events tragen die **originale Server-`tsMs`** (`AgentEvent.tsMs`, „carried down the wire rather than stamped at render") → alte Timestamps zeigen ehrlich „das ist Historie".
- **Dedup:** seq-Cursor-Reconnect (`?since=`) replayed inclusive-from-cursor + **dedupt** den re-gesendeten seq (`AgentWsReconnectTest`).
- **`deriveStatus`** (transcript-AgentStatus): ehrlich — IDLE default, RUNNING **nur** bei offenem Stream/Tool, **rät nie** WAITING/OFFLINE; UserTurn/IncomingSystem → IDLE.
- **Window-Badge** (`deriveWindowBadges`, CYP-55): feuert **NUR auf `AgentStatus.ERROR`**, **nie auf RUNNING** → **kein stale-RUNNING-Badge**; ERROR = der **letzte replayte** Event = ehrlicher letzter Stand (nicht stale). Fail-closed: keine Quelle → kein Badge (nie gefaked).
- **Header-Status-Dot** = **`AgentLifecycleState`** (separates Signal, NICHT transcript) → transcript-RUNNING treibt den Header-Dot nicht.
- **Zwei/drei Achsen distinkt:** `connection` (Chip) ≠ `AgentLifecycleState` (Header-Dot) ≠ transcript-`AgentStatus` (Badge) — getrennte ehrliche Signale, nicht konflatiert.

⟹ **Prelim-Verdikt: wahrscheinlich ehrlich.** Die Verifikation BESTÄTIGT das (+ prüft die Flächen, die ich noch nicht am Objekt schließen konnte, v.a. das separate Lifecycle-Signal über einen ECHTEN Restart).

## 1. Verifikations-Zähne — HEADLESS jetzt messbar (Objekt-Read + E2E-Test, gemessen)
- **V1 — Kein Re-Dating:** replayte Events behalten die originale `tsMs` (nicht die Reconnect-Restamp). *Mess:* `AgentWsReconnectTest` (asserted schon) re-bestätigen. *Diskriminiert:* ein Reconnect, der Historie neu datiert (läse sich als „gerade passiert").
- **V2 — Dedup / keine Doppel-Zeilen:** Reconnect-Replay dupliziert keine bereits gezeigten Events (LazyColumn-key = `event.id`). *Mess:* `AgentWsReconnectTest` (asserted). *Diskriminiert:* re-gesendeter seq erzeugt eine zweite Zeile.
- **V3 — Reconnecting-Chip ehrlich:** present ⇔ `connection != LIVE`, neutral, **nie grün**. *Mess:* Objekt-Read ✓ / optional Render-Test. *Diskriminiert:* ein grüner/„verbunden"-Chip auf ungefährem Stand.
- **V4 — Kein stale-RUNNING-Surface:** transcript-RUNNING erzeugt **kein** Badge (nur ERROR); Header-Dot = Lifecycle nicht transcript. *Mess:* Objekt-Read ✓. *Diskriminiert:* ein RUNNING-Badge aus einem replayten unvollständigen AssistantText.
- **V5 — `deriveStatus`-Ehrlichkeit bei vollständigem Replay:** ein vollständig replayter Transcript, der auf einem **COMPLETE** Event endet → **IDLE**, nicht RUNNING (ein abgeschlossener replayter Lauf zeigt nicht „läuft"). *Mess:* kleiner headless `deriveStatus`-Test (diskriminierender Kandidat). *Diskriminiert:* stale RUNNING nach einem abgeschlossenen replayten Lauf.

## 2. Verifikations-Zähne — LIVE-STAGING / guided-human (Runtime; kein JS-Browser hier → ehrlich deferred)
- **V6 — ⭐ Lifecycle-Dot über einen ECHTEN Restart (schärfste offene Flanke):** der Header-Dot kommt aus dem **separaten** Lifecycle-Signal.
  Zeigt er nach WS-Drop/Server-Restart einen **stalen „RUNNING"** von VOR dem Disconnect, oder honest **UNKNOWN/reconnecting**? *Prep:* Lifecycle-Client-
  Reconnect am Objekt lesen; *Execute:* visuell auf Staging bestätigen. *Diskriminiert:* stale-live-RUNNING-Dot während der Agent gar nicht bestätigt läuft.
- **V7 — Shell-like Backfill:** frischer Browser/Desktop-Client **und** nach Server-Restart → persistierte stream-json-Historie rendert (**NICHT blank**),
  Scroll ans neueste Ende gepinnt (shell-like, CYP-392/393-AutoScroll), Übergang Backfill→live kontinuierlich (kein Gap/Dup). *Execute:* guided-visuell.
- **V8 — Kein stale-als-live gesamt:** über einen echten Restart wird **nichts** als „live/jetzt" gerendert, das in Wahrheit replayte Historie ist
  (Timestamps zeigen alt; Chip zeigt reconnecting während des Gaps; Status ehrlich). *Execute:* guided-visuell + Timestamp-/Chip-Check.
- **V9 — Netzwerk/Contract (curl-bar, mein browser-loser Teil):** Backfill-Endpoint (`?since=`) + Persistenz-Contract (StoredAgentEvent seq/`tsMs` getragen) verhalten sich; keine Re-Stamp-Drift auf der Wire. *Mess:* `curl` gegen Staging.

## 3. Methode & Governance
- **Headless (V1–V5):** `AgentWsReconnectTest` re-run + kleiner `deriveStatus`/Render-Test (V5) + Objekt-Read; gemessen, nicht geschätzt.
- **Live-Staging (V6–V9):** ich **leite via PO-Relay**, Mensch beobachtet mit Browser+DevTools; **kein Pixel/Runtime-Claim ohne seine Bestätigung**; jede Unehrlichkeit → **Bug-Ticket via PO → PO1** (kein Redesign; der Marker-Entwurf ist post-Dogfood).
- **Ehrliche Scope-Grenze mitmelden:** was headless bewiesen wurde vs was auf die guided-Staging-Runde wartet — nie als Pixel-GO überclaimen.

## 4. Self-Validation
- Prüfmaßstab = die Honesty-Invarianten (nie-grün · replayed≠live/kein-stale-als-live · ConnectionStatus≠AgentStatus · kein-stale-RUNNING · kein-Re-Date · Dedup), jede als **diskriminierender** Zahn.
- **Prelim honest-by-construction** an Chip/Badge/Dot/`deriveStatus` (Objekt-Read); schärfste offene Flanke = **V6** (Lifecycle-Dot über echten Restart) + **V7** (shell-like Render) → Runtime → guided.
- Zweistufig (headless-jetzt vs live-staging-guided), browser-only-Disziplin; Marker-Design out-of-scope (post-Dogfood, PO1).
- Kein CYP-571-Transition beim PREP; Execute + Verdikt/etwaiges Bug-Ticket beim Live-Staging-Run.
