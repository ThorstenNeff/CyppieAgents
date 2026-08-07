# Web-Cutover — Login Verification Checklist (PREP)

> Owner (prep + web-side verification): Dev5. **Status: PREP.** The live run is **deploy-owned, guided at a-po's P4** —
> Dev5 preps this checklist + coordinates the SPA/endpoint handoff (via the coordinator) and does **NOT self-run against
> the live target environment**. Grounded at develop `4c22e956` (2026-08-07).
>
> **★ ONE OPEN VARIABLE (flagged, blocks the flow-specific rows):** which SPA does the cutover serve? The gateway serves
> `CYPPIE_GATEWAY_SPA_DIR` (deploy-set). CYP-901 (web-native login) + CYP-903 (emitter-cookie-guard) are **composeApp
> (Compose-web) Kotlin** — so if the cutover points at the composeApp-WASM dist, the login flow verified is Team-1's
> (CYP-901 `nativeOidcLoopback`/`AuthFlip`); if it points at web-ts `dist/`, the login flow is web-ts's own (CYP-515/454)
> and the emitter guard in play is web-ts CYP-902 (not CYP-903). The HTTP/cookie SPINE below holds either way; rows tagged
> **[SPA-dep]** finalize once the target is confirmed.

## 0. Build-source verification (Dev5, at the object — DONE)
- `origin/develop` tip = **`4c22e956`**; **CYP-901 (`654e8cee`) + CYP-903 (`a8d05c6d`) both ∈ 4c22e956** (ancestor-verified),
  CYP-906 Edit merged. A build FROM develop is fresh — no pinned/stale source in-repo. **Confirm at handoff:** the cutover
  actually builds/copies the SPA from develop `4c22e956` (not a stale pre-built artifact), and which build feeds
  `CYPPIE_GATEWAY_SPA_DIR`.

## 1. Preconditions (from-clean)
| # | Precondition | Confirm |
|---|---|---|
| P1 | **Same-origin frontdoor** — SPA + API + Kratos all under `https://api.cyppie-agents.com` (one origin) | httpOnly session cookie precondition (CYP-454/515) |
| P2 | **Kratos fresh + EMPTY identity-DB** (Aiven-recovery SKIP, Auftraggeber decision) | the run is a **FIRST-login**, no existing account |
| P3 | SPA built from develop `4c22e956`; `CYPPIE_GATEWAY_SPA_DIR` → the intended build **[SPA-dep]** | §0 handoff |
| P4 | Target root access + pubkey-live signal present (P4-slot gate) | PL/Auftraggeber weiche |

## 2. Login e2e — FIRST-login (the happy path)
| # | Step | Expected ("green") |
|---|---|---|
| 1 | Load `https://api.cyppie-agents.com` from a **clean** browser (no cookies) | SPA loads; unauthenticated → login screen |
| 2 | Start GitHub login | **same-origin** redirect to Kratos → GitHub OIDC; **no browser loopback / no client-constructed redirect** (CYP-901) **[SPA-dep: Compose-web]** |
| 3 | Authorize on GitHub (first time for this fresh Kratos) | GitHub returns to Kratos same-origin |
| 4 | Kratos processes the OIDC callback | **a NEW identity is created fresh** (no returning-account path); passkey re-enroll if prompted |
| 5 | Return to the SPA authenticated | **httpOnly session cookie set** on `api.cyppie-agents.com`; SPA shows the authenticated/workspace state |
| 6 | An authenticated API/WS call fires | **200 + cookie carries** (no `Authorization` header needed; no `?token=`) |

**"GREEN" = a NEW identity cleanly created + session cookie set + authenticated state reached** — NOT a returning-account login.

## 3. Regression watches (must NOT happen)
| # | Watch | Why | Ref |
|---|---|---|---|
| R1 | **No redirect loop / no browser OAuth loopback** | the login blocker was Compose-web OAuth-loopback (dead only because of the old Kratos-Aiven DB) | CYP-901 |
| R2 | **Cross-origin ⇒ cookie does NOT come ⇒ login breaks** — verify the SPA/API/Kratos are genuinely same-origin | httpOnly cookie is origin-bound | CYP-454/515 |
| R3 | **No blank credential leaves the client** — no `Authorization: Bearer ` (empty) on REST, no `?token=` (empty) on WS; the cookie authenticates | token-less member build | CYP-903 (Compose) / CYP-902 (web-ts) **[SPA-dep]** |
| R4 | **No fabricated "returning account"** — a first-login must not silently reuse/assume a prior identity | fresh empty DB | P2 |

## 5. The two SPA branches — finalize ONE at handoff (per the confirmed `CYPPIE_GATEWAY_SPA_DIR`)

Both are held ready; a-po's SPA-dir confirmation selects one. The §1–§3 HTTP/cookie spine applies to both.

### (A) composeApp-WASM dist  — *evidence-lean (CYP-901/903 grounding + the earlier web-ts→WASM rollback)*
**PRIMARY (the gate):**
- **Login flow verified:** Team-1's Compose-web — same-origin Kratos browser flow, `nativeOidcLoopback` OFF on web (CYP-901,
  `App.kt`/`AuthFlip.kt`/`HttpAuthRepository.kt`); full from-clean first-login e2e (§2) against the target Kratos
  (Guardrail-6). Step-2 watch: **no client-constructed redirect / no browser loopback**.
- **Emitter guard in play:** CYP-903 (Compose `AgentManagement`/`AgentLifecycle`/`StatusMux` clients) → R3.
- **Dev5 role:** web-side **behavioral** verification, cross-strand — the login CODE is Team-1's.

**SECONDARY (conditional — ONLY if the source also serves web-ts at a sub-path, e.g. `/webts/`):**
- **Principle — no-dead-route:** the migration replicates the FULL source-serving arrangement, so whatever the source
  serves must stay reachable post-cutover. If the source serves `/webts/`, verify it did not go dead in the move.
- **Light check (NOT a full e2e):** `/webts/` is served + reachable (200, not 404/dead) · the web-ts login path
  (CYP-515/454) works · CYP-906 Edit is visible. Just "not dead after the move", not the full first-login gate.
- **Gated on:** deploy's `CYPPIE_GATEWAY_SPA_DIR` value + the Caddy root-mapping confirming `/webts/` is actually served.
  If the source does NOT serve `/webts/`, this row is N/A.

### (B) web-ts `dist/`
- **Login flow verified:** web-ts's own — `auth/LoginScreen`/`AuthGate`/`loginFlow` (CYP-515), same-origin cookie (CYP-454).
  Step-2 watch: **in-app login, no window-redirect construction** (CYP-515 (a) killed the loop by construction).
- **Emitter guard in play:** web-ts CYP-902 (`wsAuthParams` omits a blank `?token=`) → R3.
- **Dev5 role:** this is my strand; **CYP-906 Edit IS served** (operator-only edit affordance is live post-CYP-905 server) —
  add an Edit-e2e smoke if in scope.
- **Mismatch note:** CYP-901/903 (Compose) are NOT in this SPA → the "the SPA carries CYP-901/903" framing would be
  mismatched (flagged to PL alongside the SPA-dir question).

## 4. Handoff — what Dev5 needs from a-po (via coordinator) for the live P4 run
1. **`CYPPIE_GATEWAY_SPA_DIR` target** — composeApp-WASM dist or web-ts `dist/`? (resolves every **[SPA-dep]** row + which login flow / emitter guard applies).
2. Confirmation the cutover **builds/copies from develop `4c22e956`** (fresh, not a stale artifact).
3. The live endpoints (frontdoor origin, Kratos public path, GitHub OIDC client) as configured at the target.
4. The **P4 timing** (target root access + pubkey-live). Dev5 is on-call to co-verify the web-side behavior, deploy-guided — **no self-run against live**.
