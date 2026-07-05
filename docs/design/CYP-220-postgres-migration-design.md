# CYP-220 — Per-Store BYO-Postgres Migration: Architecture & Security Design

> **Status: DESIGN / ratification artifact — NO CODE.** For Auftraggeber ratification before any implementation.
> Verified against current docs (Context7): Tink Java (encryption), Flyway (migrations), HikariCP/PG-JDBC.
> Compliance *classification* (controller/processor) is a legal question — this doc gives only the **technical
> residual surface**.

---

## 0. Scope & goals (as expanded by the Auftraggeber)

Not "all stores → one platform Postgres", but:

1. **BYO-DB + per-store instance choice** — each store binds to a *selectable, user-supplied* Postgres instance;
   our managed Aiven is only the **default**.
2. **Individually + anytime migratable** — store X: instance A→B, a **runtime switch from the UI**, no data loss.
3. **Secrets encrypted in PG** — API keys, remote tokens, **and the DSNs themselves** → key management, with the
   **master key NEVER in a user PG**.
4. **Avatar blobs → `bytea`; ReportStore persisted; per-DB UI explainer text.**
5. **Tier-driven load/compliance offloading** — non-paying users generate **minimal load on our DB** and we hold
   **as little of their data as possible** (reduce/offload the data-protection surface).

Design principle throughout: **the seam is per-store, not global.** A store is the unit of binding, migration,
encryption context, and residual-data classification.

---

## 1. Connection layer — a DSN registry + per-store binding (NOT one pool)

### 1.1 Named-DSN registry
- A **`DsnRegistry`**: named connection descriptors `{ dsnId, label, host/port/db/user, encrypted-password, sslmode,
  tier-origin (aiven-managed | byo), createdBy, createdAt }`. The password (and, at rest, the whole descriptor's
  secret parts) are **encrypted** (§3). The registry itself is a store — bootstrap-hosted (see residual, §7).
- **`StoreBinding`**: `{ storeKey, dsnId, schema, state (active | migrating | read-only), boundAt }`. One row per
  (projectId?, storeKey) — binding is **per-store**, and where a store is project-partitioned, **per (project,
  store)** so different projects can point a store at different instances.

### 1.2 Per-instance connection pools (HikariCP)
- One **`HikariDataSource` per distinct `dsnId`** (not per store) — pools are keyed by instance so N stores on the
  same BYO instance share one pool. A `ConnectionProvider.forStore(storeKey): DataSource` resolves
  `binding → dsnId → HikariDataSource` (lazily created, cached, closed on rebind/eviction).
- Pool sizing per instance is conservative for BYO (users' small DBs): low `maximumPoolSize` (e.g. 2–5),
  short `connectionTimeout`, `keepaliveTime`; validated with `connectionTestQuery`/`isValid`. Aiven default pool
  larger. Health: a failed instance surfaces as a **per-store degraded state**, never a whole-app crash
  (mirrors the connector `capability.degraded` posture).

### 1.3 Runtime switch WITHOUT data loss
The UI "switch store X to instance B" is a **migration + atomic rebind**, never a raw pointer flip:
1. **Provision + migrate schema** on B (Flyway, §4) — idempotent.
2. **Copy** A→B (§4) with the store's writes gated (§4.3 read-only window or dual-write).
3. **Row-count + checksum verify** (§4).
4. **Atomic rebind**: flip `StoreBinding.dsnId A→B` in one transaction on the *binding registry*; new
   `ConnectionProvider.forStore` lookups resolve B; drain A's pool.
5. **Retain A** (don't drop) until the operator confirms — rollback = rebind B→A (data still on A).

Fail-closed: any step error → binding stays on A, B left provisioned-but-unbound (a retry-safe no-op).

---

## 2. Store-seam interfaces (prerequisite for PG impls)

*(Signatures below are CONFIRMED against a live code inventory. The SHAPE — one interface per store, file-impl +
pg-impl behind it — is the ratification ask. Today most are concrete classes: extract an interface, the existing
JSON/atomic-move class becomes `FileX`, a new `PgX` implements the same interface. The boot factory picks the impl
from the store's `StoreBinding` (file = "instance A"/fallback; pg = a bound DSN).)*

| Store (file) | Confirmed seam methods | Persisted shape | PG table sketch |
|---|---|---|---|
| **ProjectConfigStore** (`boot/`) | `resolvedRepo · resolvedApiKey · repoView · apiKeyView · setRepo · setApiKey · remove` | repoUrl/branch + **apiKey (SECRET)** | `project_config(project_id PK, repo_url, repo_branch, api_key_ct bytea, key_ver, updated_at)` |
| **RemoteTokenStore** (`boot/`) + Issuer | `all · put · remove` / `issue · revoke` | agentId→**token (SECRET)** | `remote_token(project_id, agent_id, token_ct bytea, key_ver, issued_at, PK(project_id,agent_id))` |
| **AgentOverrideStore** (`boot/`) | `overrideOf · allFor · put · setAvatar · removeAgent · removeProject` | name/color/persona/launch/avatar (non-secret) | `agent_override(project_id, agent_id, name, color, persona, launch, avatar_json, PK(project_id,agent_id))` |
| **ChannelShareStore** (`comm/`) | `share · revoke · record · sharedInboundChannelIds` | channelId→{ownerProjectId, sharedWith:Set, consents:Set, sharedAt} (non-secret) | `channel_share(channel_id PK, owner_project_id, shared_with_json, consents_json, shared_at)` |
| **ProjectRegistry** (`boot/`) | `view · activeProjectId · projects · exists · create · rename · setActive · requireDeletable · drop` | {activeProjectId, projects:[{id,name}]} (non-secret) | `project(project_id PK, name, created_at)` + a singleton `active_project` row |
| **AvatarBlobStore** (`avatar/`) | `write · read · delete · deleteByProject` | re-encoded PNG bytes | `avatar_blob(project_id, agent_id, png bytea, ref, updated_at, PK(project_id,agent_id))` |

Also in migration scope:
- **EventSink** (SqliteEventSink) + **AgentEventStore** (SqliteAgentEventStore) — **already interface-seamed** (the
  easiest PG targets, just a new impl). **Highest write-volume + retention** (events append-heavy; transcripts keep
  last-N=2000/agent). `seq INTEGER AUTOINCREMENT` maps to PG `bigserial` (preserve gapless/monotonic). NB the events
  table's physical column is `team_id` (stores projectId) — keep the column name or migrate it.
- **ReportStore** — **⚠️ currently IN-MEMORY ONLY (ephemeral, lost on restart)**. The Auftraggeber's "persist the
  ReportStore" = **NEW persistence**, not a migration of an existing store → `report(project_id, report_id,
  snapshot_json, created_at)`. Interface `generate/list/get`.
- **SessionStore** (CYP-167 `(project,agent)→session_id`, interface-seamed) + **DeliveryLog** (CYP-132 delivered-id
  set) — small; fold into PG or keep local (trade-off §8). Session ids are not secrets.

**Invariant preserved:** every store stays **projectId-scoped and fail-closed** exactly as today — PG rows carry
`project_id`; the seam does not change the ACL/scope contracts (CYP-102/CYP-188). Corrupt-store handling (backup +
start-empty, never brick boot) must have a PG analogue (connect-fail → per-store degraded, not a boot crash).

---

## 3. Secret / DSN handling — envelope encryption, master key off the user PG

**Threat model:** a user's BYO Postgres is **semi-trusted** — the user (and their DBA/host) can read every row.
So **no plaintext secret may ever rest in a user PG**: not API keys, not remote tokens, not the DSN passwords of
*other* instances. The only defense is encryption whose **key the user does not hold**.

### 3.1 Library — **Google Tink (Java)**, AEAD envelope encryption
Verified (Context7, JAVA-HOWTO): `KmsEnvelopeAeadKeyManager.createKeyTemplate(kekUri, AES256_GCM)` + `KmsClients`
(`gcp-kms://` / `aws-kms://` / HashiCorp Vault) → `Aead aead = handle.getPrimitive(Aead.class)` →
`aead.encrypt(plaintext, associatedData)`. Why Tink over hand-rolled JCA: misuse-resistant, key rotation +
envelope built in, AEAD (integrity, not just confidentiality), AAD binding.

### 3.2 Where the master key (KEK) lives — NEVER a user PG
Two supported placements, operator-selected:
- **Cloud KMS (recommended for our managed/Aiven default):** KEK is a `gcp-kms://…`/`aws-kms://…` key. Our box
  holds only *credentials* to call KMS; the key material never leaves KMS. Envelope: a per-DEK is generated,
  KMS-wrapped, stored alongside the ciphertext; decrypt needs a KMS call. **The user's PG holds only
  `{wrapped_dek, ciphertext, aad}` — useless without our KMS.**
- **Box-local master keyset (self-hosted, no cloud KMS):** a Tink keyset encrypted at rest by a **bootstrap key
  from the environment** (`CYPPIE_MASTER_KEY`, out-of-repo, box-scoped, 0600, injected like the existing
  `ANTHROPIC_API_KEY`/operator-token). Lives on OUR box only. Same "never in a user PG" guarantee.

### 3.3 What is encrypted, and the AAD binding
- **Encrypted at rest:** API keys, remote tokens, **and every stored DSN password/secret** (the DSN registry's own
  secrets). AAD = a context string `"{storeKey}|{projectId}|{field}"` so a ciphertext **cannot be relocated** to a
  different row/store (Tink verifies AAD on decrypt → fail-closed).
- **The `mcp/` config dir is also secret-bearing** (per-agent token-bearing `--mcp-config` files, 0600 today,
  written by `HubMcpConfigWriter`). It is consumed by a spawned *local* process from the filesystem, so it is NOT a
  natural PG-row store — it stays a **local, box-only artifact** (a token in a user PG that the user can read defeats
  its purpose). Classify `MUST_STAY_HOME` / local-only (§7.7).
- **Never:** plaintext in a column, a log line, a UI render (masked `***last4` only — reuse the CYP-96/CYP-104
  masking + constant-time floor from [[cyp181-p2-progress]]), or the repo.
- **DSN chicken-and-egg:** the DSN *of a user store* is itself a secret encrypted with our KEK, held in the
  **DSN registry**, which is a **bootstrap store that must live on OUR infra** (see §7 residual) — you cannot store
  the key to a user DB *inside* that same user DB.

### 3.4 Rotation
Tink keyset rotation (add a new primary key, keep old for decrypt) → a background re-encrypt pass per store. Design
includes a `key_version` column so mixed-version ciphertext decrypts during rotation.

---

## 4. Migration mechanics — per-store copy A→B, verified, idempotent, rollback-safe

### 4.1 Schema — **Flyway**, programmatic, per-datasource
Verified (Context7): `Flyway.configure().dataSource(ds).load().migrate()` runs versioned SQL migrations against a
**runtime-supplied** DataSource; `baselineOnMigrate=true` + `baselineVersion` **baselines a non-empty BYO schema**
(the user may bring a DB with unrelated tables). Migrations live in `db/migration/` per store-family; each newly
bound instance is migrated at bind time (idempotent — re-running on an up-to-date DB is a no-op).

### 4.2 Copy A→B
Per store, stream rows A→B in batches (keyset pagination on the PK), **re-encrypting secrets under the target
context if the AAD changes** (it shouldn't — AAD is store+project+field, instance-independent — so ciphertext
copies verbatim; DEKs stay valid). Blobs (`bytea`) copy as-is.

### 4.3 In-flight writes — the read-only window (or dual-write)
- **MVP (simplest, honest):** a brief **read-only window** on that store during copy+verify (the store rejects
  writes with a typed `store_migrating` 409; reads continue from A). Small stores (config/registry/overrides) →
  sub-second. Blob/event stores → sized + surfaced in the UI ("Store X migrating, read-only ~Ns").
- **Later option:** dual-write (writes go to A **and** B during copy) → no window, more complexity + a
  reconciliation edge. Documented as a trade-off (§8), not MVP.

### 4.4 Verify + idempotency + rollback
- **Verify:** `COUNT(*)` per table A vs B **and** a content checksum (e.g. `md5(string_agg(...))` ordered by PK, or
  a per-row hash roll-up) — count-only can miss corruption. Mismatch → abort, stay on A.
- **Idempotent:** re-running a half-done migration resumes (target rows upserted by PK); Flyway schema is
  convergent.
- **Rollback:** trivial because A is **retained** until operator-confirmed — rollback = rebind B→A. Only an
  explicit, separate "decommission A" drops A after confirmation.

---

## 5. Audit

An append-only **`store_migration_audit`** (on OUR infra — it must survive a user-DB loss and is evidence):
`{ id, actor (operator identityId), ts, storeKey, projectId?, fromDsnId, toDsnId, phase (schema|copy|verify|rebind|
decommission|rollback), result (ok|failed), rowCounts{a,b}, checksumMatch, error? }`. Also audit **DSN
create/edit/delete** and **rebind** events. **No secret values** in audit (dsnIds + masked host only). Emitted
through the existing Event-Log seam where user-visible, but the durable audit table is bootstrap-hosted.

---

## 6. Schema tooling & driver

- **Driver:** `org.postgresql:postgresql` (JDBC), pinned. **Pool:** `com.zaxxer:HikariCP`, one `HikariDataSource`
  per instance (§1.2). **Migrations:** `org.flywaydb:flyway-core` + `flyway-database-postgresql`.
- Kotlin/Ktor: no ORM needed — thin JDBC in each `Pg*` store (matches the current hand-written sqlite stores);
  SQLDelight remains the documented KMP upgrade path (Spec §15) but is **not** required for server-only PG.
- Ktor is unaffected (stores sit behind their seams; the JDBC calls run on `Dispatchers.IO`).

---

## 7. Tier→DB policy + residual-data analysis (the offloading dimension)

### 7.1 Tier → DB policy — **DECIDED: minimal-fallback quota for Free (NOT BYO-mandatory)**
| Tier | Store DB placement | No-DB behavior |
|---|---|---|
| **Free** | **Minimal managed-DB fallback by default**; BYO-DB offered + encouraged (the offloading path). | A Free user with no BYO-DB runs on a **quota-capped slice of our managed DB** (§7.2). BYO is a one-click upgrade (§7.5), never forced by default. |
| **Paid** | **Managed (Aiven) default**, BYO allowed. | N/A — managed instance always present. |

The policy is **per store** (a Free user always needs the bootstrap stores on our infra — §7.6), enforced at the
`ConnectionProvider`/binding layer, fail-closed. The fallback is a real (small) tenant on managed PG, not a local
file — so it participates in the same seam/migration path (a Free user who later brings a DB just migrates A→B, §4).

### 7.2 Free-fallback quota — definition + enforcement
- **"Minimal" =** a per-account (recommended) storage + row cap across the user-DB-capable stores on managed PG.
  Concretely (numbers to ratify): e.g. **≤ N MB total** and/or **per-store row caps** (events the dominant term →
  a tight event/transcript retention for Free, e.g. keep-last-K much lower than the paid 2000, + a hard row ceiling).
  Cap is **per account** so one user can't fan out across projects to evade it (per-project would be gameable).
- **Metering:** a `quota_usage(account_id, bytes_used, rows_used, updated_at)` roll-up, refreshed on write and by a
  periodic `pg_total_relation_size`/`COUNT(*)` sweep (cheap, scoped to the account's rows).
- **Enforcement on exceed (graduated, fail-safe = never lose data):**
  1. **Soft (approaching cap):** UI banner + CTA to bring a DB (§7.5). Writes continue.
  2. **Hard (at cap):** **new writes to user-DB-capable stores rejected** with a typed `free_quota_exceeded` (409);
     **reads + already-stored data stay fully available** (never delete-to-fit — data loss is not an enforcement
     tool). The one exception is *retention* (events/transcripts already trim by keep-last-K, which naturally
     bounds the dominant term without touching config/registry/etc.).
  3. **Resolution:** connect a BYO-DB → migrate off the fallback (frees the quota) → writes resume; or upgrade to Paid.
- Auth/identity/bootstrap stores (§7.7, `MUST_STAY_HOME`) are **exempt** from the quota — they're tiny + necessary;
  the quota governs only the offloadable operational stores.

### 7.3 Free-quota lock toggle (operator)
- An **operator-gated** durable flag `freeFallbackEnabled` (default **on** = grandfather-safe). When an operator
  turns it **off**: **new** Free accounts get **no fallback → BYO-DB required** at onboarding; **existing** Free
  accounts are **grandfathered** (keep their fallback) — recommended, avoids a retroactive lockout.
- The toggle governs the *new-account provisioning branch only* (a `createdBefore`/`grandfathered` marker on the
  account decides). **Fail-safe:** if the flag can't be read (store error), default to **on** (never accidentally
  lock out onboarding). Durable on our infra (bootstrap store), audited (who/when).

### 7.4 Admin DB-monitor (managed-DB metrics)
A read-only **operator** data path feeding a future admin-monitor window (no UI build here — just the seam +
metrics source):
- **Instance metrics** (managed/Aiven): active connections vs pool max, DB size / storage headroom, per-instance
  health (from HikariCP pool stats + `pg_stat_activity` / `pg_database_size`).
- **Free-quota metrics:** aggregate + per-account fallback consumption, count of accounts near/at cap, total
  fallback footprint (the "how much Free load are we carrying" number the Auftraggeber wants).
- Shape: `GET /api/admin/db/metrics` (operator-gated, no secrets — dsnIds + masked host only), backed by a
  `DbMetricsProvider` that reads pool stats + cheap PG catalog queries. Content-free / non-secret, like the reports.

### 7.5 Onboarding — BYO-DB affiliate-link **placeholder seam only** (NO affiliate build)
- Leave a **named seam** in the onboarding "bring a Postgres" step: `byoDbProviderLink(context): Url?` returning a
  configurable URL (env/config-driven), default a neutral "how to get a Postgres" docs link. A later
  referral/affiliate link drops into that config **without code change**. **No affiliate logic, tracking, or
  partner integration is designed or built** — that's an open business decision; this is purely the extension point.

### 7.6 Residual data — what UNAVOIDABLY stays on OUR infra (even with full BYO)
This is the **honest** offloading picture. Two classes:

**(A) Must-stay-with-us (not user-DB-capable) — bootstrap + identity:**
- **Kratos identity / PII** (email, credential hashes, verification/recovery state, OIDC links) — Kratos owns its
  own datastore (today Aiven Postgres, [[cyp181-p2-progress]]). This is the **largest unavoidable PII residual**;
  it cannot move to a user PG (it *is* the login boundary, and a user can't hold the identity store for their own
  auth). Storage load: low row-volume, high sensitivity.
- **RoleStore** (identityId→OPERATOR/MEMBER) — the authZ boundary; must be on our infra with Kratos. Low volume.
- **DSN registry + StoreBinding + master-key/keyset + migration audit** — the bootstrap layer that *points at* the
  user DBs; by construction cannot live inside a user DB (§3.3). Low volume, high sensitivity (encrypted DSNs).
- **`mcp/` per-agent token config** — local box-only artifact consumed by the spawned process; token-bearing, stays
  home (§3.3). Low volume.
- **Routing/tier metadata** — which projectId belongs to which account/tier, minimal account record. Low volume.

> Note: **ReportStore is ephemeral today** (in-memory) — it holds nothing at rest currently. Persisting it (§2) is
> additive; classify it `USER_DB_CAPABLE` (content-free report snapshots, non-secret) so it offloads like the rest.

**(B) User-DB-capable (offloadable):** ProjectConfig, RemoteToken, AgentOverride, ChannelShare, ProjectRegistry
(the tenant's own project list), AvatarBlob, EventSink (highest volume), AgentEventStore (high volume), ReportStore,
Session/Delivery. **These are the bulk of per-user storage load** → moving them to BYO genuinely offloads our DB.

### 7.7 Per-store marker (drives the binding)
Each store carries a static **`residency` marker**: `USER_DB_CAPABLE` vs `MUST_STAY_HOME`. Auth/RoleStore/DSN-
registry/audit = `MUST_STAY_HOME`; the six + event/report/session = `USER_DB_CAPABLE`. The binding layer refuses a
BYO binding for a `MUST_STAY_HOME` store (fail-closed) and the UI only offers instance choice for capable stores.

### 7.8 Honest offloading statement (for the Auftraggeber)
BYO-DB moves the **high-volume, per-tenant operational data** (events, transcripts, avatars, configs) off our
infra — the real load win. It does **NOT** move **identity/PII (Kratos) or the authZ/bootstrap layer**, which stay
home by necessity. So "we hold minimal user data" is true for *operational* data, but **identity PII remains our
residual** — the data-protection surface shrinks but is not eliminated. (Controller/processor classification of
that residual = legal, out of scope here.)

---

## 8. Open trade-offs (for Auftraggeber decision)

1. **Free-fallback quota numbers (model DECIDED — §7.1/§7.2):** the open part is the *values* — the total-MB cap,
   per-store row caps, and the Free event/transcript retention-K (much lower than paid 2000). Auftraggeber to set
   the actual limits; the enforcement mechanics (soft→hard→resolve, never delete-to-fit) are fixed.
2. **In-flight writes:** read-only window (MVP, simple) vs dual-write (no window, complexity + reconciliation).
3. **Per-project vs per-account BYO granularity:** bind per (project,store) — max flexibility — vs per-account —
   simpler UX. Recommend per (project,store) with an account-level default. (Quota is per-account regardless — §7.2.)
4. **Event/transcript stores to BYO:** biggest load win but biggest migration (volume) + the user's DB perf becomes
   our UX; consider tight Free-fallback retention (§7.2) + BYO-optional, managed-default on Paid.
5. **Master key placement:** cloud KMS (rotation/HSM, a cloud dep) vs box-local keyset (self-contained, we own the
   key custody). Recommend KMS for managed, box-local for self-host.
6. **Kratos residual:** accept identity PII stays home (recommended, unavoidable) vs a future self-hosted-Kratos-
   per-tenant exploration (large, likely out of scope — flag only).
7. **SQLDelight vs raw JDBC** for the Pg impls: raw JDBC (matches today, minimal dep) vs SQLDelight (typed, KMP-
   aligned, more setup).

---

## 9. Recommended phasing (once ratified — NOT part of this doc's ask)
Seam-interfaces first (no behavior change) → DSN registry + binding + KMS/encryption spine → one low-risk store as
the vertical slice (e.g. ProjectRegistry) → migration engine + verify/rollback → tier policy + residency markers →
remaining stores by volume. Each phase reviewer-gated + teeth, as usual.
