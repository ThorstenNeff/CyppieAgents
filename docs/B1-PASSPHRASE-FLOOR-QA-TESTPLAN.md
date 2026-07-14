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

**Jira:** QA = B1-DoD evidence under **CYP-542** (no separate ticket). Executable teeth land on branch
`test/CYP-542-b1-qa-passphrase-policy`, commits `CYP-542: …`; transition CYP-542 via the real board only after the
teeth are green at the resolved seam (verify CYP-542 exists in project **CYP** before any Jira write).

---

## Seam with Team-1-Tester (2 touch-points, else disjoint)

Team-1-Tester owns the **crypto-store** (corrupt-vault, migration, KDF/integrity). Two places the seams touch — one
seam, two halves; structure the matrix rows below with these in mind. **Fixture ownership is settled between the two
testers at B1-landing (PO1 arbitrates if needed);** if I spot a seam conflict I flag it to the coordinator, not resolve
it unilaterally.

- **A6 — seal-rejection (pairs with M1/M3/M7/M8):** when the floor/blocklist rejects a passphrase **at seal**, **MY
  half** asserts the *rejection FIRES* and the policy does not proceed (input-policy contract). **Team-1's half**
  asserts **no partial `wrappedPath` artifact** remains (no 0-byte / half-envelope). I do **not** assert the artifact
  state; they do **not** assert the policy firing.
- **B6 — migrated key (pairs with M5):** on a migrated vault, **MY half** verifies the **policy applies to the
  passphrase that unlocks/rekeys** it (the floor/blocklist is enforced on the unlock/rekey path, not just create).
  **Team-1's half** verifies vault **integrity + KDF consistency (Argon2id custody, not PBKDF2)**. I assert policy,
  not KDF.
- **Shared fixture (Team-1-Tester's proposal, adopt if ownership lands cleanly):** ONE shared fixture — a
  sealed-vault-under-a-tmp-passphrase + a **shared `testTag` set** — reused by A6/B6 **and** my floor/blocklist teeth.
  Reduces drift on the seam. I build my teeth to consume it (or a thin adapter) once its owner/shape is agreed.

---

## Matrix (8 items — each REJECT paired with its POSITIVE control)

| # | Item | Reject case (fail-closed) | Positive control (accepted) | Mutation → RED |
|---|------|---------------------------|-----------------------------|----------------|
| M1 | **Floor-Reject** | An under-floor passphrase (e.g. `"hunter2"`, ~<64-bit est.) is **REJECTED**, not warned — the create/rekey does **not** proceed and no vault is written. | An over-floor passphrase (long, high-entropy, non-blocklisted) is **ACCEPTED** and the vault is created. | Drop the floor check → the under-floor input is accepted → M1 reject RED. |
| M2 | **At-Floor boundary (convention nailed)** | Exactly at the documented threshold: define + pin the convention — is `>= floor` accept and `< floor` reject (half-open)? A value **just under** the floor rejects. | A value **at/just over** the floor accepts. Pin the estimator + boundary in the assert so a later estimator swap can't silently move the line. | Flip `>=`↔`>` at the boundary → the boundary case flips → M2 RED. |
| M3 | **Blocklist-Reject** | A **structurally strong but blocklisted** phrase (`"correct horse battery staple"`) is **REJECTED** despite passing the entropy floor — the blocklist is an independent gate, not subsumed by the floor. | A structurally-similar **non-blocklisted** strong phrase is **ACCEPTED** (proves it's the blocklist, not the length, doing the rejecting). | Skip the blocklist lookup → the blocklisted phrase accepts → M3 RED. |
| M4 | **Bypass-hardening (normalization)** | A blocklisted entry submitted in a **different case / with surrounding or internal whitespace / a different Unicode (NFKC) normal form** is **still REJECTED** — the blocklist compares on a normalized form, not a raw byte match. | The same normalization applied to an **allowed** phrase does **not** spuriously reject it. | Remove the normalization (raw-compare) → `"Correct Horse "` slips past → M4 RED. |
| M5 | **Core-vs-Client (server-enforced) + unlock/rekey path (⟵ B6)** | A request that **bypasses the UI** (direct Core call / direct API submit) with an under-floor **or** blocklisted passphrase is **REJECTED by the server/Core** — on **both** the create **and** the unlock/rekey paths (the migrated-key unlock passphrase is policy-checked too, the B6 touch-point). Not a client-only guard. | The same direct path with a valid passphrase is accepted on both create and unlock/rekey (the path works — so the reject is the policy). *(KDF/integrity of the migrated vault = Team-1's B6 half, not asserted here.)* | Move the check to client-only, OR skip it on the unlock/rekey path → a direct under-floor submit succeeds → M5 RED. |
| M6 | **Enumeration hygiene** | The rejection surfaces a **generic** failure — it does **not** leak *why* (floor vs blocklist vs specific blocklist entry), so it can't be used as an oracle. Assert the reject reason is the same generic shape for a floor-fail and a blocklist-fail. | A valid passphrase returns the distinct success shape (so the generic reject isn't just "everything looks the same"). | Make the reject echo the specific cause → floor and blocklist rejects diverge / leak the entry → M6 RED. |
| M7 | **Validate-before-seal ordering (⟵ A6, my half)** | On a reject at **seal**, MY half asserts the policy rejects **before the crypto-store seal is invoked** (fail-closed ordering) and a subsequent **valid** attempt still succeeds cleanly (the reject didn't lock/wedge the policy path). *(The **no-partial-`wrappedPath`/0-byte-envelope** artifact assertion is **Team-1's A6 half** — not asserted here.)* | The valid attempt after a reject seals the vault normally. | Persist-before-validate ordering (seal invoked before the policy check) → the seal fires on an under-floor input → M7 RED. |
| M8 | **Reject is REJECT, not WARN (the headline)** | The under-floor / blocklisted path returns a **hard failure** (typed reject / non-2xx) and the operation is **not performed** — assert the policy-contract side effect (create returns no success / passphrase unchanged on rekey), not just a warning field. *(Artifact-level absence is A6/Team-1.)* | The accepted path performs the side effect (create succeeds / passphrase set). | Downgrade reject→warn (proceed anyway) → the operation succeeds despite the warning → M8 RED. |

---

## Notes
- **Estimator dependency (M1/M2):** pin whatever entropy estimator B1 uses (zxcvbn-class vs bit-length heuristic) in the
  asserts, and record the exact floor value + convention once landed — so the boundary test locks the contract.
- **Blocklist source (M3/M4):** confirm the blocklist origin (bundled top-N list?) and the normalization form used; M4
  must match the same normalization the production comparison uses (no stronger, no weaker).
- **Out of Team-2 scope (Team-1 tester):** corrupt-vault handling, format migration, at-rest crypto — not covered here.
- **Runs when:** B1 lands complete. This is a plan; the executable teeth are authored against the resolved seam then,
  each non-vacuous (positive control + mutation counter-probe), and self-gated green before reporting the SHA.
