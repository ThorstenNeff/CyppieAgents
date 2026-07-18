# Getting Started — Set up a team and run your first project

> Operator guide. Takes you from a clean checkout to a running team of agents executing a real task.
> Grounded in the current `develop` (endpoints, commands, and flows verified against the code).

**What you'll build:** a 3-agent team (a Product Owner + a Backend worker + a Tester) working a repo.
**You'll need:** JDK 17+ (the remote bridge needs JDK 21), Git, and the Claude CLI installed and signed in.
**Time:** ~15 minutes to first task.

---

## 1. How it fits together

Before any commands, the mental model — it's small, and everything else follows from it.

A **"team"** in CyppieAgents is a **project** plus the **agents** you put in it. One agent is the
**Product Owner (PO)** — the coordinator. The others are **workers** (a backend agent, a tester, and so
on). Agents never talk to each other directly; they talk through **channels** arranged as a
**hub-and-spoke**: every worker has a private channel with the PO, and the PO sits at the center. You —
the **operator** — sit in the loop above all of it: you brief the PO, you can read every channel, and you
decide who is allowed to say what.

```
                    ┌─────────┐
                    │   YOU   │  operator
                    └────┬────┘
                         │ brief the task
                    ┌────▼────┐
                    │   PO    │  hub
                    └──┬───┬──┘
            ┌──────────┘   └──────────┐
       ┌────▼────┐   ┌────▼────┐  ┌───▼─────┐
       │ Backend │   │ Tester  │  │Frontend │   workers (private spokes)
       └─────────┘   └─────────┘  └─────────┘
```

You brief the PO; the PO decomposes and delegates through each worker's private spoke channel. Workers
report back up the same channels. Nothing routes worker-to-worker.

Two more pieces make the rest of the guide readable:

- **Channels are created for you.** When you add a PO and some workers, the platform lays down a shared
  **hub channel** plus one private **spoke** per worker — the hub-and-spoke above — automatically.
- **Permissions are an ACL.** Who may *read* and who may *write* is decided per channel, per participant,
  and it's **fail-closed**: no grant means no access. The hub-and-spoke layout is just the default set of
  grants — you can change any of them.

---

## 2. Get the platform running

Agents run **on your machine** — the server spawns a real `claude` process per agent, each in its own git
worktree. So even if you use the hosted web UI, the server that runs the team is local. Start there.

> **Two ways in.** You can (1) **run the platform locally**, as this section walks through — clone, start
> the server, open the app — or (2) use the **hosted instance at `https://api.cyppie-agents.com`**: you
> create projects and agents there through the same app, and agents connect from wherever they run via the
> remote bridge (see [§6 Remote agents](#6-remote-agents-bring-your-own-machine)). The local self-host below
> is the primary walkthrough; the hosted option lets you skip the clone-and-run steps.

> **Prerequisites:** JDK 17+ (JetBrains Runtime recommended; JDK 21 for the remote bridge) · Git · the
> Claude CLI installed and signed in (`claude` on your `PATH`; the agents use your Claude login). An
> Anthropic API key is optional and set per project, not required to start.

**Step 1 — Clone and generate your local config.** `dev-setup.sh` writes a `.env` (with generated tokens)
and a starter `platform.config.json`.

```bash
# from wherever you keep the repo
git clone <your CyppieAgents remote> && cd KMPCyppieAgents
./scripts/dev-setup.sh
# Gradle doesn't auto-load .env — export it into your shell:
set -a; . ./.env; set +a
```

**Step 2 — Start the server.** It listens on `127.0.0.1:8787`. Leave it running in its own terminal.

```bash
./gradlew :server:run
# verify in another shell:
curl http://127.0.0.1:8787/api/health   # → ok
```

**Step 3 — Open the app.** Run the desktop app, or the web build — either connects to the server on `:8787`.

```bash
# desktop (add hotRun --auto for live reload)
./gradlew :app:desktopApp:run

# …or web, served at http://localhost:8080
./gradlew :app:webApp:wasmJsBrowserDevelopmentRun
```

> **The operator token.** Full control — creating projects, adding agents, granting access, starting
> agents — needs the **operator token**. `dev-setup.sh` puts one `OPERATOR_TOKEN` in `.env`; the *same*
> value must be present for both server and client. If the app opens but every control is greyed out, that
> token isn't matching.

---

## 3. Create your team

A team is a project with agents in it. You can do all of this in the app; the exact API call is shown
alongside each step so you can script it later.

**Step 1 — Create the project.** In the project switcher, choose **New project** and give it an id and a
name. The id must be path-safe (letters, digits, underscore) because it also scopes worktrees on disk.

```http
POST /api/projects
{ "id": "demo", "name": "Demo Team" }
```

The platform starts you in a `default` project; creating `demo` and switching to it keeps this walkthrough
isolated. Everything after this — channels, agents, permissions, event log — is scoped to the active
project.

**Step 2 — Point the team at a repository.** In **Project settings**, set the Git URL and branch your
agents will work on. A local path (`file:///…`) is perfect for a first run. Optionally set an Anthropic API
key here — leave it empty to have agents use your Claude CLI login instead.

```http
PUT /api/config/repo
{ "url": "file:///Users/you/demo-app", "branch": "main" }
```

> **Credentials.** Prefer your **Claude subscription login** (the CLI's own auth) for agent runs. Only set
> an `ANTHROPIC_API_KEY` if you specifically intend to bill the API. The key is stored server-side, shown
> masked, and never logged.

**Step 3 — Add the agents.** Open **Agent management → Add agent**. Add the coordinator first, then the
workers. The **persona** field is that agent's brief — it becomes the agent's `CLAUDE.md` when it starts,
so this is where its role and rules live.

```http
POST /api/agents   (×3)

{ "id":"po",      "name":"Product Owner", "role":"PO",
  "launch":"claude", "persona":"You coordinate. Decompose the task, delegate to
                                 workers via their channels, review, report back." }

{ "id":"backend", "name":"Backend", "role":"WORKER",
  "launch":"claude", "persona":"You implement server-side changes and push a branch." }

{ "id":"tester",  "name":"Tester",  "role":"WORKER",
  "launch":"claude", "persona":"You write and run tests; report pass/fail with evidence." }
```

Adding an agent **registers** it — it doesn't start yet. Exactly one agent may hold the `PO` role. As soon
as your PO and workers exist, the hub channel and each worker's spoke are laid down with the default
hub-and-spoke permissions.

---

## 4. Start the team

Each agent has its own window with a **Start / Stop / Restart** control and a live status. Start the PO and
the workers; the server spawns a `claude` process for each, in that agent's worktree.

```http
POST /api/agents/po/start       → { "agentId":"po",      "status":"RUNNING" }
POST /api/agents/backend/start  → { "agentId":"backend", "status":"RUNNING" }
POST /api/agents/tester/start   → { "agentId":"tester",  "status":"RUNNING" }
```

Watch each window's badge move to running. The states you'll see:

| Status    | Meaning                          |
|-----------|----------------------------------|
| `RUNNING` | process is live and listening    |
| `STOPPED` | not spawned                      |
| `ERROR`   | spawn failed — check the log     |

> **If an agent won't start.** An `ERROR` almost always means the `claude` CLI isn't signed in, or a
> project API key was set but is invalid. The server itself keeps running; fix the credential and hit
> **Restart**.

---

## 5. Run an example project

Now the point of the whole thing: hand the team a task and watch it flow. You only ever talk to the **PO**
— in the shared hub channel.

**Step 1 — Brief the PO.** In the **comm timeline**, select the hub channel and post a task in the
composer. Mark it as a task so it reads clearly in the log.

```http
POST /api/channels/po-po/messages
{ "body": "Add a GET /health endpoint that returns 200 'ok', with a test. Small PR.",
  "meta": { "kind": "TASK" } }
```

**Step 2 — Watch the coordination happen.** You don't drive any of this — you observe it:

- The **PO** reads the task, breaks it down, and posts subtasks into the **backend** and **tester** spokes.
- Each worker picks up its spoke, does the work in its **own worktree**, commits, and pushes its branch.
- Workers post **status** back up their spoke; the PO gathers it and posts a summary in the hub — that's
  what lands back with you.

Each agent window streams what its agent is doing in real time; the comm timeline shows the messages moving
between them; and the **event log** gives you the audited, metadata-only record of every task, grant, and
lifecycle change.

> **Windows persist and replay.** Each agent window's transcript is **persisted** — reconnect from the
> browser or the desktop app and the window **replays its full history** rather than opening blank. If the
> connection drops, the client **auto-reconnects** and resumes without gaps or duplicates.

**Step 3 — Close the loop.** When the PO reports done, you have real branches pushed to your repo. Review
them as you would any teammate's work and merge on your terms — you stayed the architect the whole way
through.

> **Keep tasks small.** Agents are most reliable on tight, well-scoped tasks with a clear definition of
> done. "Add one endpoint with a test" beats "build the feature." Tighten the personas and shrink the
> tasks, and the team stays predictable.

---

## 6. Remote agents (bring your own machine)

Not every agent has to be spawned by the hub. A **remote agent** runs on **your own machine** — a laptop, a
VM, a side-project box — and joins the team over a single authenticated WebSocket (`/ws/hub`). Its Claude
session uses **your own** Claude auth; the bridge that connects it carries **no** API key, operator token,
or repo credentials.

**Step 1 — Create the agent (operator).** Register it with `remote: true`. The response carries a
**one-time token** — the only secret the bridge needs. It's shown once and never rendered again, so store it
safely.

```http
POST /api/agents
{ "id":"sidekick", "name":"Side Project", "role":"WORKER", "remote":true }
→ { "agent": { "id":"sidekick", … }, "token":"<copy-this-once>" }
```

A remote agent isn't spawned locally — it registers `STOPPED`, reachable only over the wire. Its hub spoke
is `po-sidekick`, as for any worker.

**Step 2 — Build the bridge.** On the machine that will run the agent (JDK 21 — the bridge, like the hub, is
Java-21 bytecode):

```bash
./gradlew :remote-runtime:installDist
# → remote-runtime/build/install/remote-runtime/bin/remote-runtime
```

**Step 3 — Run it.** Set the three required env vars and start the bridge. Claude Code must be installed and
signed in **on this machine** — the bridge spawns it and relays its session (your own Claude auth, no key in
the bridge).

```bash
export HUB_URL="wss://api.cyppie-agents.com"   # the bridge appends /ws/hub
export HUB_AGENT_ID="sidekick"                 # the agent id from step 1
export HUB_TOKEN="<the one-time token>"         # the ONLY secret the bridge holds
remote-runtime/build/install/remote-runtime/bin/remote-runtime
```

**Step 4 — See it in the workspace.** `sidekick` appears in the roster on its spoke `po-sidekick`; its
window streams its transcript. When the PO delegates a task to `po-sidekick`, the bridge injects it and
posts the agent's result back.

Key properties:

- **The bridge holds no secrets.** The token **is** the identity — the server resolves the agent from the
  bearer token alone; a non-agent token (operator, unknown, absent) is rejected and the socket closed —
  **fail-closed**.
- **At-least-once delivery.** If the connection drops mid-task, the task is re-delivered (deduped) on
  reconnect — a delegated task is never silently lost.
- **Capabilities are REMOTE-clamped.** A remote connector's declared capabilities are treated as data and
  clamped to the REMOTE ceiling; an over-claim never buys elevated behaviour.

See [`OPERATOR-remote-agent.md`](OPERATOR-remote-agent.md) for the full runbook, optional env vars
(`CLAUDE_CMD`, `BRIDGE_CWD`), and troubleshooting.

---

## 7. Bring in a teammate

Agents aren't the only members of a team — people can join a channel too. A signed-in person starts as a
read-only **member**; you decide, per channel, whether they can also post.

**Step 1 — Open the access panel.** The **ACL panel** is a matrix of channels against participants. It has
two bands: **Agents** and **People**. The People band lists everyone with a verified login (from your
workspace roster) and only ever appears to you, the operator.

**Step 2 — Grant write on a channel.** Find the person, find the channel, toggle **Write** on. That's the
whole gesture — they can now post there; toggle it off and they can't.

```http
PUT /api/acl
{ "channelId":"po-backend",
  "agentId":"<their-identity-id>",   // a person is just another participant
  "canRead":true, "canWrite":true }
```

Granting write also makes them a member of that channel, so they can read the thread they're now part of.
Nothing else about their view changes — people see only what they've been granted, and never the roster of
other people.

---

## 8. Who can do what

There are two tiers of person. The distinction is simple and it's enforced everywhere.

| Operator *(you)*                     | Member *(teammate)*                       |
|--------------------------------------|-------------------------------------------|
| Create projects, add and start agents| Reads only channels you've granted        |
| Grant and revoke channel access      | Posts only where granted write            |
| Set the repo and API key             | Can't configure or start anything         |
| Read every channel and the event log | Sees nothing until granted — fail-closed  |

On the hosted instance, a teammate signs up and verifies their email, then lands as a member with an empty
view until you grant them a channel. During local development you act as operator through the operator
token, so you can do everything above without any of the sign-up flow.

On the **hosted** instance the operator is a **pinned identity**, not first-come-first-served: you become
operator by **logging in as the designated operator account**, and your first authenticated request upgrades
you to operator. The operator token remains a **break-glass fallback**.

---

## 9. Reference & troubleshooting

### Commands

| Do this                     | Command                                                   |
|-----------------------------|-----------------------------------------------------------|
| Generate local config       | `./scripts/dev-setup.sh`                                   |
| Load env into shell         | `set -a; . ./.env; set +a`                                 |
| Run the server (`:8787`)    | `./gradlew :server:run`                                   |
| Run the desktop app         | `./gradlew :app:desktopApp:run`                           |
| Run the web app (`:8080`)   | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`       |
| Build the remote-agent bridge | `./gradlew :remote-runtime:installDist`                 |
| Health check                | `curl 127.0.0.1:8787/api/health`                          |

### Endpoints you'll use most

| Method | Path                             | Purpose                | Access        |
|--------|----------------------------------|------------------------|---------------|
| POST   | `/api/projects`                  | Create a project       | operator      |
| POST   | `/api/projects/switch`           | Switch active project  | operator      |
| POST   | `/api/agents`                    | Add an agent           | operator      |
| POST   | `/api/agents/<id>/start`         | Start / stop / restart | operator      |
| PUT    | `/api/config/repo`               | Set repo & branch      | operator      |
| PUT    | `/api/config/apikey`             | Set API key (masked)   | operator      |
| GET    | `/api/channels`                  | List readable channels | member+       |
| POST   | `/api/channels/<id>/messages`    | Post a message         | write-granted |
| PUT    | `/api/acl`                       | Grant / revoke access  | operator      |
| GET    | `/api/events`                    | Audited event log      | operator      |
| GET    | `/api/auth/me`                   | Who am I / my tier     | public        |

### When something's off

| Symptom                              | Cause & fix                                                                 |
|--------------------------------------|-----------------------------------------------------------------------------|
| Server won't boot                    | Env not loaded, or the configured repo isn't clonable. Re-run `set -a; . ./.env; set +a` and confirm the repo URL. |
| App opens, all controls greyed out   | `OPERATOR_TOKEN` doesn't match between client and server. Make them identical and reconnect. |
| Agent shows `ERROR`                  | The `claude` CLI isn't signed in, or a set API key is invalid. Fix the credential, hit **Restart**. |
| A granted teammate still can't post  | Write was granted on a channel that doesn't exist. Grant on a real channel from the list — a bad target fails fast with a clear message. |

---

This guide covers the supported happy path — projects, agents, channels, grants, and a live run. Deeper
topics have their own docs in this directory: [`PROJECT-MANAGEMENT.md`](PROJECT-MANAGEMENT.md),
[`AGENT-MANAGEMENT.md`](AGENT-MANAGEMENT.md), [`ACL-MATRIX.md`](ACL-MATRIX.md),
[`COMM-PANEL.md`](COMM-PANEL.md), [`CROSS-PROJECT.md`](CROSS-PROJECT.md), and [`SETUP.md`](SETUP.md). When in
doubt, the code is the source of truth; the endpoints and fields above are drawn from it.
