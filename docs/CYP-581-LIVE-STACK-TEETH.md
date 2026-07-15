# CYP-581 — Live-Stack Teeth (dogfood run checklist)

> The CYP-581 hermetic E2E chain (merged, develop `6ee3fa94`) proves each post-login seam through the real
> server wiring with **fakes** at the outermost boundary (no real `claude`, no Argon2id cost, no Kratos). Each
> seam has exactly ONE tooth that is **hermetically un-closable** — it needs the real external dependency. This
> is the ready-to-run checklist for those teeth at the dogfood deploy. Owner: QA/Team2.

**Prereqs:** the M1+M2 dogfood stack up (server + Kratos reachable); the desktop app built; operator access; a
machine with the user's own `claude` logged in.

| # | Seam | Hermetic test (merged) proves | LIVE tooth (net-new, run here) | PASS criterion | FAIL = |
|---|------|-------------------------------|--------------------------------|----------------|--------|
| **L1** | turn → persistence (P1) | a hub message survives a real `re-boot()` over `JsonFileMessageStore`, black-box via REST | A **real `claude` turn** posted through the running desktop → its assistant reply lands on the timeline → **restart the server** → the reply is still there via the real GET | reply present pre- AND post-restart | a real turn is lost on restart (the silent-swallow class, live) |
| **L2** | connect (P3) | operator turn over real `/ws/agent` reaches the live (fake) session; routing isolation | A **real `claude`** agent RUNNING in the roster; a turn typed in the desktop reaches it and it **actually responds** (assistant event streams back over `/ws/agent`) | a genuine assistant turn appears, routed only to the target agent | turn reaches the wrong agent / no response / silent drop |
| **L3** | enroll → unlock (P2) | floor+blocklist → unlock consequence over **fake** SHA-256 KDF + JceAead | Enroll with a **real Argon2id** KDF (the frozen m≥64MiB/t≥3 params) → **relaunch the desktop** → unlock with the passphrase returns the device key; wrong passphrase fails closed | real-Argon2id enroll+unlock round-trips; wrong pp → refused | Argon2id params wrong / unlock fails / timing pathological |
| **L4** | login → hub handoff (P4) | `X-Session-Token` → fake `IdentityProvider` verified → OPERATOR → hub | A **real GitHub → Kratos** OIDC round-trip in the desktop → the resulting **real Kratos session** reaches `GET /api/agents` (roster) | the real logged-in operator session reaches the hub roster | the real session is rejected / orphaned (the CYP-576 bug class, live) |

## Run order & notes
- **L4 first** (it gates everything: no login → no operator session for L1/L2/L3 UI paths). Confirms CYP-576's fix end-to-end.
- **L3** next (enroll is the gate before the operator can drive). Watch the **real Argon2id cost** — the frozen params are deliberately heavy; the enroll dialog runs it off the UI dispatcher (`Dispatchers.Default`). Note the wall-clock; a pathological delay is itself a finding.
- **L1 + L2** together via one real `claude` turn: type a task in the desktop → observe the assistant reply (L2 response) → restart the server → confirm the reply persists (L1). One real turn exercises both.
- **Human-Go required** for the real `claude` spawn + real API key + real GitHub creds — these are the human's, out-of-band; QA does not hold them.

## Evidence to capture per tooth
A green run (screenshot/timeline), the exact reply body for L1/L2, the Argon2id enroll wall-clock for L3, and the Kratos session landing for L4. Any FAIL → minimal repro → coordinator → PO1 gate.
