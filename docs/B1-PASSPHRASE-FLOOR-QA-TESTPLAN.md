# B1 Vault Passphrase-Floor — QA Test Plan (Team-2 axis, pre-staged / on-call)

> Status: **pre-staged** (B1 not yet landed) · Owner: QA/Team2 (Tester2) · Runs when Devs land B1 complete.
> Seam split (no double-coverage): **Team-2 = INPUT-POLICY** (passphrase floor + blocklist, this doc).
> **Team-1 tester = CRYPTO-STORE** (corrupt-vault, migration). Assist adds the security-lens (full version).

**Axis (frozen with PO):** the passphrase **input policy** — a **strength FLOOR** (≥ 64-bit estimated entropy) and a
**blocklist**. Under-floor / blocklisted input is **fail-closed REJECTED, never merely warned**. The policy is a
**server/Core** guarantee, not a client-side nicety (see M5 below).

**Anti-vacuity law (mandatory):** every REJECT item is **paired with a POSITIVE control** (an over-floor,
non-blocklisted, correctly-normalized input that IS accepted through the same seam) — otherwise a reject-test passes
vacuously (e.g. against a seam that rejects *everything*). And every reject carries a **mutation counter-probe**: a
named mutation that removes the check must turn the reject RED (a green-that-can't-go-red is not coverage).

**Seam (resolve on B1 landing):** the passphrase-validation entry point B1 exposes (Core policy fn / vault-create +
vault-unlock-rekey paths). Drive at the **Core/server** layer so the proof is server-enforcement, not UI. Fill the
exact symbol/route here once B1 lands: `<TBD: e.g. VaultPassphrasePolicy.validate(...) / POST /api/vault/...>`.

---

## Matrix (8 items — each REJECT paired with its POSITIVE control)

| # | Item | Reject case (fail-closed) | Positive control (accepted) | Mutation → RED |
|---|------|---------------------------|-----------------------------|----------------|
| M1 | **Floor-Reject** | An under-floor passphrase (e.g. `"hunter2"`, ~<64-bit est.) is **REJECTED**, not warned — the create/rekey does **not** proceed and no vault is written. | An over-floor passphrase (long, high-entropy, non-blocklisted) is **ACCEPTED** and the vault is created. | Drop the floor check → the under-floor input is accepted → M1 reject RED. |
| M2 | **At-Floor boundary (convention nailed)** | Exactly at the documented threshold: define + pin the convention — is `>= floor` accept and `< floor` reject (half-open)? A value **just under** the floor rejects. | A value **at/just over** the floor accepts. Pin the estimator + boundary in the assert so a later estimator swap can't silently move the line. | Flip `>=`↔`>` at the boundary → the boundary case flips → M2 RED. |
| M3 | **Blocklist-Reject** | A **structurally strong but blocklisted** phrase (`"correct horse battery staple"`) is **REJECTED** despite passing the entropy floor — the blocklist is an independent gate, not subsumed by the floor. | A structurally-similar **non-blocklisted** strong phrase is **ACCEPTED** (proves it's the blocklist, not the length, doing the rejecting). | Skip the blocklist lookup → the blocklisted phrase accepts → M3 RED. |
| M4 | **Bypass-hardening (normalization)** | A blocklisted entry submitted in a **different case / with surrounding or internal whitespace / a different Unicode (NFKC) normal form** is **still REJECTED** — the blocklist compares on a normalized form, not a raw byte match. | The same normalization applied to an **allowed** phrase does **not** spuriously reject it. | Remove the normalization (raw-compare) → `"Correct Horse "` slips past → M4 RED. |
| M5 | **Core-vs-Client (server-enforced)** | A request that **bypasses the UI** (direct Core call / direct API submit) with an under-floor **or** blocklisted passphrase is **REJECTED by the server/Core** — the policy is not a client-only guard. | The same direct path with a valid passphrase is accepted (the direct path itself works — so the reject is the policy, not a broken route). | Move the check to client-only (Core accepts anything) → the direct under-floor submit succeeds → M5 RED. |
| M6 | **Enumeration hygiene** | The rejection surfaces a **generic** failure — it does **not** leak *why* (floor vs blocklist vs specific blocklist entry), so it can't be used as an oracle. Assert the reject reason is the same generic shape for a floor-fail and a blocklist-fail. | A valid passphrase returns the distinct success shape (so the generic reject isn't just "everything looks the same"). | Make the reject echo the specific cause → floor and blocklist rejects diverge / leak the entry → M6 RED. |
| M7 | **Idempotent / no-partial-write on reject** | A rejected create/rekey leaves **no partial vault state** — a subsequent valid attempt still succeeds cleanly (the reject didn't half-initialize or lock anything). | The valid attempt after a reject creates the vault normally. | Persist-before-validate ordering → a reject leaves partial state → M7 RED. |
| M8 | **Reject is REJECT, not WARN (the headline)** | The under-floor / blocklisted path returns a **hard failure** (typed reject / non-2xx) and the operation is **not performed** — explicitly assert the side effect (vault absent / passphrase unchanged), not just a warning field. | The accepted path performs the side effect (vault present / passphrase set). | Downgrade reject→warn (proceed anyway) → the side effect happens despite the warning → M8 RED. |

---

## Notes
- **Estimator dependency (M1/M2):** pin whatever entropy estimator B1 uses (zxcvbn-class vs bit-length heuristic) in the
  asserts, and record the exact floor value + convention once landed — so the boundary test locks the contract.
- **Blocklist source (M3/M4):** confirm the blocklist origin (bundled top-N list?) and the normalization form used; M4
  must match the same normalization the production comparison uses (no stronger, no weaker).
- **Out of Team-2 scope (Team-1 tester):** corrupt-vault handling, format migration, at-rest crypto — not covered here.
- **Runs when:** B1 lands complete. This is a plan; the executable teeth are authored against the resolved seam then,
  each non-vacuous (positive control + mutation counter-probe), and self-gated green before reporting the SHA.
