# RC2 — Known Limitation: login-timing account-enumeration on Kratos v1.3.0

> Status: **ACCEPTED (Auftraggeber decision A, 2026-07-01)** — documented + throttle-mitigated, NOT closed.
> Gated at the **same exposure milestone as CC1 / RC4 → CYP-179** (off-localhost exposure). Re-evaluate
> before that gate. Source: the CYP-178 RC2 live behavioral probe (`Rc2LiveProbeTest`) against dev-Kratos.

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
