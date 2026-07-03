# CYP-189/188 Deploy `7bf72ee` — Hub + Frontend-Grant-UI LIVE (redacted)

**Datum:** 2026-07-03 · **Auftraggeber-GO** „make Grant-UI + closeout fixes live" (via PO). Controlled Deploy.
**Host:** `api.cyppie.com` · Hub-Jar **`7bf72ee`** (md5 63ab8505) · Frontend **`7bf72ee`** (CYP-189-Grant-UI) · Edge `Caddyfile.cyp173`(=beltoff) · prod-Kratos/Aiven verify-full.
**Deltas ggü. 3832bb4:** CYP-189-Operator-Grant-UI (Human canWrite per Channel) · CYP-188-ACL-Grant-Härtung (ghost-grant→404) · BG-WS-5-Fix (Hub-Log-`?token=`-Redaction).

## Sequenz + Safety-Gates
1. **Backups:** Hub-Jar `server-all.jar.3832bb4-live.bak` (md5 69539be) + Bundle `spa-dist.3832bb4-bundle.bak` = atomarer Un-Swap.
2. **Builds:** Hub shadowJar 7bf72ee (md5 63ab8505, gestaged) · Frontend jsBrowserDistribution 7bf72ee (0 `.map`, tokenlos, keine Secrets, Grant-UI). Live-Jar bis Aktivierung crash-safe auf 3832bb4.
3. **Loopback-Re-Verify VOR Aktivierung = GRÜN:** CYP-179 register enum-safe (byte-identisch 200) · **ACL-Grant-Härtung ghost-grant→404** (war 200) · realer Channel→200 · send-gate MEMBER→403 uniform.
4. **Aktiviert:** Hub-Swap→7bf72ee + Frontend-Bundle-Swap→7bf72ee. **served webApp.js md5 == built** (neues Bundle live).

## Externe Reachability-Matrix (LIVE)
SPA/Grant-UI `/`→200 · `/webApp.js`→200 (**md5 served==built** c48a3cc8) · served tokenlos · Härtung `.git`/`.map`/`.env`→404 · `/api/auth/me`/`/api/health`→200 · `/api/events`→401-Guard · `/ws/comm,events,hub`→400-Hub · MUST-1→404 · admin proxy→404 · `:5434`→000.

## Live-Re-Verify-Suite (frisch, RC2, kein carry-over)
- **BG0 (SPA/Grant-UI served):** `/`→200, served-md5==built (Grant-UI-Bundle live), tokenlos, `.git`/`.map`/`.env`→404, kein Listing. ✅
- **BG1/MUST-1:** raw-register→404. ✅
- **BG2 (TLS):** Cookies `HttpOnly; Secure; SameSite=Lax`; http→**308**→https. ✅
- **BG3 (Throttle):** register 7× → `200×5, 429×2` (429-Onset). ✅
- **BG4 (admin/ports):** admin proxy→404, `:5434`→000, MUST-1→404. ✅
- **BG5 (authZ):** MEMBER extern → workspace/members·audit·projects→**403**, events→**200**(ACL), forged-OP-Header→**401**. ✅
- **BG-WS:** `/ws/comm,events,hub`→Hub(400), nicht SPA. ✅
- **⭐ BG-WS-5-LIVE (mein Fund, jetzt geschlossen):** fresh `?token=<canary>` an `/ws/comm`+`/ws/hub` → **0 Treffer in hub.out.log** (war 8×); 7bf72ee loggt jetzt **`token=[REDACTED]`**; **0 volle Token nach dem Restart** (die vorhandenen sind append-only-Historie vom 30. Juni, pre-fix). Edge-Log weiter clean (0). **Ehrliche Note:** append-only `hub.out.log` hält noch pre-fix-Historie → **Log-Rotation/Purge des pre-fix-Logs empfohlen** (Hygiene, kein neuer Leak).
- **⭐ BG-SEND-both:**
  - **DENY:** MEMBER ohne Grant → **403** (po-dev1==nonexistent, uniform, kein Existenz-Tell). unauth→401.
  - **ALLOW-201:** Operator `PUT /api/acl` canWrite auf **po-dev1**→200 → Route synct Membership → MEMBER-Send extern → **201**. Cleanup: revoke → members zurück `[po,dev1,operator]`, Test-Identities gelöscht (pg-seed behalten).
  - **ACL-Grant-Härtung (CYP-188):** ghost-grant auf nicht-existenten Channel → **404 `acl_channel_gone`** (loopback-verifiziert) — schließt das 200-dann-403-Symptom strukturell.

## Rollback (pre-authorized, scharf)
Jedes rote Feld → **Un-Swap:** Hub `cp server-all.jar.3832bb4-live.bak` + restart · Frontend `cp -R spa-dist.3832bb4-bundle.bak`. Non-persistent-Aktivierung (cyp173 trägt weiter die Belt-off-Config; nach Test-grün analog CYP-179 persistieren). Secrets box-local, Aiven-Lockdown unverändert.
