# Gateway Cleartext Boundary — Operator Pre-Deploy Hardening Gate

> **Status:** DRAFT → **PRE-DEPLOY HARDENING GATE.** The Auftraggeber **ratified the public flip** (the gateway
> goes **internet-public once S7 lands**; GO given, 2026-07-17). At the flip, the process that holds the RAM
> cleartext becomes the process that **accepts arbitrary internet traffic** — so the host mitigations below stop
> being background knowledge and become **actions that must be done *before* the switch.** This page is the
> checklist the PO presents to the Auftraggeber. **NO MERGE until the blocking placeholders (b)/(c)/(d), §7, are
> resolved** — a checklist with an open hardening step is worse than none.
> **Author:** UIUX-Designer (disclosure layer). **Source of truth:** `docs/design/CYP-638-gateway-design.md §13`
> + a PO-Assistant measurement (§2A/§7-a). **Audience:** whoever **runs / hosts the gateway** — here, the
> **Auftraggeber himself** (he is the operator).
> **Why this page exists:** the residual exposure and its mitigations lived *only* in an engineering design doc; the
> person who must apply them does not read it (`residual-exposure-disclosure-audit.md`, exposure ②).
> **It documents a *decided* posture (now including "public at S7"); it does not decide the flip (ratified) and
> adds no posture-shifting recommendation** — only §13's mitigations plus measured facts relayed as-is.

---

## 0. Bottom line (read this first)

The gateway **terminates the encrypted tunnel**, so **decrypted operator↔hub traffic — including terminal (PTY)
bytes — exists as cleartext in the gateway process's memory (RAM) by design.** Anything that can read that
process's memory (host root, an attached debugger/`ptrace`, a **core dump**, a **heap dump**, or memory paged to
**swap**) can read that cleartext.

**Once the gateway is public, that same process faces the open internet.** The product closes the paths it *can*
(§2A); the rest is **host hardening you must do before the flip** (§2B). **Do not assume in-memory traffic is
protected — it is not encrypted in memory.** If you finish this page believing your in-memory cleartext is
"protected," the page has failed you.

---

## 1. The residual (a test cannot close this)

From `CYP-638-gateway-design.md §13` — documented residual exposure:

- **Cleartext in RAM is definitional.** A2 (the isolated gateway process) terminates the Noise/mux tunnel, so
  decrypted operator↔hub bytes — **including PTY / terminal-exec bytes** — live in the gateway process memory
  between decryption and the loopback socket. A **core dump, heap dump, swap, or an attached debugger/`ptrace`** can
  read them. **No test can pin *"no cleartext in RAM."***
- **PTY output is rendered by the operator's own browser** — inherent to remote operation, not a gateway leak.
- **Ciphertext size/timing is visible to the relay** — a relay-threat-model property, outside this boundary.
- **TLS termination** has a pre-re-encrypt window inherent to any terminator (S7).

> **The honest line that must not blur:** a **guarantee** is verified by a test that fails the build if broken; a
> **residual** is a property no test can close, reduced only by host hardening (§2B). The RAM cleartext is a
> *residual*. Keep the two categories separate — that separation is the whole difference between a checklist that
> **protects** and one that **reassures**.

## 2. What closes it — and who does each part

The RAM cleartext becomes a **disk** artifact (readable later, unlogged, unseen) via several spill paths. Some are
closed **product-side** (you only have to *not override* them); the rest are **your host actions**. The split is
the point — so you know **what is still open for you.**

### 2A. Closed product-side — verify set, do NOT override

| Spill path | How it's closed | Your duty |
|---|---|---|
| **Heap dump on OOM** — the JVM writing the **whole heap** (incl. cleartext tokens **and** PTY bytes) to disk on out-of-memory. *PO-Assistant measured this: with it enabled, "no cleartext to disk" is simply **false**.* | **Product construction:** Backend sets **`-XX:-HeapDumpOnOutOfMemoryError`** in S7 (heap-dump-on-OOM OFF). | **Don't re-enable it** in a deploy override / debug profile. |
| **Secrets in logs / stdout → journald / docker-json → disk** — a log or stdout line carrying a secret, then persisted by the log driver. | **Toothed + enumeration-closed:** the S6 sentinel test drives every credential category through the log path at **INFO *and* DEBUG** (`TokenRedactor`/`%redactedMsg` redacts token/ticket/Bearer/Kratos-cookie/session-header at output); the stdout→journald surface-gap is **enumeration-driven closed** at S6. | **Don't run at DEBUG in prod** as a belt-and-braces; the redaction holds even there. |
| **Unhandled-exception request-line leak** | Fail-closed: a hub error → bounded **502**, logging only the exception class name (never the request line/query). | — |

### 2B. ★ Your host actions — the pre-flip checklist (BLOCKING before public)

These the product **cannot** do for you; they must be true on the host **before** the flip. Exact mechanisms are
`⟨PLACEHOLDER⟩` pending the Backend/S7 accuracy-check (§7) — **but the actions themselves are the gate.**

| Action | Why it matters (post-flip) | Exact mechanism |
|---|---|---|
| **Disable core dumps** for the gateway process | An OOM/crash on a public-facing process must not persist its memory image (with cleartext) to disk. | ⟨PLACEHOLDER (b) — Backend/S7: `ulimit -c 0` / systemd `LimitCORE=0` / `core_pattern`? state the relied-upon one⟩ |
| **No swap** (or lock the gateway's memory) | Swapped pages write cleartext to disk. | ⟨PLACEHOLDER (c) — Backend/S7: swap-off **hard requirement** or recommendation? `mlock` / `MemoryDenyWrite` instead/as well?⟩ |
| **Restrict `ptrace`** | On a public host, stop another process from attaching to and reading the gateway's live memory. | ⟨PLACEHOLDER (d) — Backend/S7: `kernel.yama.ptrace_scope`? dropped `CAP_SYS_PTRACE`? container policy?⟩ |
| **Limit host / root access** | Host root reads any process's memory directly; the boundary is only as strong as host access control. | Operational — restrict accounts on the host running the gateway. |

> **Honest note on (b)/(c)/(d):** these are **S7 territory, and S7 is being built now.** The accuracy-check answer
> may legitimately be *"not implemented as a flag — remains manual host hardening."* **That is a valid result — then
> the page says exactly that,** and the operator does it by hand. What must **not** happen: a placeholder turning
> into a reassuring phrase because the true answer is inconvenient. An unresolved mechanism stays a visible open
> item, not a soft "should be fine."

> **Scope (⟨PLACEHOLDER (e)⟩):** §13 covers the **gateway** process. Parent security-architecture notes call the
> **hub** "the only cleartext zone" too. **Does this hardening also apply to the hub process, or gateway-only?** If
> both, §2B names both hosts.

## 3. "No cleartext to disk" — the honest, joint statement

It is **not** a single product guarantee. It is a **joint property** of product flags **and** host hardening:

- **Product closes:** heap-dump-on-OOM (S7 flag, §2A) + secrets-in-logs/stdout (S6 tooth + enumeration, §2A).
  *(PO-Assistant measured the gateway package itself: **zero file-write APIs** and no persistence calls — so no
  self-authored temp-file spill; the only disk paths were the two above, now closed product-side.)*
- **You close:** core dumps + swap (§2B). Until those are set on the host, memory can still reach disk.

⟹ **"No cleartext to disk" holds only when §2A is not overridden AND §2B is done.** Miss any one and it re-opens.
That is why §2B is a pre-flip gate, not advice.

*(Guarantees that stand on their own, product-side: no secret in any gateway log line at INFO/DEBUG; fail-closed
502 on hub errors. §2A.)*

## 4. The line that must not blur — "encrypted at rest" ≠ "protected in memory"

A separate, planned change encrypts the **API key at rest on disk** (First-Run posture line; CYP-220) — a secret
**written to disk**. It says **nothing** about the §1 cleartext, which lives in **memory**. **Do not read the
coming at-rest encryption as covering in-memory cleartext — it does not.** Two different boundaries; conflating
them is the "false encrypted-at-rest" reading this project has already ruled out.

## 5. Posture statement (one paragraph)

The gateway is a **trusted-host boundary**, and after the public flip it is a **public** trusted-host boundary. By
design it holds decrypted operator↔hub traffic — including terminal bytes — as **cleartext in memory**. The product
closes the disk-spill paths it can (heap-dump flag, log/stdout redaction) and guarantees no secret is logged; it
**cannot** guarantee the cleartext is unreadable in RAM, and it **cannot** disable core dumps or swap on your host.
Those are your **pre-flip** actions (§2B). This is the same "treat the host as trusted" posture the First-Run
API-key line states to the user (`residual-exposure-disclosure-audit.md` ①/②) — here at the altitude of the person
who runs the host, and now as a **deploy gate**, not background reading.

## 6. Provenance & relationship

- **Source (engineering):** `docs/design/CYP-638-gateway-design.md §13` (pinned-vs-residual split) + PO-Assistant
  measurement (heap-dump on OOM; stdout→journald; zero file-write APIs in the gateway package).
- **Why an operator page (who-reads-it gap):** `docs/design/residual-exposure-disclosure-audit.md`, exposure ②
  (audience×moment: a hosting property belongs in operator docs, not the user UI).
- **Sibling posture doc (genre):** `docs/security/CYP-576-auth-as-shipped-posture.md`.

## 7. Placeholders — BLOCKING (Backend/S7 accuracy-check; PO routes)

The flip makes these **blocking**, not cosmetic: without them the Auftraggeber (the operator) **cannot complete the
hardening**, and he is the one flipping the switch.

- **(a) — RESOLVED (measured, no longer open).** "No cleartext to disk" is **not** a blanket guarantee. The gateway
  package has **zero file-write APIs**; the two paths that would falsify it — **heap-dump on OOM** and
  **stdout→journald/docker-json** — are closed **product-side** (§2A: S7 flag / S6 enumeration). The disk residual
  that remains is **core dumps + swap = host actions** (§2B). Config-dependent: a deploy that re-enables the
  heap-dump flag or captures stdout to disk re-opens it (operator discipline).
- **(b) [BLOCKING]** Exact **core-dump-off** mechanism relied upon (`ulimit -c 0` / systemd `LimitCORE=0` /
  `core_pattern`) — or the honest "manual host hardening, not a flag."
- **(c) [BLOCKING]** **Swap-off**: hard requirement or recommendation? `mlock` / `MemoryDenyWrite` used?
- **(d) [BLOCKING]** **`ptrace` restriction** expected (`kernel.yama.ptrace_scope`, dropped `CAP_SYS_PTRACE`,
  container policy) — or "manual."
- **(e)** **Scope:** hub process too, or gateway-only?

## 8. Self-Validation

- **Pinned-vs-residual survives, escalated to a gate:** §1 (residual) and §2A (product-closed) / §3 (joint
  statement) stay **distinct categories**; the "must not blur" line is kept — no gloss, no false "protected."
- **No over-claim, corrected by measurement:** the earlier bare "no cleartext to disk" is now stated as a **joint**
  product+host property, because PO-Assistant found heap-dump and stdout→journald would make the bare claim
  **config-dependently false**. Both are relayed as facts, not invented; the heap-dump flag is attributed to
  Backend/S7, not claimed as mine.
- **The "we-flag / you-host" split is visible (§2A vs §2B)** — the operator can see exactly **what is still open
  for him** (§2B), the PO's explicit requirement.
- **Uncomfortable answers stay visible:** (b)/(c)/(d) are marked **BLOCKING** with an explicit guard that "S7
  unbuilt → manual host work" is a legitimate result the page states plainly — never a reassuring phrase.
- **Documents, does not decide:** the flip is ratified (GO); the page adds no posture-shifting recommendation, only
  §13 mitigations + measured facts. **No merge until (b)/(c)/(d) resolved** (status header).
- **No app-surface artifacts:** operator documentation (Stufe B) — no i18n keys, no testTags, no tokens.
- **Kein Bau, docs-only** on `docs/gateway-cleartext-operator-posture`, off develop `45c5ceb4`.
