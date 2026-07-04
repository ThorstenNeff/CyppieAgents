# CYP-195 — Client-Live-Flip formaler Close (redacted)

**Datum:** 2026-07-04 · Formaler Gate-Close des Client-Live-Flips (Deploy-Runtime-Config, Auftraggeber-GO + Backend-Global-Bestätigung). Detail-Root-Cause + Apply: `2026-07-03-cyp194-github-oidc-activate.md` (§ „S1a BLOCKIERT … Auth-Live-Fix").

## Was der Flip war
Die servierte `spa-dist/index.html` war tokenlos OHNE die Auth-Live-Globals → CYP-182 `resolveAuthMode` defaultete `AuthMode.Stub` (StubAuthRepository → github.test), d.h. der Client-Login-Gate war Stub/Demo für ALLE Wege. Fix: 3 Deploy-Globals injiziert (Backup `spa-dist/index.html.pre-authlive.bak`, index.html direkt aus spa-dist serviert → persistent):
- `globalThis.CYPPIE_AUTH_LIVE="1"` · `CYPPIE_AUTH_ORIGIN="https://api.cyppie-agents.com"` · `CYPPIE_AUTH_PROXY="https://api.cyppie-agents.com/.ory/kratos/public"` (alle 3 Pflicht, sonst `AuthMode.Misconfigured` fail-loud).

## Frischer Re-Verify (2026-07-04, LOUD — Gate-Bedingungen)
- ✅ **3 Globals present** in servierter index.html (extern).
- ✅ **tokenlos** (kein OPERATOR_TOKEN-Wert; nur auskommentierter Platzhalter) — Härtung „served tokenlos" hält.
- ✅ **resolveAuthMode → `AuthMode.Live`** (flag=1 + origin/proxy `looksLikeHttpUrl`), nicht Misconfigured/Stub → echter HttpAuthRepository/Kratos-Flow (github.com, nicht Stub/github.test).
- ✅ **Operator nicht ausgesperrt:** Bearer-OPERATOR_TOKEN `/api/acl`→200 (client-mode-unabhängig).
- ✅ **Belt-off unregressed:** `/`→200 · MUST-1→404 · admin→404 · `/api/events`→401 · `.git`→404.

## Status
**CYP-195 substanziell erfüllt** — Client-Live-Modus aktiv, tokenlos, Operator-Pfad intakt, keine Belt-off-Regression. Rollback: `cp spa-dist/index.html.pre-authlive.bak spa-dist/index.html`.
