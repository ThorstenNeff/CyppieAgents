# CYP-192 Domain-Switch + Landing/Docs — LIVE `ae60635` (redacted)

**Datum:** 2026-07-03 · **Auftraggeber-GO** Option (b) (Switch + Landing/Docs jetzt, Mail=CYP-193 fast-follow). EIN kontrollierter Bündel-Deploy.
**Neuer Host:** App `api.cyppie-agents.com` · Landing/Docs `cyppie-agents.com` · Alt `api.cyppie.com`→301.
**Hub-Jar:** ae60635 **== 7bf72ee** (md5 63ab8505 — CYP-190/CYP-192 sind Config, kein Hub-Code) · Frontend host-agnostisch (`window.location.origin`), Bundle unverändert.

## Verify-Befunde (vor Bau, gegroundet)
- **GitHub-OIDC NICHT aktiv** (nur `password`) → Callback-Change SKIP.
- **`auth.kratosPublicUrl` bleibt loopback `127.0.0.1:5433`** — server-internal (Hub→Kratos whoami/settings/register); nur client-facing URLs → neuer Host.
- **Landing-Content** `site/index.html`+`site/docs/index.html` (self-contained, zeigen auf neuen Host).

## Box-Changes
- **`kratos-prod/kratos.yml`** (EIN Touch, Backup `.pre-cyp192.bak`): base_url/cors/return-urls/6× ui_url → `api.cyppie-agents.com` · `from_address: no-reply@cyppie-agents.com` (CYP-192) · **CYP-190 log-Block** (`level:info`·`format:json`·`leak_sensitive_values:false`). cookie.domain NICHT gesetzt (adaptiert). admin :5434 loopback unverändert.
- **`platform.config.json`:** `web.allowedOrigins` → `https://api.cyppie-agents.com`. `kratosPublicUrl` bleibt :5433.
- **`Caddyfile.cyp192-switch`:** app-Host `api.cyppie-agents.com` (Belt-off, host-swap) + **Landing `cyppie-agents.com`** (root=**`landing-dist` = NUR `site/`-Content**, explizit `/`+`/docs`→200, sonst 404, Härtung) + **301 `api.cyppie.com`→neuer Host**.
- **`deploy/landing-dist/`:** NUR `index.html` + `docs/index.html` (KEIN Repo/Repo-docs/ — Test-Fang).

## ⚠️ Test-Fang adressiert: `/docs` = `site/`-only, interne Docs → 404
Loopback + extern verifiziert: `/`+`/docs`→**200** (intended-public); **interne Repo-Docs → 404, kein Content-Leak:** `/docs/kratos/RC2-KNOWN-LIMITATIONS.md` · `/docs/design/human-grant-tokens.json` · `/docs/plans/…` · `/AGENT-MANAGEMENT.md` · `/ACL-MATRIX.md`.

## Loopback-Re-Verify VOR Exposure (GRÜN)
CYP-179 register enum-safe (byte-identisch 200) · CYP-188 ACL-ghost-404 / real-po-dev1-200 · **CYP-190 `?token=`-Canary → 0 Treffer im PG-Kratos-Log** (redacted, `token=00000000-…`).

## Externe Live-Re-Verify (RC2, neuer Host)
- **LE-Cert** `api.cyppie-agents.com` ✅ (Let's Encrypt). Landing-Host-Cert ✅.
- **App:** SPA `/`→200 · `/api/auth/me`→200 · register→**200** · Cookies **HttpOnly/Secure/SameSite=Lax** · base_url emittiert `https://api.cyppie-agents.com` · MUST-1→404 · admin→404 · `.git`→404.
- **BG5:** MEMBER extern → workspace/members·audit→**403** · events→**200**(ACL) · forged-OP→**401**.
- **BG-SEND-both:** DENY no-grant→**403 uniform** (po-dev1==nonexistent) · **ALLOW-201** grant+send po-dev1→**201** (Cleanup: members zurück `[po,dev1,operator]`).
- **BG-WS:** `/ws/comm`→Hub(400), nicht SPA.
- **Landing/Docs:** `/`+`/docs`→200; interne Docs→404 (s.o.).
- **301 Alt-Host:** `api.cyppie.com/foo`→**301**→`api.cyppie-agents.com/foo`.

## Offen / Rollback
- **CYP-193 (Mail, fast-follow):** courier.smtp = weiterhin Mailpit-loopback → **echte Verify/Recovery-Mail liefert NICHT** an User (from_address=cyppie-agents.com, aber kein echtes Relay). Auftraggeber-Ticket (Provider+SPF/DKIM). Switch-Gate-Mail-Canary = offen bis CYP-193.
- **Rollback (atomar):** Edge→`Caddyfile.cyp173` (alt-Host) · `kratos.yml`→`.pre-cyp192.bak` + PG-Kratos-Restart · `platform.config.json` allowedOrigins zurück + Hub-Restart. Non-persistent-Aktivierung (nach Test-grün analog CYP-179 persistieren). Secrets box-local, Aiven-Lockdown unverändert.

## GitHub-OIDC Ground-Truth (Re-Verify auf LIVE prod-Kratos) — NICHT aktiv
- LIVE Login-Browser-Flow (`:5433`) → **0 `oidc`-Nodes** (nur `password`). GitHub-Login **NICHT aktiv** in prod.
- `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS`-env am PG-Kratos-Prozess **NICHT gesetzt** (nur `GITHUB_CLIENT_SECRET` liegt ungenutzt im env aus `secrets/kratos.env`). `start-kratos-prod.sh` injiziert den Provider nicht.
- → **Kein Pull-back:** GitHub-Login war nicht aktiv → der Switch bricht es nicht. Meine „nur password"-Lesart war korrekt für die LIVE prod-Instanz.
- **Flag:** GitHub-Login ist **gebaut** (Client-UI CYP-185 + Kratos-Support via reference) aber **in prod nicht aktiviert**. Aktivierung (separat, falls gewünscht) = `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS`-env mit **neuem Callback** setzen + Auftraggeber trägt in der GitHub-OAuth-App die Callback-URL `https://api.cyppie-agents.com/.ory/kratos/public/self-service/methods/oidc/callback/github` ein. Hängt nicht am Switch.
