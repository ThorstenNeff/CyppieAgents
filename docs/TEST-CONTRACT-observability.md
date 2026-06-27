# Test-Contract — Observability Event-Log (CYP-44 / ST10) — v0.2

> Owner: QA/Tester · Epic **CYP-34** · Source: `docs/prd/06-observability-event-log.md`
> Status: **v0.1 approved by PO (2026-06-27); decisions folded into v0.2.** This is the repeatable cross-cutting
> evidence suite proving the five "passing ≠ functional" properties + `/ws/events` real-handshake + operator-only/fail-closed.
> **Activates as code as ST1/ST3/ST5/ST6 land.** Fixtures/seams coordinated with Backend (CYP-35/39/40) via the PO.

## Resolved decisions (PO, 2026-06-27)
1. **`log.dropped` is an `EventType` enum member** (in `:core`); `detail` carries the **cumulative drop count**. Asserted via `query`/`/api/events` (same telemetry surface the operator watches). **Side-condition (Backend):** the drop counter is in-memory authoritative and the `log.dropped` event itself is **exempt from the drop policy** — it must never be dropped, or the proof of a gap disappears. → test §2b.
2. **Intra-turn `context.usage` is OUT of scope** for ST10/MVP. ST10 covers band-crossing persistence (ST2). Intra-turn usage is a flagged additive DTO gap, not MVP.
3. **Spool idempotency:** **ST4 owns** the re-tail-no-dup AC; **ST10 references** it (no duplicate `hook.fired` after a restart re-tail).
4. **Repo home:** this file, `docs/TEST-CONTRACT-observability.md`, on `feature/CYP-44-test-contract`.

## 0. Placement & gating
- `:core/commonTest` — `Event` read-DTO + `EventType` enum (PO decision (a), in `:core`): serialization round-trip; **tolerant decode of unknown `type`** (PRD §4.1, like `TolerantToolsSerializer`) — a future `stall.*` (07) type must not crash decoding. `log.dropped` is part of the enum.
- `:server/src/test` — EventSink/queue/TimeSource/projector/spool/REST/WS (all `:server`-only, JVM).
- Gating = `./gradlew check` (backend/logic slice; **no Maestro** — UIs ST7/ST8 are separate). 
- Tooling: `testApplication` + ktor client; **real WS handshake via `client.webSocket{}`** (live: node `ws` / `scratchpad/ws-probe.js`) — never `client.get` on `/ws/…` (CYP-30 lesson).

## 1. Property: Ordering under load — total order over `seq` (no dup / no gap)
- **`seq_totalOrder_underConcurrentWriters`** (`:server`): N coroutines (≥16) × M appends through the REAL queue+writer path; drain; `query` ordered by `seq`. Assert contiguous, strictly-monotonic `seq` (no gap), **no duplicate `seq`**, count == N·M (within capacity). Run against BOTH the in-memory double and the SQLite impl.
- **`seq_totalOrder_survivesClockJump`**: inject a `TimeSource` whose `now()` is constant / goes backwards; `seq` still yields total order (PRD §5).
- *Why functional-not-passing:* a single-threaded happy path passes even with a non-atomic `nextSeq()`; only real concurrency + a hostile clock exposes a broken stamper.
- **Severity if it fails: S1** (order is the foundation of every correlation drilldown).

## 2. Property: Drop visibility — overflow emits `log.dropped`, never silent
- **2a `queueOverflow_emitsLogDropped_withCount_notSilent`** (`:server`): with a **latch-blocked sink** (§7) and tiny `queueCapacity`, flood K ≫ capacity. Assert: a `log.dropped` event is produced **with a cumulative count** in `detail`; **accounting closes** — `dropped + persisted == K`; policy is drop-newest (PRD §3.3).
- **2b `logDropped_isNeverItselfDropped`** (`:server`, PO side-condition): under sustained overflow, the `log.dropped` event(s) reliably surface in `query`/`/api/events` even though normal events are being dropped — i.e. the drop-accounting event is exempt from the drop policy. *Else the gap erases its own evidence.*
- *Why functional-not-passing:* a naive bounded queue silently discards — "passes" a no-crash test while **lying about completeness**. The gate is that the gap is *observable on the operator's surface*.
- **Severity if it fails: S1** (silent truncation = telemetry lies; Reviewer standing-gate "no silent caps").

## 3. Property: Observer-effect bound — tap/append non-blocking; hot path unaffected
- **3a `mediatorTap_neverSuspends_underBackpressure`** (`:server`, **gating**): with the sink blocked and queue full, the tap (`eventProjector.onStreamEvent`) **returns without suspending** (structural: `trySend`/`offer`, not `send`). Robust, non-flaky gate.
- **3b `tap_latencyUnaffected_bySinkSpeed`** (`:server`, **informational/non-gating**): microbench — tap latency with sink slow vs. fast; report p50/p99, assert p99 under a generous bound. Non-gating (CI timing is noisy) — evidence, not a flaky gate.
- *Why functional-not-passing:* the feature exists to measure under load; a tap that suspends on backpressure distorts the very thing being measured.
- **Severity if it fails: S1** (a blocking tap defeats the feature's purpose).

## 4. Property: Metadata-only needle-absence — end-to-end across all egress
- **`needle_absent_inDbFile_apiResponse_wsStream`** (`:server`): replay a `StreamJsonEvent` corpus (§7) with **two needle classes** injected into `TextBlock.text`, `ToolUseBlock.input`, `ToolResultBlock.content`, `Message.body`:
  1. a **secret-shaped** needle (`sk-ant-NEEDLE…`) — caught by `SecretMasker` (belt);
  2. a **plain-content** needle (a unique non-secret string, e.g. `NEEDLE-/etc/passwd`) — **NOT** secret-shaped, so masking would NOT catch it; only the **structural metadata-only projection** can exclude it.
  Run through the real tap → projector → sink, then **grep the raw bytes** of (a) the SQLite DB file on disk, (b) the `/api/events` JSON response, (c) the `/ws/events` stream frames. Assert **both needles absent in all three**.
- *Why functional-not-passing:* the plain-content needle decisively separates "masking works" (necessary) from "metadata-only is structural" (sufficient). A projector that copies a field through would leak it past the masker.
- **Severity if it fails: S1** (content/secret leak — the §2 Non-Goal, structurally enforced).

## 5. Property: Restart durability (WAL)
- **`events_surviveRestart`** (`:server`): append via SQLite `EventSink`; new sink over same db file → `query` returns all events in `seq` order.
- **`tornBatch_walRecoversConsistentPrefix_bootNotBricked`**: simulate a crash mid-batch (no clean flush); a fresh boot recovers a consistent prefix, **no torn/partial row**, boot does not throw (mirrors `JsonFileMessageStore` corrupt-file resilience, CYP-9 / `PersistenceTest`).
- **Severity if it fails: S2** (durability; data loss across restart).

## 6. Access surface — operator-only, fail-closed (same class as CYP-18/CYP-30)
- **REST `/api/events`:** `apiEvents_operator_200_pagedOrderedBySeq` (paging stable over `seq`; filters Agent/Type/Severity/window/`correlationId`); `apiEvents_agentToken_403`; `apiEvents_noToken_401` (fail-closed).
- **WS `/ws/events` — REAL handshake:** `wsEvents_operator_connectsAndTailsLive` (101 + appended event pushed live); `wsEvents_agentToken_upgradeRejected` (operator-only); `wsEvents_noToken_failClosed`; `wsEvents_foreignOrigin_rejected` (Origin gated at upgrade, CYP-30 — browsers don't CORS WebSockets); `wsEvents_reconnect_dedupeBy_id_seq` (no doubling).
- *Why functional-not-passing:* a single agent reading team-wide event metadata is a cross-agent leak of the same class as the filtered `AclEvent` (CYP-18). Must be fail-closed, proven on the real upgrade.
- **Severity if it fails: S1** (cross-agent metadata leak / auth bypass).

## 7. Fixtures & test seams needed from Backend (coordinated via PO — CYP-35/39/40)
1. **Replay corpus** of real `StreamJsonEvent`s (CYP-5 spike / CYP-13 runs) as a canonical NDJSON fixture — deterministic projector input for §4 needle injection + projection-type checks (ST3). I supply the needle-injection harness; Backend supplies the realistic event shapes.
2. **Latch-pausable sink/writer seam** (a pausable `EventSink` double or configurable slow drain) so overflow (§2) and non-blocking (§3) are **deterministic, not timing races**.
3. **Injectable `TimeSource`** (interface in PRD §5) for deterministic `seq` + clock-jump (§1).
4. Operator/agent **token wiring** in the events routes reuses `TokenRegistry`/`requireOperator` (CYP-30 live-token approach applies).

## 8. Cross-references
- **Spool idempotency** (re-tail-no-dup `hook.fired` after restart): owned by **ST4**; referenced here, not duplicated.
- **`context.usage` banding** (band-crossing + compact-threshold persistence): owned by **ST2**; ST10 asserts only that band-crossing events appear in the ordered/queried stream, not intra-turn usage (out of scope).

## 9. Severity scale (reused)
S1 live/blocking/security (needle leak, operator bypass, silent drop, ordering violation, blocking tap, `log.dropped` self-dropped) · S2 fragility/durability · S3 cosmetic/config-drift. Report prioritized + verified-good trust line; "verified" = ran it + saw evidence.

## Changelog
- v0.2 (2026-06-27): PO decisions folded — `log.dropped` is an `EventType` enum member (assert via query/`/api/events`) + new test §2b (`log.dropped` never self-dropped); intra-turn `context.usage` out of scope; ST4 owns spool idempotency (ST10 references); repo home confirmed.
- v0.1 (2026-06-27): first draft against the PRD; 5 properties + WS real-handshake + operator-only; fixtures/seams listed.
