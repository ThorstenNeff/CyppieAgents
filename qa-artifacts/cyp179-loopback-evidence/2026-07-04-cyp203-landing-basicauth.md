# CYP-203 — Landing hinter Basic-Auth (coming-soon Gate) (redacted)

**Datum:** 2026-07-04 · Auftraggeber-Priorität, human-gated GO. Caddy-Edge.

## Scope
- Basic-Auth NUR auf Landing: `cyppie-agents.com` (apex) + `www.cyppie-agents.com` (inkl. `/docs`).
- **`api.cyppie-agents.com` UNGATED** (Kratos `/.ory`, `/api`, `/ws/hub`-Bridge, OIDC-Callback, Operator dürfen NICHT brechen).
- Cred: **Auftraggeber-`BASIC_AUTH_USER`/`BASIC_AUTH_PASSWORD` aus `local.properties`** (box-local, gitignored) → bcrypt via `caddy hash-password` → `basic_auth` in Caddy. Hash in `Caddyfile.cyp173` (box-local, kein Repo). Werte nie geloggt/gechattet. Cred änderbar (neuer PW → re-hash + reload).

## Config
- apex-Block: `basic_auth` site-level (nach HSTS, vor root/route).
- **www-Block: `route { basic_auth {...}; redir ... }`** — `route{}` erzwingt `basic_auth` VOR `redir` (sonst www→301 statt 401).
- Backup `Caddyfile.cyp173.pre-cyp203-basicauth.bak`. Reload (zero-downtime). Persistent (Plist-Pfad).

## Verify (RC2)
- **Landing gegated:** apex/ 401/200 · apex/docs 401/200 · www/ **401**/301→apex · falsches PW→401.
- **App-Host UNGATED:** app/ 200 · /api/auth/me 200 · /.ory login 303 · MUST-1 404 · **/ws/hub 400 (Hub, kein 401)** · OIDC-Callback erreichbar.
- **Unregressed:** HSTS max-age=259200 auf apex+www · http→www 308→https · interne `/ACL-MATRIX.md`→404 auch mit Cred (Disclosure-Schutz hält).

## Rollback
`cp Caddyfile.cyp173.pre-cyp203-basicauth.bak Caddyfile.cyp173` + reload → Landing offen. (Coming-soon-Gate entfernen wenn Launch.)
