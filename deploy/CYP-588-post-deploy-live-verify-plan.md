# CYP-588 — Post-Deploy LIVE-Verify Plan (subscribe-gap backfill, `/ws/agent`)

> Owner: **Backend (Team-1)** — deliberately NOT the CYP-588 builder (Backend2), so the fix isn't verified by its own
> author. **"Live proven," not "merged."** Run this AFTER the PO deploys CYP-588 to staging.
> Read-only diagnosis is free NOW; the forcing step (a controlled event burst) is MUTATING → runs post-deploy, controlled.

---

## 0. The mechanism (grounded, so the repro is real not theatrical)

Production store `SqliteAgentEventStore`:
- `live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 256, onBufferOverflow = **DROP_OLDEST**)` (`:39`),
  fed by `live.**tryEmit**(stored)` (`:81`) — **non-blocking; on a full 256-buffer it DROPS the oldest.**
- `subscribe(agentId, sinceSeq)` (`:85-97`): replay durable `query(seq>cursor)` then live `collect { if (rec.seq > cursor) { emit; cursor = rec.seq } }`.
- **The silent loss (pre-CYP-588):** a **slow** `/ws/agent` subscriber + a **>256 burst** overflows the live buffer → DROP_OLDEST
  drops a contiguous seq range → the subscriber's next live event has `seq = cursor + N` → `if (rec.seq > cursor)` just
  advances `cursor`, **silently skipping the dropped seqs**. The durable table is LOSSLESS (rows are all there) — only the
  live fan-out dropped them.
- **CYP-588 fix (what we're proving):** on a detected jump (`rec.seq > cursor + 1`), re-query `query(agentId, cursor)` to
  **backfill the missing durable rows** before emitting `rec` + a **WARN** on the detected gap. `seq` stays gapless to the client.

`/ws/agent?agentId=<id>&since=<seq>` emits one `StoredAgentEvent {seq, agentId, projectId, tsMs, event}` per frame; `seq`
is the load-bearing cursor (`AgentSocket.kt:117-120`).

---

## 1. Read-only pre-checks (NOW, no mutation)

| # | Check | Expect |
|---|---|---|
| 1.1 | Deployed store is the DROP_OLDEST/tryEmit path | `SqliteAgentEventStore:39/81` unchanged in the deployed build (the loss mechanism the fix guards) |
| 1.2 | `/ws/agent` is OPERATOR-only, fail-closed | unauth WS upgrade → refused (don't leak transcript) |
| 1.3 | Durable backfill endpoint reachable | `subscribe`/`query` present; the fix's re-query target exists |

---

## 2. Deploy-didn't-break baseline (reconciles with my standard §1/§2)

| # | Check | Expect |
|---|---|---|
| 2.1 | Health / boot markers | CP/Kratos/Relay up; hub self-admit LIVE-not-INERT (per CYP-576/M1-M2 runbooks) |
| 2.2 | **default-agents 7/7 byte-identical** | `GET /api/agents` = the 7 seeded agents, ids unchanged (governor roster-floor; preserve-safe import) |
| 2.3 | Normal `/ws/agent` replay | a fresh `/ws/agent?agentId=<A>` replays durable history then goes live — no blank screen, seq-continuous |

> §2 proves the deploy BREAKS nothing. It does NOT prove the gap-detect works — that's §3.

---

## 3. LIVE proof: force a real subscribe gap → prove backfill (the point of this plan)

### 3a. Force the gap (controlled mutation)
1. Open a **deliberately SLOW** `/ws/agent?agentId=<A>` subscriber — closest-to-dogfood = a **backgrounded browser tab**
   (throttled timers stop draining frames); deterministic alt = a scripted WS client that reads a few frames then **pauses
   draining for ~3–5 s** while the burst flows.
2. While it's paused, generate a **burst of > 256 events for agent A** faster than it drains. Closest-to-dogfood =
   **a real verbose agent turn** (a task that emits a long stream-json burst, e.g. a command with large output).
   **>256 must be GUARANTEED, not hoped** — if the backgrounded-tab burst can't reliably clear 256, use the **fallback: a
   scripted slow-reader with a controlled inject of a KNOWN count** (e.g. exactly 400) so the drop is guaranteed and measurable.
3. Resume the slow subscriber's draining.

### 3b — STEP 1 (MUST PASS FIRST): prove the gap was REAL — the non-vacuity gate
> **A green Step 2 is meaningless unless a drop provably happened.** If no gap was created (burst ≤ buffer / consumer wasn't
> actually slow), "no hole" is **VACUOUS** — it only shows "no burst happened," NOT "backfill works." Verify the drop FIRST:

| # | Check | Must hold (gap was real) |
|---|---|---|
| 3b.1 | **durable count jumped > 256 in the slow window** | `query(A)` count rose by **> 256** during the paused window (the burst really exceeded the 256 buffer) |
| 3b.2 | **the live stream RECEIVED FEWER than durable during the window** | the slow subscriber's live-received count (pre-backfill) < durable appended count → **a drop provably occurred** |
| 3b.3 | **WARN fired** | the gap WARN is present → the drop path was actually triggered (second, independent confirmation) |

**If 3b.1–3b.3 do NOT all hold → the test is VACUOUS. Re-run with the guaranteed-burst fallback. Do NOT read Step 2 as a pass.**

### 3c — STEP 2 (only meaningful after Step 1): prove the gap-detect BACKFILLED it
The slow subscriber records **every `seq` it receives**. After it settles, assert against the durable truth
`GET /ws/agent?agentId=<A>&since=0` (or `query`) as the reference:

| # | Check | GREEN (fix works) | RED (silent loss) |
|---|---|---|---|
| 3c.1 | **seq continuity** | received seqs form a **contiguous** range (no hole) from the subscribe cursor to the latest appended seq | a missing seq range = the DROP_OLDEST hole never backfilled |
| 3c.2 | **event-count match** | count(distinct received seq) == count(durable rows for A in the window) | received < durable |
| 3c.3 | **no cursor-jump** | the received transcript == the durable `query(A, 0)` transcript, byte-for-byte per event | a jump past the dropped range |

> Client-side dedup by `seq` is expected (the backfill re-query may overlap the buffered tail — dedup keeps it to one row/seq).
> **Order is load-bearing: Step 1 (gap real) BEFORE Step 2 (gap healed). Step 2 green without Step 1 = vacuous, not a pass.**

---

## 4. No-regression (the fix must not fire in normal operation)

| # | Check | Expect |
|---|---|---|
| 4.1 | **Normal (non-slow) delivery unchanged** | a normally-draining `/ws/agent` client during a normal-rate turn receives every event, seq-continuous, **no backfill re-query, NO WARN** |
| 4.2 | **WARN only on a real gap** | the gap WARN is PRESENT in §3 (forced gap) and **ABSENT** in §4.1 (normal) — the discriminator is a real drop, not normal load |
| 4.3 | **No perf/latency regression on the hot path** | normal turn latency unchanged (the jump-check is O(1) per event; the re-query fires only on an actual jump) |

---

## 5. Combined post-deploy sequence (one run)

`§1 read-only pre-checks` → `§2 deploy-didn't-break (health + default-agents 7/7)` → `§3 force gap: **Step 1 prove the gap was
REAL (durable +>256 · live-received < durable · WARN)** → **Step 2 prove backfill (seq-continuous · count-match · no jump)**` →
`§4 no-regression (normal delivery clean + WARN-only-on-gap)`.
**"CYP-588 live proven" = §3 Step 1 GREEN (a real >256 drop provably occurred) AND §3 Step 2 GREEN (it was backfilled, transcript
complete) AND §4 GREEN (no false-fire).** Step 2 green WITHOUT Step 1 = **vacuous, NOT a pass** (no gap was ever created).
Anything RED in §3 Step 2 (after Step 1 passed) → the gap-detect does not work live → **do NOT call it proven; escalate.**

---

## Appendix — honesty notes

- §3 is inherently **mutating** (needs live event activity) → it runs post-deploy, controlled; pure read-only cannot trigger it.
- The `> 256` burst is the load-bearing condition — under 256, no drop, no gap, nothing to prove. This is now the hard
  **§3 Step-1 non-vacuity gate** (prove the drop happened BEFORE reading the backfill as a pass) — not a footnote. Guarantee
  >256 via the scripted-inject fallback if the backgrounded tab can't.
- Reference truth is the **durable** store (`query`/a full `since=0` replay), which is lossless within retention
  (`DEFAULT_RETAIN_PER_AGENT`); the whole test is "did the LIVE-path gap get healed from the durable truth."
