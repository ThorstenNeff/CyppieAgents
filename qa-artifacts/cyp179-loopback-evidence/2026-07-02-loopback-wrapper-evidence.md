# CYP-179 — Staging-Wrapper-Build + Loopback-G1-Evidence (Stufe 1)

**Datum:** 2026-07-02 · **Modus:** LOOPBACK, nicht-public. **KEIN Public-Bring-up.**
**Build:** develop-tip `321e454` (Merge CYP-187). Jar neu gebaut (`server:shadowJar`, 14:43).
**Config:** `platform.config.json` auth-Block + `"kratosAdminUrl":"http://127.0.0.1:4434"` (mountet den Wrapper, MUST-1) + `kratosPublicUrl :4433`.
**Hub:** LaunchAgent `com.cyppie.hub` neu gestartet (PID 36476), Health 200, `POST /api/auth/register` → 400 (leer) = **Wrapper gemountet** (nicht 404).
**Stack:** Kratos v1.3.0 (Build 0a49fd05, 2024-09-26) :4433/:4434, Mailpit :8025, alle loopback.

## G1-Set — Evidence (redacted; keine Passwörter/Codes/Token geloggt)

| Check | Ergebnis | Beleg |
|---|---|---|
| **Content-Parität new==existing** | ✓ | beide `POST /api/auth/register` → **HTTP 200**, body byte-identisch `{"status":"verification_pending"}` |
| Header-Parität | ✓ (mit Nuance) | identisch außer per-Request-random `cyppie_csrf`-Cookie (gleich geformt in BEIDEN Branches: `Path=/; SameSite=Strict` — **kein Branch-Orakel**, wie `Date`) |
| **admin-count no-leak (existing)** | ✓ | isoliert gemessen: count **2→2 unverändert** bei erneutem EXISTING-register (kein Dup) |
| new-branch create | ✓ | NEW → admin **+1** (Identity `03b4c097`, `verified:false`, `state:active`). *(Hinweis: create landet minimal verzögert nach dem 200 — async zum Response-Pfad; erste Zählung war race, re-query bestätigte +1.)* |
| **Mail-Seam** | ✓ | Mailpit: **verify-Mail** „Please verify your email address" → NEW-Email · **recovery-Mail** „Recover access to your account" → EXISTING (rc2-test). Beide Branches mail-symmetrisch. |
| `code` vs `link` | code | dev-Kratos `recovery.use: code` / `verification.use: code` → Mails tragen **Code** (kein Magic-Link). |
| **fail-mode uniform (MUST-3)** | ✓ | kratosAdminUrl→toter Port :4599, Hub-Restart: NEW **und** EXISTING → **HTTP 503**, body identisch; admin-count **10→10 kein create**. Config restored → 200. |
| **Timing-Baseline** | ⚠️ **BEFUND** | loopback (low-noise), 8×/Branch: **new ~0.011–0.088s (median ~0.045)** vs **existing ~0.003–0.043s (median ~0.004 nach Warmup)** → **branch-separierbar**: der create+mail läuft im new-Branch **synchron im Response-Pfad**, nicht deferred. |
| **Version-Pin** | ✓ | Kratos **v1.3.0** (Binary + laufender Prozess + config `version: v1.3.0`). Standing-Rule: Bump → RC2-Re-Probe. |

### Timing-Befund (ehrlich, an Test/Backend)
Auf loopback ist die Response-Latenz **new vs existing unterscheidbar** (~40ms Delta): der new-Branch wartet im Response-Pfad auf admin-create + verification-flow-Trigger, der existing-Branch nur auf existence-check + recovery-notice. Das ist ein **branch-separierbares Timing-Orakel** für Enumeration.
- **Kompensation (akzeptiert, §A-Posture):** der per-IP G3-Edge-Throttle hebt die Kosten pro Versuch.
- **Empfehlung (Backend):** create+mail **off den Response-Pfad** deferren (fire-and-forget nach dem 200), dann verschwindet das Delta auch ohne Throttle. Sonst bleibt die Timing-Trennung unter Traffic-Rauschen evtl. nicht verdeckt.

## Test-Harness (kanonisch)
`RegisterWrapperLiveProbeTest` (RUN-gated, `KRATOS_ADMIN_URL`+`KRATOS_PUBLIC_URL`) → **BUILD SUCCESSFUL** gegen den Live-Stack (C1 existence-false-for-random + C2 create/existence-true).

## G4 — CORS-Policy-Core ✓
`web.allowedOrigins = [http://localhost:8080, https://api.cyppie.com]`.
- fremder Origin `http://evil.example`: Preflight OPTIONS → **403 Forbidden**; echter POST → **kein `Access-Control-Allow-Origin`** (nicht gespiegelt, **nicht `*`**).
- Kontrolle: erlaubter Origin `https://api.cyppie.com` → ACAO korrekt gespiegelt.

## G5 — authZ-403-Matrix-Core ✓ (mit 2 ehrlichen Einschränkungen)

**Principals:** O = `rc2-test@cyppie.dev` (OPERATOR, hält die Rolle aus P3 im durable SqliteRoleStore, single-operator-Index) · M = `g5-member@example.org` (frisch, verifiziert, MEMBER). Beide via native Login-Flow → Session-Token (`X-Session-Token`). `/api/auth/me`: O→`OPERATOR/verified`, M→`MEMBER/verified`. **Guard verlangt `verified==true`** (`resolvePrincipal`: `if (!resolved.verified) return null`) — Principals daher verifiziert provisioniert.

**403-Matrix (O=OPERATOR vs M=MEMBER):**
| Route | O | M | |
|---|---|---|---|
| `GET /api/workspace/members` | 200 | **403** | OPERATOR-only ✓ |
| `GET /api/audit` | 200 | **403** | OPERATOR-only ✓ |
| `GET /api/projects` | 200 | **403** | OPERATOR-only ✓ |
| `GET /api/events` | 200 | 200 | MEMBER-lesbar **by-design** (s.u.) |
| `GET /api/agents` | 200 | 200 | participant-lesbar |

**Operator-Audit-Isolation (aussagekräftig, echte Mutation erzeugt):**
- OPERATOR-Mutation `PUT /api/config/repo` (leerer Body → 400, **kein** state-change; Guard recordet den Audit *vor* dem Handler).
- O `GET /api/audit` → **1 Eintrag** `{actor:"human:7e247283…", method:"PUT", path:"/api/config/repo"}` — **content-free** (nur actor/method/path/tsMs, **nie** Body/Secret) ✓.
- M `GET /api/audit` → **403** ✓.
- Operator-Mutation **abwesend** in M's `/api/events` (0 Treffer) ✓ — der Audit lebt in einem **separaten Sink** (`InMemoryAuditSink`), operator-identityIds/-activity betreten den MEMBER-Event-Stream nie (gegroundet in `WorkspaceRoutes.kt` + `EventRoutes.kt`).

**Warum `/api/events` M==O (byte-identisch, 100/536 Events):** **by-design, kein Leak.** `eventRoutes` ist an `authenticatedApi(deps, AuthRole.MEMBER)` gemountet — der Event-Log ist „secret-free metadata → readable at the MEMBER tier". Die MEMBER-Restriktion ist **nicht** weniger Events im aktiven Projekt, sondern: der **`?projectId=`-Cross-Project-Override ist OPERATOR-only** (`resolveEventScope(if(isOperator) q["projectId"] else null, …)`) → ein MEMBER wird auf das aktive Projekt gezwungen, kann andere Projekte nie enumerieren.

### Ehrliche Einschränkungen (nicht live-diskriminierbar in diesem Setup)
1. **Cross-Project-MEMBER-Confinement:** Registry hat nur **1 Projekt ("default")** → `?projectId=all` == aktives Projekt (O:536 = M:536). Der Override-ignoriert-für-MEMBER-Pfad ist **code-enforced + gelesen**, aber live **nicht diskriminierbar** (all==active). Teeth-Test braucht ein **2. Projekt** (oder ist durch Test's hermetische CYP-94/BE2-Tests gedeckt).
2. **Cross-MEMBER-read-denied:** comm-read ist channel-scoped ACL (`/api/comm/channels/{id}/messages`, ACL-read-subject=identityId). Für die synthetischen Principals existieren **keine Channel-Fixtures** → live nicht sauber zeigbar. Gedeckt durch Test's hermetische **BE1/BE2 deny-side proof-tests** (d461591/ac140ed).

**Fazit G5-Core:** die harte 403-Trennung (MEMBER↛OPERATOR-Surfaces) + Operator-Audit-Isolation sind **live grün belegt**; die zwei projektbezogenen ACL-Feinheiten brauchen Fixtures/2. Projekt bzw. sind hermetisch gedeckt.

## Timing-Locus-Isolation (Nachschärfung) — mein ursprünglicher ~40ms-Alarm war MESS-ARTEFAKT

Nachdem Test hermetisch zeigte, dass create+mail **schon** off-path deferred ist, isoliert gemessen (redacted, loopback, direkt gegen :4434 bzw. den Wrapper):

| Messung | Ergebnis |
|---|---|
| **Raw Admin identityExists** `?credentials_identifier=` (N=15 je) | found **median 0.9ms** · not-found **median 0.7ms** → **~0.2ms Delta, vernachlässigbar** — **NICHT der Locus** |
| **Raw Admin CREATE** `POST /admin/identities` (N=12) | **median 82ms** (argon2-Hashing, 128MB/3-iter) — teuer, aber… |
| **Wrapper NEW** (N=25, warm, interleaved) | **median 11.6ms** ≪ 82ms → **create ist DEFERRED bestätigt** (sonst wäre NEW ≥82ms) |
| **Wrapper EXISTING** (N=25) | median 16.3ms |
| **new vs existing Delta** | **−4.7ms** (jetzt existing langsamer!), **|Delta| < stdev** (5.3/9.1ms), **Verteilungen überlappen** (jeder Median im [p10,p90] des anderen) |

**Befund/Korrektur (ehrlich):** Mein erster „new +40ms = load-bearing Timing-Orakel" (aus dem ersten, **unwarmed** Lauf) **reproduziert nicht**: über 3 Messungen dreht das Vorzeichen (new+40 → existing+16 → existing+4.7), das Delta ist kleiner als die Streuung, die Verteilungen überlappen. → **Es gibt kein stabiles branch-separierbares Timing-Signal am Wrapper.** Der erste Wert war ein Warmup-/Connection-Artefakt (die existing-Serie warmte mid-run von 42ms→3ms, während new frische Identities/Verbindungen erzeugte).
- **identityExists** ist ~1ms für found/not-found (kein Index-Miss-Scan bei 1 Identity; bei großem Bestand separat re-messen — offener Vorbehalt).
- **create** (~82ms argon2) ist der einzige teure Schritt, **aber deferred** → nicht im Response-Pfad.
- **Konsequenz für Backend:** die „MUST-2 Timing-Mitigation" jagt kein reproduzierbares Signal — der G3-Edge-Throttle (§A) bleibt die Kosten-pro-Versuch-Kompensation, eine zusätzliche Timing-Deferral ist nach dieser Evidenz **nicht nötig** (höchstens Defense-in-Depth). Re-Messung bei großem Identity-Bestand empfohlen, um den Index-Hit/Miss-Vorbehalt zu schließen.

### Floor-Daten für Backends Konstant-Zeit-Floor (raw identityExists, N=40 je, interleaved, warm)
| Zweig | min | median | mean | p90 | p95 | p99 | max | stdev |
|---|---|---|---|---|---|---|---|---|
| **found** (existing) | 0.71 | 0.79 | 0.88 | 1.18 | 1.41 | 1.66 | **1.66** | 0.20 |
| **miss** (new/random) | 0.48 | 0.55 | 0.66 | 0.96 | 0.99 | 1.04 | **1.04** | 0.19 |
(alle ms) · **gemeinsamer Worst-Case = 1.66ms** · **Floor-Vorschlag: worst-case + Jitter-Marge (~+50%) → ~2ms** (Backend setzt final).
- **Mikro-Tell:** found−miss Median-Delta = **+0.24ms** (found liefert 1 Zeile vs. leer) — real, aber ~0.24ms, **3 Größenordnungen unter meinem Phantom-40ms**, von einem ~2ms-Floor trivial verdeckt. Das ist das *echte* (winzige) identityExists-Signal; genau das killt der Konstant-Zeit-Floor.
- **Re-Run nach Fix-Merge:** beide Zweige flach **≥ Floor**, Buckets überlappen, kein found/miss-Tell mehr.

## Re-Measure-Trigger für das monitored Timing-Residual (CYP-179 Residual-Doc)

FINAL: **kein Konstant-Zeit-Floor gebaut** — das reale Tell (+0.24ms) ist heute unausnutzbar (§A-/G3-konsistent). Damit das Residual *monitored* bleibt, wird `identityExists` (found vs not-found) neu gemessen, wenn EINER dieser Trigger greift:

1. **MANDATORY — DB-Backend-Wechsel (dev SQLite → prod Postgres/o.ä.):** die heutigen ~1ms / +0.24ms gelten **nur für SQLite-on-loopback**. Prod-DB hat anderen Query-Planner, Index-Impl und Netz-Hop → **Zahlen übertragen sich NICHT.** Vor dem Vertrauen aufs Residual auf der prod-DB **neu messen** (N≥40 interleaved found/miss). Das ist der wichtigste Trigger.
2. **Identity-Count-Schwelle:** Re-Measure ab **≥ 10.000 Identities**, danach bei jedem **10×** (100k, 1M). Rationale: der Lookup ist ein indizierter Seek (`identity_credential_identifiers.identifier`, O(log n)) → Delta bleibt in der Theorie beschränkt, aber page-cache-Miss / B-Tree-Tiefe / Planner-Regression können bei großem Bestand ein Fenster öffnen. 10k = konservativer, billiger Früh-Checkpoint weit unter jeder erwarteten Regression.
3. **Kratos-Version-Bump** (bereits Standing-Rule) — neues Query-/Index-Verhalten.
4. **Schema-/Index-Änderung** an der Identity-Credentials-Tabelle.

**Re-Measure-Protokoll + Gate:** exakt der N≥40 interleaved found/miss-Lauf dieses Runs. **Grün-Kriterium:** found−miss Median-Delta bleibt rausch-dominiert (Verteilungen überlappen, |Delta| < stdev) **und** ≪ der G3-Throttle-Kosten-pro-Versuch. **Eskalation** (dann Floor **oder** deferred-existence-check bauen), falls das Delta material wächst (z. B. > wenige ms, nicht mehr rausch-dominiert).

## Ausstehend / Stufe 2 (Public-Fenster, NICHT jetzt)
raw-`:4433`-unreachable · TLS-secure-cookie (G2) · Throttle/XFF (G3) · admin-port-extern. Kein Public-Bring-up ohne weitergeleitetes GO.
