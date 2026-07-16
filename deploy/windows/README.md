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
**gated**, `CYPPIE_MASTER_KEY` (enables the encrypted SecretStore that holds the ANTHROPIC_API_KEY at rest). None of
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
  (`PUT /api/config/apikey`) and stored **encrypted-at-rest** under the master key (CYP-629).

**Flag for security review** (with the master-key-custody flag in the design doc §3.1): confirm the account-scoped-env
approach vs. an ACL-locked `<env>`-in-XML for the auto-generated local tokens. The API-key-encrypted-at-rest posture is
settled; the local-token custody is the open detail. This is CYP-628's provisioning to implement against this shape.

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

## 6. Follow-up flagged from CYP-626 (not blocking)

`hubJlink`/`hubInstaller` resolve `jlink`/`jpackage` from `System.getProperty("java.home")` (the JDK running Gradle).
That produced a correct JDK-21 runtime in both the backend and PO envs, but it is **JDK-of-Gradle dependent** — a build
on JDK 25 would emit a 25 runtime, not the pinned 21 LTS. **Hardening:** resolve the packaging JDK via a Gradle
**toolchain** (`javaToolchains.launcherFor { languageVersion = 21 }`) so the runtime is deterministic regardless of the
Gradle JVM. Small, own follow-up ticket; the current tasks work when Gradle runs on JDK 21.
