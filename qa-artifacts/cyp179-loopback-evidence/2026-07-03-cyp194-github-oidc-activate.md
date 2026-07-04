# CYP-194a — GitHub-OIDC-Aktivierung auf prod (redacted) — HARD S1-Gate

**Datum:** 2026-07-03 · **Auftraggeber-GO** (human-gated) GitHub-Login live · prod-Kratos `api.cyppie-agents.com`.
**HARD-Gate:** S1-Account-Takeover safe (Config+Version-Parität + empirischer Prod-Durchstich wenn feasible) + S2 verify-then-admit. Rot → sofort deaktivieren (`env` zurück).

## Aktivierung (durch)
- `kratos-prod/start-kratos-prod.sh`: `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS`-env gesetzt (providers-Array github, client_secret box-local aus `secrets/kratos.env` `$GITHUB_CLIENT_SECRET`, nie Literal/Log).
- `kratos-prod/kratos.yml`: `selfservice.methods.oidc.enabled: true` ergänzt.
- PG-Kratos-Restart → public :5433 / admin :5434 = 200, keine Startfehler.
- **LIVE Login-Browser-Flow: 1 `oidc`/`github`-Node** (war 0) → **GitHub-OIDC AKTIV**.

## S1-Gate: Config-/Version-Parität prod == gestern-S1-verifiziertes dev (attestiert)
- **Version (S1-sensitiv):** prod+dev = **dasselbe Binary** `kratos-dev/kratos-1.3.0` = **v1.3.0**. Keine Version-Diff → kein Re-Probe-Zwang.
- **Mapper/Jsonnet:** `kratos-dev/oidc.github.jsonnet` md5 **c557f3aae2088afbd2e22c15584f99c8** (`claims.email → traits.email`) — identisch, GLEICHE Datei referenziert.
- **Provider:** client_id `Ov23lioeKkKuWAesQBKT` · provider `github` · scope `user:email` — identisch. verified-Handling/no-auto-link = Kratos-v1.3.0-Default (unverändert).
- **Gestriger S1a-AUTORITATIVER READOUT (SAFE, `runs/2026-07-02-p4-s1-oidc-spike-prep.md`):** VICTIM unverändert (kein Takeover); neue OIDC-Identity trägt GitHubs **verifizierte Primär-Email A** (NICHT die Kollisions-E) → **Takeover AT GITHUB blockiert** (GitHub sendet nur verifizierte Primär-Email); neue OIDC-Identity **platform-verified=FALSE**.

## S2 verify-then-admit
Frische OIDC-Identity landet **platform-verified=FALSE** → App-Guard verlangt `verifiable_addresses[].verified==true` → **denied bis on-platform-Verify** (safe default; gestern bestätigt, live-prod nachzuweisen im Durchstich).

## Callback (2-p-Domain — Auftraggeber trägt in GitHub-OAuth-App ein)
`https://api.cyppie-agents.com/.ory/kratos/public/self-service/methods/oidc/callback/github`
**NICHT** `cypppie` (3p). Bis registriert: Flow kann nicht durchlaufen (Callback-Mismatch) → kein Takeover-Fenster.

## OFFEN — empirischer Prod-Durchstich (braucht Auftraggeber, ich halte keine GitHub-Creds)
1. Callback registriert (2-p) → 2. Auftraggeber fährt **S1a-Consent** (Test-GitHub-Account, unverified-attacker-Email==victim) → ich capture Admin-API (VICTIM unverändert + separate Identity platform-verified=FALSE = kein auto-link/verified-flip) + **Live-Sign-in end-to-end**. S1b ideal auch.
**⛔ Durchstich unsafe → `env` entfernen (SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS raus) + `oidc.enabled:false` + Restart → GitHub-Login aus, melden.**
**Rollback-Deaktivierung:** `start-kratos-prod.sh` env-Zeile entfernen + `kratos.yml` `oidc.enabled:false` + PG-Kratos-Restart → 0 oidc-Nodes.

## 2026-07-04 — S1a BLOCKIERT durch Client-Stub-Modus (Root-Cause) + Auth-Live-Fix

### Symptom (Auftraggeber): "Sign in with GitHub" → https://github.test/... (nicht ladbar)
### Verify-don't-trust: Kratos UNSCHULDIG (empirisch)
- Prod-Login-Flow gesubmittet → Location: `https://github.com/login/oauth/authorize?client_id=<real>&redirect_uri=https://api.cyppie-agents.com/.ory/kratos/public/self-service/methods/oidc/callback/github&scope=user:email` → **REAL github.com, 2-p-redirect_uri, real client_id.** `github.test` in KEINER kratos-Config/env, /etc/hosts kein Mapping. **Kein Kratos-Fix nötig.**
### Root-Cause: SERVIERTER SPA im STUB-Modus
- `spa-dist/webApp.js` hat `github.test` (aus `StubAuthRepository.kt:25`). CYP-182 `AuthFlip`: `resolveAuthMode` → `AuthMode.Stub` (→ Stub → github.test) außer wenn `globalThis.CYPPIE_AUTH_LIVE` gesetzt. Servierte `index.html` setzte die 3 Auth-Globals NICHT → Stub. **Deploy-Config-Gap (bei CYP-179/188/192 nie injiziert).**
- **Implikation:** Client-Login-Gate war seit Public-Flip Stub/Demo (ALLE Wege). Backend real+sicher (Guard weist Stub ab → kein Leak), aber echter User konnte via servierte Seite nie real einloggen.
### Fix (Deploy-Runtime-Config, Auftraggeber-GO + Backend-Global-Bestätigung): 3 Globals in index.html
`CYPPIE_AUTH_LIVE="1"` · `CYPPIE_AUTH_ORIGIN="https://api.cyppie-agents.com"` · `CYPPIE_AUTH_PROXY="https://api.cyppie-agents.com/.ory/kratos/public"` (alle 3 Pflicht, sonst Misconfigured/fail-loud). Backup `spa-dist/index.html.pre-authlive.bak`. index.html direkt aus spa-dist serviert → persistent.
### LOUD-Verify (extern) — ALLE GRÜN
1. ✅ 3 Globals in servierter index.html. 2. ✅ tokenlos (kein OPERATOR_TOKEN-Wert). 3. ✅ resolveAuthMode→`AuthMode.Live` (nicht Misconfigured/Stub). 4. ✅ Operator-Pfad: Bearer-Token `/api/acl`+`/api/workspace/members`→200 (client-mode-unabhängig, nie ausgesperrt); `role_assignments` leer → erste verifizierte Identity→OPERATOR (V wird nach S1a weggeräumt). 5. ✅ keine Belt-off-Regression (SPA 200, MUST-1/admin 404, events 401, .git 404).
### Offen: Test-Live-Verify (Client-Live-Modus alle Wege) → dann S1a real github.com (Victim V BEFORE-captured). Revert: `cp spa-dist/index.html.pre-authlive.bak spa-dist/index.html`.
