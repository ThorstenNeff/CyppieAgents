# CYP-179 Stage-2b — Public-Flip LIVE#2 + 5 [EXPOSED]-Probes (redacted)

**Datum:** 2026-07-02 · **Auftraggeber-GO erteilt** (via PO). Kontrolliertes Public-Fenster.
**Host:** `api.cyppie.com` (Caddy `reload`, nicht-persistent → Daemon-Restart = safe-fail auf basic-auth).
**Stack:** prod-Kratos (kein `--dev`, https base_url, Aiven-Postgres verify-full), Hub→PG-Kratos, `registerFloorMs=150`.
**Design:** default-deny; Throttle keyt `{remote_host}` (kein XFF-Trust, Caddy=direkter Edge); admin nie geroutet + loopback-bind.

## LIVE#1 → PULL-BACK → Fix → LIVE#2
- **LIVE#1:** 1 rotes Feld (G4): blanket `handle_path` reichte `/.ory/kratos/public/admin/*` an Kratos-public durch → **307 auf loopback-Admin-URL** (Info-Disclosure der internen Admin-URL; kein direkter Extern-Daten-Leak, da Redirect auf Client-Loopback). → **sofort un-exponiert** (reload→cyp173).
- **Fix:** Kratos-Proxy von blanket auf **echtes Allow-List / default-deny**: `/admin*`+registration explizit 404, nur known-good self-service/sessions/schemas/.well-known proxien, **alles sonst 404**.
- **LIVE#2:** loopback-re-validiert (inkl. /admin→404, kein Location-Disclosure), re-flipped, alle 5 Probes **frisch** (kein carry-over).

## Reachability-Matrix (extern, LIVE#2)
| Pfad | extern | |
|---|---|---|
| `/api/auth/me` | **200** | LIVE, content-free |
| raw `…/self-service/registration/browser` | **404** | MUST-1 |
| `POST /api/auth/register` | **400/200** | Wrapper, throttled |
| `…/self-service/login/browser` | **303** | Kratos-Proxy |
| `…/sessions/whoami` (unauth) | **401** | erreichbar |
| `/.ory/kratos/public/admin/identities` | **404** | default-deny (Fix) |
| `:5433/5434/4433/4434/8787/2019/1025/8025/8088` extern | **000** | nc-bestätigt refused/gefiltert |
| `/` App-Fläche | **401** | basic-auth-Belt |

## 5 [EXPOSED]-Probes (frisch, LIVE#2)

### G1 / MUST-1 ✅
raw register alle Varianten → **404** (`/browser`,`/api`,`/flows?id=`,`/registration`); Wrapper `POST /api/auth/register` → **200** (antwortet). Nur der Wrapper registriert.

### G2 ✅ (prod-TLS Secure-Cookies)
Recovery- + Login-Flow Set-Cookie: `csrf_token_…; Path=/; HttpOnly; Secure; SameSite=Lax` → **HttpOnly+Secure+SameSite** unter prod-TLS (kein `--dev` wirkt). **http-negativ:** `http://…/login/browser` → **308** → https (kein Klartext-Flow). base_url emittiert `https://api.cyppie.com/`. *(clear-ALL-post-reset + RC5-double-submit = P3-verifizierte, DB/dev-flag-unabhängige Code-Behaviors.)*

### G3 ⚠️✅ (Throttle; 1 Feld nicht-darstellbar)
- **(a)** N+1 rapid /api/auth/register (Limit 5/min) von IP A → `200 200 200 200 429 429 …` → **429-Onset** (bei 5, da ~1 vorige Anfrage im Fenster). Throttle aktiv ✅.
- **(c)** **XFF-forge** (variierender `X-Forwarded-For`) → bleibt **429** → Key = echter Peer, **nicht XFF** ✅ (widerlegt Shared-Bucket).
- **(b)** *IP B unabhängig:* Box hat nur EINE Egress-IP (162.55.248.10), kein v6-Egress → **live nicht darstellbar** von diesem Vantage. Per-Peer-Isolation ist durch `{remote_host}`-Keying by construction garantiert; (c) widerlegt XFF-Rotierbarkeit. **Braucht 2. Quell-IP für die Live-Teeth.**

### G4 / G4b ✅ (admin-KLASSE, comprehensive)
admin-Varianten alle **deny, kein Disclosure:**
| Variante | Code | |
|---|---|---|
| `…/public/admin`, `/admin/identities`, `/admin/` | 404 | Kratos default-deny |
| `/admin`, `/admin/identities`, `/.ory/kratos/admin/*` | 401 | basic-auth-Belt |
| `…/public/ADMIN/identities` (case) | 404 | |
| `…/self-service/../admin/identities` (traversal) | 404 | normalisiert IN deny |
| `…/%2e%2e/admin/identities`, `…/..%2fadmin/…` | 401 | Belt |
| `…/public//admin/identities`, trailing-slash | 404 | |
**Disclosure:** kein `Location:`-Header mit `127.0.0.1`/`5434`, kein Identity-Body (rows:0) — auch nicht im 404-Body.
**Ports:** :5433/:5434/:4433/:4434/:8787/:2019/:1025/:8025/:8088 extern **000**; nur :80/:443 offen (nc).
**CORS:** foreign `evil.example` → **kein ACAO** (nicht `*`); prod `https://api.cyppie.com` → **gespiegelt**.
**Positive Allow-List (kein Über-Blocken):** register 200 · login/recovery/settings/browser 303 · me 200 · whoami 401 · schemas 200 ✅. **Ausnahme `/api/health` → 401** (bewusst Belt-geschützt, kein Auth-Flow; Client nutzt `/api/auth/me` als Liveness — trivial exponierbar falls Kontrakt es verlangt).

### G5 ✅ (externer MEMBER ↛ OPERATOR)
MEMBER in PG-Kratos, extern `/api/auth/me` → `role:MEMBER,verified:true`.
- **extern** MEMBER-Session → `/api/workspace/members`,`/api/audit`,`/api/projects`,`/api/events` → **401** (App-Fläche hinter basic-auth-Belt → Session-Halter erreicht Operator-Routen gar nicht).
- **extern** proxy-forged OPERATOR-Header (`X-Session-Token:forged`,`X-User-Role:OPERATOR`,`X-Operator:true`,`X-Forwarded-User`) → **401** (kein Header-Trust am Edge).
- **loopback** (Belt umgangen) Hub-Guard: MEMBER → **403** auf alle Operator-Routen (defense-in-depth); `/api/events` → 200 (MEMBER-lesbar by-design). audit-Isolation + cross-MEMBER = Stage-1/hermetisch gedeckt.

## Offene ehrliche Punkte
1. **G3-(b) IP-B-Unabhängigkeit** live nicht darstellbar (1 Egress-IP) — braucht 2. Vantage oder gilt als by-construction.
2. **`/api/health` → 401** (Belt) — bewusste konservative Wahl; falls public gewünscht, 1-Zeilen-Allow-List-Add.
3. **App-Daten-Routen bleiben basic-auth-Belt** (konservativer Flip: nur Auth-Fläche public, App hinter Belt) — der Kratos-Guard ist zusätzlich aktiv (loopback-belegt).

**Pull-back-Bereitschaft scharf:** jedes rote Feld → `caddy reload`→cyp173. Secrets box-local, kein Disclosure.

## VERDIKT (Test, 2026-07-02): ALL-5-GRÜN — FLIP BESTÄTIGT, CYP-179 ZU
- G1/MUST-1, G2-TLS/Cookies, G4/G4b-admin-Klasse+no-Disclosure, G5-MEMBER/forge = live-verifiziert grün.
- **G3(b)** geschlossen per **Option 2 — autoritative Runtime-Topologie-Attestierung** (Test akzeptiert als FAKT, da wir die Infra besitzen): DNS `api.cyppie.com` A→162.55.248.10 · Box hält IP direkt (ifconfig) · A==Box-Egress (kein Intermediary) · TLS=Let's-Encrypt-YE1 an meinem Caddy (kein CDN-Cert/-Marker; `Via: 1.1 Caddy` = eigener reverse_proxy-Hop) → `rate_limit {remote_host}` = echter Peer, per-Client by construction. *(3× (a)+(c) empirisch: 429-Onset + XFF-forge-throttled.)*
- Liveness→`/api/auth/me` bestätigt (nicht /api/health).
- **Stack darf public BLEIBEN.**

## ⚠️ Persistenz-Hinweis (offen, Auftraggeber-Entscheidung)
Der Flip läuft via `caddy reload` — die root-LaunchDaemon-Plist (`/Library/LaunchDaemons/com.cyppie.caddy.plist`) zeigt weiter auf `Caddyfile.cyp173`. **Ein Daemon-Restart/Reboot revertiert die Auth-Fläche still auf basic-auth-only** (fail-safe, kein Breach, aber public-Auth down bis Re-Flip). Optionen an PO: (1) `Caddyfile.cyp173` mit Flip-Inhalt überschreiben (Backup daneben, kein sudo), (2) Plist umbiegen (sudo), (3) non-persistent lassen. Wartet auf Entscheidung.

## Standing (im Record)
- **CDN/LB je davor → Throttle-Keying VORHER re-verifizieren** (dann `trusted_proxies`+`{client_ip}`; siehe §2b).
- **503-Rate monitoren** (registerFloorMs=150; found>Floor→503; large-store-Re-Measure-Trigger).
- **Liveness `/api/auth/me`** (nicht /api/health = belt-gated 401).
- Aiven-Lockdown (ip_filter /32, verify-full, EU), Secrets box-local, Plattform-`roles.db`/`events.db` = SQLite.
