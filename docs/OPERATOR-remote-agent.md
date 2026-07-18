# Operator guide — running a Remote Agent (Bridge)

A **remote agent** runs on **your own machine** (laptop, VM, or a side-project box) instead of being
spawned by the hub. Its Claude Code session uses **your own** Claude auth (OAuth keychain / login) — the
bridge never carries an API key, operator token, or repo credentials. It joins the team over a single
authenticated WebSocket (`/ws/hub`), appears in the workspace like any agent, and coordinates through its
hub spoke channel (`po-<id>`).

This is the path for the side-project remote agent. Four steps.

---

## 1. Create the remote agent → get its token (operator, once)

As the **operator**, create the agent with `remote: true`. The response carries a **one-time bearer token**
— the only secret the bridge needs. It is shown **once** and never rendered again; store it safely.

```bash
curl -sS -X POST https://api.cyppie-agents.com/api/agents \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"sidekick","name":"Side Project","role":"WORKER","remote":true}'
# → {"agent":{"id":"sidekick",...},"token":"<COPY-THIS-ONCE>"}
```

- `id` becomes the agent id; its hub spoke is `po-sidekick`.
- A remote agent is **not** spawned locally by the hub (it's registered `STOPPED`, reachable only over the
  wire) — that's expected.
- Lost the token? Delete + recreate the agent (revokes the old token, mints a new one). Tokens are never
  re-disclosed.

---

## 2. Get the bridge binary

On the machine that will run the agent, build the bridge dist (JDK 21 — the bridge, like the hub, is Java-21 bytecode):

```bash
./gradlew :remote-runtime:installDist
# → remote-runtime/build/install/remote-runtime/bin/remote-runtime
```

The bridge ships **no secrets**; the token is supplied at runtime via the environment (step 3).

---

## 3. Start the bridge

Set the three required env vars and run it. Claude Code must be installed and logged in **on this machine**
(the bridge spawns it and relays its stream-json session — it uses *your* Claude auth, no key in the bridge).

```bash
export HUB_URL="wss://api.cyppie-agents.com"   # the hub; the bridge appends /ws/hub
export HUB_AGENT_ID="sidekick"                 # the agent id from step 1
export HUB_TOKEN="<the one-time token>"         # the ONLY secret the bridge holds
# optional:
# export CLAUDE_CMD="claude"                    # the Claude Code launch command (default: claude)
# export BRIDGE_CWD="/path/to/worktree"         # the agent's working dir (default: .)

remote-runtime/build/install/remote-runtime/bin/remote-runtime
# log: "bridge connected: agent=sidekick spoke=po-sidekick hub=wss://api.cyppie-agents.com"
```

The bridge stays running until you stop it (Ctrl-C). It auto-relays: tasks the PO sends to `po-sidekick`
are injected into your Claude Code session; its turn results are posted back to `po-sidekick`.

---

## 4. See it in the workspace

- The agent **`sidekick`** appears in the roster; its agent window streams its transcript.
- When the PO delegates a task to `po-sidekick`, the bridge injects it; the agent's result appears back in
  the `po-sidekick` channel.
- Its capabilities show as **REMOTE-clamped** (structured token usage unavailable; tool/rate-limit signals
  limited; coordination available) — the platform clamps a remote connector's declared capabilities to the
  REMOTE ceiling regardless of what it claims. That's expected and by design.

---

## Security & behaviour notes

- **The token is the identity.** The server resolves the agent **only** from the bearer token; the bridge
  never supplies an agent id in a frame. A non-agent token (operator/unknown/absent) is **rejected and the
  socket is closed** — fail-closed.
- **No secrets in the bridge.** No API key, no operator token, no repo credentials. Your Claude Code uses
  your own auth; git push/pull uses your machine's credentials.
- **At-least-once delivery.** If the connection drops mid-task, the task is **re-delivered on reconnect**
  (deduped) — you won't silently lose a delegated task.
- **Capabilities are clamped, not trusted.** A remote connector's declared capabilities are treated as data
  and clamped to the REMOTE ceiling; an over-claim never buys elevated behaviour.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Bridge exits immediately / `missing required env` | One of `HUB_URL` / `HUB_AGENT_ID` / `HUB_TOKEN` is unset or blank. |
| Connects then nothing happens on a task | Confirm `CLAUDE_CMD` runs a working Claude Code on this machine (logged in). |
| Socket closes right after connect | The token is not a valid **agent** token (e.g. an operator token, or a deleted agent). Recreate the agent (step 1) for a fresh token. |
| Messages don't appear in `po-<id>` | Confirm the PO is sending to the `po-<id>` spoke and the agent has write on it (default for its own spoke). |

---

*Mechanism proven end-to-end (real bridge binary as a separate process against a real `/ws/hub` socket) in
`server/.../RemoteBridgeSubprocessE2eTest` (RUN-gated: `RUN_CYP199=1` + `:remote-runtime:installDist`).
Reconnect / at-least-once is covered by `RemoteAcceptTest` (RC2/RA3/RA5).*
