# RC2 — Account-enumeration known limitations on Kratos v1.3.0

> Two **graded limitations** surfaced by the CYP-178 RC2 live behavioral probe (`Rc2LiveProbeTest`) against
> dev-Kratos, both **gated at the same exposure milestone as CC1 / RC4 → CYP-179** (off-localhost exposure),
> both **re-evaluated before that gate**. CLOSED by contrast: login + recovery **content** are enum-safe
> (masked full-body parity holds — hard gates).
>
> - **§A — login-timing** (Auftraggeber decision A, 2026-07-01): ACCEPTED — documented + throttle-mitigated;
>   soft posture (re-evaluate at exposure).
> - **§B — registration-content** (Auftraggeber decision A, 2026-07-01): documented + bound to a **HARD**
>   exposure gate — the account-creation side-effect makes off-localhost materially worse, so throttle is NOT
>   an accepted mitigation and the surface **does not go off-localhost until §B is actually closed** (v26 or a
>   platform register-wrapper). Config-unreachable on v1.3.0 (proven).

---

## §A — login-timing account-enumeration

## The finding

On Kratos **v1.3.0**, a login for a **non-existent** identifier returns in ~4 ms (Kratos short-circuits — it
does **not** hash a dummy password), while an **existing** identifier with a wrong password runs argon2
(~80 ms). The HTTP **response is byte-identical** (generic `4000006`, `content-type`, status all equal — the
probe's masked-body parity holds), but the **~35× response-time difference leaks account existence** — a
timing side-channel account-enumeration oracle.

## Why it is not closed by config

`security.account_enumeration.mitigate: true` (which we DO set) makes the **content** generic (register /
recovery no longer reveal existence — proven by the probe's teeth-demo on the leaky control). But on v1.3.0
it does **not** equalise **timing**: absent identifiers are still not dummy-hashed. Verified two ways:

- **empirically** — deploy's probe run measured the ~35× ratio persisting with `mitigate:true`;
- **by docs** (Context7 / `embedx/config.schema.json`) — `mitigate` is the ONLY enumeration knob, and Ory's
  own wording is verbatim: *"does not mitigate all possible attack vectors yet."* There is **no** separate
  v1.3.0 dummy-hash / constant-time login knob.

**The timing PROFILE is run-variable — the leak is a statistical distribution, not a fixed profile.** Across
runs the `absent` branch measured ~11 ms and, on a later run, ~487 ms (existing/wrong-password stayed a stable
~81 ms — the argon2 cost), i.e. the ratio can even INVERT run-to-run under host noise (GC/JIT/DB/cleanup
contention). This is **run noise, not a regression**: a 487 ms absent is a latency outlier, NOT a dummy-hash
(a dummy-hash would land absent ≈ 81 ms). Consequences: (1) the RC2 probe **reports raw `timings_ms`** and does
not gate on a fixed ratio (any such gate would be flaky); (2) any mitigation MUST NOT assume a fixed timing
profile — the exploitable signal is the *statistical distribution* over many samples, which is exactly what the
per-IP throttle (CYP-179) raises the cost of sampling.

## Mitigation (accepted posture)

1. **Content leak: CLOSED** — `mitigate:true` + the modern flows; the probe's teeth-demo confirms a
   mitigate:false instance leaks and ours does not.
2. **Timing leak: MITIGATED, not closed** — the **per-IP edge throttle (CYP-179)** raises the cost of the
   many timed requests enumeration needs. It does **not** eliminate the side-channel (a patient / distributed
   attacker remains a theoretical vector).
3. **Bounded exposure** — the platform binds to localhost while the pre-exposure gates stand (CC1/RC4), so the
   login endpoint is not off-box reachable until CYP-179.

## Re-evaluate before off-localhost exposure (CYP-179)

Before the login surface is exposed off localhost, re-assess: **upgrade to Kratos v26** (which may dummy-hash
absent identifiers — but v26 has a config-schema break, e.g. `session.cookie.secure` string-vs-bool, that this
reference is pinned away from → it must be re-schema-validated and the RC2 probe re-run against v26 first), or
accept the throttle-mitigated posture with sign-off. This limitation is referenced from the auth design §8
test-contract surface.

---

## §B — registration-content account-enumeration

### The finding

On Kratos **v1.3.0**, submitting the password-method **registration** flow reveals whether an email exists:
a **new** email → `200` (creates the identity), an **existing** email → `400` with `4000007`
("An account with the same identifier exists already"). The masked bodies differ (status 200 vs 400) — a
**response-level** enumeration tell in a **single request** (no timing/statistics needed). `mitigate:true`
does **not** close it on v1.3.0.

**Worse than §A (timing):** one deterministic request (not a statistical side-channel), and the `new` branch
has an **account-creation side-effect** (a real identity is written → store-pollution / a registration-DoS
vector). A per-IP throttle barely helps.

### Why it is config-unreachable on v1.3.0

Registration uniqueness is a **structural reject**: Kratos's `ConflictingIdentity` (identity/manager.go)
checks credential identifiers + verifiable + recovery addresses and returns the conflict that becomes
`4000007`. The config schema (Context7 / `embedx/config.schema.json`) exposes **no** register-specific
enum-safe knob — only `account_enumeration.mitigate`, which empirically does not intercept the conflict on
v1.3.0 (Ory: *"does not mitigate all possible attack vectors yet"*). There is no reachable
verification-required flow that returns a generic `200 "check your email"` for both new and existing on this
version. (The only untested avenue, the non-deprecated `style` control, governs UI step-rendering, not the
uniqueness-reject path — assessed as not helping.)

### Disposition — HARD exposure gate (Auftraggeber decision A, 2026-07-01)

Unlike §A (soft/throttle-accepted), §B is bound to a **HARD** exposure gate: **the registration surface does
NOT go off-localhost until §B is actually closed.** Rationale (Test emphasis): the account-creation
side-effect makes the leak materially worse off-box (a one-request response-level tell *and* a
registration-DoS / store-pollution vector), so **a per-IP throttle is NOT an accepted mitigation** here.

- **Closed BEFORE off-localhost exposure (CYP-179), by one of:**
  1. **Kratos v26 upgrade** (documented fix-path — may add an enum-safe registration flow; needs
     re-schema-validation against the v26 schema break + an RC2 probe re-run), **or**
  2. **a platform register-wrapper** (the backend mediates registration: a quiet existence check → a generic
     `200 "check your email"` for both new and existing, with the appropriate mail — closing §B in our layer).
- **Until then:** the platform stays localhost-bound (same gate as CC1/RC4); the RC2 probe **REPORTS** the
  `P1_registration_new_vs_existing` pair (status/masked-body delta) — recovery + login content remain the
  **hard** content gates.
- **Probe hygiene:** the register pair runs **once** per branch (not N) to bound the account-creation
  side-effect; deploy runs `cleanup-junk-identities.sh` (admin API :4434) **before each re-run**.

## Teeth (why a green here is not vacuous)

On v1.3.0 `mitigate:true` has little API-observable effect vs `mitigate:false` (content was already generic
via defaults; timing + register are not fixed), so the leaky-instance negative control does **not** cleanly
discriminate — register leaks on **both**. The load-bearing teeth is therefore the **hermetic two-way mask
proof** (emitted into the evidence as `teeth_proof`): the mask collapses two enum-safe branches to a
byte-identical result while keeping `4000006` distinct from `4000007` — proving the probe's content gates
discriminate the real enumeration signal. Plus the probe's real discovery of the §A + §B leaks on the safe
instance is itself the demonstration that it catches what it guards.

---

## §C — email-change POST-auth account-enumeration (CYP-181 / P2.4)

### The finding

Changing a logged-in member's email to one that **already belongs to another account** goes through Kratos's
settings flow, which — like registration (§B) — rejects the duplicate (a `4000007`-class conflict). So a
member can learn whether an arbitrary email is registered by trying to set it as their own. This is a **POST-
auth** enumeration vector on the `POST /api/auth/settings/email` surface.

### Why it is graded BELOW §A and §B (weakest of the three)

- **Requires a verified MEMBER session** — the attacker must already be an authenticated, verified user (a
  high bar; not an anonymous probe).
- **Self-scoped + throttled** — one probe per settings submit from the caller's own session, rate-limited.
- **No account-creation side-effect** — unlike §B, nothing is written (§B's DoS/pollution vector is absent).

### Disposition — documented soft limit (backend developer's call, PO-affirmed)

**NOT mediate-to-generic.** Masking Kratos's duplicate error in the shim would require a **version-fragile**
error match that could silently **fail-open** on a future Kratos version (the same v26/v1.3.0 drift class that
bit §A/§B), and it would add platform security-logic against the **thin-shim** invariant (all security is
Kratos's). Instead: a **documented soft post-auth limit**, gated at the same exposure milestone as §A/§B
(**CYP-179**); re-evaluate at the v26 upgrade (which may make the settings flow generic). The shim stays thin
(pure passthrough), and the residual weakest-of-three vector is thrown into the throttle + localhost-binding
posture until then.

---

## §D — OIDC "Sign in with GitHub" security posture (CYP-183 / P4, 2026-07-02)

Not a graded *leak* like §A–§C, but the canonical record of the OIDC security decisions (full detail +
evidence in `P4-OIDC-SPIKE-RUNBOOK.md`).

- **S1a linking-takeover: GO for GitHub, but a PER-PROVIDER HARD gate.** The unverified-secondary-email →
  auto-link → takeover vector is structurally impossible with GitHub (it never exposes an unverified secondary
  email in the OIDC claim; the spike proved the victim identity untouched). **Residual:** Kratos's own
  "refuse to link an unverified email to an existing identity" defense was therefore never exercised. So
  **before adding ANY future OIDC provider, S1a MUST be re-probed against that provider's unverified-email
  exposure AND Kratos's link-refusal tested** — the GitHub result does NOT generalize. This is a standing gate,
  not a one-time check.
- **S2 verified-mapping: verify-then-admit is the SAFE default (kept).** An OIDC identity lands
  platform-`verified=false` — Kratos does not blindly trust the provider's verified flag, so the P1 guard
  (verified-required) denies it until an on-platform verification (P2.3 flip). The UX cost (GitHub sign-in is
  not one-click) is an Auftraggeber decision; enabling per-provider "trust the provider's verified flag" is a
  documented per-provider risk (auto-verify + a provider that surfaces unverified emails = the S1a takeover
  class). Recommendation: keep the safe default.
- **S1b silent auto-merge: GO.** For a GitHub-verified email colliding with an existing password identity,
  Kratos REFUSES the auto-link and pauses at an ownership proof ("email already used — sign in to add github");
  the victim identity is untouched (no `oidc` credential appended, no merge). Login-at-the-account first.
- **✅ FINAL S1 VERDICT: GO — GitHub-OIDC is safe against email-collision/takeover**, on three independent
  layers: (1) at-GitHub — unverified secondary emails never reach the OIDC claim (S1a); (2) Kratos — an
  ownership proof is required before linking to an existing same-email identity (S1b); (3) on-platform — OIDC
  identities are `verified=false`, so the guard denies until on-platform verify (S2). Residuals (documented,
  non-blocking): the S1a per-provider gate above + the S2 one-click-UX Auftraggeber decision.
