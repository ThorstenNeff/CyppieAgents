# CYP-199 — Remote-Bridge: End-to-End Verify Plan + Connect Guide (Team-2 design, design-first)

> Status: **design-only, PO1-ratify pending** · Epic: CYP-197 (Side-Project Readiness) · Owner: QA/Team2 (Tester2).
> The bridge is **already built** (`remote-runtime/BridgeMain` + `BridgeRelay` + `KtorWireLink`; server `/ws/hub`
> `HubWireRoutes`; remote-agent mint `POST /api/agents{remote=true}`). This doc = the **verify plan** + the
> **connect guide** so that, the moment the M1+M2 dogfood stack is up, the Auftraggeber can connect their own
> remote `claude` in minutes and we can prove it end-to-end.
>
> **Honest scope boundary:** the LIVE run needs the running stack → it executes at the **dogfood deploy** (it IS part
> of the dogfood). This document is the ready-to-run plan + user doc; nothing here runs a stack now.
>
> **What's already proven (do not re-litigate):** the wiring is covered by real tests — most notably
> `RemoteBridgeSubprocessE2eTest` (launches the **real bridge binary** as a subprocess, drives connect→REMOTE-clamp→
> relay + the non-agent-token fail-closed path) — **but with a FAKE `CLAUDE_CMD`, not a real `claude`.** So the
> live-run's **net-new value is exactly one thing: a REAL `claude` end-to-end** on top of already-green wiring. The
> plan below is therefore a thin real-`claude` smoke + a re-confirm of the negative gates on the live stack.

---

## 0. Ground truth (so the plan/doc match the code, not folklore)

- **Bridge is ENV-only** (`BridgeMain.main()` parses no CLI args). Required: `HUB_URL`, `HUB_AGENT_ID`, `HUB_TOKEN`.
  Optional: `CLAUDE_CMD` (default `claude`), `BRIDGE_CWD` (default `.`).
- The bridge **spawns the user's own `claude`** (stream-json, **bypass-free** — never `--dangerously-skip-permissions`,
  CYP-321) and carries **NO** `ANTHROPIC_API_KEY` / operator token / repo creds — the user's `claude` uses the user's
  OWN credentials. Only `HUB_AGENT_ID` is passed into the child env.
- **Transport auth = `Authorization: Bearer <HUB_TOKEN>` at WS open** to `${HUB_URL}/ws/hub`. This is auth-*first*:
  resolved before any frame. The application `WireHello` is the first *frame* (capability handshake), **not** the auth
  step. Spoke channel = `po-<agentId>` (hub-and-spoke).
- **Remote-agent mint:** operator-gated `POST /api/agents` with `NewAgentSpec{ …, remote = true }` → `201 Created`
  `CreatedAgent(agent, token)`; the **token is minted once and disclosed once** (null for a non-remote create).
- **Token at rest:** production store = **SQLite `.cyppie/remote-tokens.db`** (`SqliteRemoteTokenStore`), `0600`
  file perms, re-bound into the registry on boot. ⚠ **Not encrypted at rest yet** (an explicit later slice) — say so.

### ⚠ Accuracy flags (things NOT to over-claim in the doc)
1. **There is NO numeric "max remote agents" cap.** The only "REMOTE cap" is a **capability CEILING clamp**
   (`REMOTE_CEILING`) applied to the bridge's self-declared caps on `WireHello`. Document the ceiling clamp, **not** a
   remote-agent-count quota (it does not exist).
2. Token store is **SQLite** (not the JSON `FileRemoteTokenStore`); both are `0600`, but reference the SQLite path.
3. Tokens are `0600`-at-rest but **not encrypted** — an honest limitation to state.

---

## Part A — End-to-End Verify Plan (runs at the dogfood deploy)

**Prereqs:** the M1+M2 stack up (hub reachable at `HUB_URL`); operator access to create an agent; a machine with the
user's own `claude` installed + logged in; the bridge runtime (`remote-runtime`) available on that machine.

| # | Step | Acceptance criterion | Negative / already-proven |
|---|------|----------------------|---------------------------|
| **V1** | **Mint the remote agent** — operator `POST /api/agents {id,name,role,remote:true}`. | `201` + a **non-null token** in `CreatedAgent.token`; the agent shows as remote and is **not locally spawned** (no PTY on the host). | `RemoteTokenMintTest` (mints once), `RemoteNoSpawnBootTest` (not spawned, REMOTE-clamped from boot). Reserved/operator id → rejected, nothing minted. |
| **V2** | **Start the bridge** on the user's machine — `HUB_URL`, `HUB_AGENT_ID=<id>`, `HUB_TOKEN=<minted token>` (+ optional `CLAUDE_CMD`/`BRIDGE_CWD`), run `BridgeMain`. | The bridge process starts; a **real `claude`** is spawned bypass-free; **no API key** is carried by the bridge (the child uses the user's own creds). | `BridgeSkipPermissionsGuardTest` (bypass-free), `BridgeLazyInitE2eTest` (real spawner, no deadlock). |
| **V3** | **Auth-first connect + clamp** — the bridge opens `/ws/hub` with the Bearer token, sends `WireHello(caps, provider)` then `WireSubscribe([po-<id>])`. | The agent **appears in the Hub roster**; its recorded caps are **REMOTE-clamped** (`ceilingFor(REMOTE)`), **not** the self-declared values. | `HubWireRoutesTest.helloAllAvailable_recordsRemoteClampedCaps_notTheLie`. |
| **V4 ★** | **Downlink → REAL claude responds** (the net-new tooth) — operator/PO sends a message/task to the spoke → server `WireDeliver(text)` → bridge injects it as `UserTurn` → **the real `claude` processes it** → its result rides up as `WireSend(spoke, body, kind)`. | A **genuine assistant turn** from the real `claude` appears in the Hub timeline on `po-<id>` (not just the echoed inbound). This is the one thing the fake-`CLAUDE_CMD` E2E can't prove. | `BridgeRelayTest` proves the WireDeliver→turn→WireSend relay with a fake CC; V4 proves it with a **real** CC. |
| **V5** | **Uplink + bidirectional** — the `claude` emits output/tool events → `WireSend`/content-free `WireEvent` up the wire. | Output appears on the spoke; `WireEvent`s are **content-free** (rate-limit/tool signals only, no payload). | `BridgeSelfReportTest` (content-free events, LIMITED caps). |

**Negative gates (re-confirm on the live stack — each is a named acceptance):**

| # | Negative case | Expected (live) | Test that already pins it |
|---|---------------|-----------------|---------------------------|
| N1 | **Bad / absent `HUB_TOKEN`** | `/ws/hub` closes `VIOLATED_POLICY "unauthorized"` **before any frame**; the bridge cannot relay. | `HubWireRoutesTest.noToken_and_operatorToken_areClosedBeforeAnyFrame`; `RemoteBridgeSubprocessE2eTest` non-agent-token fail-closed. |
| N2 | **Operator token used as `HUB_TOKEN`** | Same `unauthorized` close (an operator token is not an agent token — `agentFor` returns null). | same as N1. |
| N3 | **Authed but no `WireHello` within 10 s** | Slow-loris reaper closes `VIOLATED_POLICY "handshake timeout"`. | `WireHandshakeTimeoutTest`. |
| N4 | **Bridge declares all-`AVAILABLE` caps (a lie)** | Server records the **REMOTE-clamped** caps, never the lie; `WireEvent`s never escalate caps. | `HubWireRoutesTest` clamp test; `WireEventRoutesTest.event_neverEscalatesCaps`. |
| N5 | **Rate-limit (CYP-161)** | Burst > `CAPACITY=20` → `WireError(RATE_LIMITED)` (transient, back off); sustained `FLOOD_CLOSE_AFTER=50` consecutive throttled sends → close `VIOLATED_POLICY "flood"`; refill `5/sec`; one agent's flood does not throttle another; two connections share one bucket. | `HubWireRateLimitTest` (rl1–rl5, rc2). |
| N6 | **Reconnect** | An already-delivered message is **not** re-delivered; a failed push **re-delivers** on reconnect; the old connection closing after reconnect does not orphan the new session. | `RemoteAcceptTest` (ra3, rc2, ra5). |

**Result:** a short PASS/FAIL list to the coordinator. **The gating tooth is V4** (a real `claude` responds end-to-end);
V1–V3/V5 + N1–N6 are re-confirms of already-green behavior on the live stack. A V4 fail = the remote-connect story is
not dogfood-ready.

---

## Part B — Connect Guide outline: "Connect your own remote `claude`"

> Audience: the Auftraggeber (a technical user). Target: **working in minutes.** (Final prose lands with the doc; this
> is the ratified outline + the exact commands/vars.)

**Prerequisites**
- A running Cyppie hub you can reach at a `HUB_URL` (e.g. `https://…` or `http://localhost:8787`).
- **Operator access** to that hub (to create the remote agent) — or ask the operator to create it and hand you the token.
- On **your** machine: `claude` installed and **logged in with your own credentials**; the Cyppie `remote-runtime` bridge.

**Step 1 — Operator creates your remote agent (one-time, mints your token)**
- `POST /api/agents` (operator-authenticated) with body `{"id":"<your-id>","name":"<Name>","role":"WORKER","remote":true}`.
- The response `201` includes a **token — shown once.** Copy it now; it is your agent's identity. (Stored server-side
  at `0600` in `.cyppie/remote-tokens.db`; **not encrypted at rest yet** — treat the hub host as trusted.)

**Step 2 — Start the bridge on your machine**
- Set the environment and run `BridgeMain`:
  - `HUB_URL` = the hub base URL (the bridge appends `/ws/hub`).
  - `HUB_AGENT_ID` = your `<your-id>` from Step 1.
  - `HUB_TOKEN` = the minted token from Step 1.
  - *(optional)* `CLAUDE_CMD` (default `claude`) if your CLI is elsewhere; `BRIDGE_CWD` (default `.`) for the working dir.
- The bridge spawns **your** `claude` (bypass-free — it will still ask you for permissions) and carries **no** API key —
  your `claude` uses **your** credentials.

**Step 3 — Verify it's connected**
- Your agent appears in the hub roster (caps shown as **LIMITED/REMOTE** — that's expected, the server clamps remote caps).
- Send it a message/task from the hub → your `claude` responds; the reply shows up on your `po-<your-id>` channel.

**Security notes (state plainly)**
- The bridge carries **no** `ANTHROPIC_API_KEY`, operator token, or repo credentials — your `claude`'s own creds only.
- The bridge is **bypass-free**: no `--dangerously-skip-permissions`; your `claude` still gates tool use.
- Your `HUB_TOKEN` **is** your agent — keep it secret; if leaked, the operator revokes it (removing the agent revokes the token).

**Troubleshooting**
| Symptom | Cause | Fix |
|---|---|---|
| WS closes immediately, `unauthorized` | wrong/absent `HUB_TOKEN`, or you used the operator token | use the exact minted agent token from Step 1 |
| Closes with `handshake timeout` | the bridge couldn't send `WireHello` within 10 s (network/stall) | check `HUB_URL` reachability, retry |
| `RATE_LIMITED` / closed with `flood` | too many sends (burst > 20, or 50 consecutive throttled) | back off; the bucket refills at 5/sec |
| Caps show `LIMITED` | **expected** — the server clamps remote agents to the REMOTE capability ceiling | none |
| Agent never appears | bridge not started / wrong `HUB_AGENT_ID` / `claude` not spawnable | check env vars + that `claude` runs standalone |

---

## Acceptance of this design package
Design-only → **PO1-ratify**. On ratify: the Part-B outline becomes the shipped connect doc; the Part-A plan runs at the
M1+M2 dogfood deploy (V4 is the gating real-`claude` tooth). No numeric remote-agent cap is claimed anywhere (flag 1);
the SQLite `0600`-not-encrypted token store is stated honestly (flags 2–3).
