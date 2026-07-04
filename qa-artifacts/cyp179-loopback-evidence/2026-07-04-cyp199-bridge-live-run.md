# CYP-199 — Remote-Bridge Live-Lauf (vantage-a) (redacted)

**Datum:** 2026-07-04 · Auftraggeber/PO-GO. Bridge-Jar `c7e520a` (`:remote-runtime:installDist`, separater Worktree — live-Hub `ae60635`/server-all.jar unberührt). Vantage-(a): Prozess auf der Box → `wss://api.cyppie-agents.com/ws/hub` über echten TLS+Caddy(`/ws/*`)+Hub-Stack. Fake-CC (stream-json-Stub aus BridgeLazyInitE2eTest) statt echtem Claude → **kein Claude-Quota / Abo-Cred / Box-Auth**.

## Alle 5 Wire-Verhalten LIVE bewiesen (gegen prod-Hub)
1. **✅ connect:** operator-create `bridgetest` (remote:true) → once-Token box-local (nie geloggt) → `bridge connected: agent=bridgetest spoke=po-bridgetest`. Spoke-Kanal `po-bridgetest` angelegt.
2. **✅ WireHello→REMOTE-clamp:** Hub-Roster caps geclamped — `structuredUsage: unavailable · tool/reliable/rateLimit: limited · coordination: available · kind: stream_json`.
3. **✅ WireSend→Hub-Kanal:** operator postet Task an po-bridgetest → Hub→WireDeliver→Fake-CC → `WireSend from=bridgetest body='ack'` in po-bridgetest sichtbar.
4. **✅ Reconnect + un-ACKed-redeliver:** Bridge gedroppt → Task WÄHREND down gepostet → Bridge up → Task auf Reconnect zugestellt (ack-count 1→2, at-least-once). Reconnect re-established Spoke + REMOTE-clamp.
5. **✅ fail-closed (non-agent→VIOLATED_POLICY):** OPERATOR_TOKEN UND bogus-Token → Hub-Log 2× `CloseReason(VIOLATED_POLICY, unauthorized)` → Socket closed, nichts relayed. (Bridge loggt „connected" beim Transport-101 + reconnect-loopt, authentifiziert aber NIE — grounded via Hub-Log, nicht Bridge-Log.)

## Cleanup + Revoke (verifiziert)
- Bridge-Prozess(e) gestoppt · `DELETE /api/agents/bridgetest` → 204 (Token revoked) · operator-Grant auf po-bridgetest zurück · **Revoke verifiziert:** alter Token → +1 VIOLATED_POLICY (2→3), `bridgetest` aus Roster · Worktree `cyp199-bridge-worktree-c7e520a` entfernt · box-local Token-File gelöscht.
- Live-Hub `ae60635` unberührt (nur remote-runtime gebaut). Kein Repo-Change.
