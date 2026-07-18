# CYP-234 — Language-neutral `/ws/hub` Agent-Wire Specification (Hub-Wire-Protocol v1)

> **Status:** design/doc only — **specify, not implement** (no codegen, no client lib, no protocol change).
> **Lead:** Backend2 (measured the protocol live in the M1.2 BYOA dry-run, 2026-07-18).
> **Grounded on:** `origin/develop` @ `60c65b6a` — `core/…/model/WireProtocol.kt`, `CommModel.kt`,
> `ConnectorCapabilities.kt`, `AgentMgmtModel.kt`; `server/…/routing/HubWireRoutes.kt`; + my live dry-run.
> **Scope boundary (important):** this is the **`/ws/hub` remote-AGENT wire** — the connector contract a
> foreign-language (Python/Node/Go) *agent* uses to join over the wire. It is the **explicit carve-out**
> from the CYP-234 *frontend* contract work (234a/b/c): Dev5's `ContractGenerator` sets
> `EXCLUDED_WS_PATHS = setOf("/ws/hub")` ("the remote-agent wire, not a frontend transport"), so `/ws/hub`
> is **absent from the generated `asyncapi.json`**. This doc fills exactly that gap (the M2/BYOA-external
> lane named in CYP-234 comment 14664).

## 0. Provenance legend (per PL: tag observed-vs-declared)

Every normative statement is tagged:
- **[D]** = *declared in `:core`* (a `@Serializable` DTO / KDoc; what the ContractGenerator could emit).
- **[O]** = *observed live in the M1.2 dry-run* (measured against a running Hub, not read from source).
- **[S]** = *server-enforced runtime behavior* in `HubWireRoutes.kt` — real, but **not expressible in a
  `:core` DTO and absent from any generated contract** (a foreign agent can only learn it from this doc).

`[D]` says what the bytes look like; `[O]`/`[S]` say what the server actually *does* with them — including
where it does something other than a naïve reading of `:core` would assume (§5.2, §8, §12).

---

## 1. Transport & envelope

- **[D]** Transport: a single WebSocket, `GET /ws/hub` (same Ktor WS host as the UI sockets).
- **[D]** Every frame, both directions, is wrapped in a **versioned envelope**:
  `WireEnvelope { "v": <int>, "frame": <WireFrame> }`.
- **[D]** `WireFrame` is a **discriminated union**: kotlinx.serialization `classDiscriminator = "type"` →
  on the wire the frame object carries a **`"type"` field** naming the variant. JSON, UTF-8 text frames.
- **[D]** Decoding is **`ignoreUnknownKeys = true`** (`CommJson`) → forward-compatible: unknown object
  keys are ignored, so additive fields don't break older clients.
- **[D]** `SUPPORTED_WIRE_VERSIONS = {1}`. Only `v=1` today. **[S]** An unsupported `v` is rejected
  fail-closed (§8), never best-effort-parsed.

Envelope example (a send):
```json
{ "v": 1, "frame": { "type": "send", "channel": "po-sidekick", "text": "ready" } }
```

---

## 2. Connection & authentication

- **[S]** **Auth is first — before any frame is read.** The bearer credential is taken from **either** the
  `Authorization: Bearer <token>` header **or** a `?token=<token>` query param on the upgrade request.
- **[S]** The server resolves identity by `agentFor(token)` → a bound `agentId`. **There is no agent-id
  field in any frame** — identity is 100% token-derived and server-bound.
- **[S]/[O]** A token that does not resolve to an **agent** (operator token, unknown, absent) → the socket
  is closed **`VIOLATED_POLICY "unauthorized"` before any frame**, and **no `WireError` frame is sent**
  (see §8 asymmetry). Observed live 6× (operator token + garbage token attempts): registered nothing,
  relayed nothing.
- **[O]** **`HUB_AGENT_ID` (the reference Kotlin bridge's env var) is cosmetic.** In the dry-run a bridge
  started with a *lied* `HUB_AGENT_ID=po` but a valid `sidekick` token was bound by the server to
  **`sidekick`** (the token's agent); the `po` identity was untouched, and nothing could be posted as `po`.
  → A foreign agent author sets **no** identity anywhere; the token *is* the identity.
- **[O]** Provisioning is out-of-band REST (§10); the token is minted once and is the only secret the
  agent holds. Token at rest (server side): SQLite `.cyppie/remote-tokens.db`, `0600`, **not encrypted yet**.

---

## 3. Session lifecycle (ordering is enforced)

```
connect (auth-first)  →  WireHello (MUST be first frame, ≤10 s)  →  WireAck("hello")
                      →  [ WireSubscribe ]  →  WireMessage* (history + live)
                      →  WireSend / WireEvent  (agent → hub)
                      →  WireDeliver           (hub → agent, pushed)
```

- **[S]** **`WireHello` MUST be the first frame**, and within **10 s** (`helloTimeoutMs = 10_000`). Miss →
  `WireError(protocol, "handshake timeout")` **then** close `VIOLATED_POLICY "handshake timeout"`.
- **[S]** Exactly **one** hello per connection. A second hello → `WireError(protocol, "already handshook")`
  + close `PROTOCOL_ERROR "second hello"` (**not** a second, unclamped caps set).
- **[S]** `send` / `subscribe` / `event` **before** hello → `WireError(protocol, …)` + close
  `PROTOCOL_ERROR "<x> before hello"`.
- **[S]** `WireAck("hello")` is sent **before** the session is registered for delivery, so a replayed
  `WireDeliver` can never precede the ack.

---

## 4. Client → server frames  `[D]`

| `type` | Fields | Meaning |
|---|---|---|
| `hello` | `capabilities: Capabilities`, `provider: ProviderInfo` | Handshake. Self-declared caps — **clamped** by the server (§5.2). Carries no identity/trust/project. |
| `send` | `channel: string`, `text: string`, `kind?: MessageKind` | The abstract send. **No `from`, no `projectId`** — both server-stamped (§6). |
| `subscribe` | `channels: string[]`, `since?: long` | Request ACL-filtered readable history for `channels`, optional `since` cursor. Replies as `message` frames. |
| `event` | `signal: WireEventType`, `rateLimit?: map<string,string>`, `tool?: string` | Content-free self-report (rate-limit / tool-name only — **never** tool I/O). Server stamps `source=remote`, whitelists+size-caps every field; **never** touches the caps clamp. |

`WireEventType [D]`: `rate_limit` · `tool_call` · `tool_result`.

## 5. Server → client frames  `[D]`

| `type` | Fields | Meaning |
|---|---|---|
| `ack` | `detail?: string` | A hello/send/subscribe was accepted (`detail="hello"` after handshake). |
| `message` | `message: Message` | One readable inbound message (ACL-filtered + secret-masked at source). |
| `deliver` | `text: string` | Hub→agent push: a durable, deduped, at-least-once task/status. `text` is a **server-formatted string** `"[hub:<channel>] <from>: <body>"` (see finding G8). |
| `error` | `code: WireErrorCode`, `detail?: string` | A fail-closed error (usually followed by a close, §8). |

### 5.2 The capability clamp `[D]+[O]` — *the server accepts-and-degrades, it does not reject*

- **[D]** `WireHello.capabilities` is **self-declared** and treated as **data, not authority**.
- **[S]** On handshake the server applies `CapabilityCeiling.clamp(caps, ceilingFor(REMOTE))` — the single
  caps ingress for a wire agent. Trust is **structurally REMOTE** (a literal, never frame-derived).
- **[O]** A bridge that declared elevated/all-available caps was **accepted** — the connection was *not*
  rejected — and its recorded caps were **clamped**: `structuredUsage → "unavailable"`, `coordination`
  stayed `"available"`. A foreign agent must **not** assume an over-declaration is refused; it succeeds and
  is silently reduced. (Lies **degrade**, never escalate.)

---

## 6. Server-stamped identity & tenant `[D]+[S]`

Every `send` funnels through the same in-process chokepoint (`Hub.postAsAgent`) the local paths use:
- **[S]** `from` ← the bearer-bound `agentId` (a frame cannot spoof it — there's no field for it).
- **[S]** `projectId` ← the server's active project (a frame cannot set the tenant).
- **[S]** body is secret-masked + size-capped at the edge; `canWrite` is enforced.
- **[O]** Confirmed live: a `send` from `sidekick` appeared on `po-sidekick` with server-stamped
  `from=sidekick` + `projectId`, and the PO's downlink `deliver` carried the exact nonce.

---

## 7. Shared DTOs a foreign agent must (de)serialize  `[D]`

```
Message      { id:string, channelId:string, from:string, body:string, ts:long,
               meta?:MessageMeta, projectId:string="default" }
MessageMeta  { inReplyTo?:string, kind?:MessageKind }
MessageKind  = "TASK" | "STATUS" | "NOTE"
Capabilities { structuredUsage:CapabilityStatus, toolGranularity:CapabilityStatus,
               reliableResult:CapabilityStatus, rateLimitSignal:CapabilityStatus,
               coordination:CapabilityStatus, kind:ConnectorKind }
CapabilityStatus = "available" | "limited" | "unavailable"
ConnectorKind    = "stream_json" | "mcp"
ProviderInfo { id:string, displayName:string }        // e.g. {"claude","Claude"}
Channel      { id, name, kind:("DIRECT"|"GROUP"|"HUB"), members:string[], projectId }
AclEntry     { channelId, agentId, canRead:bool, canWrite:bool, projectId }
```
Note the enum **serialized values**: `CapabilityStatus`/`ConnectorKind` are **lowercase** (`@SerialName`);
`MessageKind`/`ChannelKind` are **UPPERCASE**. (A foreign agent must match these exactly.)

---

## 8. Error & close-code taxonomy  `[S]` — *observation-only; not in `:core`, not in any generated contract*

Two distinct layers a foreign agent must handle separately:

**(a) `WireError.code` (an in-band error *frame*) `[D]` enum values:**
`unsupported_version` · `forbidden` (403 — **uniform** for "no such channel" AND "forbidden channel", no
topology leak) · `too_large` (oversized/blank body) · `bad_request` (malformed/unknown frame) · `protocol`
(ordering: x-before-hello, 2nd hello, handshake timeout) · `rate_limited` (transient — back off).

**(b) WebSocket close code + reason `[S]` — NOT derivable from `:core`:**

| Trigger | `WireError` frame first? | WS close code | reason |
|---|---|---|---|
| bad/absent/operator token (auth) | **no** | `VIOLATED_POLICY` | `unauthorized` |
| no hello within 10 s | yes (`protocol`) | `VIOLATED_POLICY` | `handshake timeout` |
| sustained flood (send/event) | yes (`rate_limited`) | `VIOLATED_POLICY` | `flood` |
| malformed / unknown frame | yes (`bad_request`) | `PROTOCOL_ERROR` | `bad request` |
| unsupported `v` | yes (`unsupported_version`) | `PROTOCOL_ERROR` | `unsupported version` |
| second hello | yes (`protocol`) | `PROTOCOL_ERROR` | `second hello` |
| send/subscribe/event before hello | yes (`protocol`) | `PROTOCOL_ERROR` | `<x> before hello` |

**Key asymmetry [O]:** on **auth** failure the socket just **closes (`VIOLATED_POLICY unauthorized`) with
NO error frame** — a foreign agent must not block waiting for a `WireError`; on framing/ordering failures it
gets a `WireError` frame **then** a close.

---

## 9. Rate limits  `[S]` (CYP-161; from `docs/CYP-199-…` — not in `:core`)

Token bucket, per bound agent: burst `CAPACITY=20`; sustained `FLOOD_CLOSE_AFTER=50` consecutive throttled
sends → close `VIOLATED_POLICY "flood"`; refill `5/sec`. One agent's flood does not throttle another; two
connections of the same agent share one bucket. A foreign agent needs these to pace sends — **they are not
in any machine contract**.

---

## 10. Out-of-band provisioning REST (mint / revoke)  `[D]+[O]`

- **Mint (operator):** `POST /api/agents` with `Authorization: Bearer <operatorToken>` and body
  `NewAgentSpec { id, name, role, remote:true }` → **`201`** `CreatedAgent { agent, token }`. **[O]** The
  `token` is **non-null and disclosed exactly once** (null for a non-remote create). Without the operator
  token → **`401`**, nothing minted.
- **Revoke (operator):** `DELETE /api/agents/{id}` → removes the agent (and its token). **[O]** `204`.
- **[O] Restart caveat (CYP-172 / CYP-690):** a *runtime-minted* remote agent does **not** survive a Hub
  restart (vanishes from the roster; token row orphaned). A **config-declared** remote agent (in
  `platform.config.json`) **is** durable. Foreign-agent operators should prefer the config-declared path
  for restart-stable identity (or await the CYP-690 fix).

---

## 11. Worked happy-path (from the live dry-run)  `[O]`

```
1. operator: POST /api/agents {id:sidekick,role:WORKER,remote:true}      → 201 {token: "uz-…"}
2. agent connects: GET /ws/hub   (Authorization: Bearer uz-…)            → (auth ok, no frame yet)
3. agent → {"v":1,"frame":{"type":"hello","capabilities":{…},"provider":{"id":"claude",…}}}
   server → {"v":1,"frame":{"type":"ack","detail":"hello"}}              (caps clamped: structuredUsage=unavailable)
4. agent → {"v":1,"frame":{"type":"subscribe","channels":["po-sidekick"]}}   → message* (history)
5. PO posts a task to po-sidekick (REST)  → server → {"type":"deliver","text":"[hub:po-sidekick] po: TASK …"}
6. agent → {"v":1,"frame":{"type":"send","channel":"po-sidekick","text":"ACK …"}}
   → appears on po-sidekick with server-stamped from=sidekick + projectId
```

---

## 12. GAP FINDINGS (the deliverable — *named, not fixed*)

- **G1 — no machine contract for `/ws/hub`.** It is **excluded by design** from the generated `asyncapi.json`
  (`ContractGenerator.EXCLUDED_WS_PATHS`). A foreign agent has only `:core` Kotlin + this doc — the exact
  CYP-234 M2/BYOA-external gap. *Decision needed:* is `/ws/hub` to get its own generated AsyncAPI (parallel
  to the frontend one), or stay doc-only? **(Open weiche — routed to PL 2026-07-18; not decided here.)**
- **G2 — close-code taxonomy is invisible to any generated contract** (§8b). `VIOLATED_POLICY` vs
  `PROTOCOL_ERROR` + reason strings are runtime-only; a generator over `:core` can never emit them.
- **G3 — auth-failure vs framing-failure asymmetry** (§8): bare close vs error-then-close. Undocumented
  anywhere but here; a naïve client hangs waiting for an error frame on auth failure.
- **G4 — identity is 100% token-derived; `HUB_AGENT_ID` is cosmetic** [O]. Not a `:core` fact. Foreign
  authors need to know there is *no* identity field to send.
- **G5 — `WireError.code` ↔ close-code pairing is not 1:1.** e.g. handshake-timeout emits code `protocol`
  but closes `VIOLATED_POLICY` (not `PROTOCOL_ERROR`); the `protocol` code is overloaded across ordering
  *and* timeout. Document the pairs (§8) rather than assume a mapping.
- **G6 — rate-limit constants (CAPACITY=20 / FLOOD_CLOSE_AFTER=50 / 5-per-sec) are server-only** (§9).
  A foreign agent cannot pace itself from the contract; they should be published in the neutral spec.
- **G7 — `subscribe.since` cursor semantics underspecified.** `:core` types it `since: Long?` but whether
  it is a ts or a seq, inclusive/exclusive, and the at-least-once + dedup-by-`message.id` replay contract
  (CYP-198/204) live in the deliverer, not the wire type. A foreign agent needs the exact cursor semantics.
- **G8 — `WireDeliver.text` is an unstructured server-formatted string** `"[hub:<channel>] <from>: <body>"`.
  A foreign agent that needs `channel`/`from`/`body` back must parse a brittle, undocumented text format —
  there is no structured deliver payload. Notable underspecification (candidate for a structured `deliver`).
- **G9 — no `:core`↔server *contradiction* found** at an important spot (checked: clamp, identity-stamp,
  ordering — all declared in `:core` KDoc and matched by the server). The gaps above are **absences**
  (`:core` cannot express WS close codes / rate limits / runtime identity), not contradictions. (Reported
  to coordinator per the live-flag directive.)

---

---

## 13. Fold of Dev5's client cross-check (F1–F7) — with my runtime axis

Dev5's `docs/protocol/cyp234-web-ts-client-contract-crosscheck.md` (@ `0e56cfc6`) measured the **frontend**
surface (`asyncapi.json`/`openapi.json`) on two axes he *can* reach — `:core`↔client and `:core`↔exported-
schema — and explicitly **cannot** reach the third: `:core`↔**server-runtime**. That third axis is mine
(the M1.2 dry-run). Below I fold each finding and, where I have it, **final-nail it with runtime
measurement**, keeping the three provenances separate: **[D]** declared-`:core` · **[O]** my runtime
observation · **[C]** Dev5's client-side finding (not server-measured).

- **F1 (HIGH) — how to *connect* is not in the schema.** [C] `asyncapi.json` has no `servers`/channel
  `parameters`/`bindings`. **[O] for `/ws/hub`:** the connect carries the credential as a
  `Authorization: Bearer <token>` **header OR** a `?token=<token>` **query param** (both resolve
  `agentFor`); `subscribe.since` is an in-frame field, not a connect param. → For the agent wire, F1 is
  **nailed**: the connect contract is {bearer header | `?token=` query} + WireHello. (Frontend channels'
  `?agentId=`/`?since=` connect params are Dev5's [C] surface, not `/ws/hub`.)
- **F2 (HIGH) — the `type` discriminant is on the wire but not a subtype property.** [D] Applies to
  `/ws/hub` identically: `WireFrame` uses `classDiscriminator="type"`, and each subtype (`hello`, `send`,
  …) declares only its own fields — `type` is synthetic on the wire (kotlinx.serialization). **[O] measured
  bytes carry `"type"`** (e.g. `{"type":"send",…}`). → A naïve codegen over any `/ws/hub` schema yields a
  **non-discriminated** union. **DECIDED by PL (2026-07-18): option (a) — export-change.** The **wire does
  NOT change** (bytes already carry `"type"`); only the *exported schema* becomes honest — the `:core`
  export is changed so each subtype **declares the `type` literal as a required property**, after which a
  naïve codegen is correct and the generator's discriminant pre-injection (`contractSchema.mjs`) becomes
  unnecessary. Schema-only, additive-honest → **no compatibility risk** (Auftraggeber-Kenntnisnahme).
  **Implement-follow-up (a separate build ticket, NOT this doc):** `:core` export declares subtype `type`
  literals + drop the generator pre-injection + a conformance tooth pinning that the literal survives codegen.
- **F3 (HIGH) — WS auth undeclared.** [C] `asyncapi.json` declares `securitySchemes: NONE`. **[O] for
  `/ws/hub` (nailed):** **token-only** — Bearer header or `?token=` query; a non-agent token (operator/
  unknown/absent) → close `VIOLATED_POLICY "unauthorized"` before any frame; there is **no cookie path** on
  `/ws/hub` (a session can't bind to an agent identity). **Precision / do-not-blur:** the *`/ws/agent`
  cookie-only* leg is Dev5's **[C]** client finding (CYP-454) on a **frontend** channel — **I did NOT
  runtime-measure it**; it is not part of the `/ws/hub` agent-wire. The `?token=`-in-URL hygiene note
  (proxy/access logs) applies to `/ws/hub` too and should be documented deliberately. **→ CYP-286:** this
  measured `?token=`-query bearer (on `/ws/hub` [O] and the 7 frontend channels [C, Dev5 F1]) is the
  concrete evidence feeding the **WS-ticket/token-hardening** track (short-lived single-use `?ticket=` vs a
  long-lived `?token=`) — link F3 there.
- **F4 (MED) — unknown-field policy undefined.** [C] 0/39 frontend schemas declare `additionalProperties`.
  **[D] for `/ws/hub`:** the server decodes with **`ignoreUnknownKeys = true`** (`CommJson`) → the wire is
  **tolerant** (additive fields are non-breaking) — a partial answer F4 asks for, but it is a `:core`
  decode flag, **not** an exported-contract guarantee; publish it normatively (my G-series concurs).
- **F5 (MED) — free-form payloads.** [C] frontend `StreamJsonEvent` carries untyped `JsonElement`s.
  **[D] contrast for `/ws/hub`:** `WireEvent` is **content-free/typed** (`rateLimit: map<string,string>`,
  `tool: string`, server-whitelisted + size-capped) — the agent wire deliberately has **no** free-form
  tool-I/O payload. Worth stating so a foreign author doesn't expect stream-json bodies on `/ws/hub`.
- **F6 (LOW) — client→server payload naming.** [C] frontend naming (`UserTurn` etc.). Not a `/ws/hub`
  issue (the wire frames are uniformly `WireX`), noted for completeness.
- **F7 (MED) — REST authority boundary declared-but-unapplied.** [C] `openapi.json` defines
  `securitySchemes` but sets no `security` per-op, and no `servers`. **[O] for the provisioning REST
  (nailed):** `POST /api/agents {remote:true}` is **operator-only** — `201` with the operator bearer,
  **`401` without**; `DELETE /api/agents/{id}` operator → `204`. → The authority boundary for the mint/
  revoke endpoints a foreign-agent operator needs is **runtime-confirmed**, even though the exported
  contract doesn't state it. (The broader per-endpoint `security` gap stays Dev5's [C] REST finding.)
  **→ CYP-286:** the operator-tier bearer measured here is the same credential surface CYP-286 hardens;
  link F7's authority-boundary evidence there.

**New inconsistency check (per the live-flag directive):** folding F1–F7 surfaced **no new `:core`↔server
contradiction** on the `/ws/hub` surface. One divergence worth naming (not mine to resolve): the CYP-234
audit comment (2026-07-06) called `/ws/agent` *token-only*, while Dev5 [C] reports it *cookie-only*
(CYP-454) today — a **frontend-channel** evolution, outside the `/ws/hub` agent wire; flagged for the
frontend-contract track, not measured by me.

---

*Boundaries honored: specify-not-implement; no codegen/client-lib/protocol-change; every claim tagged
[D]/[O]/[S] (+ [C] for Dev5's client findings); gaps named not fixed; F2 = **PL-decided (a) export-change**
(schema-only, wire unchanged) with a separate implement-follow-up; G1 open weiche routed to PL. Built to
read alongside Dev5's client cross-check + ContractGenerator (which cover the frontend surface and
deliberately exclude this wire).*
