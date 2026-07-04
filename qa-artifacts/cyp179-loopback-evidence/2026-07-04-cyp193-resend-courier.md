# CYP-193 — courier.smtp → Resend (echtes SMTP-Relay) LIVE (redacted)

**Datum:** 2026-07-04 · **Auftraggeber-GO** (human-gated) · Provider **Resend** (EU/eu-west-1, DSGVO).

## Wiring (durch)
- **Key box-local:** `secrets/resend-api-key.txt` (chmod 600, gitignored) — Wert NIE geloggt/committed.
- **start-kratos-prod.sh** (Backup `.pre-cyp193.bak`): `export COURIER_SMTP_CONNECTION_URI="smtps://resend:<KEY>@smtp.resend.com:465"` (implicit-TLS, env-Override, ersetzt yaml-Mailpit-Placeholder). `from_address=no-reply@cyppie-agents.com` (schon gesetzt).
- Kratos-restart → public :5433 / admin :5434 = 200, keine smtp-Startfehler, **oidc-Node erhalten** (keine Regression).

## DNS-Pre-Check (dig) + Resend-Domain-Status
- SPF TXT `send.cyppie-agents.com` = `v=spf1 include:amazonses.com ~all` · MX `send` → `feedback-smtp.eu-west-1.amazonses.com` (prio 10) · DMARC `_dmarc` = `v=DMARC1; p=none;`.
- Resend GET /domains → `cyppie-agents.com` **status=verified**, region **eu-west-1** (EU/DSGVO).

## Canary (recipient-side, mail.tm)
- Kratos self-service Verification-Flow für Test-Identity @web-library.net (mail.tm) → **Resend `last_event=delivered`** (recipient-MX akzeptiert, kein Bounce) · From `no-reply@cyppie-agents.com`.
- Roh-.eml an mail.tm empfangen (real externe MX).

## Recipient-Auth (definitiv berechnet)
- **✅ SPF=pass** — sending-IP `54.240.6.245` ∈ amazonses (envelope `send.cyppie-agents.com`), aligned (relaxed, Subdomain).
- **✅ DMARC=pass** — via SPF-pass-aligned (kein DKIM nötig), From=cyppie-agents.com authentifiziert. Policy p=none.
- **✅ DKIM** — signiert d=cyppie-agents.com (aligned mit From), Public-Key published (`shh3fegwg5fppqsuzphvschd53n6ihuv._domainkey`). 2 Sigs (Domain + amazonses).

## Ehrlicher Gap
- Literaler recipient-`Authentication-Results: dkim=pass`-Header **nicht autonom beschaffbar**: mail.tm fügt keinen Auth-Results-Header hinzu + alteriert den Body → lokaler dkimpy-Crypto-Verify FAIL (mail.tm-Artefakt, nicht die Original-Sig). mail-tester/1secmail = 403.
- SPF+DMARC=pass sind definitiv; DKIM aligned+published → echter MX (Gmail) zeigt dkim=pass. **Option (b):** Auftraggeber-Gmail-Canary („Show original", Code redacted) = literaler Header, falls Test es verlangt.

## Cleanup / Live-Stand
- Canary-Test-Identity gelöscht (204). prod-Identities: V (cyppie.dev), pg-seed, ab7c54e3 (thorsten-neff.de).
- **courier=Resend LIVE** (delivered ✓, kein Rollback). Bonus: echte User self-verify + `ab7c54e3` real per Mail verifizierbar.
- **Rollback:** `start-kratos-prod.sh.pre-cyp193.bak` (bzw. COURIER-env raus) → yaml-Mailpit + Kratos-restart.

## ⭐ Recipient-computed Authentication-Results (Auftraggeber echte Inbox, thorsten-neff.de) — Test condition-1
Aus „Show original" seiner echten Inbox (Recovery-Mail via courier=Resend, Code redacted):
- **`spf=pass`** (smtp.mailfrom=<local>@`send.cyppie-agents.com`)
- **`dmarc=pass`** (header.from=`cyppie-agents.com`, policy.dmarc=none) — trägt via **SPF-alignment (relaxed)**
- **`iprev=pass`** (`54.240.6.53` = amazonses)
- `dkim=` NICHT in der Empfänger-Zeile gelistet (sein MX listete SPF/DMARC/iprev; SPF-alignment reichte für DMARC). Literaler `dkim=pass` = confirmatory/Forwarding-Robustheit; per Test-Option-(a) **kein Blocker** (die Mail ist DKIM-signed d=cyppie-agents.com aligned + Key published, s. oben).

**→ CYP-193-Gate (Option a) erfüllt:** recipient-computed **SPF=pass + DMARC=pass** aus echter Inbox + Resend `delivered` + real-world-Beweis, dass sein echtes Postfach Plattform-Mail empfängt. (Inbox-vs-Spam-Reputation-Standing separat abgefragt.)
