# CYP-562 — Kratos return-URL allow-list (open-redirect boundary)

> Deploy-time requirement + guardrail for `selfservice.allowed_return_urls` in `kratos.reference.yml`.
> Surfaced by the M1-auth adversarial re-read (CYP-550 follow-on) as [MED]: the return-URL allow-list had **no
> test binding**, unlike the enumeration / cookie / admin knobs. This note + the config-assertion close that gap.

## Why it matters

After a **valid** login Kratos redirects the browser to the `?return_to=` target **only if it matches an entry in
`selfservice.allowed_return_urls`**. If that list is too broad, an attacker crafts
`GET /self-service/login/browser?return_to=https://evil.example`; the victim logs in for real, and Kratos 302s them
(with their now-authenticated session) to the attacker origin — a **post-auth open redirect** (session/flow-token
exfil, phishing landing). This is the classic OAuth/OIDC open-redirect class, gated entirely by this allow-list.

## The rule (each entry = an EXACT origin/URL)

An `allowed_return_urls` entry MUST be an exact origin/URL. It MUST NEVER be:

- a wildcard (`*`, `https://*.example.com`, `http://*`),
- a scheme-only value (`http://`, `https://` — matches every host),
- a host **prefix** (Kratos matches by prefix in some versions → `https://app.example` would also match
  `https://app.example.evil.com`),
- an all-interfaces host (`0.0.0.0`),
- an origin that **also serves attacker-controlled user content** (open-content hosts, user-page subdomains).

## Required deploy substitution

`kratos.reference.yml` ships with:

```yaml
selfservice:
  default_browser_return_url: REPLACE_ME_SPA_ORIGIN
  allowed_return_urls:
    - REPLACE_ME_SPA_ORIGIN            # the web SPA origin, EXACT
    - http://127.0.0.1:47472/callback  # desktop loopback — FIXED, keep verbatim
```

At deploy:

1. Replace **both** `REPLACE_ME_SPA_ORIGIN` occurrences with the **exact** web SPA origin
   (e.g. `https://app.cyppie-agents.com`) — scheme + host + (port). No trailing wildcard, no path prefix.
2. **Keep `http://127.0.0.1:47472/callback` verbatim.** It is the desktop app's fixed RFC 8252 loopback return
   (`desktopApp/.../main.kt`, `LOOPBACK_PORT = 47472`). Changing/dropping it breaks the desktop OIDC return.
3. Do **not** add any further entries unless each is an exact, first-party origin under the rule above.
4. Run a `grep REPLACE_ME deploy/kratos/kratos.reference.yml` completeness check — it must return nothing after
   substitution (every placeholder still contains the substring `REPLACE_ME`).

## Guardrail (merge-gate)

`Rc2ConfigAssertionTest.kratosReferenceConfig_returnUrlAllowlist_hasOpenRedirectGuardrail` binds the reference config:

- `allowed_return_urls` is present,
- the fixed desktop loopback `127.0.0.1:47472` is an explicit entry,
- no **concrete** (non-`REPLACE_ME_*`) entry is a wildcard / all-interfaces / scheme-only, and each is an absolute
  http(s) origin/URL,
- the CYP-562 security comment is present at the point of edit.

The guardrail acts on the committed reference template (placeholders excluded from the anti-pattern scan). The
**deployed** value is verified out-of-band by the deploy operator against the rule above (the repo cannot see the
substituted value).
