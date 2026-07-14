# CYP-556 — Accept-Loop Concurrency Fidelity (QA/Team-2 adversarial plan, pre-staged / on-call)

> Status: **pre-staged** (Backend2's restructure + fidelity test not yet landed) · Owner: QA/Team2 (Tester2).
> Runs when Backend2's branch lands. My axis = **adversarially verify his fidelity test is NOT vacuous.**

## The gap (why this ticket exists)

`RemoteTunnelHubTransport.kt:60` pumps **inline**:

```kotlin
while (isActive) {
    val conn = acceptor.accept() ?: break
    val tunnel = tunnelSource.acquire()
    if (tunnel == null) { conn.reset(); continue }
    bridge.pump(tunnel, conn)   // ← AWAITED: suspends until this connection ends
}
```

`bridge.pump` runs to completion before the loop can `accept()` again ⇒ the accept-loop is **serial / single-flight**.
So even with the CYP-537 N-tunnel **pool**, the transport carries **one connection at a time** — the pool's N tunnels
are **never concurrently active through the real transport**. That leaves **F-M2-1 (the very deadlock M2-A exists to
fix — the ~8 eager persistent WS on one duplex stream) UNPROVEN-FIXED at the transport**, despite the
`RemoteHubTransport.jvm.kt:16` KDoc claiming "true N-tunnel concurrency".

**Honest note on the milestone e2e (my `CypM2NTunnelHarnessTest`):** it proved the **pool** (dialer semantics, cap,
C3 state) and an N-tunnel **datapath using N INDEPENDENT transports** (one tunnel each) — deliberately sidestepping the
single-flight accept-loop. It did **NOT** prove **one transport's accept-loop drives N concurrent connections**. That
is precisely this fidelity gap. The fix (Backend2): a **per-connection `launch { bridge.pump(...) }`** so the loop
accepts the next connection immediately.

## What Backend2's fidelity test MUST prove (and what makes it non-vacuous)

**The discriminating property:** ONE real `RemoteTunnelHubTransport` + a real pooling `TunnelSource`, **N
LONG-LIVED connections held open concurrently**, are **all simultaneously live**.

**⚠ The crux (the vacuity trap I must adversarially rule out):** a test that uses **one-shot** requests (a `GET` that
completes instantly, like the milestone datapath tooth) does **NOT** discriminate — single-flight just serializes fast
one-shots and they all still succeed. Only **LONG-LIVED, held-open, concurrent** connections expose the gap: under
inline single-flight the loop **blocks in `pump(conn1)`** (conn1's long-lived stream never ends), so **conn2..N are
never accepted** → they hang / never go live. Under the per-connection `launch{}` fix, all N are live at once.

**Faithful test shape (what I verify his test does):**
- Long-lived connections = e.g. **N WebSocket subscriptions** (`/ws/events` — stays open) OR N streaming/slow-body
  connections held open by the client, NOT N one-shot GETs.
- A **simultaneity barrier**: assert all N are live **at the same time** (e.g. all N have each received a frame while
  the others remain open; or the pool's C3 state shows **N active concurrently**; or a `CountDownLatch(N)` all N reach).
- Driven through the **real accept-loop** (one `RemoteTunnelHubTransport`, `acquireTunnel = pool::acquire`), **not** N
  independent transports and **not** the dialer/harness in isolation (those were already green pre-fix).

## Adversarial verification checklist (I run this on his landed SHA)

| # | Check | Reject if… |
|---|-------|-----------|
| V1 | **Real accept-loop** — the test drives ONE `RemoteTunnelHubTransport` with a pooling source, not N transports. | It uses N independent transports (proves nothing new) or calls the dialer/pool directly. |
| V2 | **Long-lived + concurrent** — connections are held open simultaneously, not one-shot. | One-shot GETs (serialize fast → pass even inline → vacuous). |
| V3 | **Simultaneity asserted** — a barrier proves all N live AT ONCE (not "N succeeded eventually"). | Only sequential success is asserted (inline would also pass). |
| V4 | **★ KEYSTONE MUTANT** — I revert the restructure to inline `bridge.pump(...)` (no `launch{}`) and re-run his test → it **MUST go RED** (only 1 concurrent; the rest hang/timeout). | It stays GREEN under inline pump ⇒ the test measures pool existence, not accept-loop concurrency ⇒ **VACUOUS, reject to coordinator.** |
| V5 | **No deadlock/leak** — N long-lived pumps launched concurrently tear down cleanly on `close()` (no orphaned pump coroutines, no tunnel leak). | `close()` leaves pumps running / tunnels unclosed. |
| V6 | **`--rerun-tasks`** — his self-gate SHA was a clean-cache run, not from-cache. | Green only from cache. |

**The one-line summary of my job:** prove his test would have **caught the bug** (inline → RED) and **passes the fix**
(launch → GREEN). If the mutant doesn't redden it, the fidelity test is theatre.

## Notes
- If his test is faithful (V1–V6 pass), I report CLEAN + the mutant-RED evidence to the coordinator; the teeth key to
  CYP-556. If vacuous, I report the specific failing V-item + a concrete fix (e.g. "switch the 3 one-shot GETs to held
  WS subscriptions + a CountDownLatch(N) barrier") — I do not hand-wave "make it concurrent".
- Complements, does not replace, Backend2's own test — my axis is the **adversarial non-vacuity gate** on it.
- On-call for his branch-SHA; this plan pins the discriminator so I can verify fast when it lands.
