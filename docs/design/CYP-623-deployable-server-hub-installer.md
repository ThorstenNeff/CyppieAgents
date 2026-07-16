# CYP-623 — Deployable Server-Hub-Installer (self-hosted Hub, Windows-first) — Design Pass

> Status: **DESIGN — awaiting PO review → Auftraggeber ratification (esp. §2 topology) → THEN build/stories.**
> Owner: backend. Convention: design-doc-first, no build until ratified.
> Grounding: this doc is written against the ACTUAL `:server` code (refs in-line + §7 appendix), not assumptions.

## 0. Executive summary (the recommendation, one screen)

Package the headless `:server` hub as a self-contained **Windows `.msi` built with `jpackage` over a `jlink`-minimized JRE 21**, bundling the application distribution; run it headless as a **Windows Service** (via a small service wrapper — `jpackage` has no native service mode). **Not** GraalVM native-image (§1).

- **Topology (§2) is the one Auftraggeber decision.** Present TWO options: **A = standalone/local** (LAN/localhost, no cloud) vs **B = registers-at-cloud-relay** (remote). Backend framing (not a decision): **A first** — it is *already the code's default* (the relay path is INERT unless a full `CYPPIE_CP_*` env set is present), needs zero cloud infra, and gets a dogfood team running fastest. **B** is the natural follow-on and is where the CYP-620 mux investment pays off.
- **The `.msi` is secret-free and redistributable (§3).** The installer wizard provisions the runtime + data-dir + a secret-free `platform.config.json` + generates the master key and tokens; the **ANTHROPIC_API_KEY + operator device-enroll + agent roster** are completed at **first run** through the existing operator GUI (operator-gated, encrypted-at-rest). No secret ever ships inside the installer.
- **MVP (§4)** = the Windows `.msi`, Option A, service auto-start, a thin install wizard + first-run operator setup. **Later** = Linux `.deb` / Intel-Mac + ARM-Mac `.dmg` (portable `jpackage --type`), Option B relay + the mux flip, code-signing, auto-update.
- **Mux `:core` seams (§5)** — the Windows-standalone MVP does **not** touch the mux (mux is the *remote* datapath; standalone uses the local transport), so the 3 `:core` contract seams (streamClass-byte · cap-consts/CYP-613 · CYP-621 lane→streamClass mapping) are **backlog**, NOT ratify-before-build. They become a precondition **only if** the Auftraggeber picks Option B.

---

## 1. Q1 — Packaging the headless SERVER

**What we are packaging:** the Gradle `application` module `:server` — `mainClass = com.tneff.cyppieagents.ApplicationKt`, Ktor on the **Netty** engine, `embeddedServer(Netty, …).start(wait = true)` (a blocking daemon). It already produces an `installDist` distribution (a `bin/` start script + `lib/` jars) via the application plugin; there is **no fat-jar/shadow** plugin today.

### The load-bearing constraint: two runtime-extracted NATIVE dependencies
Two dependencies bundle per-OS/arch native payloads inside their jars and **extract them to a temp dir at runtime**:
- **`org.jetbrains.pty4j:pty4j` 0.13.4** — per-OS PTY helper binaries (Linux/macOS/Windows; on Windows winpty/conpty). Used by `pty/PtyManager.kt` to launch the interactive `claude` TUI per agent.
- **`org.xerial:sqlite-jdbc` 3.53.2.0** — JNI + a per-platform `.so/.dll/.dylib`, extracted at runtime. Backs every `.cyppie/*.db` store (messages, events, secrets, …).

Both work **transparently on a normal JVM** (self-extract → load) but are the classic friction point for any approach that removes the jar/classloader (native-image) or strips a runtime (jlink module gaps). Everything else is pure-JVM: Netty's *native* transports are optional and **unused** (NIO transport), and Tink / BouncyCastle / noise-java are pure-Java crypto.

**JDK floor = 17** (driven chiefly by **Flyway 10.17**, which requires Java 17+; Kotlin 2.4 + Ktor 3.5 are comfortable on 17/21). `:server` itself declares no `jvmToolchain`/`jvmTarget`, so this must be pinned explicitly for the installer. **Recommend bundling a JRE/JDK 21 LTS.**

### Options & tradeoffs

| Option | What it is | Pros | Cons / risk | Verdict |
|---|---|---|---|---|
| **A. `jpackage` + `jlink` runtime** (over the app-plugin dist or a fat-jar) | jpackage bundles a minimal jlink'd JRE + the app into a per-OS installer. `--type msi` on Windows (needs WiX 3.x on the build host). | **One tool for all 4 targets** (`--type msi/deb/dmg`) → portable by design. Native libs ride inside the jars and self-extract **exactly as today** — zero code change. Standard JDK-17+ tooling. | ~50–90 MB installer (bundled JRE). **No native service mode** → needs a service wrapper (below). Must **preserve the 2 JVM args** + set the service working dir / absolute config paths (below). WiX is a Windows-build-host prereq. | **✅ RECOMMENDED** |
| B. `jlink` alone | Produces a custom runtime *image*, not an installer. | Smaller runtime than a full JRE. | Not an installer — you still need jpackage/WiX on top. | Not an alternative — it is a **component of A**. |
| C. **GraalVM native-image** (single binary) | AOT-compile to one native executable. | Fast startup, small-ish, no JRE. | **HIGH RISK.** pty4j's runtime helper extraction has no jar to extract from in a native binary; sqlite-jdbc JNI + extraction; Netty/Tink/Flyway need extensive reflect/resource/JNI reachability metadata + a tracing-agent pass, and likely patching pty4j. Startup/size wins are **irrelevant for a long-lived daemon** (one-time cost). *(Context7 `/graalvm/native-build-tools`: reachability metadata + the tracing agent are required; runtime-loaded native libraries are the exact unsupported friction.)* | ❌ Reject for MVP. Revisit only if a native single-binary becomes a hard requirement **and** pty4j is made optional/replaced. |
| D. Bundled-JRE + a generic installer (Inno Setup / signed zip) | Hand-roll the JRE bundle + a Windows installer toolchain. | Full control of the installer UX. | jpackage **already** does JRE-bundling + `.msi` natively **and** portably to deb/dmg; a second installer toolchain doubles the surface with no gain. | Fallback only. |

### Recommendation — Option A, concretely
- **`jlink`-minimized JRE 21 LTS.** Include the modules the runtime needs (JDBC → `java.sql`; Flyway/logback/Tink service-loaders; **keep `jdk.jfr` in the image OR keep the `-Dio.netty.jfr.enabled=false` arg** — the arg is cheaper and already required, see below). Verify pty4j + sqlite-jdbc extraction works against the jlink runtime on Windows (a build-task-0 smoke test).
- **`jpackage --type msi`** (WiX 3.x on the Windows CI host) → a self-contained `.msi`. Keep the `jpackage` invocation OS-parameterized so `--type deb/dmg` reuse it later (portability is a first-class goal).
- **Headless service.** jpackage produces a launcher, not a service. Recommend bundling **WinSW** (small, MIT — an `.exe` that registers the app as an auto-start Windows Service, headless, with restart-on-failure) inside the app-image; the service command runs the app-image launcher. *(Alternatives: NSSM; or jpackage `--win-console` + a manual "run" shortcut for a first cut — but a dogfood team wants auto-start, so WinSW is the MVP choice.)* Per-OS later: systemd unit (`.deb`) / launchd plist (`.dmg`).
- **Preserve the two JVM args in the service launch:** `-Dio.netty.jfr.enabled=false` (else `NoClassDefFoundError: FreeChunkEvent` on a stripped JDK — the app-plugin start script carries it, but a custom service command must re-apply it) and `-XX:MaxRAMPercentage=75.0` (the ResourceGovernor needs a knowable heap ceiling; deploy may override with `-Xmx`).
- **Working dir / config paths.** `:server:run` sets `workingDir = repoRoot` so relative `platform.config.json` / `.cyppie` resolve; the installed service must do the equivalent — set the service **working directory** to the data-dir **or** (preferred) pass absolute `PLATFORM_CONFIG` and `PLATFORM_GIT_ROOT` (§3).
- **Build-task 0 (spike, pre-build):** `jpackage` a trivial app-image on Windows and confirm sqlite-jdbc + pty4j self-extract + a PTY spawns under the jlink runtime. This retires the only real packaging risk before committing to the full installer.

---

## 2. ★ Q2 — Topology (core architecture question → Auftraggeber ratifies)

**This is the one decision the design pass does NOT make.** Both options below are viable; the choice drives install complexity, infra, and whether the mux flip (§5) is pulled in.

**Ground truth:** the hub runs **standalone by default**. `buildRemoteTransport` returns `InertRelayConnector` (no dial) unless **ALL** of `CYPPIE_REMOTE_RELAY_URL` + `CYPPIE_MASTER_KEY`-gated custody + `CYPPIE_OPERATOR_ID` + `CYPPIE_CP_{URL,ISSUER,KID,PUBKEY,OPERATOR_TOKEN}` + `CYPPIE_OPERATOR_RP_ID` + a stored `dhKey` are present (any gap → INERT). The public bind defaults to **`127.0.0.1:8787`** (loopback). So "standalone/local" is not a new mode — it is the code as-shipped.

### Option A — Standalone / Local (LAN or localhost)
The hub binds `127.0.0.1:8787` (or `0.0.0.0`/a LAN IP via `hub.host`); the team connects on the same host or LAN. No cloud relay, no control-plane, no rendezvous.

- **Pros:** simplest install (no external accounts, no relay/CP infra, no `CYPPIE_CP_*`); fully self-contained; **data never leaves the host**; works **offline**; **matches the code default**; the client uses the existing local transport (no relay/mux).
- **Cons:** reachability limited to the LAN / a VPN into the host — no remote-from-anywhere; an off-loopback bind (`hub.host = 0.0.0.0`) is an **explicit operator opt-in** and, while the `?token=` WS fallback exists, should sit behind TLS/a reverse proxy or stay loopback with the operator on the host.
- **Security envelope:** operator token + (optional) local Kratos/device-enroll; `CYPPIE_MASTER_KEY` optional (only needed to encrypt the API key at rest / enable identity).

### Option B — Registers at Cloud-Relay (remote)
The hub dials the relay + registers a rendezvous at the control-plane (the CYP-427 remote-hub path + the new CYP-620 mux); the team reaches it from anywhere via the relay.

- **Pros:** **remote / BYOA** access from anywhere; the CYP-620 mux + the whole remote-transport investment pays off; no LAN/VPN constraint.
- **Cons:** requires the **full `CYPPIE_CP_*` config** (operator-id, issuer, kid, pubkey, rp-id, CP url, operator token) **+ `CYPPIE_MASTER_KEY` + a running relay + CP infra** (Anthropic-hosted or team-hosted); a heavier install and the RR3 **device-enroll** security envelope; and it makes the **mux flip + its 3 `:core` seams (§5) live** (ratify-before-build).
- **Security envelope:** RR3 (CpJwt ∧ operator-device-PoP), operator device-enroll (CYP-525), E2E-through-untrusted-relay (RR4).

### Tradeoff summary

| Dimension | A — Standalone/Local | B — Cloud-Relay |
|---|---|---|
| Reachability | Same host / LAN / VPN | Anywhere (via relay) |
| Install complexity | Low (no `CYPPIE_CP_*`) | High (full CP set + device-enroll) |
| External infra | **None** | Relay + control-plane |
| Data locality | On-host only | Traffic transits the (untrusted, E2E-encrypted) relay |
| Offline | Yes | No |
| Security surface | Operator token + optional local auth | RR3 + device-enroll + E2E |
| Mux relevance (§5) | None (local transport) | **Pulls in the mux flip + 3 `:core` seams** |
| Matches code default | **Yes (INERT relay)** | No (opt-in gate) |

**Backend recommendation (NOT a decision):** **A for the first Windows dogfood** — fastest to a running team, zero infra, already the default; **B as the explicit follow-on** once remote access is wanted. The Auftraggeber ratifies. **Flag:** choosing B for the MVP pulls the mux-flip + the 3 `:core` seams + the `maxStreams=64` sign-off into the installer's critical path.

---

## 3. Q3 — Guided-install flow

**Principle: the `.msi` is secret-free and redistributable.** Split configuration into **install-time (wizard, no secrets)** and **first-run (operator GUI, sensitive)**. There is **no `.env` loader** in the server — all secrets come from the process environment or the encrypted-at-rest stores — so the installer provisions env/config for the *runtime*, never bakes a secret into the artifact.

### Install-time wizard (no secrets)
1. **Data directory** — default `%PROGRAMDATA%\CyppieHub`, writable by the service account, ACL-locked (Administrators + the service account only). Sets **`PLATFORM_GIT_ROOT`** (all durable state — `clones/`, `projects/`, `.cyppie/*.db` — lives here). *(Later: `/var/lib/cyppie-hub` on Linux, `~/Library/Application Support/CyppieHub` on macOS.)*
2. **Bind** — *localhost-only* (default, safest) vs *LAN* (`0.0.0.0` or pick an interface IP) → writes `hub.host` / `hub.port` (default 8787) into `platform.config.json`. This is the Option-A reachability knob.
3. **Master key** — generate a random keyset for **`CYPPIE_MASTER_KEY`** (the KEK that encrypts the API key + identity at rest). Store it **protected**, service-account-only ACL (optionally DPAPI-wrapped). **Open decision (§3.1).**
4. **Required tokens** — generate random `OPERATOR_TOKEN` + per-agent `HUB_TOKEN_<AGENTID>` (boot **throws** without these) → write to a protected service-env file / the service definition (0600-equivalent ACL). *(Alternatively defer the operator token to a first-run bootstrap; the per-agent tokens must exist for the seeded roster.)*
5. **Default `platform.config.json`** — ship a template: `repo` (blank + prompt, or a team preset), `hub` host/port (from step 2), `agents` roster (**empty vs a minimal PO/frontend/backend preset** — recommend a minimal preset with an obvious "edit in the GUI" path), `auth` (Kratos **off** by default for a local single-operator dogfood).
6. **Preconditions check** — `git` on PATH (required for clone/worktrees), **`claude` CLI on PATH** (warn if missing — agents cannot spawn without it; see §4 open item), and (if a repo is set) network reachability to `repo.url`.
7. **Register + start the Windows Service** (auto-start, restart-on-failure).

### First-run operator setup (GUI, sensitive)
1. Operator authenticates (the wizard's bootstrap `OPERATOR_TOKEN`, or a first-run operator bootstrap).
2. **Enter `ANTHROPIC_API_KEY`** via the existing operator route **`PUT /api/config/apikey`** (operator-gated, masked, stored **0600 encrypted-at-rest** under the master key) — keeps the key out of the installer *and* out of plaintext env if the team prefers the store over env.
3. (Option B only, or if enroll is wanted) **operator device-enroll** (CYP-525).
4. Configure the **agent roster** (add/edit agents) + the `repo.url` if not set at install.

**Split rationale:** secrets + team-specific + security-sensitive config → first-run GUI (operator-gated, encrypted-at-rest); host/runtime/data-dir/token-provisioning → the install-time wizard. **UIUX owns the wizard + first-run UX** once this shape is ratified (PO to pull them in).

### 3.1 Open decision — master-key custody on Windows (security review + Auftraggeber)
How `CYPPIE_MASTER_KEY` is protected on the installed host is a security-design point:
- **Random keyset in an ACL-locked file (recommended default):** service-account-only ACL, optionally DPAPI-wrapped. Simple; the encrypted secrets are bound to the host (acceptable for a self-hosted hub).
- **Windows DPAPI (machine/user-bound):** OS-managed; ties the ciphertext to the machine/account (a restore-to-new-machine needs re-provisioning).
- **Operator passphrase (PBKDF2 custody — the code supports it):** portable, but a **prompt at every service start** is hostile to a headless auto-start service. Not recommended for the default.

Recommend the **ACL-locked random keyset**; surface the choice for review.

---

## 4. Q4 — MVP scope (Windows installer first) vs later

### MVP — the first deliverable (Windows `.msi`)
- `jpackage --type msi` bundling `:server` + a jlink'd JRE 21 + a **Windows Service wrapper** (headless auto-start), with the **two JVM args preserved** and the working-dir / absolute config paths set.
- A **thin install wizard** (§3): data-dir, bind localhost-vs-LAN, generate master key + tokens, ship the default `platform.config.json`.
- **First-run operator setup** in the GUI (§3): API key, roster, optional device-enroll.
- **Option A** (standalone/local) topology — **no relay**.
- Windows data-dir with correct ACLs; secret stores 0600-equivalent.
- **Service control** (start/stop/status) + an **uninstall that PRESERVES the data-dir by default** (opt-in wipe — never silently delete a team's `.cyppie`).
- **Build-task 0 spike** (§1) up front to retire the pty4j/sqlite native-extraction risk on the jlink runtime.

### Later (post-MVP, in the Auftraggeber's stated order)
- **Linux `.deb` → Intel-Mac `.dmg` → ARM64-Mac `.dmg`** — the same jpackage invocation with `--type deb/dmg`; per-OS service integration (systemd unit / launchd plist) is the only genuinely new work per target.
- **Option B** — cloud-relay registration + the **mux flip** (pulls in §5's 3 `:core` seams + the `maxStreams=64` Auftraggeber sign-off + the client+hub lockstep flag flip).
- **Code-signing** — an unsigned `.msi` trips Windows SmartScreen; macOS needs notarization. A **distribution-trust** item that needs certificates with **procurement lead time** → flag to the Auftraggeber early even though it is post-MVP.
- Auto-update; multi-operator / multi-team; Kratos-based end-user auth inside the standalone install (if wanted).

### Explicit MVP non-goals
Relay/CP infra · code-signing · multi-platform · auto-update · the mux flip.

### Open item to resolve with the PO
**The `claude` CLI dependency.** Agents spawn `claude` (Connector A, stream-json) via pty4j; the hub cannot run real agents without it on PATH. Options: (a) document `claude` as an install prerequisite the wizard checks/links to; (b) bundle/auto-install it. Recommend **(a)** for MVP (a check + a link), since bundling a third-party CLI has its own licensing/update surface. Needs a PO call.

---

## 5. Q5 — Does the installer touch mux / transport work?

**No — not for the Windows-standalone MVP.** The mux is the **remote (relay) datapath**; Option A (standalone/local) uses the existing **local transport**, which the installer does not modify. Therefore the three `:core` transport-contract seams —
1. **streamClass-byte** (the first-payload-byte stream class, §4.2 of the CYP-620 design),
2. **cap-consts** (single-sourcing `maxStreams` + the window config to `:core` — CYP-613),
3. **CYP-621 lane→streamClass mapping** (CONTROL-lane → streamClass 0, the reserved-priority class),

—are **backlog**, not a ratify-before-build precondition for CYP-623.

**They become ratify-before-build ONLY IF the Auftraggeber picks Option B (§2)** (cloud-relay + the mux flip). Recorded here so the topology decision carries its true downstream cost: Option B = installer work **+** the mux-flip + the 3 seams + the `maxStreams=64` sign-off + the client/hub lockstep flip.

---

## 6. Proposed stories (for the PO to shape under Epic CYP-623) — pending ratification

1. **Spike / build-task 0** — jpackage a trivial app-image on Windows; prove sqlite-jdbc + pty4j self-extract + a PTY spawns under a jlink JRE 21. *(retires the only real packaging risk.)*
2. **`:server` packaging build** — jlink module set + jpackage `--type msi` config (OS-parameterized for deb/dmg reuse); preserve the 2 JVM args; a `:server:packageMsi`-style Gradle task. WiX on the Windows build host.
3. **Windows Service wrapper** — bundle WinSW (or chosen wrapper); auto-start, restart-on-failure, correct working-dir / absolute `PLATFORM_CONFIG`+`PLATFORM_GIT_ROOT`.
4. **Install wizard (no secrets)** — data-dir, bind mode, master-key generation + custody (§3.1), token provisioning, default `platform.config.json`, preconditions check. *(UIUX for the wizard UX.)*
5. **First-run operator setup** — wire the existing operator GUI (API key via `PUT /api/config/apikey`, roster, optional device-enroll) as the post-install sensitive-config path.
6. **Uninstall + data preservation** — service deregister; preserve the data-dir by default, opt-in wipe.
7. *(Deferred, own epic slice)* deb/dmg targets · Option-B relay registration + mux flip · code-signing.

---

## 7. Appendix — grounding (code references)

- **Entry / bind:** `server/.../Application.kt:23` (`fun main`), `:52` (`embeddedServer(Netty …)`), `:56-57` (public `config.hub.host:port` default `127.0.0.1:8787`; tunnel `127.0.0.1:8786`), `:58` (`.start(wait=true)`). `boot/PlatformConfig.kt:107-121` (HubConfig defaults). `routing/PlatformWiring.kt:258` (`bootHost = 127.0.0.1`).
- **Boot prerequisites:** `boot/BootOrchestrator.kt:285` (`boot()`); `WorktreeManager.kt:62-69` (`ensureClone` → `git clone`), `:138-153` (`ensureWorktree` → `git worktree add`); spawn loop `:883-897` (local agents spawn `claude`; `remote:true` await `/ws/hub`).
- **Required env (throws):** `boot/Secrets.kt:69-75` (`HUB_TOKEN_<ID>`, `OPERATOR_TOKEN`); `ANTHROPIC_API_KEY` `:80` (optional, env-injected, masked `:31-53`).
- **Config knobs:** `PLATFORM_CONFIG` / `PLATFORM_GIT_ROOT` `Application.kt:36-37`; data-dir stores under gitRoot per `PlatformWiring.kt:294-368` (all secret stores 0600).
- **Master-key gate:** `CYPPIE_MASTER_KEY` → `PlatformWiring.kt:362-366` (`SqliteSecretStore` + `EnvKeysetMasterKeyCustody`), consumed `BootOrchestrator.kt:993-1004`.
- **Relay INERT-by-default gate:** `transport/RemoteRelayWiring.kt:130-156` (all `CYPPIE_CP_*` + `CYPPIE_REMOTE_RELAY_URL` + master-key custody required, else `InertRelayConnector`).
- **Native deps:** `server/build.gradle.kts` deps + `gradle/libs.versions.toml` — `pty4j 0.13.4` (runtime native helpers), `sqlite-jdbc 3.53.2.0` (JNI + runtime extraction); Flyway 10.17 → JDK-17 floor. `applicationDefaultJvmArgs = [-Dio.netty.jfr.enabled=false, -XX:MaxRAMPercentage=75.0]`.
- **Separate deployable (NOT the hub):** `relay/RelayServer.kt` (`:server:relayRun`, `CYPPIE_RELAY_HOST` default `0.0.0.0` / `CYPPIE_RELAY_PORT` 8788) — the untrusted rendezvous relay, relevant to Option B only.
