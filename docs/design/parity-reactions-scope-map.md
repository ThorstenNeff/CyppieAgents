# Parity — Reactions: web-ts Client Scope-Map (recon, NOT a commitment)

> Owner: Dev5 (web-ts). **Status: read-only pre-scope / front-load.** Reactions is NOT a chosen parity axis — the next
> axis is a PL/Auftraggeber call. This de-risks the *probably-heaviest* lane (Reactions = L, greenfield end-to-end) so a
> story can be cut fast if chosen. **No build, no commitment.** Grounded at develop (2026-07-28); greenfield confirmed
> (no reaction/emoji concept in web-ts today).

Kept deliberately **tight** (PL-steer): the design fork, its dependency, the recency signal, the two honesty classes,
and the union/envelope taxes — the decision-relevant facts, not an exhaustive build plan.

## 1. Store / reducer route — the design fork (Backend2 + Dev5 pairing, like Edit)

The fork hangs on **Backend2's emit model** — does a reaction re-emit the message, or emit a separate delta?

- **Route A — aggregate on the `DeliveredMessage` envelope** (`reactions?: ReactionView[]`, beside `mentions`). Folds
  through the SAME `applyMessage` path → **reuses the CYP-906 E1 upsert-by-id, NO new union variant (no CYP-834 tax).**
  Cost: couples a reaction change to a message re-emit, AND the upsert's newer-wins needs a **reaction-recency signal**
  (§2) — `editedAt` is body-edit-only, so a reaction change needs its OWN recency (ts/version) or a stale message replay
  could revert the reaction aggregate.
- **Route B — separate reaction event + `reactionsByMessageId` store slot.** Cleaner separation (no message re-emits)
  but pays the **CYP-834 closed-union tax**: a 5th variant on `CommWsServerEvent (Acl|Channels|Message|ReadState)` →
  the generated skew-validator + every client must tolerate it (contract-freeze coordination) + a new reducer/slice.

**Trade:** A = less tax, more coupling + a recency signal; B = cleaner, closed-union tax. Decide with Backend2's emit
model once the axis is chosen.

## 2. Two honesty classes (the sharp finds)

1. **Aggregation is server-authoritative — NEVER client-tally.** Counts-per-emoji + who-reacted come from the server;
   the client renders the aggregate, never computes it. (The recency signal in §1 is what lets the upsert know a newer
   aggregate supersedes an older one under Route A.)
2. **"Did I react" is viewer-relative = the leak class** (exactly today's `editableByViewer` / CYP-745 catch). The
   aggregate (counts + who-reacted) is **viewer-independent** → broadcast-safe (may ride the `DeliveredMessage`
   envelope). But **"have *I* reacted"** is self-only → it must **NEVER** be stamped on the viewer-independent broadcast
   payload (that leaks one viewer's state to all, like a per-viewer bool on `ChannelsEvent`). **Guardrail (PL):** even
   under Route A, the self "did-I-react" signal is carried SEPARATELY (ReadState-like, per-principal / self-only), not
   inside the `reactions[]` aggregate.
   - Corollary: the client has **no self-identity** (§5b, `AuthMe` content-free) → deriving "mine" client-side hits the
     same gap as Edit authorship. **Operator-only MVP:** `who-reacted.includes(OPERATOR_AGENT_ID)` when operator
     (client-derivable, like CYP-906 Edit); broader = a §5b self-signal. who-reacted-for-humans = §5b; agent reactions
     are identity-clean (`from = agentId`).

## 3. Taxes (summary)

- **Closed-union tax:** Route B only — a new `CommWsServerEvent` variant every client + the CYP-834 skew-validator must
  tolerate. Route A avoids it (rides the existing `message` variant).
- **Envelope-share tax (CYP-744):** reactions ride the **`DeliveredMessage` envelope**, never `Message1` (the stored
  message on the `/ws/hub` BYOA wire) — same discipline as `mentions` / `editedAt`.

## 4. Reuse when built (pointers, not a plan)

- Write path: `restRepo.add/removeReaction` → POST/DELETE `/api/channels/{id}/messages/{msgId}/reactions`.
- Optimistic-add + rollback-on-reject: reuse the CYP-875 / `state/aclCommit.ts` pattern (server-authoritative counts,
  so an optimistic "mine" rolls back if the server rejects).
- Render: pills in the CommPanel message row (emoji + count), colour never the sole signal (emoji + count text).

## 5. Open decisions before any build

1. **Is Reactions the next axis?** — PL/Auftraggeber. (Not yet.)
2. **Route A vs B** — Backend2 emit model (message re-emit vs separate delta), a Backend2+Dev5 pairing like Edit.
3. **Reaction-recency signal** (Route A) — a per-reaction ts/version for the newer-wins upsert.
4. **Affordance identity** — operator-only MVP vs broader §5b self-signal (mirrors the Edit ruling).
