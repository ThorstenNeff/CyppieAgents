# CYP-565 — Dogfood pre-deploy ENV & config runbook (server)

> The pre-deploy gate for the M1+M2 dogfood (controlled personal-staging window). Every value below was classified at
> the code on develop `26605c59` (the `?: return Inert…` / boot `missing +=` pre-flight / `?: <default>` fallbacks are
> the ground truth). Each REQUIRED var carries a concrete **VERIFY** line — the log marker / curl / observation that
> proves *present + effective*, not merely *set*.

## 0. The one principle that governs this whole runbook

**Fail-closed here means SILENT INERT, not a crash.** A missing REQUIRED var does not stop the process — it leaves the
corresponding surface INERT (no live remote-hub, no live mint). So a green exit code / "server booted" proves nothing
about the remote datapath. **"Deploy is through" = the active VERIFY lines below pass, not the exit code.** The
headline verification is:

1. Boot log shows **no** `hub self-admit INERT` warning (the `missing:` list is empty).
2. Boot log shows `hub self-admitted to CP as owner … now discoverable in GET /api/cp/hubs`.
3. A one-shot mint→RR3-verify round-trip is green (proves `CYPPIE_CP_SIGNING_SEED` ↔ `CYPPIE_CP_PUBKEY` are one keypair).
4. All REQUIRED groups A/B/C present.

Boot-log markers to grep (logger names in parentheses):
- `cyp530.selfadmit` WARN `hub self-admit INERT — the hub will NOT appear in GET /api/cp/hubs; missing: {…}`
- `cyp530.selfadmit` INFO `hub self-admitted to CP as owner {ownerId} — hubId {…} now discoverable in GET /api/cp/hubs`
- `BootOrchestrator` WARN `CYP-524 hub admission not granted: {reason}`
- `cyp525.rr3gate` WARN `operator anchor unreadable (tampered/corrupt) — refusing ALL connects` ← the MASTER_KEY-instability tell (§C)

---

## A. Remote-hub admission — REQUIRED together (the boot pre-flight collects these into `missing`)

The boot pre-flight (`HubAdmissionClient.hubAdmissionInertReasons`) checks these together; ANY blank → the whole
self-admit is INERT and the `cyp530.selfadmit` WARN names which one(s).

| Var | Purpose | Missing → | VERIFY |
|---|---|---|---|
| `CYPPIE_CP_URL` | control-plane base URL the hub registers/admits against | admission INERT | in the boot WARN `missing:` list ⇒ **not** present; positive: the `hub self-admitted … now discoverable` INFO fired |
| `CYPPIE_CP_OPERATOR_TOKEN` | operator bearer for `/cp/admit` + `/cp/register` (usually == the deploy `OPERATOR_TOKEN`) | admission INERT | `curl -H "Authorization: Bearer $CYPPIE_CP_OPERATOR_TOKEN" $CYPPIE_CP_URL/api/cp/hubs` → 200 + the hub listed (401/403 ⇒ wrong/absent token) |
| `CYPPIE_OPERATOR_ID` | the operator identity; SINGLE-SOURCED for admit owner-binding AND `/api/cp/hubs` discovery scope | admission INERT + discovery empty | the self-admit INFO prints `as owner {CYPPIE_OPERATOR_ID}`; `GET /api/cp/hubs` returns the hub under that owner. It MUST match the id discovery scopes by, else the hub self-admits but is invisible to `/hubs` |

**VERIFY (group A, one shot):** `curl -sf -H "Authorization: Bearer $OPERATOR_TOKEN" http://<hub>/api/cp/hubs | jq '.[].hubId'` returns the hub id. Empty array ⇒ INERT ⇒ read the boot WARN for the missing var.

---

## B. CP hubTicket mint + relay connector — REQUIRED for the live remote datapath (else Inert)

| Var | Purpose | Missing → | VERIFY |
|---|---|---|---|
| `CYPPIE_REMOTE_RELAY_URL` | the Phase-2-Remote GO gate (relay WS URL) | `InertHubTicketMinter` **and** `InertRelayConnector` — no remote hub at all | absence ⇒ mint returns `NOT_AUTHORIZED_FOR_HUB` for every request (see roundtrip below) |
| `CYPPIE_CP_SIGNING_SEED` | CP Ed25519 signing seed (base64, **exactly 32 bytes**) for the CpJwt mint | `InertHubTicketMinter` (no mint) | roundtrip below mints a token (non-null `cpJwt`); a non-32-byte seed ⇒ Inert ⇒ mint denied |
| `CYPPIE_CP_KID` | CP signing key id (JWT `kid`) | Inert (mint + relay) | the minted JWT header `kid` == this value |
| `CYPPIE_CP_ISSUER` | CP JWT issuer (`iss`) | Inert (mint + relay) | the minted JWT claim `iss` == this value |
| `CYPPIE_CP_PUBKEY` | the CP public-key pin the HUB verifies inbound CpJwts against (base64) | `InertRelayConnector` | roundtrip: a real client tunnel authorizes at RR3 (see below) |
| `CYPPIE_OPERATOR_RP_ID` | the operator-device RP id (PoP / Fido2 rpId, RR3) | `InertRelayConnector` | the operator device enrolls + a tunnel authorizes (a wrong rpId ⇒ PoP fails ⇒ `auth_failed`) |

**VERIFY (group B — the mint→RR3 round-trip, proves SEED ↔ PUBKEY = one keypair):**
`CYPPIE_CP_SIGNING_SEED` (CP side, mints the CpJwt) and `CYPPIE_CP_PUBKEY` (hub side, verifies it) are a **keypair** — a
mismatch is invisible at mint time (mint only uses the seed) and only bites at the hub RR3 gate. The observable proof:
1. Mint a ticket: `curl -sf -H "Authorization: Bearer $OPERATOR_TOKEN" -X POST http://<cp>/api/cp/hubticket -d '{"hubId":"<hubId>","cb":"<cb>"}'` → returns `{ cpJwt: "…" }` (a `NOT_AUTHORIZED_FOR_HUB`/`CP_SESSION_EXPIRED` failure ⇒ group B is Inert or the operator doesn't own the hub).
2. A **real client tunnel authorizes** (the desktop/web client connects over the remote hub). If SEED↔PUBKEY match, RR3 authorizes and the client connects. On mismatch, RR3 rejects with the uniform `auth_failed` and the client never connects — grep the hub log for repeated RR3 rejects with a good CpJwt as the tell.

**Isolated cryptographic proof — when no standalone Noise client is on hand (as at the B1 deploy).** The keypair check
does NOT require a full tunnel: mint a CpJwt with the live `CYPPIE_CP_SIGNING_SEED`, then **verify its Ed25519 signature
against the live `CYPPIE_CP_PUBKEY`** — a valid signature ⟺ the two are a matching keypair. This IS exactly the RR3
gate's own check (`CpJwtVerifier` verifies the CpJwt signature against `cpPublicKey(kid)` = `CYPPIE_CP_PUBKEY`), so it
settles the mint-invisible SEED↔PUBKEY silent-fail *more directly* than a full tunnel. The full end-to-end (live-`h`
channel-binding + nonce + operator PoP) is a separate superset — proven by the CI test below and re-confirmed by the
Dogfood first-connect.

> CI equivalent (no staging): `Cyp536LiveRelayLoopbackE2eTest` exercises the same real-relay datapath (RR3→200+roster).

---

## C. Hub secret custody — `CYPPIE_MASTER_KEY` (REQUIRED for prod, and it MUST BE STABLE)

`CYPPIE_MASTER_KEY` (base64 keyset) backs the S-B `SqliteSecretStore` (`.cyppie/hub-secrets.db`, `EnvKeysetMasterKeyCustody`)
that seals the hub identity keys AND the operator device-enrollment anchor + finalized backup codes. It has **two distinct
fail-modes** — the second is the tricky, B1-relevant one:

**C.1 — ABSENT/blank → hub INERT on boot 1 (LOUD).** `hubSecretStore = null` → `hubIdentity = null`, `operatorDeviceStore = null`,
and the pre-flight lists `CYPPIE_MASTER_KEY (local-hub custody)` in the self-admit INERT WARN. The hub never goes live.
VERIFY: covered by the group-A self-admit check (its absence shows in the `missing:` list).

**C.2 — PRESENT but UNSTABLE/CHANGED between boots → operator LOCKOUT on the 2nd boot (SILENT until the restart).**
This is the dangerous one. On boot 1 the operator device-enroll blob is sealed **under boot-1's key** and persists in
`.cyppie/hub-secrets.db`. If boot 2 comes up with a **different** `CYPPIE_MASTER_KEY` (e.g. the deploy regenerates it per
container start instead of persisting one fixed value), the store's canary/decrypt fails against the boot-1 blob →
`SecretCipherException` → the RR3 gate reads the operator anchor as **unreadable** → `cyp525.rr3gate` WARN
`operator anchor unreadable (tampered/corrupt) — refusing ALL connects, OOB recovery required` → **the enrolled operator
is locked out of every tunnel** until OOB recovery. Boot 1 looked perfectly healthy; the 2nd boot bites.

- **Root rule:** `CYPPIE_MASTER_KEY` must be a **single, fixed, persisted** value across the hub's entire lifetime —
  never regenerated per restart/container, never rotated without a planned re-enroll. Store it out-of-repo (secret
  manager / host env), never in the image.
- **VERIFY (do this before the dogfood):** enroll the operator device on boot 1 → **restart the hub** → confirm the
  operator still connects (no `operator anchor unreadable` WARN). A green restart proves the key is stable AND the blob
  persists. This restart-survives check is the concrete B1 gate for the sealed device-key blob.

---

## D. Optional (safe defaults — set only to override)

| Var | Default | Set when |
|---|---|---|
| `CYPPIE_OP_SESSION_TTL_MIN` | 15 (min) | longer/shorter op-session. **[CYP-563]** single-sourced → BOTH the tunnel-cap AND the hubTicket exp honor it (no drift). |
| `CYPPIE_COOKIE_SECURE` | `false` | **prod over TLS → set `true`** (marks the CSRF cookie `Secure`). **[CYP-563]** |
| `CYPPIE_RELAY_HOST` | `0.0.0.0` | bind the relay listener elsewhere |
| `CYPPIE_RELAY_PORT` | `8788` | relay port override |
| `CYPPIE_OPERATOR_TOKEN_DISABLED` | `false` | disable the static operator token (Kratos-only operator auth) |
| `CYPPIE_TERMINAL_DELEGATION_ENABLED` | `false` | enable terminal delegation |

> `CYPPIE_OP_SESSION_TTL_MIN` + `CYPPIE_COOKIE_SECURE` land with CYP-563 (their single-source / env-gate wiring + the
> `Secure` default). Defaults above are final once CYP-563 merges.
> **DO NOT set `CYPPIE_REMOTE_RENDEZVOUS`** — removed (the static rendezvous is gone; the CP mints the epoch-derived
> rendezvous SET now, CYP-536).

---

## E. Per-project secret (NOT a boot env)

- `ANTHROPIC_API_KEY` — resolved **per project/team** via the CYP-96 config store. **GET `/api/config/apikey` is
  PARTICIPANT-gated** (any authed agent/operator sees the masked status) and returns `{ set, masked }` — never the raw
  key; **PUT `/api/config/apikey` is OPERATOR** (secret-at-rest 0600, masked, never logged). A change takes effect on the
  **next agent spawn** (restart the agent).
- **[STANDING CREDS-POLICY]** The default/intended posture is **subscription-OAuth** — no API key is set without an
  explicit human instruction. In that posture `GET /api/config/apikey` legitimately returns **`{set:false}`** and this
  is GREEN, not a gap.
- **VERIFY:** a spawned agent **produces output (not an auth error)** — the operative check (7/7 RUNNING). A **masked**
  value only when a team explicitly opts into an API key instead of OAuth.

  *(Live-Referenz: B1 deploy `a3730297`, apikey→`{set:false}`, 7/7 RUNNING via Abo-OAuth = green. Corrected from the
  pre-live assumption `set:true`/operator-GET; §E-Policy-Wording authored by `deploy`.)*

---

## F. Kratos (deploy substitutes the `REPLACE_ME` template + injects OIDC)

- `deploy/kratos/kratos.reference.yml` DSN / cookie-secret / cipher-secret / base-URLs = `REPLACE_ME` → substitute real
  prod values (secrets out-of-repo). VERIFY: `grep REPLACE_ME` the deployed config returns nothing.
- `SELFSERVICE_METHODS_OIDC_CONFIG_PROVIDERS` — the WHOLE OIDC providers JSON array **including `client_secret`** is
  injected via this env (Kratos does not interpolate `${VAR}` in YAML). Omitting it FAILS CLOSED (Kratos validation error).
- `COURIER_SMTP_CONNECTION_URI` — prod SMTP (the reference default is dev mailpit, credential-free).
- **[CYP-562] `allowed_return_urls`** — substitute `REPLACE_ME_SPA_ORIGIN` with the EXACT web SPA origin; keep
  `http://127.0.0.1:47472/callback` verbatim. NEVER a wildcard/prefix/scheme-only. See
  `deploy/kratos/CYP-562-RETURN-URL-ALLOWLIST.md` (the `Rc2ConfigAssertion` merge-gate binds it).

---

## G. Security posture acknowledgement for THIS window

**OIDC silent-link → operator-takeover (HIGH, CYP-532/194):** the residual is **Auftraggeber-ACCEPTED for the dogfood
window only** (controlled personal staging; access controlled). NOT a waiver — **CYP-532/S1b stays gated + REQUIRED
before any broader/public exposure** (the pre-public-flip gate). No live Kratos OIDC change lands before the S1b probe +
Auftraggeber ratify.

---

## Pre-deploy verification sequence (run before the flip)

1. Boot the hub. Grep the log: **no** `hub self-admit INERT`; **yes** `hub self-admitted … now discoverable`. (§A/§0)
2. `curl -sf -H "Authorization: Bearer $OPERATOR_TOKEN" http://<hub>/api/cp/hubs` lists the hub. (§A)
3. Mint→RR3 round-trip: mint a ticket, connect one real client tunnel → it authorizes. (§B — proves SEED↔PUBKEY)
4. **Restart the hub; the enrolled operator still connects** (no `operator anchor unreadable` WARN). (§C.2 — MASTER_KEY stable + blob persists)
5. `grep REPLACE_ME` the deployed Kratos config = empty; OIDC providers env injected; return-URL allow-list = {SPA-origin, loopback}. (§F)
6. `CYPPIE_COOKIE_SECURE=true` iff serving over TLS. (§D)
7. `GET /api/config/apikey` = `{ set: false }` (subscription OAuth intended; `set: true` only if a BYO key was deliberately provisioned) AND the agents produce output (functional auth proof). (§E)
8. No secret value in any repo file / log / chat.

---

## Validated live (B1 deploy, 2026-07-14)

This runbook's 8-step sequence was run live on the first B1 deploy (`deploy` held the creds; read-only non-secret
markers arbitrated against runbook+code). **Result: 8/8 green — Deploy-VERIFY durch.** Notably: #3 settled via the
isolated cryptographic keypair proof (§B — mint-sig verifies under the live `CYPPIE_CP_PUBKEY`); #4 §C.2 disproven (0
`operator anchor unreadable` + MASTER_KEY-md5-stable → the sealed device blob decrypts after restart); #5 even tested an
attacker `return_to` rejects; #7 corrected to `{ set: false }` + OAuth (see §E). The observed-event layer (#3 full
tunnel, #4 post-restart connect, #7 agent output) is closed by the Dogfood first-connect as the live E2E.

---

## Appendix — paste-ready VERIFY commands

Secrets are NEVER hardcoded: set `STG` (the `/api` base host) + `OPT` (operator token) out-of-band at the deploy host,
never in a shared channel. "Deploy is through" = these steps green, not the exit code (fail-closed = silent INERT).

```bash
# Step 1 — self-admit (boot log)
grep -E 'hub self-admit INERT|hub self-admitted .* now discoverable' "$BOOTLOG"   # want the INFO, NOT the INERT WARN
grep 'CYP-524 hub admission not granted'                             "$BOOTLOG"   # want: empty

# Step 2 — /hubs lists the hub (operator) + no-auth fails closed
curl -sf -H "Authorization: Bearer $OPT" "$STG/api/cp/hubs" | jq '.[].hubId'      # want the hubId; [] => INERT (read Step 1)
curl -s -o /dev/null -w '%{http_code}\n'  "$STG/api/cp/hubs"                       # want: 401

# Step 3 — mint; then EITHER a real client tunnel connects OR verify the mint-sig against the live PUBKEY (§B)
curl -sf -H "Authorization: Bearer $OPT" -H 'Content-Type: application/json' \
     -X POST "$STG/api/cp/hubticket" -d '{"hubId":"<hubId>","cb":"<cb>"}' | jq .   # want {cpJwt:...}; {failure:...} => Inert/not-owned

# Step 4 — MASTER_KEY stable (enroll -> RESTART -> still connects)
grep 'operator anchor unreadable' "$BOOTLOG_AFTER_RESTART"                         # want: EMPTY

# Step 5 — Kratos config substituted
grep REPLACE_ME "$DEPLOYED_KRATOS_YML"                                             # want: EMPTY (+ allow-list {SPA, loopback})

# Step 6 — CSRF Secure over TLS
curl -sI "$STG/api/health" | grep -i '^set-cookie:.*cyppie_csrf'                   # want: contains 'Secure'

# Step 7 — API key per-project (participant GET -> masked; {set:false}=OAuth intended)
curl -sf -H "Authorization: Bearer $OPT" "$STG/api/config/apikey" | jq .           # want {set:false,...} (OAuth) or {set:true,masked:"..."} (BYO)

# Step 8 — no secret leak
grep -RiE 'ory_st_|ANTHROPIC|BEGIN .*PRIVATE KEY|MASTER_KEY=' "$LOGDIR"            # want: EMPTY
```
