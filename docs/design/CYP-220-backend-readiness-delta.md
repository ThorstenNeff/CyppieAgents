# CYP-220 — Backend Phase-7 Activation-Readiness Delta (für Auftraggeber-Ratifikation)

> Status: **Design/Readiness-Scoping (ungated — KEIN Bau ohne Auftraggeber-GO)** · Epic CYP-220 Postgres-Migration · Backend lane
> Base: develop `2db9768f` · Komplement: `docs/design/CYP-220-postgres-migration-design.md` (das DESIGN) + UIUX' Migration-UI-Spec (HA/HC/HF) + `backend/plans/CYP-220-live-bind-wiring.md`
>
> **Zweck.** Der **Phase-7-Aktivierungs-Stand** am echten Code: **was gebaut + INERT ist** vs. **was an Live-Wiring fehlt**, um die Sqlite→Postgres-Migration real fahrbar zu machen (der Analog zu CYP-513, das die Client-Naht live-verdrahtete) — plus die Aktivierungs-Reihenfolge/Gates, die Auftraggeber-Entscheidungen und die Fail-closed-Achsen. Das DESIGN ist ratifiziert-reif dokumentiert; dieses Delta macht den **Aktivierungs-Weg** ratifikations-reif.

## 0. Headline (grep-bestätigt)
Der **gesamte per-Store-Postgres-Vertical ist GEBAUT + INERT — 0 prod-caller.** Der Wiring-Seam `StoreRouter`, die Migrations-Engine `StoreMigrator`, die DSN-Infra (`DsnRegistry`/`BindingRegistry`/`ConnectionProvider`) und der Admin-Metrics-Pfad werden in **`server/src/main` NIRGENDS konstruiert** — jeder Treffer ist die Definition, ein KDoc-Verweis oder ein Test. `BootOrchestrator`/`PlatformWiring` referenzieren sie nicht. **Exakt die „built-INERT-bis-zum-Flip"-Form wie die CYP-427-Aktivierung** — nur der Flip (Live-Wiring) fehlt. (Die eine LIVE Krypto-Primitive, `SqliteSecretStore`+`EnvKeysetMasterKeyCustody`, ist für CYP-441-Hub-Identität verdrahtet, `PlatformWiring:349-353` — nicht für die CYP-220-Stores.)

## 1. Was GEBAUT + INERT ist (der Bau-Unsicherheits-Senker)
- **DSN/Binding-Schicht** (`db/DsnRegistry.kt`, `db/StoreBinding.kt`, `db/ConnectionProvider.kt`): `DsnDescriptor` = @Serializable **non-secret** View (`jdbcUrl()` credential-frei, user/pw auf Pool nie URL/Log); Passwort at-rest via `SecretCipher` + AAD `dsn_registry|{dsnId}|password`; `StoreBinding{storeKey, projectId, dsnId, state=ACTIVE|MIGRATING|READ_ONLY}` per-(store,project); `ConnectionProvider.forStore` → 1 Hikari-Pool/dsnId + `evictUnreferenced()`. **0 caller.**
- **Migrations-Engine** (`db/StoreMigrator.kt`, `boot/MigrationGate.kt`, `db/FlywayMigrator.kt`, `db/MigrationAudit.kt`): `migrate` = bind(MIGRATING) → WINDOW_OPEN → COPY(export→import) → **VERIFY (count UND SHA-256-Content-Checksum)** → REBIND(ACTIVE); Mismatch → abort + **Rollback (unbind → retained A)**. `MigrationGate` = **Read-only-Window** (kein Dual-Write): Writes werfen `store_migrating` → **HTTP 409** während des Fensters; 9 `MigrationGated*`-Decorators. Flyway = 10 Script-Sets (`baselineOnMigrate` für non-empty BYO). `MigrationAudit` = Phasen-Log, keine Secret-Werte. **0 caller (test-only).**
- **Pg-Store-Vertical — 10/11 geportet:** `PgProjectConfigStore`·`PgRemoteTokenStore`·`PgAgentOverrideStore`·`PgChannelShareStore`·`PgProjectRegistry`·`PgEventSink`·`PgAgentEventStore`·`PgReportStore`·`PgSessionStore`·`PgDeliveryLog`. **★GAP: `avatar_blob` ist USER_DB_CAPABLE aber NICHT Pg-geportet** (nur `SqliteAvatarBlobStore`, kein `db/migration/avatar`).
- **Secret-Custody** (`crypto/SecretCipher.kt`, `crypto/MasterKeyCustody.kt`): **Tink AEAD**, version-geroutet (Rotation), fail-closed uniform (kein Plaintext-on-error); `SecretAad(storeKey,projectId,field)` **length-prefixed injektiv → relocation-proof** (verschobener Ciphertext dekryptet nicht). Master-Key: `MasterKeySource.Kms(kekUri)` **| Box(keyset)**; Custody `EnvKeysetMasterKeyCustody`=`CYPPIE_MASTER_KEY` (MVP) | `PassphraseMasterKeyCustody` PBKDF2-600k. **KMS-Seam da, aber `KmsClients.add(...)` deferred** → heute nur env/box + passphrase end-to-end nutzbar. **Kein separater `PgSecretStore`** — Secret-at-rest ist der per-Field-Cipher IN `PgProjectConfigStore`/`PgRemoteTokenStore`/`DsnRegistry`.
- **Residency** (`tier/TierPolicy.kt`): `StoreResidency{USER_DB_CAPABLE, MUST_STAY_HOME}`; **FAIL-CLOSED Allow-List** — 11 capable, **8 MUST_STAY_HOME** (roles·account·dsn_registry·store_binding·migration_audit·mcp_config·free_fallback_toggle·quota_usage), **unbekannte/neue Keys → default HOME** (nie versehentlich offloadbar). Binding-Layer verweigert eine BYO-Route für MUST_STAY_HOME.

## 2. Was an Live-Wiring FEHLT (Phase-7 — der „Flip")
Analog zu Doc-19-§3.0 (CYP-427). Grober Aufwand: das meiste ist Verdrahtung, kein neues Design.
1. **Boot-Konstruktion der DB-Infra (W1):** nichts konstruiert `DsnRegistry(file, cipher)` / `BindingRegistry(file)` / `ConnectionProvider` / `StoreRouter` in `BootOrchestrator`/`PlatformWiring`. **★Zero-behavior-change-Eigenschaft:** die Registry **startet leer → jeder Accessor gibt File zurück** → NICHTS ändert sich, bis ein Operator bindet (genau die INERT-Sicherheit wie CYP-427).
2. **Consumer-Rewiring auf per-op-Provider (W2/W3):** heute injiziert der Boot jeden Store **einmal als Singleton** (RemoteTokenIssuer, config-routes, spawn-key-resolution, sessions, deliveryLog, eventSink, agentEventStore, agentOverrides, channelShares, reportStore). Ein gecachter Handle sieht **nie** einen post-boot `MIGRATING`-Flip → Consumer müssen einen `(projectId)->Store`-Provider halten, der **pro Op** den `StoreRouter` fragt. **Nicht gemacht** (der load-bearing Teil).
3. **Admin-Bind/Migrate-API + echter Migrations-Run:** keine Route konstruiert `StoreMigrator` oder exponiert DSN-create/bind/migrate. Der Operator-Einstieg `bind → MIGRATING → StoreMigrator.migrate → verify → ACTIVE` **existiert nicht**.
4. **Admin-DB-Metrics nicht gemountet:** `AdminMetricsRoutes` + `DsnRegistryMetricsProvider` existieren, sind aber **nicht in Live-Routing registriert** (DARK).
5. **KMS-Client-Registrierung:** `MasterKeySource.Kms` erst nutzbar nach `KmsClients.add(...)`-Wiring.
6. **`avatar_blob`-Pg-Port** (§1-GAP).
7. **Health/Degraded-State:** `ConnectionProvider` **wirft** bei unerreichbarer/toter DB (kein graceful per-store-degrade wie Design §1.2 fordert) — **Gap** (siehe §4).

## 3. Aktivierungs-Reihenfolge/Gates (Vorschlag, Doc-19-§3.0-Analog)
Jeder Schritt ist ratifikations-gegatet + fail-closed; INERT vor dem Flip.
1. **W1 Boot-Infra (INERT):** DsnRegistry/BindingRegistry/ConnectionProvider/StoreRouter konstruieren (Registry leer → File-Fallback). **Zero observable change** — mergebar VOR jeder Auftraggeber-Aktivierung (wie CYP-427s INERT-Seams).
2. **W2/W3 Per-op-Provider (INERT):** Consumer auf `(projectId)->Store` umstellen. Weiterhin File (kein Binding) → zero change. Zahn: der Provider spiegelt einen Runtime-Binding-Flip (nicht boot-fixiert).
3. **Admin-API (operator-gated, INERT-bis-benutzt):** DSN-create (masked) + bind + migrate-trigger. Fail-closed: unerreichbare DB nie binden; Secret-Store nie ohne Cipher routen.
4. **Ein Low-Risk-Store zuerst** (z.B. `report` oder `agent_override` — klein, nicht auth-kritisch) durch den vollen Zyklus: bind→MIGRATING(409-Window)→copy→**verify(count+checksum)**→ACTIVE. **Retain A** bis Operator-Confirm → Rollback = rebind B→A trivial.
5. **Rest nach Volumen/Risiko** (event_log/agent_events = high-volume zuletzt). MUST_STAY_HOME nie.
6. **Flip = Auftraggeber-GO** (BYO-DB-Provisionierung + Bind-Anweisung an `deploy`) — **nie autonom**; DB-Provisionierung/Custody = Auftraggeber-Eskalation.

## 4. Auftraggeber-Entscheidungen (was zu ratifizieren ist)
Das DESIGN-Doc §-Decisions deckt das meiste; offen/hervorzuheben fürs Rückkehr-Paket:
1. **Master-Key-Pfad env-vs-KMS.** Heute nutzbar: `CYPPIE_MASTER_KEY` (box/env, MVP) + Passphrase. **KMS-Seam gebaut, Client-Registrierung deferred.** → **Reco:** env/box für Self-Host + MVP (wie die anderen `CYPPIE_*`-Secrets); **KMS für managed/Aiven** (Rotation/HSM, KEK verlässt KMS nie, User-PG hält nur `{wrapped_dek, ciphertext, aad}` = nutzlos ohne unser KMS). Auftraggeber wählt den Default-Pfad.
2. **BYO-DB-Custody.** Ein mitgebrachter Postgres (BYO tierOrigin) hält **verschlüsselte** Secret-Felder (per-Field-Cipher); der Master-Key bleibt **auf unserer Box/KMS, NIE im User-PG**. → **Reco ratifizieren:** BYO-PG bekommt nie Klartext-Secrets + nie den Master-Key (die relocation-proof-AAD + die File-Fallback-ohne-Cipher-Regel erzwingen das strukturell).
3. **MUST_STAY_HOME-Stores.** Settled im Code (8 + default-home): roles/account/dsn_registry/store_binding/migration_audit/mcp_config/free_fallback_toggle/quota_usage. → **Reco bestätigen:** Auth/Identity/Bootstrap bleiben lokal (nie auf einem BYO-PG des Nutzers) — der Trust-Root.
4. **Migrations-Rollback-Fenster.** Read-only-Window (409 auf Writes) während COPY+VERIFY; **A wird NIE gedroppt** vor Operator-Confirm → Rollback = rebind B→A (Daten noch auf A). Offen: **wie lang darf das 409-Window sein** (high-volume-Store = längeres Window = längere Write-Sperre)? → **Reco:** per-Store-Confirm + ein Max-Window-Timeout (auto-rollback bei Überschreitung); DECOMMISSION(A-drop) = separater, expliziter Operator-Schritt.
5. **Free-Fallback-Quota-Zahlen** (Design §8, offen): die konkreten Row/Size-Limits für den Free-Home-Fallback + dual-write-vs-read-only-window (Code = read-only-window). → Auftraggeber-Produkt-Call.

## 5. Fail-closed-Achsen (matcht UIUX HA/HC/HF)
| Achse | Stand | Grund |
|---|---|---|
| **Checksum-Verify vor Cutover** (HC) | **GEBAUT** | count + SHA-256, Mismatch → abort + rollback (`StoreMigrator:69-87`). |
| **Secret nie zurück-gerendert** (HF) | **GEBAUT** | masked Views (`apiKeyView`/`repoView`); der `MigrationGated*`-Decorator bewahrt den masked-Contract im Window. |
| **Secret nie auf Pg ohne Cipher** | **GEBAUT** | `StoreRouter` File-Fallback wenn `cipher==null` — ein Secret-Store landet nie unverschlüsselt auf einer User-DB. |
| **Residency fail-closed** | **GEBAUT** | Allow-List default-home (`TierPolicy:53`), erzwungen am einen Zugriffspunkt `PgStoreRouting.activeDataSource`. |
| **Write-Loss im Window** | **GEBAUT (inert)** | `store_migrating` 409 auf alle Mutations, per-op re-evaluiert. |
| **★ Unerreichbare DB nie binden / graceful degrade** (HA) | **PARTIAL — GAP** | Unbekannte DSN → `IllegalStateException`; Pool-Timeout 10s. Aber **kein Health-Probe/per-store-degraded-State** (Design §1.2) — eine tote Instanz wirft an der ersten Nutzung statt graceful zu degradieren. **Vor Live schließen** (Health-Probe beim Bind + degraded-Fallback-auf-A). |

## 6. Gaps zu schließen vor/bei Phase-7 (die konkrete Bau-Liste bei GO)
- **W1/W2/W3-Boot-Wiring** (Infra-Konstruktion + per-op-Provider) — INERT, zero-behavior-change, mergebar vor der Aktivierung.
- **Admin-Bind/Migrate-API** (operator-gated) + `AdminMetricsRoutes` mounten.
- **`avatar_blob`-Pg-Port** (der eine capable-aber-nicht-geportete Store).
- **Health/Degraded-State im `ConnectionProvider`** (HA-Achse) — Health-Probe beim Bind, degrade-auf-A statt throw.
- **KMS-Client-Registrierung** — nur falls der Auftraggeber KMS-Custody wählt (§4.1).

## 7. Scope / Ehrlichkeit
- **Design/Readiness-Scoping only** — kein Bau ohne Auftraggeber-GO. Das DESIGN ist ratifiziert-reif (`CYP-220-postgres-migration-design.md`); dieses Delta ist der **Aktivierungs-Weg** (built-vs-missing + Sequenz + Entscheidungen + fail-closed).
- **Der Vertical ist gebaut+INERT (0 caller)** — die Aktivierung ist reines Live-Wiring (analog CYP-513/CYP-427), kein neuer Design-Pass; die Bau-Unsicherheit ist niedrig, der Auftraggeber ratifiziert **Custody-Pfad + Aktivierungs-Sequenz + Rollback-Fenster**, dann ist es ein gegateter Wiring-Job.
