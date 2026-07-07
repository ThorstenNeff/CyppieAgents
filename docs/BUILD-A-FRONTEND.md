# Building a Frontend for CyppieAgents

> **Audience:** a developer building a client — web (any framework), desktop, mobile, Go, Godot, a CLI —
> against the CyppieAgents platform API. You do **not** need Kotlin or the reference client.
>
> **The contract is the source of truth, not this guide.** Every shape and route below is generated from the
> server's `:core` types (kotlinx.serialization) and drift-tested against the live routing, then browsable at
> **`/docs`** (OpenAPI 3.1 for REST, AsyncAPI 2.6 for the WebSockets). If this document and `/docs` ever
> disagree, `/docs` wins — it is regenerated from the code every build. This guide is the *narrative*; `/docs`
> is the *reference*.

---

## 1. What you're building against

CyppieAgents is a platform for running a coordinated team of terminal-style agents on a shared repository. A
frontend renders three things:

- **Agents** — the participants (a roster with role, run-state, avatar, connector).
- **The comm hub** — channels + messages between agents, governed by a per-channel/per-participant ACL.
- **Observability** — a structured event log and per-agent live output streams.

The API is **frontend-agnostic**: a neutral REST + WebSocket contract, no server-rendered HTML, no framework
assumptions. Everything a client needs is JSON over HTTP and a few WebSockets.

---

## 2. The contract, hosted and generated

Point a browser at **`/docs`** on a running server (it renders both surfaces in one shell):

| URL | What it serves |
|---|---|
| `GET /docs` | Human docs — Redoc (REST) + AsyncAPI (WS), with a toggle. |
| `GET /docs/openapi.json` | The REST contract, OpenAPI 3.1 (Bearer-auth variant). |
| `GET /docs/asyncapi.json` | The WebSocket contract, AsyncAPI 2.6. |

`/docs*` is **authenticated** (a participant/operator token or a verified session — see §4). It is not public,
so you'll pass a credential to view it, exactly like the API it documents.

Because the specs are generated from the same `:core` types the server (de)serializes at runtime, a schema can
never silently drift from the wire. You can safely code-generate models from `/docs/openapi.json` if you like —
just don't hand-transcribe shapes from *this* page; pull them from the spec.

---

## 3. Versioning: `/api` and `/api/v1`

Every REST endpoint is served under **both** prefixes:

- `/api/...` — the current, live surface.
- `/api/v1/...` — a pinned alias, **byte-identical by construction** (the server mounts the same route tree
  under both prefixes in one loop).

**Pin `/api/v1`** in a client you intend to ship. A future breaking change would arrive as `/api/v2`, leaving
`/api/v1` stable. Everything in this guide that says `/api/...` works identically as `/api/v1/...`.

WebSockets, the connector wires (`/mcp/hub`, `/ws/hub`), and `/docs*` are **not** under the version prefix —
they are transports, not versioned REST resources.

---

## 4. Authentication — the two credential paths

There are exactly two ways to authenticate, chosen by *what kind of client you are*.

### 4a. Machine / non-browser clients → a participant Bearer token

For a Go service, a Godot game, a CLI, a server-side integration — anything that is not a human in a browser.

**How you get one:** an **operator** mints it for you (you don't self-issue):

```
POST /api/participant-tokens          (operator only)
Authorization: Bearer <operator-token>
Content-Type: application/json

{ "subject": "my-dashboard", "ttlMs": 604800000 }     // ttlMs optional (null = no expiry)
```

Response — **the raw token is disclosed exactly once, here.** Store it securely; it is never shown again and
never logged:

```json
{ "token": "R2h0c3…opaque…", "subject": "my-dashboard", "expiresAtMs": 1712345678901 }
```

> The mint rejects a `subject` that collides with a privileged principal (an agent id, `operator`, or a human
> identity) with **409 `reserved_subject`** — subjects live in their own namespace and can never impersonate.

**How you send it:**

```
Authorization: Bearer <participant-token>
```

For a **WebSocket** (browsers can't set an `Authorization` header on a WS handshake), pass it as a query
parameter instead:

```
GET /ws/comm?token=<participant-token>
```

**What it can do:** a participant token is **read-tier by construction.** It resolves to a first-class ACL
read-*subject* (like a human member), so it can read exactly the channels/messages an operator has granted it —
**fail-closed empty until granted.** When an operator grants it (via `PUT /api/acl`), the entry's `agentId` is
the token's **namespaced principal** `participant:<subject>` — the mint `subject` with the reserved
`participant:` prefix — **not** the bare subject; a grant written against the bare subject silently does not
match, and the token stays empty. It is **rate-limited** per subject (a flood returns **429**). And it
**can never write**: a send with a participant token is **403 `participant_read_only`**, even if someone
granted its subject `canWrite`. If you need to post messages, you need a human session (§4b) or an agent — a
participant token is a read/observe credential.

Revoke: `DELETE /api/participant-tokens?subject=my-dashboard` (operator).

### 4b. Human / browser clients → a Kratos session

For an interactive app where a person logs in. Authentication is handled by the platform's Ory Kratos identity
provider; your frontend drives its login/registration flows and then rides the resulting session.

**How you send it:**

- **Browser:** the `ory_kratos_session` **cookie** is set by Kratos on login and sent automatically
  (same-origin). You don't manage it.
- **Native / non-browser session clients:** the `X-Session-Token` header.

**Verify who you are:**

```
GET /api/auth/me          (public — never 401s)
→ { "authenticated": true, "role": "MEMBER", "verified": true }
```

`role` is `"OPERATOR"`, `"MEMBER"`, or `null` (unauthenticated, or authenticated-but-unverified). It is
deliberately **content-free** — no id or email. A session must be **verified** to carry a role; an unverified
session reports `{ "authenticated": true, "role": null, "verified": false }`.

Self-management: `POST /api/auth/settings/password` and `POST /api/auth/settings/email` (member tier).

### Which path for which client

| You are building… | Use | Can read | Can write messages |
|---|---|---|---|
| A web app with human login | Kratos session (§4b) | per ACL | yes, where `canWrite` granted |
| A Go/Godot/CLI/dashboard integration | Participant token (§4a) | per ACL grant | **no** (read-tier) |
| An operator/admin console | Kratos session **as OPERATOR**, or the operator token | everything | yes |

The **operator token** is a static, deploy-configured machine credential that authenticates as OPERATOR — used
for automation and bootstrapping. Human operators get the same authority via a verified OPERATOR session.

---

## 5. Authorization tiers

Every endpoint records the auth posture it enforces. From lowest to highest:

| Tier | Who satisfies it |
|---|---|
| **PUBLIC** | anyone (no credential). |
| **PARTICIPANT** | a read credential: agent token, operator token, a participant token (→ read-subject), or a verified human session. The **ACL `canRead`** then filters the result, fail-closed empty until granted. |
| **PARTICIPANT_WRITE** | same resolution as PARTICIPANT, but the **ACL `canWrite`** governs the outcome (deny-without-grant → 403). Participant tokens are **rejected** here (read-tier). |
| **MEMBER** | an authenticated principal of role MEMBER or higher (agent token, operator token, verified human). An unknown or participant bearer is **401** here. |
| **OPERATOR** | role OPERATOR only (operator token, or a verified OPERATOR session). A member/agent bearer is **403**. |

Two things worth internalizing:

1. **Tier is the gate; the ACL is the authority.** Passing the PARTICIPANT tier only means you *reach* the
   comm hub — *which* channels you see and *whether* you can post is decided by the per-channel ACL
   (`canRead`/`canWrite`), which an operator manages via `PUT /api/acl`. A caller with no grant sees an empty
   list, not an error.
2. **Reads never leak.** `GET /api/channels`, `/api/acl`, `/api/inbox`, messages — all are filtered to what the
   caller may read. You will only ever receive channels and messages you're entitled to.

---

## 6. CORS (cross-origin web clients)

If your web frontend is served from a **different origin** than the API, that origin must be on the server's
CORS allow-list (a deploy-time configuration — ask the operator to add it; include your docs-viewer origin if
you use "try it" tooling). Notes:

- Allowed methods: **GET, POST, PUT, DELETE**, OPTIONS. Allowed request headers: **`Authorization`,
  `Content-Type`**.
- **Credentials (cookies) are not allowed cross-origin** (`allowCredentials` is false). Cross-origin auth is
  therefore **token-based** (`Authorization: Bearer`), not cookie-based — which also means no cross-origin CSRF
  surface. A cookie session (§4b) works **same-origin** (serve your SPA from the API origin) or via the
  `X-Session-Token` header.
- If no origins are configured, cross-origin requests are **blocked** (fail-closed). Non-browser clients
  (Go/CLI) are unaffected by CORS entirely.

---

## 7. The core flows

### 7.1 Read the roster and the channels

```
GET /api/agents      → Agent[]     (the participants; run-state, avatar, color, connector)
GET /api/channels    → Channel[]   (only channels you may read; each has members[])
```

`Agent`:
```json
{
  "id": "backend", "name": "Backend", "role": "WORKER", "worktree": "backend",
  "runState": "RUNNING", "connectorKind": "STREAM_JSON",
  "color": "#4f9", "avatar": { "type": "preset", "style": "bottts", "seed": "backend" },
  "provider": { "id": "anthropic", "displayName": "Anthropic" }
}
```
`role` ∈ `PO | WORKER | PRODUCT_LEAD`; `runState` ∈ `RUNNING | STOPPED | ERROR`. `avatar` is a tagged union
(`type: "preset" | "upload"`), or absent.

`Channel`:
```json
{ "id": "po-backend", "name": "PO ↔ Backend", "kind": "HUB", "members": ["po","backend","operator"] }
```

### 7.2 Read messages and the inbox

```
GET /api/channels/{id}/messages?since={epochMs}   → Message[]   (a channel's history; `since` optional)
GET /api/inbox?since={epochMs}                    → Message[]   (recent across all your readable channels)
```

`Message`:
```json
{
  "id": "01J…ULID", "channelId": "po-backend", "from": "po",
  "body": "please implement CYP-273", "ts": 1712345678901,
  "meta": { "inReplyTo": "01J…", "kind": "TASK" }
}
```
`meta` is optional; `meta.kind` ∈ `TASK | STATUS | NOTE`.

### 7.3 Send a message

```
POST /api/channels/{id}/messages
Authorization: Bearer <…>            (or a session; NOT a participant token)
Content-Type: application/json

{ "body": "on it", "meta": { "kind": "STATUS" } }
```
- The **sender is your identity** (from the credential) and the **channel is the path** — the body carries
  neither, by design.
- **201** with the created `Message` on success. **403** if you lack `canWrite` on that channel — and the deny
  is a **uniform 403** whether the channel is un-granted *or* doesn't exist (no channel-existence oracle).

### 7.4 Know whether to enable the composer — `GET /api/channels/writable`

To disable your "send" box on channels the user can't post to, don't guess and don't rely on catching the 403:

```
GET /api/channels/writable    → ["po-backend", "po-frontend"]     (channel ids you may currently write)
```
This is a plain array of channel ids — a **subset of your readable channels** — computed by the *same* rule the
send endpoint enforces, so `id ∈ writable` ⟺ a POST to that channel would be accepted. Bind your composer's
enabled-state to membership in this set. (A participant token always gets an empty list — it's read-tier.)

Keep it fresh by **re-fetching on any `AclEvent`** from `/ws/comm` (§7.6) — an operator granting/revoking write
rights flips the set live. The server-side 403 remains the real authority; this endpoint just drives the UI.

### 7.5 See the ACL (and, as operator, change it)

```
GET /api/acl?channelId=&agentId=    → AclEntry[]   (filtered to channels you may read; operator sees all)
PUT /api/acl                        (operator)     body: AclEntry → the stored AclEntry
```
`AclEntry`:
```json
{ "channelId": "po-backend", "agentId": "backend", "canRead": true, "canWrite": true }
```
`PUT /api/acl` upserts one entry; granting `canRead`/`canWrite` also adds the agent to the channel's members.

### 7.6 Live updates — `/ws/comm`

Open a WebSocket for push updates. Auth as in §4 (`?token=` or same-origin cookie):

```
GET /ws/comm?token=<participant-token>
```
On connect you receive a snapshot, then live events. All frames are tagged by a `type` discriminator.

**Server → client** (`CommWsServerEvent`), pre-filtered to what you may read:
```json
{ "type": "channels", "channels": [ … Channel … ] }     // snapshot + on membership change
{ "type": "message",  "message": { … Message … } }      // a new message (dedupe by message.id)
{ "type": "acl",      "entry":   { … AclEntry … } }      // an ACL change on a channel you read
```
**Client → server** (`CommWsClientEvent`), optional:
```json
{ "type": "subscribe", "channelIds": ["po-backend"] }   // narrow to a subset (still ACL-filtered)
```

Two client rules: **dedupe messages by `message.id`** (a reconnect may replay), and **re-fetch
`/api/channels/writable` when an `acl` frame arrives** (§7.4).

### 7.7 The other live streams

| WS | Direction | Payload | Use |
|---|---|---|---|
| `/ws/events` | both | `EventPushed{event}` / `CaughtUp`; client sends `SubscribeEvents{…filters}` | the observability event log, live (MEMBER tier). |
| `/ws/lifecycle` | server→client | `AgentRunStateEvent{agentId, runState}` | agent run-state changes (a snapshot per agent on connect, then transitions). Content-free. |
| `/ws/agent?agentId=&token=` | both | server: `StreamJsonEvent` (assistant/tool/result/…); client: `UserTurn{text}` | one agent's live CLI stream; send a turn to the agent. Token auth only. |

`/ws/events` and the `GET /api/events` page share the `Event` shape:
```json
{
  "id": "01J…", "ts": 1712345678901, "seq": 42, "agentId": "backend", "projectId": "default",
  "type": "tool.call", "severity": "info", "detail": { … content-free metadata … },
  "correlationId": "…", "sessionId": "…"
}
```
`severity` ∈ `debug | info | warn | error`. `type` is an open vocabulary (`turn.start`, `tool.call`,
`result.final`, `stall.suspected`, …) — unknown values decode to `unknown` with the raw string preserved in
`rawType`, so a client is forward-compatible. Page via `GET /api/events` → `EventPage{events, nextAfterSeq,
hasMore}` and pass `nextAfterSeq` as the next `since`.

---

## 8. Endpoint reference

Grouped by area. Tier is the gate (§5); every non-2xx uses the error envelope (§9). All paths also exist under
`/api/v1`.

### Auth & identity
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| GET | `/api/auth/me` | PUBLIC | → `AuthMe` |
| POST | `/api/auth/register` | PUBLIC | `RegisterRequest` → 204 |
| POST | `/api/auth/settings/password` | MEMBER | `ChangePasswordRequest` → 204 |
| POST | `/api/auth/settings/email` | MEMBER | `ChangeEmailRequest` → 204 |
| GET | `/api/health` | PUBLIC | → `ok` (text) |

### Agents
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| GET | `/api/agents` | PARTICIPANT | → `Agent[]` |
| GET | `/api/agents/{id}` | PARTICIPANT | → `AgentDetail` |
| GET | `/api/agents/{id}/avatar` | PARTICIPANT | → image/png |
| GET | `/api/agents/{id}/avatar/preview?style=&seed=` | PARTICIPANT | → image/png |
| POST | `/api/agents` | OPERATOR | `NewAgentSpec` → `CreatedAgent` |
| PUT | `/api/agents/{id}` | OPERATOR | `AgentEdit` → `Agent` |
| DELETE | `/api/agents/{id}` | OPERATOR | → 204 |
| POST | `/api/agents/{id}/avatar` | OPERATOR | multipart → `AgentDetail` |
| DELETE | `/api/agents/{id}/avatar` | OPERATOR | → 204 |
| POST | `/api/agents/{id}/stop \| start \| restart` | OPERATOR | → `AgentRunStateEvent` |
| POST | `/api/agents/{id}/connector` | OPERATOR | `ConnectorChoice` → `Agent` |

`AgentDetail` (edit-prefill) adds `launch` and `persona` to the agent fields. `NewAgentSpec` requires
`id`/`name`/`role`; avatar on create/edit accepts only a `preset` (uploads go through the multipart endpoint,
which mints the `upload` ref). `CreatedAgent.token` is present **only** for a remote-agent create and disclosed
once.

### Comm hub
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| GET | `/api/channels` | PARTICIPANT | → `Channel[]` |
| GET | `/api/channels/writable` | PARTICIPANT | → `String[]` |
| GET | `/api/channels/{id}/messages?since=` | PARTICIPANT | → `Message[]` |
| POST | `/api/channels/{id}/messages` | PARTICIPANT_WRITE | `SendMessageRequest` → `Message` |
| GET | `/api/inbox?since=` | PARTICIPANT | → `Message[]` |
| GET | `/api/acl?channelId=&agentId=` | PARTICIPANT | → `AclEntry[]` |
| PUT | `/api/acl` | OPERATOR | `AclEntry` → `AclEntry` |
| GET | `/api/channels/{id}/share` | PARTICIPANT | → `ChannelShareView` |
| PUT / DELETE | `/api/channels/{id}/share` | OPERATOR | → `ChannelShareView` |

### Observability
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| GET | `/api/events?…filters&since=` | MEMBER | → `EventPage` |
| GET | `/api/reports` / `/api/reports/{id}` | OPERATOR | → `ReportSnapshot[]` / `ReportSnapshot` |
| POST | `/api/reports` | OPERATOR | `GenerateReportRequest` → `ReportSnapshot` |
| GET | `/api/audit` | OPERATOR | → `OperatorAudit[]` |

### Configuration & projects (operator)
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| GET | `/api/config/repo` / `/api/config/apikey` | PARTICIPANT | → `RepoConfigView` / `ApiKeyView` (masked) |
| PUT | `/api/config/repo` / `/api/config/apikey` | OPERATOR | `…Request` → `…View` |
| GET | `/api/projects` | OPERATOR | → `ProjectsView` |
| POST | `/api/projects` | OPERATOR | `CreateProjectRequest` → `Project` |
| POST | `/api/projects/switch` | OPERATOR | `SwitchActiveRequest` → `ProjectsView` |
| PUT | `/api/projects/{id}` | OPERATOR | `RenameProjectRequest` → `Project` |
| DELETE | `/api/projects/{id}` | OPERATOR | → 204 |
| GET | `/api/workspace/members` | OPERATOR | → `WorkspaceMember[]` |

### Participant tokens (operator — see §4a)
| Method | Path | Tier | Body → Response |
|---|---|---|---|
| POST | `/api/participant-tokens` | OPERATOR | `MintParticipantTokenRequest` → `MintedParticipantToken` |
| GET | `/api/participant-tokens` | OPERATOR | → `ParticipantTokenSummary[]` (secret-free) |
| DELETE | `/api/participant-tokens?subject=` | OPERATOR | → `RevokedParticipantTokens` |

> `GET /api/config/apikey` returns a **masked** view (last-4 only) — the API key is never re-rendered.
> `GET /api/participant-tokens` lists subjects + expiry, never a token or hash.

---

## 9. Errors

Every error is the same envelope:

```json
{ "error": { "code": "participant_read_only", "message": "participant tokens are read-only" } }
```

| Status | When | Example `code` |
|---|---|---|
| 400 | malformed request / missing required param | `bad_request`, `subject_required` |
| 401 | missing/invalid credential on a gated route | `unauthorized` |
| 403 | authenticated but not authorized (tier or ACL deny) | `forbidden`, `operator_required`, `participant_read_only` |
| 404 | no such resource | `not_found` |
| 409 | conflict / collision | `reserved_subject`, `conflict`, `project_not_runnable` |
| 413 | payload too large (e.g. a message over the cap) | `payload_too_large` |
| 429 | rate limit exceeded (per participant subject) | `rate_limited` |
| 500 | server error | `internal` |

Branch on `error.code` (stable) rather than `error.message` (human text). Note the deliberate **uniform 403**
on message-send: an un-granted channel and a non-existent channel both return 403, so a client cannot enumerate
channels it can't see.

---

## 10. A minimal integration checklist

1. **Pick your path (§4):** browser-with-login → Kratos session; machine/observer → an operator-minted
   participant token. Pin **`/api/v1`**.
2. **Confirm identity:** `GET /api/auth/me` (session) — or just start using the token.
3. **Load state:** `GET /api/agents`, `GET /api/channels`, and (for a composer) `GET /api/channels/writable`.
4. **Go live:** open `/ws/comm`; dedupe messages by `id`; on an `acl` frame, re-fetch `writable`.
5. **Interact** (session/agent only): `POST /api/channels/{id}/messages`; expect a uniform 403 where you lack
   `canWrite` — the disabled composer from step 3 should prevent most of these.
6. **Observe (optional):** `/ws/events` + `GET /api/events` for the timeline; `/ws/lifecycle` for run-state;
   `/ws/agent` for a single agent's live output.
7. **Handle errors (§9)** by `error.code`; handle **429** with backoff (participant tokens are rate-limited);
   reconnect WebSockets and rely on `id`-dedup.

When in doubt about a shape, open **`/docs`** — it is generated from the running server and cannot drift.

---

## Appendix — deployment conditions (for the operator, not the frontend dev)

A frontend developer doesn't action these, but they gate a working cross-origin deployment; flag them to whoever
runs the server:

- **CORS allow-list** must include your frontend origin (and the docs-viewer origin if used). Empty list =
  cross-origin blocked.
- **Reverse-proxy log hygiene:** the `?token=` WS query parameter is credential-bearing — the proxy access log
  must strip/mask the query string before logs are retained or exposed.
- **Kratos** must be reachable for the human-session path; the operator token and API key are deploy-configured
  secrets, never shipped to clients.
