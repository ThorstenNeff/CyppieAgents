# CYP-273 — `canWrite` seam: durable build-plan (PO-ratified)

> Off develop `d89b5bd`, branch `feature/CYP-273-canwrite-seam`. Unblocks Dev's composer enable/disable.
> **RATIFIED shape (build exactly this):** `GET /api/channels/writable` → `List<String>`; live = client re-fetch
> on `AclEvent` (0 new WS types); server-403 stays as defense-in-depth. Dual-gate (PO-Assistant + Test),
> emulator-free (`:server:check`).

## The seam (why)
The client cannot self-derive `canWrite`: `/api/auth/me` is content-free (CYP-182 — no resolved id), so it can't
call the shared `:core AclMatrix.canWrite(channel, myId)` itself. The server resolves the caller at the read
chokepoint (`requireCommReader`) anyway → it computes the writable set and returns only channel ids (content-free,
no id disclosed). Computed via the **same `AclMatrix.canWrite` the send POST enforces** (`Hub.postAsAgent`,
Hub.kt:48) → prediction == enforcement, single-source, no drift.

## Implementation (4 edits + tests)

1. **`Hub.writableChannels(readerId): List<String>`** (server/.../comm/Hub.kt, next to `readableChannels`
   line 111):
   ```kotlin
   /** CYP-273 — the channel ids [readerId] may currently WRITE (⊆ readableChannels). SAME AclMatrix.canWrite
    *  the send chokepoint (postAsAgent) enforces → the client's composer-enable == the server's write authz
    *  (single-source). Subset-of-readable: content-free (only ids the caller already sees) AND correct — you
    *  never compose in a channel you can't read; also never discloses a write-only-not-readable channel. */
   fun writableChannels(readerId: String): List<String> =
       state.acl.readableChannels(readerId).map { it.id }.filter { state.acl.canWrite(it, readerId) }
   ```

2. **Route** `GET /channels/writable` (server/.../routing/CommRoutes.kt, sibling to `get("/channels")` ~line 163;
   inside `route(apiBase)` so BOTH `/api` + `/api/v1` auto-mount via the versioning loop). Same gate as
   `/channels` (`requireCommReader` = participant-read; operator/member/agent/participant-token all resolve):
   ```kotlin
   // CYP-273 — the per-viewer WRITABLE subset (composer-enable seam). Content-free (channel ids), same
   // participant-read gate as /channels; computed via the send-enforcing AclMatrix.canWrite (single-source).
   get("/channels/writable") {
       val participant = call.requireCommReader(deps, registry)
       call.respond(hub.writableChannels(participant))
   }
   ```
   Path safety: no `GET /channels/{id}` exists (only `/channels/{id}/messages` + `/share`), so `writable` is
   unambiguous.

3. **RestContract op** (server/.../contract/RestContract.kt, after `GET /api/channels` line 85):
   ```kotlin
   Op("GET", "/api/channels/writable", Tier.PARTICIPANT, response = arr<String>()),
   ```
   `arr<String>()` = the FIRST primitive-element array in REST_OPS. Verified safe end-to-end: walker
   `StructureKind.LIST` → `{type:array, items:{type:string}}` (SchemaWalker:65-68,150), registers NO component
   → no collision-ledger / tightness / conformance entry; ContractGenerator renders `Body.JsonArray` inline
   (ContractGenerator:105-106); ContractConformanceTest uses a hand-curated `cases()` not REST_OPS iteration.
   Drift test just needs route+op to match (they will). Tier-tooth: route uses `requireCommReader` (not
   `authenticatedApi`) → classifies PARTICIPANT, matching the op.

## Tests (dual-gate, `:server:check` — emulator-free)
- **`WritableChannelsRoutesTest`** (new, server test):
  - **T1 (the seam)**: seed a channel where the caller has canRead=true, canWrite=false → `/channels/writable`
    OMITS it; a canWrite=true channel → INCLUDED. `writable ⊆ readable`.
  - **T2 (single-source parity)**: for every readable channel, `id in writable` ⟺ `POST .../messages` returns
    201 (not 403). The endpoint's answer matches the actual send outcome — mutation-RED if `writableChannels`
    used `canRead` instead of `canWrite`.
  - **T3 (participant/member fail-closed)**: a human MEMBER (identityId, no grant) → empty writable; after an
    operator canWrite grant → the channel appears. (Reuses the auth test harness pattern.)
  - **T4 (gate)**: no credential → 401 (same as `/channels`).
  - **Mutation checks**: (a) revert filter `canWrite`→`canRead` → T1/T2 RED; (b) drop the `.filter` (return all
    readable) → T1 RED.
- **Drift/version gates** already present re-run green (RestContractDriftTest, ApiVersioningGateTest — the op
  auto-appears under both prefixes).

## Out of scope (do NOT build now — PO)
- WS `WritableChannelsEvent` push (additive later if the re-fetch round-trip ever bites).
- Client-side composer wiring / debounce (Dev owns, building in parallel against this shape).
