# CYP-798 landing — N3 per-hub trust render: web-ts wiring plan (design-prep, NOT a build)

**For:** Dev5 (web-ts client-render) · **Owner:** Dev5 · **Coordinated via:** a2-po · **UX-QA / rendering-spec owner:** uiux2
**Status:** DESIGN-PREP. **No production build against the `HubTrustState` enum until it is exported to web-ts** (CYP-798
gate green). Building the render against a not-yet-existent enum would be F2-vacuous (green-by-absence). This plan makes
the landing a *mechanical* step, not a from-scratch design, the moment two gates go green.

**Authoritative inputs (read at the object):**
- (a) **Rendering spec — uiux2, CYP-755** `docs/design/cyp755-model2-hub-trust-state-ux-spec.md`: the 5-state render
  (glyph/label/tone/testid/aria), placement, teardown/setup flow, cause-separation, the 9 honesty teeth (§6), and the
  **mandatory ⚠ malformed signal** (§4/§6.9).
- (b) **Enum spec — CYP-798** `docs/design/CYP-798-hubtrust-enum-and-fingerprint-spec.md`: `HubTrustState { UNKNOWN,
  PENDING, TRUSTED, REJECTED, STALE }` + closed `TrustRejectReason { KEY_CHANGED, OOB_REJECTED }` in `:core/commonMain`
  (`com.tneff.cyppieagents.model`, openapi.json-exported); **malformed is a distinct UPSTREAM signal, NOT an enum value**.
- (c) my **CYP-800** N1.1 registry (`net/hubRegistry.ts`) — where per-hub trust state attaches (the N1 `{hubId → (endpoint,
  trust, role/tier, credential)}` vision; today only `endpoint`).

**Axis discipline (PL-ratified, do not conflate):** this is **axis a (TOFU / hub-key trust) = `HubTrustState`**. Keep
SEPARATE from **axis c (issuer) = `RemoteIssuerTrustState`** (CYP-797, Compose/Team-1 — NOT web-ts, NOT this plan). The
CYP-797 `hubTrust_neverReferences_issuerAxis` tooth requires the separation; my render must never read the issuer axis.

---

## §0 The two landing gates (what must be green before any build)

| Gate | What lands | Blocks |
|---|---|---|
| **G1 — enum export** | `HubTrustState` (+ `TrustRejectReason`) in `:core`, openapi.json-exported → web-ts `contract.ts`/`contractSchemas.ts` via `contract:gen`/`zod:gen`. | the render model's typed state. |
| **G2 — connect-flow promotion** | the trust-state/connect-flow layer promoted `app/shared → :core` (CYP-755 §4b, Backend2/Team-1) so web-ts can consume it, carrying per-`hubId` trust + a **malformed/upstream** signal + per-hub reject `code`. | the (c) mapping's *inputs*. |

Until **both** are green, this stays a plan. The render *shape* (§2) and the *mapping table* (§3) are frame-independent
and fixed here; the **[TF] edges** (§5) — the PENDING→TRUSTED trigger, the reject-code taxonomy, the revocation signal —
are filled in only when Team-1's trust frame lands (per CYP-755 §5).

---

## §1 The consumed contract shape (as it will arrive in web-ts)

**★ Boundary reconciliation (measured 2026-07-22 — `feature/CYP-798-ts-build` is building this in parallel):** the trust
**vocabulary already exists there** as `web-ts/src/connector/hubTrustModel.ts` (a-po/Frontend) — the string-literal
unions `HubTrustState`/`TrustRejectReason`/`HubDescriptorValidity` + `*_DEFAULT`/`*_STATES` constants, parity-bound to
`:core/model/HubTrust.kt`; plus `connector/hubFingerprint.ts` + `pgpWordList.ts` (the byte-exact OOB derivation).
**Those are NOT mine.** My render layer **imports** the vocabulary from `connector/hubTrustModel.ts` — it does **NOT**
redefine it (an earlier draft of this plan named a colliding `comm/hubTrustModel.ts`; corrected). Clean split:
**a-po/Frontend = vocabulary + fingerprint (`connector/`); Dev5 = render view + connect-flow mapping + badge (`comm/`).**

At G1 the Kotlin `@Serializable enum` serializes as **UPPERCASE** string literals; the generated `contract.ts` type is
`export type HubTrustState = "UNKNOWN" | …` (measured, see the enum-codegen bilateral) and the app-facing vocabulary in
`connector/hubTrustModel.ts` mirrors it (parity-bound). My render consumes the `connector/` vocabulary.
**★ Naming reconciliation (flag to uiux2 via coordinator):** CYP-755 §1 writes the client model lowercase
(`'unknown'|'pending'|…`); the wire enum is UPPERCASE. Decision to ratify at landing: the render model consumes the
UPPERCASE wire value and maps to the lowercase css-class/`data-testid` vocabulary (`hub-trust-{state}`,
`hub.trust.{hubId}.{state}`) — i.e. the wire word is uppercase, the *presentation* token lowercase. This mirrors how
`remoteSecurityTierModel` maps a wire tier to a css tone. Pin the exact casing map with uiux2 before building (avoids a
[[shared-key-landing]] drift).

**Casing (Q1 — uiux2 answered, CYP-755 `0ac8215e`):** a plain 1:1 `.toLowerCase()`, no special case (all 5 are single
words): `UNKNOWN→unknown … STALE→stale`. Mapped **at the seam** (pattern `remoteSecurityTierModel`) to css class
`hub-trust-{state}`, `data-testid="hub.trust.{hubId}.{state}"`, glyphs `◯◔●⊘◑`. The wire word stays UPPERCASE; only the
presentation token is lowercase.

**★ malformed is NOT a `HubTrustState` — and NOT an arm of the trust badge either (uiux2 correction, `tier*≠trust*`
discipline).** It is the separate `HubDescriptorValidity { VALID, MALFORMED }` enum (a-po's, `connector/`), a per-`hubId`
field (Q2). On `MALFORMED` the render produces **TWO independent outputs**, never one conflated arm:
1. the **trust badge** = `unknown` (fail-closed — trust could not be evaluated; never `rejected`, never `trusted`);
2. a **separate ⚠ upstream-error marker** in a **DISTINCT token namespace** — `hub-descriptor-invalid` /
   `data-testid="hub.trust.{hubId}.upstreamError"`, **NOT** `hub-trust-*` (folding it into the trust namespace would
   conflate a descriptor/upstream error with a trust verdict). **⚠ is MANDATORY/always on malformed** (CYP-798 §4b, PL).
   *(Exact slug tracks a-po's CYP-798 §4b signal name; `hub-descriptor-invalid` is uiux2's proposal.)*

So the model is **two signals, not one union arm** — honesty in keeping them separate:
```ts
// the trust badge is ALWAYS one of the 5 states; a malformed descriptor maps to UNKNOWN (fail-closed), NOT a 6th value.
export function hubTrustBadgeState(trust: HubTrustState | null, validity: HubDescriptorValidity): HubTrustState {
  if (validity === 'MALFORMED') return 'UNKNOWN' // couldn't evaluate → unknown; NEVER trusted/rejected
  return trust ?? 'UNKNOWN'                       // fail-closed default (no signal ⇒ unknown, not absence, not TRUSTED)
}
// SEPARATE upstream signal — its OWN namespace, rendered (⚠) iff malformed, ALWAYS (mandatory).
export const descriptorUpstreamError = (validity: HubDescriptorValidity): boolean => validity === 'MALFORMED'
```
Fail-closed default (no trust signal) = `UNKNOWN` — never absence, never TRUSTED (same rule as CYP-800 `endpointFor→null`
and `unreadModel` `unknown ≠ zero`).

---

## §2 The render component (reuse, not a third dot-vocabulary)

Per CYP-755 §1/§7, the trust badge is **an instance of the existing glyph+fail-closed+always-visible pattern**, not a
new one. Reuse ledger:
- **`remoteSecurityTierModel.ts` / `RemoteSecurityTierBadge`** — distinct glyph *form* + label + always-visible +
  never-green-by-default. The trust model mirrors its `model → {glyph, label, tone, testid}` selector shape.
- **`statusDotSpec` / `dotRoleVar` (`lifecycleStatus.ts`, CYP-431)** — UNKNOWN as a distinct ring *form* (not colour-only).
- **`ProjectSwitcher` (`App.tsx:957`)** — list + active-pointer, for the hub switcher (§4).
- **`AuthGate`** — resolve-then-render + per-hub whoami/role (§3 flow, N5b).

**Planned files (created only at landing — RENDER layer, distinct from a-po's `connector/` vocabulary):**
- `web-ts/src/comm/hubTrustView.ts` — pure: **imports** `HubTrustState`/`HubDescriptorValidity` from
  `../connector/hubTrustModel` (a-po's vocabulary, do NOT redefine); the render-only **two-signal** derivation (§1) —
  `hubTrustBadgeState(trust, validity) → HubTrustState` (malformed→UNKNOWN, fail-closed) **and** the separate
  `descriptorUpstreamError(validity) → boolean` — plus a `hubTrustGlyphSpec(state) → {glyph, label, tone, testid, aria}`
  selector (lowercase presentation token, mapped at the seam). Framework-free + unit-tested (the honesty rules live
  here, provable — like `unreadModel`/`capacityModel`). *(Named `hubTrustView`, NOT `hubTrustModel` — the latter is
  a-po's on `connector/`; avoids the collision.)*
- `web-ts/src/comm/HubTrustBadge.tsx` — renders the selector; `data-testid="hub.trust.{hubId}"`, `role="status"`
  `aria-live="polite"` (assertive **only** for the *active* hub going REJECTED/STALE mid-session — CYP-755 §1 a11y).
- CSS `hub-trust-{state}` (5 tones via css-var) **and a SEPARATE `hub-descriptor-invalid`** class for the ⚠ upstream
  marker — a distinct namespace, NOT `hub-trust-malformed` (do not conflate descriptor-error with trust state). Guard
  BOTH shipped classes with a `readFileSync` presence test (jsdom is CSS-blind — [[jsdom-tests-are-css-blind]]).

The 5 glyph forms + DE labels are fixed by CYP-755 §1 (◯ „Vertrauen nicht geprüft" / ◔ „wird geprüft…" / ● „vertraut" /
⊘ „abgelehnt" / ◑ „abgelaufen — erneut bestätigen"), + the ⚠ malformed „Ungültiger Hub-Descriptor — Status nicht
interpretierbar". Colour-never-sole: every arm carries a distinct glyph-form **and** word (WCAG 1.4.1).

---

## §3 (c) Connect-flow-state → `HubTrustView` mapping

The client derives `HubTrustView` from the promoted (G2) connect-flow + TOFU inputs — it is **client-local derivation**,
the ONE place the mapping lives (like `unreadViewFrom` is the one place the presence rule lives). Inputs (from the
promoted layer, per-`hubId`):
- **connection posture** (transport): unreachable/handshake-failed → NOT a trust verdict.
- **TOFU key primitive** (client-local, stays local per CYP-798 layering): `{UNPINNED, PINNED_OK, KEY_CHANGED}`.
- **OOB-confirm flow state**: confirm-open/awaiting-human-compare.
- **reject `code`** (machine, `{error:{code}}` — reuse `restErrorCode`): `TrustRejectReason` values.
- **revocation/expiry signal** (G2/[TF] mode: push vs lazy).
- **descriptor validity**: malformed/≠32-byte → the separate upstream signal.

**Mapping (frame-independent rows fixed now; [TF] rows marked):**

| Input condition | → `HubTrustView` | Note |
|---|---|---|
| no signal yet / not contacted / **network unreachable** | `state: UNKNOWN` | transport ≠ trust verdict; network error is UNKNOWN, **not** REJECTED (CYP-755 §4, CYP-798 N4) |
| descriptor malformed / ≠32-byte key | **`malformed`** | distinct arm, ⚠ always; never UNKNOWN, never REJECTED (CYP-798 fail-closed `decodePin→null`) |
| OOB fingerprint confirm open / verification in progress | `state: PENDING` **[TF-trigger]** | only during *real* verification — never a catch-all for "not connected" (CYP-755 tooth 2) |
| TOFU `PINNED_OK` **and** hub affirms | `state: TRUSTED` **[TF-trigger]** | the ONLY green; never derived from "connection up"/absence (CYP-798 P3) |
| reject `code = KEY_CHANGED` or `OOB_REJECTED` | `state: REJECTED` **[TF-code]** | machine-code, distinct from network + revocation |
| revocation/expiry after TRUSTED | `state: STALE` **[TF-signal]** | fail-closed: stop showing hub surfaces as trusted (CYP-755 tooth 3, stale-lit) |
| inactive hub whose state is not fresh | `state: UNKNOWN` | switcher list shows non-fresh as UNKNOWN, never last-cached TRUSTED (CYP-755 §2, [[forecast-vs-observed-disclosure]]) |

**★ [TF] edges RESOLVED (Q3 — a-po/PL answered 2026-07-22, CYP-747-grounded).** The state machine is now fully
specified, so every row above is fillable at landing:
- **UNKNOWN** (default) **→ PENDING**: a TOFU pin is recorded, awaiting the OOB fingerprint compare.
- **PENDING → TRUSTED**: a **successful** OOB fingerprint confirmation via `OobFingerprintConfirmer` (post-pin) — **NEVER
  from absence / "connection up"** (this is the F4 / PL-0089 core: TRUSTED is hub-affirmed, never inferred).
- **PENDING → REJECTED**: `TrustRejectReason` = **`KEY_CHANGED`** (presented key ≠ the pinned TOFU key → MITM/rotation)
  or **`OOB_REJECTED`** (the human compared the OOB fingerprint and rejected). Client maps `code → curated copy`, never a
  message string-match (reuse `restErrorCode`).
- **TRUSTED → STALE**: a **PUSH** revocation signal (sub-weiche a2 decided push, not lazy — so STALE enters promptly,
  no stale-lit window).
- **malformed** (`HubDescriptorValidity.MALFORMED`, N4's 4th axis, separate per-`hubId` field): badge `UNKNOWN` + the
  mandatory ⚠ upstream marker (§1) — not a transition, a parallel signal.

All rows (non-[TF] + the now-resolved [TF]) are buildable at G1+G2 — nothing in the mapping is left guessed.

---

## §4 Placement (deferred wiring points, CYP-755 §2)

- **Active-hub chrome strip** — beside the CYP-733 tier-strip (`App.tsx` workspace chrome, always-visible, not a
  closable window). Shows the **active** hub's real trust view — a dead/rejected active hub renders REJECTED/STALE/UNKNOWN,
  never optimistic PENDING (Sweep-Fund-#5 lesson).
- **Hub switcher** — `ProjectSwitcher`-shaped list + active pointer; each entry carries its own trust glyph; inactive
  non-fresh entries render UNKNOWN.
- **Hub-scoped 401 already landed** (my CYP-800 N4.b): a Hub-B 401 fires only B's handler — the precondition CYP-755 §4
  flags for N4b is **already satisfied** on develop, so the switch-flow's per-hub fail-closed does not need new 401 work.

Both placements are wiring over the §2 component; they attach at landing once the active-hub/registry surface carries
the per-hub trust view.

---

## §5 What is buildable-at-landing (mechanical) vs [TF]-gated

**Mechanical at G1+G2 (no new design):** the `comm/hubTrustView.ts` pure model + selector (importing the `connector/`
vocabulary), `HubTrustBadge.tsx`, the `hub-trust-{state}` + separate `hub-descriptor-invalid` CSS + their presence
guard, the fail-closed default, the two-signal malformed handling (badge→UNKNOWN + separate ⚠), the UNKNOWN/inactive-
not-fresh mapping rows, and teeth 1–4, 7, 8, 9 from CYP-755 §6.

**★ The [TF] edges are now RESOLVED (Q3 answered) — no longer gated:** the full state machine (UNKNOWN→PENDING→
TRUSTED/REJECTED, TRUSTED→STALE via PUSH; codes KEY_CHANGED/OOB_REJECTED; TRUSTED only via `OobFingerprintConfirmer`,
never from absence) is specified in §3, so teeth 3 (stale) and 4 (cause-separation) are buildable at landing too.

**Still gated (NOT on the trust enum — on multi-hub):** teeth 5 (role-doesn't-travel) and 6 (no-optimistic-switch) and
the active-hub-unambiguous indicator exercise the **switch between ≥2 hubs**, which does not exist yet (single 'local'
hub). This is the **N5.a** slice — measured GATED (2026-07-22): the role/whoami is global today, the per-hub whoami CALL
is already satisfied by CYP-800 (`RestHubRepo(hubId).fetchAuthMe()` + threaded `activeHubId`), and role ≠ trust (distinct
axis, not enum-gated). A `roleByHub` pre-build now would be F2-vacuous (no non-vacuous acceptance until a 2nd hub + a
switcher). So N5.a's behavioral half **hangs on CYP-801's multi-hub render + switch flow** — it becomes testable then,
not as a standalone slice now (coordinator-confirmed).

## §6 Teeth to build (from CYP-755 §6 — mutation-verified at landing)

Carry all 9 as mutation-red acceptance teeth. The high-value ones for this render:
- **fail-closed default** — no signal → UNKNOWN render (mut: default TRUSTED/optimistic → RED).
- **PENDING ≠ UNKNOWN ≠ TRUSTED** — three distinct glyph-forms+labels (mut: "wird geprüft" for not-connected, or
  unknown==trusted look → RED = Sweep-Fund-#5 class).
- **malformed = badge UNKNOWN + a SEPARATE mandatory ⚠** (two signals, not one arm): on `MALFORMED` the trust badge is
  `unknown` (fail-closed — correct) AND the `hub-descriptor-invalid` ⚠ marker is present. Muts: ⚠ omitted on malformed →
  RED (the distinct upstream signal vanishes); malformed rendered as `rejected`/`trusted` → RED (invented verdict); the
  ⚠ emitted in the `hub-trust-*` namespace instead of the distinct one → RED (conflates descriptor-error with trust).
- **colour-never-sole** — each arm glyph-form + word (WCAG 1.4.1).
- **inactive not stale-trusted** — switcher non-fresh renders UNKNOWN, not last TRUSTED.

## §7 Questions — ALL RESOLVED (routed + answered 2026-07-22)

1. **Casing map** (§1) — RESOLVED (uiux2, CYP-755 `0ac8215e`): plain 1:1 `.toLowerCase()`, wire UPPERCASE → presentation
   lowercase, mapped at the seam. See §1.
2. **malformed signal form** — RESOLVED: `HubDescriptorValidity { VALID, MALFORMED }` (a-po), a **separate per-`hubId`
   field** (confirmed by a-po/PL) — matches the parallel `trustByHub` store (Q4). Rendered as badge→UNKNOWN + a distinct
   ⚠ namespace, NOT a trust-badge arm (§1).
3. **[TF] set** — RESOLVED (a-po/PL): the full state machine + `KEY_CHANGED`/`OOB_REJECTED` cause-map + PUSH revocation +
   TRUSTED-only-via-`OobFingerprintConfirmer`. See §3.
4. **Registry attach point** (my CYP-800) — RESOLVED (my call, CYP-800 now merged): the per-hub trust view attaches as a
   **parallel per-`hubId` store in `HubState`** (a `trustByHub: ReadonlyMap<HubId, HubTrustView>`, keyed like
   `unreadView`/`terminalStateByAgent`/`runStateByAgent` already are), **NOT** by extending the pure
   `HubEndpointRegistry`. Rationale: `hubRegistry.ts` is a pure endpoint resolver whose `endpointFor → null` fail-closed
   semantics are single-purpose; folding trust into the entry couples two lifecycles (endpoints are boot-seeded, trust
   is live/evolving) and muddies the fail-closed contract. Trust is server-confirmed live state — the same shape as the
   other per-key live maps in `HubState`, fed by the same reducer idiom (`applyCommEvent`-style). The registry stays the
   *endpoint* single-source; trust is its own per-hub source. **Confirmed (coordinator, 2026-07-22):** parallel
   per-`hubId` trust store, NOT on the registry entry — the CYP-800 Assist2/Tester2 F-A/bypass guard relies on
   `hubRegistry.ts` staying **PURE** (static endpoint config), while trust is dynamic (evolves in the connect-flow).
   Both keyed by `hubId`; the render reads both. Final wiring reconciles against the real connect-flow state at build.

## §8 Honesty invariants carried (non-negotiable, same family as prior work)

unknown ≠ trusted (fail-closed default); absence never reads as all-clear; a distinct diagnostic (malformed) is never
folded into a benign one (UNKNOWN); network-error ≠ trust-reject ≠ revocation (cause-honest); scope/role does not travel
(re-resolved per hub); express the states as a **closed union in the TYPE**, not caller discipline. These are the same
invariants proven in CYP-744 (mentions), CYP-705 (unread three-state), CYP-800 (registry fail-closed), and CYP-759
(non-optimistic overload) — the trust render is the next instance, not a new discipline.
