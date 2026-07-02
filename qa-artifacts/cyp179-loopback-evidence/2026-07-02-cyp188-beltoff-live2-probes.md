# CYP-188 Belt-off — Public-Flip LIVE#2 + [EXPOSED-BELT-OFF]-Probes (redacted)

**Datum:** 2026-07-02 · **Auftraggeber-GO** (pre-autorisiert „flip as soon as ready", via PO). Kontrolliertes Fenster.
**Host:** `api.cyppie.com` · Hub-Jar **`3832bb4`** (CYP-179-Floor + CYP-188-Foundation + Send) · prod-Kratos/Aiven verify-full · Edge `Caddyfile.cyp188-beltoff-prestaged` (non-persistent, safe-fail auf Belt).
**Design:** default-deny · `/api/*`→Hub-Guard + `/ws/*`→Hub VOR SPA-catch-all · Kratos allow-list · admin nie geroutet · Throttle `{remote_host}`.

## Flip-Sequenz (mit Safety-Gates)
1. **Hub-Swap → 3832bb4 + loopback-CYP-179-Re-Verify (VOR Exposure) = GRÜN:** Wrapper up · NEW/EXISTING register byte-identisch 200 (enum-safe) · no-leak (+1 nur NEW) · Floor aktiv (~0.21s) · admin loopback. Live-Jar-Backup (a589a0e) + Belt-Config (cyp173) als atomarer Rollback.
2. **LIVE#1 → 1 rotes BG-WS-Feld:** `/ws/comm,events,agent` → 200 SPA-index (vom SPA-catch-all geschluckt; nur `/ws/hub` war explizit geroutet). **Kein Security-Leak** (SPA-HTML, keine Daten). → **sofort un-exponiert (Edge→cyp173); Hub NICHT revertiert** (re-verify-grün + CYP-179-kompatibel).
3. **Fix:** WS-Route `/ws/hub`-only → **`/ws/*`→Hub** (Hub enforced WS-authZ per-Endpoint). Loopback: alle WS → 101 (Hub), nicht SPA. **LIVE#2**, alle Probes frisch.

## Reachability-Matrix (extern, LIVE#2)
| Feld | extern | |
|---|---|---|
| SPA `/` · `/webApp.js` · `/spa/route` | 200 · 200 · 200 | App-UI public, Fallback |
| Härtung `.git/config`·`x.map`·`.env` | 404·404·404 | kein Source/Config-Leak |
| `/api/auth/me` · `/api/health` | 200 · 200 | |
| `/api/events` (unauth) | 401 | Guard, nicht SPA |
| **`/ws/comm,events,agent,hub`** | **400 (Hub)** | nicht mehr SPA ✓ |
| MUST-1 raw registration | 404 | |
| admin proxy · `:5434` direkt | 404 · 000 | dicht |
| served `/` | tokenlos | |

## [EXPOSED-BELT-OFF]-Probes (frisch, LIVE#2)
- **BG0 (SPA/Static-Host):** `/`→200 SPA, **tokenlos** (kein aktives op-Token-Script), `.git`/`.map`/`.env`→404, kein Directory-Listing, SPA-Fallback. ✅
- **BG1/MUST-1:** raw Kratos-register alle Varianten → 404; nur Wrapper registriert. ✅
- **BG2 (TLS/Cookies):** login/recovery Set-Cookie `HttpOnly; Secure; SameSite=Lax`; `http://`→**308**→https. ✅
- **BG3 (Throttle):** register 6× rapid → `200×5, 429×2` (429-Onset); XFF-forge-throttled + Topologie-Attestierung (kein Intermediary, `{remote_host}`=echter Peer) aus CYP-179. ✅
- **BG4 (admin/CORS/Ports):** admin-Klasse (Varianten+Traversal `%2e%2e`/`..%2f`/`//`/case) alle 404/401, **kein Location/Body-Disclosure**; Ports :5433/5434/4433/4434/8787/2019/1025/8025/8088 extern **000**; CORS foreign→kein ACAO, prod→gespiegelt. ✅
- **BG5 (authZ, App-Routen jetzt public):** MEMBER-Session extern → `/api/events`→**200** (MEMBER-lesbar, ACL) · `/api/workspace/members`,`/api/audit`,`/api/projects`→**403** (OPERATOR-only) · proxy-forged OPERATOR-Header→**401** (kein Header-Trust). ✅
- **BG-WS:** `/ws/comm,events,agent,hub` → **Hub (400/101), nicht SPA** (Fix); WS-authZ hub-seitig enforced (unauth-Upgrade abgelehnt). ✅
- **BG-SEND (CYP-188 P2b-iii Human-Send) — DENY grün, ALLOW ehrlich offen:**
  - **DENY (security-kritisch) ✅:** `POST /api/channels/{id}/messages` (valider Body `{"body":…}`, MEMBER **ohne Grant**) → **uniform 403** (general==nonexistent==po, **kein Kanal-Existenz-Tell**); unauth→**401**; forged-writer-Header→401; **kein 201**. Chokepoint `requireCommWriter`→`postAsAgent`/`canWrite`.
  - **DOPPELT fail-closed (stärker als erwartet, gegroundet):** `canWrite` verlangt **BEIDES** — `isMember(channelId, agentId)` aus **`Channel.members`** (agent-/wire-provisioniert) UND einen `canWrite:true`-ACL-Entry. Ein blanker Operator-`PUT /api/acl`-Grant (Entry via `GET /api/acl` **bestätigt** `canWrite:True`) zieht **NICHT** → Send bleibt **403 (loopback+extern)** ohne Channel-Membership. Deny-wins.
  - **ALLOW-201 nicht live-demonstriert (ehrlich):** die 201-Richtung braucht einen Channel mit dem MEMBER in `Channel.members` — Channels/Members entstehen über den **Agent-Wire** (kein Operator/Human-REST-Channel-Create gefunden). Ein Live-201 bräuchte einen **agent-provisionierten Channel** (echter Agent-Run) — schwerer Setup, nicht ohne Freigabe gefahren. Der 201-Pfad ist **code-evident** (`postAsAgent` → `HttpStatusCode.Created` bei `canWrite=true`); die security-kritische DENY-Seite ist live-belegt. **Offen für Test:** ob die Live-201 (agent-provisioniert) verlangt wird oder Code-Evidenz + doppelt-fail-closed-DENY genügt.

## Ehrliche Punkte / Rollback
- **G3-(b) IP-B-Unabhängigkeit** live nicht darstellbar (1 Egress-IP) — durch Topologie-Attestierung (kein Intermediary) gedeckt.
- **Rollback scharf:** jedes rote Feld → Edge→cyp173 (Belt) + optional Hub→a589a0e. Non-persistent = Reboot safe-fail auf Belt.
- Secrets/DSN box-local, redacted. Test-Junk-Identities (belt-member, bg3-*) in PG-Kratos → cleanup nach dem Lauf.
