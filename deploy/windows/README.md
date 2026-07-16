# CYP-627 — Windows service wrapper (WinSW) for the Cyppie hub

> Epic CYP-623 (Deployable Server-Hub-Installer, Windows-first / Topology A). This wraps the CYP-626 jpackage
> app-image as a **headless, auto-starting Windows Service** and captures its `--win-console` stdout to a rotating log.
>
> **Scope honesty:** WinSW is a Windows-only .NET executable, so this story is a **config + design deliverable**
> verified structurally here; the **live service lifecycle (install/start/health/stop/uninstall) is a Windows-runner
> verification** (§5) — the same honest pattern as the `.msi` and conpty legs (jpackage cannot cross-build/run from
> Linux). The `:server` boot itself is already e2e-proven under the packaged runtime (CYP-626 boot smoke).

## 1. Why WinSW

`jpackage` produces a launcher, **not a service** — a self-hosted hub must run headless, auto-start on boot, and
restart on crash. WinSW (`winsw/winsw`, a small MIT .NET wrapper) registers any executable as a Windows Service with
auto-start, failure-restart, and stdout capture, configured by one XML file — no code, no per-OS service API. (The
PO-chosen tool for CYP-627; NSSM is the equivalent fallback.)

## 2. Layout & bundling (the installer, CYP-628, places these)

```
%PROGRAMFILES%\CyppieHub\            (@INSTALL_DIR@ — the CYP-626 app-image, ACL: Admins + service acct)
  ├─ CyppieHub.exe                   the jpackage launcher (its .cfg carries the 2 mandatory JVM args)
  ├─ app\  runtime\  ...             the bundled app + jlink JRE 21
  ├─ CyppieHubService.exe            WinSW.exe, renamed (the service host)
  └─ CyppieHubService.xml            THIS config (CyppieHub-service.xml with @…@ substituted)

%PROGRAMDATA%\CyppieHub\             (@DATA_DIR@ — PLATFORM_GIT_ROOT, ACL-locked; all durable state)
  ├─ platform.config.json           non-secret boot config (wizard-written)
  ├─ .cyppie\*.db  clones\  projects\  ...   sqlite stores + git clone/worktrees
  └─ logs\                           WinSW-captured stdout (rolling)
```

WinSW convention: the wrapper exe and its XML share a basename (`CyppieHubService.exe` ↔ `CyppieHubService.xml`).

## 3. Service lifecycle (run on the Windows host, elevated)

```bat
CyppieHubService.exe install     REM register the service (Automatic + delayed-auto-start)
CyppieHubService.exe start       REM start it now (boot will also auto-start)
CyppieHubService.exe status
CyppieHubService.exe stop        REM graceful: 30s stoptimeout → the JVM flushes stores + closes WS
CyppieHubService.exe uninstall   REM deregister (data dir is PRESERVED — that is CYP-630's concern)
```

Config highlights (see `CyppieHub-service.xml`): `startmode=Automatic` + `<delayedAutoStart/>`; escalating
`<onfailure action="restart">` (10s→30s→120s) with `<resetfailure>1 hour` so a bad-config hub can't tight-loop;
`<log mode="roll-by-size">` 10 MB × 8 files under `@DATA_DIR@\logs`; `<stoptimeout>30 sec` + `stopparentprocessfirst`
so the JVM (and its pty4j children) shut down cleanly. **No JVM args here** — the launcher `.cfg` owns them (CYP-626).

## 4. ★ Secrets — the service stays secret-free (security-review item)

The hub needs, at boot: **required** `OPERATOR_TOKEN` + `HUB_TOKEN_<AGENTID>` (boot throws without them) and,
**gated**, `CYPPIE_MASTER_KEY` (enables the encrypted SecretStore — hub identity / device / remote tokens; **not** the
ANTHROPIC_API_KEY, which is plaintext@0600, see §8). None of
these are written into the XML or the app — that would leak them into a readable/redistributable file.

**Recommended flow (matches the design doc's secret-free principle + the PO's ACL-keyset master-key decision):**
- The wizard (CYP-628) runs the service under a **dedicated low-privilege service account** and provisions the
  secrets into **that account's environment** (ACL-readable only by the account + Admins — NOT machine/system env,
  which is world-readable). WinSW runs the service AS that account → the launcher inherits the env → `Secrets.fromEnv`
  reads them. The XML carries only the **non-secret** `PLATFORM_CONFIG` / `PLATFORM_GIT_ROOT` paths.
- `CYPPIE_MASTER_KEY` follows the PO's decision — an **ACL-locked random keyset**; the wizard sets it as an
  account-scoped env var (or a tiny launch shim reads the ACL-locked keyset file into the env before exec). No
  passphrase prompt at start (headless auto-start requires this).
- The **ANTHROPIC_API_KEY** is never in env/XML at all — it is entered at first-run via the operator GUI
  (`PUT /api/config/apikey`, operator-gated) and stored in an **owner-only (0600) file** — OS file-permission
  protection, **NOT** encrypted-at-rest under the master key. Encryption-at-rest for the API key is **deferred to
  CYP-220** (§8). The master key today protects only the **SecretStore** (hub identity / device / remote tokens) —
  **never** the API key.

**Flag for security review** (with the master-key-custody flag in the design doc §3.1): confirm the account-scoped-env
approach vs. an ACL-locked `<env>`-in-XML for the auto-generated local tokens. The API-key posture is settled
(Auftraggeber, Option B: **plaintext in an owner-only 0600 file**, OS-permission-protected; encryption-at-rest deferred
to CYP-220 — §8); the local-token custody is the open detail. This is CYP-628's provisioning to implement against this shape.

## 5. Windows-runner verification (retires the Windows leg)

On a Windows host with the CYP-626 `.msi` installed (or the app-image staged) + WinSW:
1. Substitute `@INSTALL_DIR@` / `@DATA_DIR@` in the XML; place `CyppieHubService.{exe,xml}` in `@INSTALL_DIR@`.
2. Provision the service-account env (tokens + master key) per §4; write `platform.config.json` (bind `127.0.0.1`).
3. `CyppieHubService.exe install && CyppieHubService.exe start`.
4. **Expect:** `sc query cyppiehub` = RUNNING; `@DATA_DIR@\logs\*.out.log` shows Ktor `Application started` +
   `Responding at http://127.0.0.1:8787`; `curl http://127.0.0.1:8787/api/health` → `ok` (mirrors the CYP-626 Linux
   boot smoke, now as a Windows service).
5. `CyppieHubService.exe stop` → the log shows a clean shutdown within the 30 s timeout; `uninstall` leaves `@DATA_DIR@`
   intact.

Green here = the hub runs as a Windows service headless with stdout captured → CYP-627 done; proceed to CYP-628.

## 7. Provisioning (CYP-628) — how the secrets + config are minted, secret-free

The install wizard (UIUX owns the UX) drives `provision.ps1`, which mints every secret **on the host at install
time** — the `.msi` ships none. The one secret PowerShell can't make itself is `CYPPIE_MASTER_KEY` (a Tink AES-256-GCM
keyset), so a Java **provisioning entrypoint** does it:

- **`CyppieHubProvision`** — a second app-image launcher (jpackage `--add-launcher`, wired into the CYP-626
  `hubInstaller`; spec in `provision-launcher.properties`). It runs `com.tneff.cyppieagents.provision.ProvisionMain`:
  mints `CYPPIE_MASTER_KEY` (single-line, env-safe minified Tink keyset), `OPERATOR_TOKEN` + `HUB_TOKEN_PO`
  (secure-random), and a default secret-free `platform.config.json` (1 PO agent, loopback bind). Config → the data
  dir; secrets → a `--secrets-out` `KEY=VALUE` file (owner-only) the wizard consumes then securely deletes. Secret
  values are never printed.
- **`provision.ps1`** — creates + ACL-locks the data dir; runs `CyppieHubProvision`; sets the 3 secrets in the
  **service account's ACL-protected environment** (not machine/system env — world-readable); securely deletes the
  transient file; substitutes the WinSW XML + installs the service. The **ANTHROPIC_API_KEY is NOT provisioned here**
  — it is entered at first-run via the operator GUI (CYP-629) and stored in an owner-only (0600) file (OS-permission
  protection, **NOT** encrypted-at-rest — §8).

**PROVEN e2e on Linux** (the mechanism is cross-platform; only the ACL/service-account/env steps are Windows):
`CyppieHubProvision` minted the master key + tokens + config → the packaged hub booted with that generated env →
`Application started`, `Responding at 127.0.0.1:8787`, the master-key-gated `SqliteSecretStore` initialized, and
`GET /api/health = ok`. Unit teeth (`ProvisionMainTest`, 5): the minted key roundtrips through the REAL cipher
(encrypt/decrypt), the config loads as `PlatformConfig` with exactly one PO, the key is single-line (env-safe), the
tokens are non-blank/distinct, and each run mints fresh secrets. The Windows service-account/ACL steps in `provision.ps1`
are the Windows-runner leg (§5).

## 6. Follow-up flagged from CYP-626 (not blocking)

`hubJlink`/`hubInstaller` resolve `jlink`/`jpackage` from `System.getProperty("java.home")` (the JDK running Gradle).
That produced a correct JDK-21 runtime in both the backend and PO envs, but it is **JDK-of-Gradle dependent** — a build
on JDK 25 would emit a 25 runtime, not the pinned 21 LTS. **Hardening:** resolve the packaging JDK via a Gradle
**toolchain** (`javaToolchains.launcherFor { languageVersion = 21 }`) so the runtime is deterministic regardless of the
Gradle JVM. Small, own follow-up ticket; the current tasks work when Gradle runs on JDK 21.

## 8. First-run operator setup (CYP-629)

After the service starts, the operator completes the sensitive config through the GUI — no secret ships in the `.msi`
and none is provisioned into env. **The existing endpoints already support this — CYP-629 adds no new backend
endpoint.** The install wizard hands the operator the wizard-minted `OPERATOR_TOKEN` (CYP-628); the first-run GUI
(UIUX owns the UX) then:

1. **Detect "not configured yet"** — `GET /api/config/apikey` returns `{ "set": false }` → the GUI prompts for the key.
2. **Enter the key** — `PUT /api/config/apikey` (operator-gated) `{ "apiKey": "sk-ant-…" }` → `{ "set": true, "masked": "***last4" }`.
3. **Confirm** — `GET /api/config/apikey` → `{ "set": true, "masked": "***last4" }`. The **raw key is never egressed by any GET** (masked only; a MEMBER never sees it).
4. **Repo + roster** — `PUT /api/config/repo` (operator) and the agent-CRUD endpoints, if not set at install.

**Proven e2e on Linux:** against the packaged hub (provisioned per §7, booted with the generated env), an operator-token
flow returned `GET apikey` → `{set:false}` → `PUT apikey` → `{set:true,masked:"***test"}` → `GET` → `{set:true,masked:"***test"}`.

### ★ Honest API-key posture (Auftraggeber decision, Option B) — no false claim
The **ANTHROPIC_API_KEY is stored PLAINTEXT in an owner-only (0600) file** (`project-config.db`, `SqliteProjectConfigStore`),
protected by **OS file permissions** — it is **NOT encrypted-at-rest under the master key**. We do not ship the
self-hoster a protection they do not have. The `CYPPIE_MASTER_KEY` (CYP-628) encrypts **only** the `SecretStore` (hub
identity / device anchor / remote tokens), never the API key. **Encryption-at-rest for the API key is deferred to
CYP-220** (the SecretCipher "S-B" slice, already scoped there; today only the Postgres store — a dark path — encrypts
the project config). A self-hoster running the default embedded-SQLite store gets 0600 file protection, no more.

## 9. Uninstall + data preservation (CYP-630)

**The uninstall NEVER silently deletes a team's data.** `uninstall.ps1` is data-preserving by default: it stops +
deregisters the service and (optionally) removes the app-image, but leaves the **data dir** (`@DATA_DIR@` —
`PLATFORM_GIT_ROOT`) fully intact — the sqlite stores, git clones/worktrees, `platform.config.json`, the encrypted
SecretStore. A reinstall or upgrade reattaches to the team's existing state. A destructive wipe is a **separate,
explicit, confirmed** action (`-WipeData`, `ConfirmImpact=High`).

```powershell
# default — data-preserving: stop + deregister the service, keep @DATA_DIR@
uninstall.ps1 -InstallDir "C:\Program Files\CyppieHub" -DataDir "C:\ProgramData\CyppieHub" -RemoveAppImage

# destructive — deletes the data dir AND the account-scoped secrets, together (prompts unless -Confirm:$false)
uninstall.ps1 -InstallDir "…" -DataDir "…" -ServiceAccount "…" -WipeData
```

**★ Master-key ↔ SecretStore coupling.** The SecretStore in the data dir (`.cyppie\hub-secrets.db`) is encrypted under
`CYPPIE_MASTER_KEY` (in the service account's env, CYP-628). So preserve-mode also **preserves the master key + tokens**
— otherwise the kept SecretStore would be orphaned (undecryptable) on reinstall. Wipe-mode removes **both** the data dir
**and** the account-scoped secrets, together — never a half-deleted state (an orphaned key XOR an orphaned store). (The
API key rides in the data dir as plaintext@0600, §8, so it is preserved/wiped with the data dir either way.)

**MSI note:** an MSI uninstall removes the files the installer *placed* (the app-image); the data dir is created at
runtime by the service, so the MSI does **not** touch it → data is preserved by default at the MSI level too, matching
this script. The service stop/deregister is the one step the MSI cannot do cleanly (a running JVM + pty4j children), so
the uninstall runs `uninstall.ps1` first (a WiX custom action / a documented pre-uninstall step).

**Windows-runner verification:** with the service installed (§5) + data present, `uninstall.ps1` (default) → `sc query
cyppiehub` = not-found (deregistered), `@DATA_DIR@` still present with its stores; re-running `provision.ps1` +
`CyppieHubService.exe install/start` → `GET /api/health = ok` on the SAME data (the API key + config survive). Then
`uninstall.ps1 -WipeData` (confirmed) → `@DATA_DIR@` gone + the account-scoped secrets cleared.
