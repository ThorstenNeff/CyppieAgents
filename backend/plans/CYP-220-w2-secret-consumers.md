# CYP-220 W2 — Secret-store consumer rewiring (per-op via StoreRouter)

**Goal:** make S1 residency + S3 read-only-freeze **load-bearing in prod for the secret stores** by having
their WRITE consumers fetch the store **per-op** through the `StoreRouter` (W1), instead of a boot-cached
handle. Stays **INERT** (no bindings → File) until an operator binds via the Phase-7 admin DSN API.

## Two consumers to rewire (WRITE paths only — the 409-enforcement points)
1. **`RemoteTokenIssuer`** (`boot/RemoteTokenStore.kt`) — `issue()→store.put`, `revoke()→store.remove`.
2. **ProjectConfig PUTs** — `setApiKey`/`setRepo` (the `/api/config/*` routes; find the route file + how it
   holds `booted.projectConfig`).

## Step 0 — construct the DB infra + StoreRouter in boot (INERT), like W1's test but wired
In `BootOrchestrator.boot()` (near the other store construction) + supplied paths in `PlatformWiring`:
- `DsnRegistry(gitRoot/.cyppie/dsn-registry.json, cipher)`  (nullable file param → in-memory for tests)
- `BindingRegistry(gitRoot/.cyppie/store-bindings.json)` — **starts EMPTY** → all File (inert)
- `ConnectionProvider(dsns, bindings)`
- `cipher: SecretCipher?` from **`CYPPIE_MASTER_KEY`** env → `MasterKeySource.Box(it)`; **absent → null**
  (fail-safe: secret stores stay File — never plaintext on a user DB). KMS variant later.
- `val storeRouter = StoreRouter(bindings, connections, cipher, secrets, config.repo)`
- Add `reportFile`-style nullable ctor params so tests inject null (in-memory/no-router → File fallbacks).

## Step 1 — RemoteTokenIssuer per-op (SMALL)
```kotlin
class RemoteTokenIssuer(
    private val registry: TokenRegistry,
    private val store: () -> RemoteTokenStore,   // was: RemoteTokenStore — now a per-op provider
) {
    fun issue(agentId): String { val t = registry.mint(agentId); store().put(agentId, t); return t }
    fun revoke(agentId) { registry.revoke(agentId); store().remove(agentId) }
}
```
Boot: `RemoteTokenIssuer(tokenRegistry) { storeRouter.remoteTokenStore(config.projectId) { fileRemoteTokenStore } }`
(the `fileRemoteTokenStore` = the existing boot-constructed `FileRemoteTokenStore`, the fallback + source A).
Tests that construct the issuer with a fixed store: pass `{ theStore }`.

## Step 2 — ProjectConfig PUTs per-op
The config routes call `projectConfig.setApiKey/setRepo`. Rewire the WRITE handlers to resolve per-write:
`storeRouter.projectConfigStore(projectId) { fileProjectConfig }.setApiKey(...)`. Reads (repoView/apiKeyView/
resolvedRepo/resolvedApiKey at spawn/clone/cascade) may stay on the File handle for W2 (the 409-freeze is the
WRITE guard; a read during the window already reads File=source-A). Confirm the route file + thread the router
(or a `projectConfigProvider: (projectId)->ProjectConfigStore`) into it.

## Teeth (server test, embedded PG)
- **post-boot MIGRATING flip → next `issue()`/`revoke()` → `store_migrating` 409** (the consumer holds NO handle
  across the flip — per-op re-resolve catches it). Same for `setApiKey`/`setRepo`.
- **restart-decrypt still green** (bind ACTIVE, issue → token on Pg encrypted; reopen → decrypts).
- **residency**: remote_token is user-DB-capable so it routes; assert a MUST_STAY_HOME store never would (already
  in S2 — re-assert at the router if cheap).
- **inert**: no bindings → issue/revoke/setApiKey all hit File (no behavior change) — the default path.
- Mutation: rewire consumer back to a cached handle (hold `store` not `()->store`) → flip-then-write does NOT
  409 (writes to the stale Pg/File) → RED. This is THE W2 money tooth (proves per-op beats cached handle at the
  real consumer).

## SECURITY watch (secret path)
- Never log a token/key. The File fallback stays 0600. `CYPPIE_MASTER_KEY` from env only, never repo/logs.
- Fail-safe: null cipher → File (StoreRouter already enforces). Don't let a bound secret store reach Pg without
  the cipher.
- Deploy note: `CYPPIE_MASTER_KEY` must be set in the deploy env for a secret store to EVER be Pg-offloadable;
  absent = permanently File (safe). Document for the deploy agent.
