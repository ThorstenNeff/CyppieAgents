# CYP-220 Live-Bind-Wiring — decomposition (the must-close-before-live-migration slice)

**Goal:** make every latent P6 guard (S1 residency, S3 migration-freeze) actually FIRE by routing all
store access **per-op** through the `PgStoreRouting` accessors, instead of the boot-cached File/SQLite
handles. Today the Pg routing layer is built but has **0 prod callers** — so none of the guards run in prod.

## The core problem
BootOrchestrator constructs each store ONCE and injects the singleton into its consumer
(`remoteTokenStore`→`RemoteTokenIssuer`; `projectConfig`→config routes + spawn; `sessions`, `deliveryLog`,
`eventSink`, `agentEventStore`, `agentOverrides`, `channelShares`, `reportStore`). A cached handle would
**never see a post-boot MIGRATING flip** (the S3-finding bypass). Fix: consumers hold a **per-op provider
`(projectId) -> Store`** (or call the router each op), so residency + window are re-evaluated every access.

## The seam: `StoreRouter`
A boot-constructed facade holding the DB infra and delegating to `PgStoreRouting`:
- `DsnRegistry(file, cipher)` — DSNs (encrypted passwords), file under gitRoot/.cyppie.
- `BindingRegistry(file)` — per-(storeKey,projectId) bindings, file under gitRoot/.cyppie. **Starts EMPTY**
  → `activeDataSource` always null → every accessor falls back to File. **INERT: zero behavior change**
  until an operator binds a store (admin API = a later slice, NOT here).
- `ConnectionProvider(dsns, bindings)` — one HikariDataSource per DSN instance.
- `SecretCipher` — from master key `CYPPIE_MASTER_KEY` env (→ `MasterKeySource.Box`) or KMS. **Fail-safe:
  absent key → secret-store accessors stay File** (never route a secret store to Pg without a cipher).
- Accessors: `remoteTokenStore/projectConfigStore/agentOverrideStore/channelShareStore/sessionStore/
  deliveryLog/eventSink/agentEventStore/reportStore(projectId, fileFallback)` — each binds the
  (bindings, connections, cipher, …) and delegates to `PgStoreRouting`. Heavy stores (event/agent_events/
  report) take a MEMOIZING pg-factory (per-DataSource instance), re-selecting gating per-op.

## Sub-slices (each PO-gated; seam first)
- **W1 (seam, THIS sub-slice):** build `StoreRouter` + inert boot construction (files + master key) + the
  **combined enforcement test** on the router (bind→MIGRATING→ next write 409; **fetch-before-flip /
  write-after-flip → 409** = per-op re-resolution catches the flip a cached handle would miss; residency:
  MUST_STAY_HOME never routes to a user DB in the live path). NO consumer rewiring → no behavior change.
- **W2 (secret stores):** rewire `RemoteTokenIssuer` (issue/revoke) + config PUTs (`/api/config/*`) + spawn
  key resolution to per-op providers via the router. Enforcement at the real write paths.
- **W3 (non-secret + volume):** agent_override, channel_share, session, delivery, event_log, agent_events,
  report — per-op providers at their write paths. Heavy stores: memoized instance, per-op gating re-select.
- **(later, NOT this epic):** admin bind/migrate API + the actual File→Pg migration run.

## Test spine (combined enforcement)
1. post-boot MIGRATING flip → next write through the router → `store_migrating` 409.
2. fetch-before-flip / write-after-flip: resolve handle (ACTIVE→Pg), flip MIGRATING, **re-resolve per-op** →
   gated → 409 (proves per-op beats a cached handle).
3. MUST_STAY_HOME store bound+ACTIVE → router still returns File/null (residency, live path).
4. inert: no bindings → every accessor returns the File fallback (no behavior change).
