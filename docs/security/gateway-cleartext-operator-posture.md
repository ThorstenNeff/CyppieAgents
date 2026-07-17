# Gateway Cleartext Boundary — Operator Security Posture

> **Status:** DRAFT — operator-facing translation of the *decided* posture. Awaiting a **Backend accuracy-check**
> on the `⟨PLACEHOLDER⟩` markers (see §7) before it is authoritative.
> **Author:** UIUX-Designer (disclosure layer). **Source of truth:** `docs/design/CYP-638-gateway-design.md §13`
> (engineering) — this page re-addresses that truth to the audience who can act on it.
> **Audience:** whoever **runs / hosts the gateway process**. The mitigations here are **host actions** — they are
> yours, not the product's.
> **Why this page exists:** the residual exposure and its mitigations were written *only* in an engineering design
> doc; the person who must apply the mitigations does not read that doc. This is a **who-reads-it gap**, not a
> missing fact — see `docs/design/residual-exposure-disclosure-audit.md` (exposure ②).
> **This page documents a decided posture; it does not decide one.** It makes no claim about whether or when the
> gateway is network-exposed (that is S5/S6 and the Auftraggeber's call), and recommends nothing that shifts the
> risk posture — only the host hardening already decided in §13.

---

## 0. Bottom line (read this first)

The gateway **terminates the encrypted tunnel**, so **decrypted operator↔hub traffic — including terminal (PTY)
bytes — exists as cleartext in the gateway process's memory (RAM) by design.** Anything that can read that
process's memory (the host root, an attached debugger/`ptrace`, a **core dump**, or memory paged to **swap**) can
read that cleartext.

**The product cannot close this.** It guarantees it never *logs* secrets and (⟨see §7-a⟩) never *writes* this
cleartext to disk — but it **cannot** guarantee "not readable in RAM." **Reducing this exposure is a host-hardening
job, and it is yours.** Do not assume in-memory traffic is protected: it is not encrypted in memory.

---

## 1. The residual you own (a test cannot close this)

From `CYP-638-gateway-design.md §13` — documented residual exposure, verbatim in substance:

- **Cleartext in RAM is definitional.** A2 (the isolated gateway process) terminates the Noise/mux tunnel, so
  decrypted operator↔hub bytes — **including PTY / terminal-exec bytes** — live in the gateway process memory
  between decryption and the loopback socket. A **heap dump, core dump, swap, or an attached debugger/`ptrace`** can
  read them. **A test can pin only *"no cleartext spill to disk"*, never *"no cleartext in RAM."***
- **PTY output is rendered by the operator's own browser** — inherent to remote operation, not a gateway leak.
- **Ciphertext size/timing is visible to the relay** — a relay-threat-model property, outside this boundary.
- **TLS termination** has a pre-re-encrypt window inherent to any terminator (S7).
- The gateway can pin only **its own** logs — **not** the hub process, and **not** a deploy misconfiguration
  (e.g. running at DEBUG in production).

> **The honest line that must not blur:** the product's *guarantees* (§3) and this *residual* (§1) are **different
> categories**. A guarantee is verified by a test that fails the build if broken. A residual is a property no test
> can close — it is reduced only by the host hardening in §2. **If you finish this page believing your in-memory
> cleartext is "protected," the page has failed you** — it is not, and only §2 reduces the exposure.

## 2. What you must do — host hardening (your actions)

These are the operational mitigations named in §13. They **reduce** the §1 exposure; they do not eliminate it
(cleartext in RAM remains readable by whoever can read the process's memory, e.g. host root).

| Mitigation | Why it matters | Exact mechanism |
|---|---|---|
| **Disable core dumps** for the gateway process | A crash must not persist process memory (with its cleartext) to a dump file on disk. | ⟨PLACEHOLDER — Backend-Check: exact mechanism? `ulimit -c 0` / systemd `LimitCORE=0` / `/proc/sys/kernel/core_pattern`? state the one you rely on⟩ |
| **No swap** (or ensure the gateway's memory is never paged out) | Swapped memory pages cleartext to disk, defeating "no spill to disk." | ⟨PLACEHOLDER — Backend-Check: is swap-off a **hard requirement** or a **recommendation**? is `mlock`/`MemoryDenyWrite`-style locking used instead?⟩ |
| **Restrict `ptrace`** | Stops another process on the host from attaching to and reading the gateway's memory. | ⟨PLACEHOLDER — Backend-Check: expected control? `kernel.yama.ptrace_scope=2/3`? container `CAP_SYS_PTRACE` dropped?⟩ |
| **Treat the gateway host as trusted** (limit who has root/host access) | Host root can read any process's memory directly; the boundary is only as strong as host access control. | Operational — limit accounts/access on the host running the gateway process. |
| **Do not run the gateway at DEBUG in production** | The no-secrets-in-logs guarantee (§3) is verified even at DEBUG, but a misconfigured deploy is still yours to avoid. | Operational — production log level. |

> **Scope note (⟨PLACEHOLDER — Backend-Check⟩):** §13 describes the **gateway** process. The parent
> security-architecture notes call the **hub** "the only cleartext zone" as well. **Does this same host hardening
> apply to the hub process, or only the gateway?** If both, this page's mitigations should name both hosts.

## 3. What the product already guarantees (the green posture — so you know the boundary)

These **are** verified by tests that fail the build if broken (`CYP-638-gateway-design.md §13`, pinned-by-teeth) —
you do **not** have to arrange them:

- **No secret ever appears in a gateway log line — at INFO *and* DEBUG.** A sentinel test drives a unique marker
  through every credential category (session cookie, Bearer, `?token`/`?ticket`, PTY-like WS frame, raw-credential
  body, and the exception path); a careless `log.info(uri)` at a forward seam turns the test red. A construction
  seam (`TokenRedactor` / `%redactedMsg`) redacts token/ticket/Bearer/Kratos-cookie/session-header at output, so a
  future careless log line still cannot emit them.
- **Fail-closed on an unreachable hub:** a hub error yields a bounded **502**, logging only the exception class
  name — never the request line or its query.
- **⟨PLACEHOLDER — Backend-Check (§7-a): is "no cleartext written to disk / no temp files / no persistence" pinned
  by a current test, or only the *logging* categories above?⟩** This determines whether §0 may state "never written
  to disk" as a **guarantee** or only as a **claim to confirm**. Until confirmed, treat disk-spill prevention as
  **partly your job** (core dumps + swap, §2) rather than a product guarantee.

## 4. The line that must not blur — "encrypted at rest" ≠ "protected in memory"

A separate, planned change encrypts the **API key at rest on disk** (the First-Run posture line; CYP-220). That is
about a **secret written to disk**. It says **nothing** about the cleartext in §1, which lives in **memory**.
**Do not read the coming at-rest encryption as covering in-memory cleartext — it does not.** These are two
different boundaries; conflating them is exactly the "false encrypted-at-rest" reading this project has already
ruled out.

## 5. Posture statement (one paragraph)

The gateway is a **trusted-host boundary.** By design it holds decrypted operator↔hub traffic — including terminal
bytes — as **cleartext in memory**; the product guarantees this is never logged and (⟨§7-a⟩) never written to disk,
but **cannot** guarantee it is unreadable in RAM. That residual is reduced, not removed, by the host hardening in
§2, which is the operator's responsibility. This is the same "treat the host as trusted" posture that the First-Run
API-key line states to the user (`residual-exposure-disclosure-audit.md` ①/②) — stated here at the altitude of the
person who runs the host.

## 6. Provenance & relationship

- **Source (engineering):** `docs/design/CYP-638-gateway-design.md §13` — the pinned-vs-residual split this page
  translates.
- **Why an operator page (the who-reads-it gap):** `docs/design/residual-exposure-disclosure-audit.md`, exposure ②
  and §1/§5 (audience×moment: a hosting property belongs in operator documentation, not the user UI).
- **Sibling posture doc (genre):** `docs/security/CYP-576-auth-as-shipped-posture.md` (auth as-shipped residuals).

## 7. Open placeholders — for the Backend accuracy-check (cheap, at the end)

Each `⟨PLACEHOLDER⟩` above marks a fact I must not invent. Backend confirms/corrects these in one pass; no
pre-authoring on their critical chain:

- **(a)** Is *"no cleartext to disk / no temp files / no persistence"* pinned by a **current test**, or is only
  **logging** toothed? (Gates whether §0/§3/§5 say "guarantee" vs "claim to confirm".)
- **(b)** Exact **core-dump-off** mechanism relied upon (`ulimit -c 0` / systemd `LimitCORE=0` / `core_pattern`).
- **(c)** Is **swap-off** a hard requirement or a recommendation? Is memory-locking (`mlock` / `MemoryDenyWrite`)
  used instead/as well?
- **(d)** Expected **`ptrace` restriction** (`kernel.yama.ptrace_scope`, dropped `CAP_SYS_PTRACE`, container policy).
- **(e)** **Scope:** does this hardening apply to the **hub** process too (parent docs: hub = "the only cleartext
  zone"), or gateway-only?

## 8. Self-Validation

- **Pinned-vs-residual survives:** §1 (residual, no test can close) and §3 (guarantees, build-failing tests) are
  kept as **distinct categories**, with an explicit "must not blur" line (§1 quote, §4) — no gloss, no false
  "protected."
- **No over-claim:** every guarantee statement is traceable to a §13 pinned-by-teeth item; the one uncertain
  guarantee ("no cleartext to disk") is a **placeholder**, not asserted, until Backend confirms a tooth (§7-a).
- **Documents, does not decide:** no statement about whether/when the gateway is exposed; no posture-shifting
  recommendation — only §13's decided mitigations, re-addressed to the operator.
- **Placeholder + exact question** at every point I lack precision (§2 table, §3, §7) → Backend does a cheap
  accuracy-check, not expensive pre-authoring; their Enumeration-Sentinel→S4→S5 chain is untouched.
- **No app-surface artifacts:** operator documentation (Stufe B), so **no i18n keys, no testTags, no tokens** — a
  who-reads-it fix, not a screen.
- **Reuse-first:** mirrors the `docs/security/` posture genre (CYP-576) and the audit's audience×moment taxonomy;
  no divergent one-off.
- **Kein Bau, docs-only** on `docs/gateway-cleartext-operator-posture`, off develop `45c5ceb4`.
