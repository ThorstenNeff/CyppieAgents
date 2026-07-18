# CYP-705 read-state — server `:core` contract (RATIFIED)

**Owner:** Backend2 · **Reconciled with:** UIUX2 §8 (`docs/design/cyp705-unread-per-channel-ux-spec.md`) · **For:** Dev5 (renderer/cursor path) + Tester2.
**Status:** ratified bilaterally (Backend2 pin ⇄ UIUX2 §8; independently converged on ①③). This doc is the surface both sides build against.

Read-state answers "unread **per channel**, durable, cross-session/-device" — the *unread-of-record*. It is **only** truthful with a **server-authoritative, per-principal `lastRead` cursor**; a client-only marker is forbidden as the source (UIUX2 §0).

## Ordering key: `seq`, not `ts`
`Message` now carries `seq: Long` (CYP-705) — the store's monotonic append-order ordinal (SQLite `AUTOINCREMENT` / in-memory insertion order), stamped on the returned/delivered copy. It is **canonical / viewer-independent** and the authoritative ordering key (client `ts` is observed, not authoritative — like the event-log). Global-monotonic ⇒ also a total order within any channel. `seq` is a `Message` field; the read cursor is **not** (it is per-viewer — CYP-704 discipline). Input contract (UIUX2 §8 ⑤ / Tester2 #68): channel message reads arrive **seq-ascending** (the stores already `ORDER BY seq`), so the client's `firstUnread = findIndex(m.seq > lastReadSeq)` and `upToSeq` are well-defined; the client does **not** re-sort.

## DTOs (`:core`)
```kotlin
data class ChannelReadState(val channelId: String, val lastReadSeq: Long, val unreadCount: Int)  // present ⟺ a cursor exists
data class MarkReadRequest(val upToSeq: Long)
// ReadStateEvent(channelId, lastReadSeq, unreadCount) — a new CommWsServerEvent variant (server increment)
```

## Three states — carried by PRESENCE (UIUX2 §8 ①), never a nullable field
- **present, `unreadCount > 0`** → unread (count badge).
- **present, `unreadCount == 0`** → confirmed-read (render nothing — authoritative all-clear).
- **ABSENT from the read-state list** → **UNKNOWN** (no cursor yet) → the visible neutral "•", never silence, never a fabricated 0. `seed-at-join` is rejected (would fabricate "all prior read").

A `ChannelReadState` exists **iff** the principal has a cursor for that channel ⇒ both fields non-null.

## Endpoints
- **`GET /api/read-state` → `List<ChannelReadState>`** — the caller's read-state, one entry per channel the caller has a cursor for (absence ⇒ UNKNOWN). Read-tier (`requireCommReader` subject); ACL-`canRead`-scoped. Standalone carrier (①): read-state is volatile (every message/read moves it) → kept off the stable `/api/channels`.
- **`POST /api/channels/{id}/read` `{upToSeq}` → `ChannelReadState`** — advances the caller's cursor to `max(existing, upToSeq)` (monotonic; a lower/late `upToSeq` is a no-op), returns the updated state. **Non-optimistic**: the client shows "read" only on this 200 / the `ReadStateEvent` echo — never local scroll optimism. `requireCommReader` subject; ACL-`canRead`-gated (uniform 403 without a read grant).
- **Live `ReadStateEvent`** on `/ws/comm` (server increment) — a standalone, **self-only** per-`(viewer,channel)` delta (routed only to the viewer's own connections via the existing per-participant filter). Fires on: a new message (each `canRead` viewer ≠ sender recomputed), the viewer's own mark-read (→ their other devices), an ACL grant/revoke. **Single count source:** the server computes + pushes `unreadCount`; the client renders it, never does arithmetic. Not folded into CYP-704 `MessageEvent` (704 Phase-2 is parked — decoupled).

## Compute + store
- `unreadCount(viewer, channel) = count( messages in channel with seq > lastReadSeq ∧ canRead ∧ in-project ∧ from ≠ viewer )` — reuses the existing channel query; own sends excluded (mirrors `MessageDeliverer`).
- `ReadCursorStore` keyed `(projectId, subject, channelId) → lastReadSeq`, dual Sqlite/Pg + a `MigrationGated` wrapper (the `DeliveryLog` form). `subject` = the ACL read-subject (`requireCommReader`/`wsReaderOrNull` → agentId / operator / human identityId) — the **same viewer-identity axis** as ACL + CYP-704 `mentionsYou` + CYP-706 presence (`AuthMe` stays content-free; the client needs no `identityId`).

## Out of scope
Read-receipts (cross-principal "who read this") = a separate guarantee (its own cursor-visibility + disclosure policy). Human/identity **mention** targets = CYP-704 Phase-2. This contract is self-only read-state.

## Build order (a2-po)
1. **`Message.seq` additive (`:core`)** — unblocks Dev5's divider (`firstUnread`) + `upToSeq`. ← this increment.
2. `ReadCursorStore` (dual + MigrationGated) + seq store-threading + `GET /api/read-state` + `POST …/read` + `ReadStateEvent`. ← server increment.
Dev5 builds the degraded scaffold (UNKNOWN "•") now against this surface; wires the live path after the server increment.
