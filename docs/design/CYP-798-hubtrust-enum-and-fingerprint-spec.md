# CYP-798 — Hub-trust (axis a) enum + byte-exact OOB-fingerprint spec (RATIFIED · BUILT · MERGED — design record)

> Author: Frontend/Client dev · **STATUS: ratified → built → MERGED to develop `275494e0`.** This is the retained
> **design/reasoning record** (`docs/design/` convention, cf. CYP-747 seam-analysis / trust-UI spec) — it captures
> WHY the contract has this shape. The implementation now lives in code; this doc is not re-edited to track code.
> Cross-language freeze so **web-ts** and Compose share ONE contract (pattern CYP-622 `MuxHello`). Two parts:
> **(1)** the axis-a hub-key trust enum(s) — the small gate part; **(2) the security-load-bearing byte-exact
> OOB-fingerprint derivation** — a divergent derivation silently defeats the §3 `OobFingerprintConfirmer` MITM
> protection (the human console-compare), so it is the core of 798.
>
> **★ Scope disambiguation (PL-ratified):** this is **axis a (TOFU / hub-key trust)** — KEEP SEPARATE from Backend's
> **axis c** `RemoteIssuerTrustState` (issuer, = S1c, held). Two distinct `:core` enums, never folded. Reasons: the
> CYP-797 `hubTrust_neverReferences_issuerAxis` tooth requires the separation; N4 needs distinct cause-handling;
> §5 distinct concerns.
>
> **As-built (all ratified as proposed):** enums in `:core/model/HubTrust.kt` (`HubTrustState` 5-state · `TrustRejectReason`
> closed · `HubDescriptorValidity`), standalone openapi-exported via `ContractGenerator.STANDALONE_ENUM_EXPORTS` +
> `SchemaWalker.registerNamedEnum`; the byte-exact TS port + fixture + freshness-guard + the cross-language parity
> harness (`Cyp798CrossLangParityTest`) all green both sides. See "Ratification gate" + the flag resolutions below.

---

## Part 1 — Enum(s) (axis a, hub-key trust)

Language-neutral, `@Serializable`, `:core/commonMain` (beside `AuthMe`). **Vocabulary shared; the TOFU state-machine
+ pin logic stay client-local** — only the enum names cross the wire, so Compose + web-ts speak the same trust words.

```kotlin
// The 5-state CLIENT-FACING TOFU trust model (uiux2 §5b = the N3 model from the CYP-747 seam analysis).
// Fail-closed default = UNKNOWN — NEVER a trusted/green look on an unknown/absent value ("never green-by-default").
@Serializable enum class HubTrustState { UNKNOWN, PENDING, TRUSTED, REJECTED, STALE }
```
- **UNKNOWN** — no trust verdict yet (fail-closed default; not-yet-evaluated / no data). Never renders as trusted.
- **PENDING** — a trust decision is in progress (e.g. the OOB fingerprint confirm is open, awaiting the human compare).
- **TRUSTED** — the hub key is pinned and matches (the only "green" state).
- **REJECTED** — trust was refused/aborted (see [TrustRejectReason]); terminal for this attempt.
- **STALE** — a previously-trusted verdict that can no longer be confirmed current (e.g. a non-LIVE feed) — marked, not
  silently shown fresh (safe-but-silent, cf. CYP-789).

> **★ Layering (PL-ratified — do NOT conflate three things):**
> 1. **This `HubTrustState` (shared `:core`)** = the 5-state client-facing UX model — the wire vocabulary Compose +
>    web-ts both speak.
> 2. **The 3-state raw-key primitive** `{UNPINNED, PINNED_OK, KEY_CHANGED}` (≈ today's `TrustResolution`) is the
>    **client-local** TOFU key decision — it STAYS local (state-machine + pin logic), it is NOT the shared enum. It is
>    an input the client derives `HubTrustState` from.
> 3. **Backend's `RemoteIssuerTrustState`** `{REMOTE_NOT_CONFIGURED, ISSUER_NOT_TRUSTED, ISSUER_TRUSTED}` is **axis c
>    (issuer)** — a SEPARATE enum, never folded in (the CYP-797 `Cyp443` tooth requires the separation).

```kotlin
// A CLOSED, machine-code enum of ONLY real trust-EVALUATION reject reasons (axis-a TOFU). Members grounded in the
// actual reject conditions (below), NOT guessed. PL-frozen RULE: closed · trust-eval-only · N4-distinct · machine-code
// (never a string-match). PL ratifies the LIST at this design-pass.
@Serializable enum class TrustRejectReason { KEY_CHANGED, OOB_REJECTED }
```
- **KEY_CHANGED** — the presented hub static ≠ the pinned key (a TOFU mismatch, auto-detected vs the pin) ⇒ HARD BLOCK,
  needs OOB re-pin. (Today's `RemoteFailure.TrustChanged` / `TrustResolution.Changed`.)
- **OOB_REJECTED** — the operator rejected the first-use OOB fingerprint compare ("doesn't match") — nothing pinned,
  terminal. (Today's `RemoteFailure.TrustRejected`, CYP-478/696.)

> **EXCLUDED from `TrustRejectReason` (N4 — the client MUST distinguish these; folding pollutes the reject enum + loses
> the distinction):**
> - **network error** (relay unreachable / hub offline / handshake failed) → `HubTrustState.UNKNOWN` (couldn't
>   evaluate), NOT a reject.
> - **issuer revocation** → `HubTrustState.STALE` (was trusted, no longer confirmable) — and the issuer verdict is
>   axis c (`RemoteIssuerTrustState`) anyway, separate.
> - **malformed / ≠32-byte key** → a distinct **UPSTREAM ERROR** (PL-freeze 1/2: "couldn't evaluate — invalid input",
>   surfaced as "invalid hub-descriptor"), fail-closed to `null` via `decodePin`, **NEVER a reject and NEVER TRUSTED**.
>   Anchored by the negative golden vectors (below).

> **PENDING is a distinct STATE, not a reject** (PL-freeze 2/2, §5b-P2: pending ≠ unknown) — it lives in `HubTrustState`.

**Package (my grounded choice):** `com.tneff.cyppieagents.model` in **`:core`** (beside `AuthMe`) — openapi.json-exported,
the cross-module contract home, the **TOFU axis**, and **NOT coupled to any `:server` issuer type** (axis c stays separate).

## Part 2 — ★ Byte-exact OOB fingerprint derivation (the security core)

**Source-of-truth — PORT byte-identically, DO NOT reinvent:** `app/shared/.../net/hub/trust/HubFingerprintDisplay.kt`
(CYP-482) + `PgpWordList.kt`. The TS derivation must reproduce it exactly; a divergence breaks the console-compare
(a silent MITM hole).

**Algorithm** (input `dhPubKey = base64(32 raw bytes)`):
1. **Decode + fail-closed:** base64 → raw bytes. Empty / malformed / **≠ 32 bytes → NO fingerprint (null)**, never a
   fabricated one. (This decode/guard is the caller layer; all golden vectors are valid 32-byte keys.)
2. **Digest:** `SHA-256(rawKey)` → 32 bytes. **All three forms fold the SAME digest** (word/hex always agree).
3. **Words (primary, security-bearing):** the **first 11** digest bytes; `token[i] = (i even ? EVEN : ODD)[byte]`,
   using the PGP **256**-word even/odd lists (exact 256 ⇒ zero modulo bias). `11 × 8 = 88 bit` ≥ the 80-bit OOB floor.
   The EVEN/ODD alternation is the PGP transposition guard.
4. **Hex (secondary, copyable):** colon-separated **LOWERCASE** hex of the full 32-byte digest.
5. **QR payload:** `"cyppie-hub-key:" + base64(rawKey)`  (the RAW key, NOT the digest — a scanner re-derives).

**Word-table drift guard (freeze):** `PGP_LIST_SHA256 = e9b7b0052233a74aa56724ed3f79c272036aed68d8f4c0856e833c8043b9ac62`
= `SHA-256( EVEN.join('\n') + '\n' + ODD.join('\n') )`, each list 256 distinct disjoint words. **The TS test MUST
recompute this same checksum over its ported EVEN/ODD tables** — a single diverging word breaks OOB equality.
Anchors: `EVEN[0]=aardvark` · `EVEN[255]=Zulu` · `ODD[0]=adroitness` · `ODD[255]=Yucatan`.

### The golden vectors (the cross-language freeze contract) — canonical location, NOT re-tabled here

The 6 positive (`zeros32`, `seq_0..31`, `mul7`, `mul5p1`, `all_0x01`, `all_0xFF`) + 4 negative (`too_short_31B`,
`too_long_33B`, `not_base64`, `empty`) golden vectors are the freeze contract. **To avoid a driftable copy, this doc
does NOT re-table them** — the single canonical, machine-readable set lives at:

- **`app/shared/src/jvmTest/resources/cyp798-golden-vectors.json`** — the authoritative Kotlin-side set (full colon-hex
  + all 11 words + indices + qrPayload per vector, positive and negative).
- **`app/shared/src/jvmTest/resources/cyp798-ts-derived.json`** — the live-TS emit (Frontend), byte-exact, freshness-
  guarded by `web-ts/src/connector/parityFixtureFreshness.test.ts`.

### Cross-language reproduction (as-built)
The `TS ≡ Kotlin` gate is proven by the tests, not by a table in this doc:
- **`app/shared/src/jvmTest/kotlin/.../net/hub/trust/Cyp798CrossLangParityTest`** (Team-2 harness) — reproduces the
  golden vectors against the Kotlin source-of-truth (`HubFingerprintDisplay` + `PgpWordList`) AND compares them to the
  live-TS `cyp798-ts-derived.json` emit → one assertion catches a co-drift either side alone would miss.
- **`web-ts/src/connector/hubFingerprint.test.ts`** (Frontend) — the TS port reproduces the 6 positives byte-for-byte,
  fail-closes the 4 negatives to `null`, and **recomputes `PGP_LIST_SHA256` over the ported EVEN/ODD tables** (a single
  diverging word REDs).
- **`HubFingerprintDisplayTest`** (commonTest, CYP-482) — the Kotlin source-of-truth's own coverage.

(The design-pass shipped a Kotlin `Cyp798FingerprintGoldenVectorTest` anchor; it was **dropped at merge as redundant**
with `Cyp798CrossLangParityTest` above — a 4th vector copy, no added coverage. No 3+ driftable copies of the vectors.)

## Ratification gate (PL) — what "no divergence" means
- TS derivation reproduces the **6 golden vectors** byte-for-byte (words, colon-hex, qrPayload).
- TS recomputes the **word-table checksum** == `PGP_LIST_SHA256` over byte-identical EVEN/ODD tables.
- **Fail-closed ANCHORED (Reviewer ②):** ≥1 **NEGATIVE golden vector** (≠32 bytes: 31B/33B · non-base64 · empty) →
  expected **`null`** via the `decodePin` seam (`PinnedHubStore.kt:68`: base64 → exactly `HUB_STATIC_KEY_SIZE`=32 bytes,
  else null). Without it the TS≡Kotlin gate never exercises the fail-closed path (all positive vectors are valid 32B).
  The Kotlin anchor test asserts `decodePin` returns null for these; the TS decode must match.
- The final authority is the **Kotlin object** (`HubFingerprintDisplay` + `PgpWordList`); the canonical machine-readable
  set is `app/shared/src/jvmTest/resources/cyp798-golden-vectors.json` (see "canonical location" above).

## Not decided here (flags)
1. `TrustRejectReason` MEMBERS proposed grounded ({KEY_CHANGED, OOB_REJECTED}); the **PL ratifies the LIST** at this
   design-pass and has **frozen the RULE** (closed · trust-eval-only · N4-distinct · machine-code). New members only if
   a new trust-EVALUATION reject condition appears (never a net/revoke/malformed cause — those route elsewhere).
2. Package chosen: `:core` `com.tneff.cyppieagents.model` (grounded; openapi-exported, TOFU axis, no `:server`-issuer
   coupling) — reviewer/PL may relocate within `:core`.
3. The TS build itself (tables + derivation + its golden test) is the post-ratification slice, not this draft.
4. **BUILD tooth (post-ratification, Reviewer ①):** extend the CYP-797 `Cyp443TrustAxisSeparationGuardTest` to scan the
   NEW `HubTrustState`/`TrustRejectReason` file **both directions** — the new enum must not reference axes a/b/c types,
   and they must not reference it. Today the guard scans only `TofuHubTrust.kt` / `ClientOperatorAuth.kt` → blind to the
   new enum file. Note as a build tooth (created with the enum), NOT this draft.

## Revisions (Reviewer-sanity GO → 3 revisions, this commit)
- **R1 (PL hard-constraint):** `HubTrustState` = the **5-state** client-facing TOFU model `{UNKNOWN/PENDING/TRUSTED/
  REJECTED/STALE}` (fail-closed default UNKNOWN), NOT the 3-state raw-key primitive (client-local) and NOT the axis-c
  issuer enum. (Part 1 above.)
- **R2 (Reviewer ②):** added negative golden vectors (fail-closed → null) + anchored `decodePin` in the test.
- **R3 (Reviewer ①):** noted the `Cyp443` guard extension as a post-ratification BUILD tooth (flag 4).
- **PL-freeze 1/2 (derivation-failure):** the ≠32B/malformed path is a distinct **UPSTREAM error** ("invalid
  hub-descriptor"), NOT a `TrustRejectReason` and NOT a `HubTrustState` value (REJECTED = evaluated-then-refused;
  malformed = couldn't-evaluate). The negative vectors anchor exactly this, distinct from the reject.
- **PL-freeze 2/2 (enum rules):** `HubTrustState` 5-state with **PENDING a distinct state** (≠ UNKNOWN);
  `TrustRejectReason` = closed, trust-eval-only, N4-distinct, machine-code — members grounded {KEY_CHANGED, OOB_REJECTED};
  package `:core/model`, TOFU-axis, no `:server`-issuer coupling.
