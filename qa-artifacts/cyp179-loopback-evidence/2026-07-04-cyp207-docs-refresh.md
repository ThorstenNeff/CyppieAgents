# CYP-207 — Docs-Refresh republish `56567a1` (redacted)

**Datum:** 2026-07-04 · Auftraggeber-autorisiert. NUR `site/docs/index.html` republished (Landing unverändert).

## Change
- `landing-dist/docs/index.html` ← `git show 56567a1:site/docs/index.html` (43734 bytes; §6 Remote-agents + reconnect-history + local-vs-hosted + hosted-operator-identity). Backup `docs/index.html.pre-cyp207.bak`. Direkt serviert (kein Caddy-reload nötig).

## Verify (RC2)
- `/docs` mit Cred → **200** + „Remote agents" (6) + reconnect-history (4) + local-vs-hosted (9) · **9 unique TOC** (concepts/run/team/start/task/remote/grant/access/ref).
- `/docs` ohne Cred → **401** (CYP-203-Gate).
- interne repo-docs MIT Cred → **404** (ACL-MATRIX.md, RC2-KNOWN-LIMITATIONS.md, human-grant-tokens.json, AGENT-MANAGEMENT.md) — site-only-Root hält, Republish exponiert nichts.
- Landing unverändert (`/`→200, Title identisch). HSTS max-age=259200 preserved.
- Secret-Scan: 2 Treffer = Doku-Text (`OPERATOR_TOKEN` als Config-Var-Name), kein echter Wert (Kontroll-Scan 0).

## Persistenz / Rollback
Direkt serviert → reboot-fest. Rollback: `cp landing-dist/docs/index.html.pre-cyp207.bak landing-dist/docs/index.html`.
