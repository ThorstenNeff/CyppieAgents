# CYP-179 Stage-2a — Aiven-Postgres Prod-Config-Kratos (loopback) + DB-Trigger-Re-Measure

**Datum:** 2026-07-02 · **Modus:** LOOPBACK, nicht-public. **KEIN Public-Bring-up.**
**DSN-Ziel:** Aiven managed Postgres `cyppie-agent-postgres-svc` (do-fra/Frankfurt, pg 17.10), **nur Kratos' Identity-DB** (Plattform-`roles.db`/`events.db` bleiben SQLite).

## Security-Checks (Pflicht, vor Kratos) — alle grün, 2 Findings geschlossen
1. `local.properties` **gitignored ✓** (nicht getrackt). **Finding:** `ca.pem` war NICHT ignored → `.git/info/exclude` lokal gesetzt; Empfehlung `*.pem` in getrackte `.gitignore` (Dev-Follow-up). ca.pem = reines CERTIFICATE (kein private key).
2. Secrets server-side ✓ (DSN/Password/ca.pem box-local, nie Repo/Log/Chat/Evidence; Aiven-MCP liefert `[REDACTED]`).
3. TLS **verify-full ✓** — TLSv1.3/TLS_AES_256_GCM_SHA384; ohne ca.pem verweigert (MITM-Schutz). Kratos-DSN `sslmode=verify-full&sslrootcert=<ca.pem>`.
4. IP-Allowlist **war OFFEN** (`0.0.0.0/0`,`::/0`) → **eingeschränkt auf `162.55.248.10/32`** (Box-Egress, gegroundet) via Aiven-MCP (Projekt-Admin).
5. **EU-Region ✓** do-fra (Frankfurt, DSGVO).

## Prod-Config-Stack (loopback)
- `kratos-prod/kratos.yml` + `start-kratos-prod.sh`: DSN=Postgres (via `DSN`-Env, verify-full, box-local), **KEIN `serve --dev`** (Prod-Cookie-Posture), Ports **:5433/:5434** (parallel zum SQLite-Dev), security-knobs unverändert (argon2, enum-mitigate, notify_unknown:false).
- base_url loopback-http (native/admin-Smoke funktioniert token-basiert); **einziger Rest-Prod-Delta = base_url http→https+public Host** (Exposure/G2, Stage-2b).
- `migrate` → **26 Kratos-Tabellen** in Aiven-`kratos`-DB. serve → public/admin ready 200.

## ⚠️ DB-Trigger-Re-Measure (task 2a) — REAL SIGNAL, NICHT GRÜN

Tight-alternating found/miss Paar-für-Paar (Test's Schärfung), 3 Läufe × 40 Paare, **paired-Δ** (found−miss pro Paar, hebt Netz-Jitter als common-mode raus):

| Lauf | found_med | miss_med | **paired Δmed** | paired sd | found>miss | overlap | \|Δ\|<sd |
|---|---|---|---|---|---|---|---|
| 1 | 26.10ms | 13.32ms | **+12.77ms** | 1.00ms | **40/40** | False | False |
| 2 | 25.78ms | 13.07ms | **+12.73ms** | 5.73ms | **40/40** | False | False |
| 3 | 26.02ms | 13.18ms | **+12.78ms** | 3.17ms | **40/40** | False | False |

**Vorzeichen der paired-Δ: `+ + +` (STABIL, kein Flip).** → Test's Grün-Kriterium (Overlap UND [sign-flip ODER \|Δ\|<sd]) **NICHT erfüllt** → **KEIN grün**. Ein echtes, reproduzierbares found>miss-Timing-Signal (~12.8ms).

### Mechanismus (gegroundet — nicht die DB-Query, sondern Kratos-Round-Trips × Netz)
- **Server-side Postgres-Execution: found 0.116ms vs miss 0.092ms** → DB-Query selbst **konstant** (~0.02ms, wie SQLite). Kein Index-Scan-Problem.
- **Base Netz-RTT Box↔Aiven: ~4–6ms/Round-Trip** (SELECT 1 \timing).
- → Der ~12.8ms-Delta = **Kratos' `?credentials_identifier=` found-Pfad macht EXTRA Round-Trips** (hydratisiert die Identity: identities + verifiable_addresses + recovery_addresses + credentials nach Credential-Fund); miss short-circuit't nach 1 Query. Auf **SQLite-loopback** waren diese Extra-Queries sub-ms (unsichtbar: +0.24ms). Auf **Aiven** kostet jeder Extra-RTT ~4–6ms → der found-Pfad-Overhead wird ein **stabiles ~13ms-Orakel**.

### Konsequenz → routet an Backend
- **Der Timing-Residual hält NICHT für den Aiven/Postgres-Flip as-is.** Der wrapper-Response erbt diesen Delta (existing-Branch ruft identityExists-found → ~13ms langsamer als new) → netz-verstärktes Enumeration-Orakel.
- **Mitigation (Backend, aus DIESEN Postgres-Daten zu dimensionieren):**
  1. **Equalized existence-check** (strukturell, bevorzugt): den Wrapper-`identityExists` auf **konstante Round-Trip-Zahl** bringen — nur die credential-identifier-Existenz prüfen (KEINE Identity-Hydration), so dass found und miss beide 1 Query sind. Kollabiert den Delta an der Quelle.
  2. **Konstant-Zeit-Floor ≫ 13ms** (aus found_med~26ms + Netz-Jitter-Marge) — maskiert, statt zu beseitigen; muss bei Latenz-Änderung neu dimensioniert werden.
- **Test-Gate Stage-2a:** „kein exploitierbares DB-Layer-Signal jetzt" ist damit **nicht erfüllt** — offen bis Backend-Fix; finaler Timing-Grün ohnehin auf G3 (Stage-2b) contingent.

**Der MANDATORY-DB-Trigger hat exakt seinen Zweck erfüllt:** SQLite-loopback verdeckte ein reales Orakel, das die netz-behaftete prod-DB (Aiven) aufdeckt.

## Behavioral-Smoke-4 (task 2b) — alle Gates halten auf Postgres + ohne `--dev` ✓

Hub temporär auf Postgres-Kratos (:5433/:5434) repointed. Ergebnis (unabhängig vom Timing-Fix):

| Gate | Ergebnis (Postgres, kein --dev) |
|---|---|
| **G1 content-Parität** | NEW+EXISTING → beide **200**, body **byte-identisch** `{"status":"verification_pending"}` ✓ |
| **no-leak** | EXISTING kein Dup; NEW +1 ✓ |
| **Mail-Seam (code)** | Mailpit: **verify**-Mail → NEW · **recovery**-Mail → EXISTING (createAndVerify-Follow-up-Trigger funktioniert auch auf Postgres) ✓ |
| **fail-mode uniform (MUST-3)** | Admin-Port tot → NEW+EXISTING beide **503** identisch, **kein create** ✓ |
| **G4 CORS** | fremder Origin `evil.example`: Preflight **403**, kein ACAO, nie `*` ✓ |
| **G5 403-Matrix** | frische O2/M2 in Postgres-Kratos, roles.db kontrolliert bootstrapped: MEMBER→**403** auf `/api/workspace/members`,`/api/audit`,`/api/projects`; `/api/events` MEMBER-lesbar (by-design). Identisch zum SQLite-Ergebnis ✓ |

**Fazit Smoke-4:** die Auth-Gates sind **DB-backend- UND dev-flag-unabhängig** — halten 1:1 auf Aiven-Postgres ohne `--dev`. Der einzige Postgres-spezifische Effekt ist das Timing-Orakel oben (separat, an Backend geroutet).

## Endzustand + Restore
- **Validierter SQLite-Stack wiederhergestellt:** Hub → SQLite-Kratos (:4433/:4434), health 200; roles.db-OPERATOR = rc2-test (via Login re-bootstrapped — RC3-Semantik; das cp-Backup war wg. SQLite-WAL leer, daher SQL/Login-Restore statt Datei-Copy, kein echter Verlust: Store re-bootstrappt deterministisch).
- **Postgres-Kratos (:5433/:5434) geparkt** mit `pg-seed`-Identity — bereit für den Re-Measure nach Backends equalized-existence-check-Fix.
- **Aiven:** ip_filter `162.55.248.10/32`, verify-full, EU. Secrets box-local (`secrets/kratos-aiven.env`, ca.pem, local.properties — alle gitignored/geschützt).
- **Kein Public.** Plattform-`roles.db`/`events.db` unverändert SQLite (Scope = nur Kratos' DSN).

## Floor-Dimensionierung (Backend nahm Konstant-Zeit-Floor statt equalized-check)
found-Zweig n=120 (Aiven): median 26.0 · p90 28.4 · p95 29.9 · **p99 44.9** · **MAX 61.1ms** · stdev 3.8.
**Vorschlag `AuthConfig.registerFloorMs = 95`** (config-gated) = max(p99,MAX)×1.5 → überschreitet found-Tail + ~55% Jitter-Marge (Median ~26ms wäre zu niedrig → Spikes leaken).
**Vorbehalt:** Basis = rohe admin-`identityExists`-Latenz, nicht volle Wrapper-Response → **final gegen Wrapper-Response-Tail im Live-Re-Measure validieren/justieren** (mit aktivem Floor).
**Post-Merge-Sequenz (Hold):** (1) Floor setzen, (2) PG-Kratos+Floor hochfahren, (3) Live-Re-Measure tight-alternating paired-Δ N≥40 → Gate = Collapse (Overlap + [flip ODER |Δ|<sd] + Δ≪G3).

## Stage-2b — Floor-Fix (a589a0e) LIVE gegen Aiven — COLLAPSE ✓

Floor gemergt (develop `a589a0e`), Hub-Jar neu gebaut, `AuthConfig.registerFloorMs` config-gesetzt (kein Rebuild), Hub→PG-Kratos.

**Floor final dimensioniert aus large-N found-Sample (N=1200, Aiven):** median 26.9 · p95 32.4 · p99 38.7 · p99.9 77.9 · **MAX 96.3ms** (der große Tail bestätigte die PO-Schärfung: N=120-Max war nur 61ms). → **`registerFloorMs = 150`** (> found-identityExists-MAX 96ms + Marge). Der Floor cappt **identityExists** (`withTimeout(floorMs)`), nicht die Total-Response → P(found-check > 150) = 0 → 503-Rate 0.

**Live-Re-Measure gegen den Wrapper (`POST /api/auth/register`, existing=found/new=miss), tight-alternating, 3 Läufe:**
| Lauf | found_med | miss_med | paired Δmed | sd | found>miss | 503 f/m |
|---|---|---|---|---|---|---|
| rapid N=50 | 299.0 | 278.7 | **+11.68ms** | 66.5 | 31/50 | 0/0 |
| spaced N=30 | 254.1 | 297.5 | **−30.67ms** | 42.2 | 12/30 | 0/0 |
| final N=50 | 300.6 | 300.0 | **+0.77ms** | 60.3 | 28/50 | 0/0 |

**Vorzeichen `+ / − / +` → FLIPPT = Rauschen.** Alle 3: |paired Δmed| < paired-sd. **503-Rate 0/0 (symmetrisch + vernachlässigbar) in allen Läufen.**
→ **Test-Gate erfüllt:** (1) 200-paired-Δ-**Collapse** (von stabilem +12.8ms/40-40-sign+++ pre-fix zu +0.77ms/sign-flip post-fix) UND (2) 503-Rate vernachlässigbar + zweig-symmetrisch. Der Aiven-found>miss-Oracle ist zu.

### Floor-Mechanik verifiziert (gegroundet, nicht geraten)
- **Floor bindet:** Diagnose floor=600 → Response ~760ms (skaliert mit floorMs). Bei 150 → ~300ms.
- **Response = floorMs + ~150ms branch-UNABHÄNGIGe Konstante** (Side-Effect-Dispatch/HTTP-Pipeline nach `register()`-Return; create/notify sind deferred via `dispatch{}` off dem Response-Pfad). Die Konstante leakt nicht (branch-invariant). Register-Total-Latenz ~300ms = akzeptabel (register low-freq, Security>Latenz).
- **503-Cap korrekt:** cappt identityExists (found-worst 96ms) < floor 150 → 0 Timeouts. `registerFloorMs=150` ist der validierte Wert.

**Vorbehalt-Auflösung:** mein früherer 95ms-Startwert (aus N=120-Max 61) wäre zu niedrig gewesen (large-N found-MAX=96ms) — das größere Sample trieb ihn korrekt auf 150ms. Wrapper-Response-Tail (~310ms) ist branch-invariant, drückt NICHT die 503-Rate (die hängt am identityExists-Cap, nicht an der Total-Response).
