# CYP-623 — macOS `.pkg` installer — design pass (Intel + ARM64)

> Status: **DESIGN — awaiting PL ratification of this artifact → then build.**
> Direction ratified (Project Lead, 2026-07-18, §4a — inside the already-ratified installer epic; the binding
> platform order Windows→Linux→**Intel-Mac→ARM64-Mac** is part of it; reversible packaging work, no §4b escalation).
> Convention: design-first. This is a **lean** pass — the Linux `.deb` chain (CYP-634→637) is the precedent; the
> architecture, Topology A, secret-custody model and preserve/purge semantics are **identical**, only the macOS
> **mechanisms** differ (`.pkg` instead of `.deb`, **launchd** instead of systemd, **dscl** instead of `useradd`). This
> doc maps the proven Linux legs → macOS and names the macOS-specific calls.

## 0. Why macOS is the real open non-Windows scope (survey finding)

The binding platform order has Intel-Mac + ARM64-Mac, but macOS has **zero stories** and is quasi-unbuilt: `hubInstaller`
(`server/build.gradle.kts` ~L256, L272-302) currently selects `installerType = "dmg"` for macOS and the macOS branch
adds **no extra args** — a bare drag-to-install `dmg` app-image with **no service install, no provisioning, no
maintainer scripts, no uninstall**. The only macOS assets in-tree (`deploy/launchd/com.cyppie.hub.plist` +
`hub-run.sh`, CYP-670) are the **team's own central-host** LaunchDaemon (hand-installed on the Auftraggeber's Mac) — a
reusable *pattern*, **not** a customer-facing installer. So the whole `.deb`-equivalent stack is the open work. (Linux
`.deb` = complete + e2e-proven; Windows `.msi` = design+config complete, execution runner-blocked on CYP-625.)

## 1. Packaging (macOS analog of CYP-626/634) — jpackage `--type pkg`, same single-call chain

`hubInstaller` already selects `--type` per OS and rides one `installDist → jlink → jpackage` chain. macOS changes from
`--type dmg` (bare app-image) to **`--type pkg`** — the flat installer package suitable for **distribution outside the
Mac App Store** ([jpackage/JDK 24 docs](https://docs.oracle.com/en/java/javase/24/jpackage/support-application-features.html)).

**★ Key de-risking finding (Context7-verified against the current jpackage spec):** jpackage `--type pkg` supports
**custom `preinstall`/`postinstall` scripts via `--resource-dir`**, plus an **uninstaller script** and *service-related
pre/postinstall* — the docs call these out **"especially for background services"**. So the macOS `.pkg` uses the **same
`--resource-dir` maintainer-script mechanism the Linux `.deb` already uses** (CYP-635's `postinst`/`prerm`/`postrm`) —
**no separate `pkgbuild`/`productbuild` post-process is needed.** The gradle branch stays a one-call mirror of Linux:

```kotlin
// server/build.gradle.kts — the macOS branch, mirror of the isLinux branch (L296-300)
val isMac = os.isMacOsX
// installerType: "msi" (win) / "deb" (linux) / "pkg" (mac, was "dmg")
if (isMac) args += listOf(
    "--resource-dir", macResourceDir,   // deploy/macos/pkg-resources: CyppieHub-preinstall / CyppieHub-postinstall / uninstaller
    "--app-content", plistFile,         // ship the LaunchDaemon plist into the payload → postinstall installs it (single-source, CYP-636 pattern)
    // "--install-dir", "/opt",         // ← DECISION/spike: see §7 (mac .pkg default install dir is /Applications)
    // "--mac-sign", "--mac-signing-key-user-name", "<Developer ID Installer>"  ← DEFERRED (§8)
)
```

- Same jlink runtime (`java.se,jdk.unsupported,jdk.crypto.ec,jdk.jfr` — CYP-625/626), same two mandatory JVM args baked
  via `--java-options` (already OS-independent in the task). Same JDK-21 **toolchain pin** (CYP-634) → a deterministic
  bundled JRE regardless of the Gradle JVM.
- Same second launcher `--add-launcher CyppieHubProvision=…` (the cross-platform provisioning entrypoint) — already
  emitted on all OSes.
- The `--app-content` single-source trick (CYP-636): ship the reviewable `com.cyppie.hub.plist` into the payload so the
  `postinstall` **copies** it into `/Library/LaunchDaemons/` — no heredoc duplication, drift structurally impossible.

## 2. Service (macOS analog of CYP-627/systemd → **launchd**)

A **LaunchDaemon** replaces the systemd unit. **Reuse the CYP-670 form verbatim** (`deploy/launchd/com.cyppie.hub.plist`
+ `hub-run.sh`) — it already encodes the ratified posture; we only adapt it from *team-host, hand-installed* to
*customer, installer-delivered*:

- Plist at `/Library/LaunchDaemons/com.cyppie.hub.plist`: `UserName`/`GroupName` `_cyppie` (dedicated non-root),
  `RunAtLoad`+`KeepAlive`+`ThrottleInterval=10` (headless auto-start + restart, the launchd analog of
  `Restart=on-failure`+`StartLimit*`), `StandardOut/ErrPath` → `/var/log/cyppiehub/`, and **`Soft`+`Hard` `Core` = 0**
  (the CYP-670 **D2** hardening: the hub holds the decrypted master key + tokens in RAM, so a core dump must never spill
  it — the macOS equivalent of the `.deb`'s `LimitCORE=0`).
- **★ The secret-safe wrapper is load-bearing (PL criterion — secret-safety).** launchd has **no `EnvironmentFile` key**,
  and `/Library/LaunchDaemons/*.plist` is **world-readable** → secrets **must not** live in the plist. So the plist execs
  a tiny wrapper `hub-run.sh` that **sources the 0600 `/etc/cyppiehub/hub.env`** (owned `_cyppie`) then execs the hub —
  exactly the CYP-670 pattern. The **customer** wrapper is *simpler* than CYP-670's: it execs the jpackage launcher
  `/opt/cyppiehub/bin/CyppieHub` (which already bakes the 2 JVM args via `--java-options`), so it needs **no**
  `CYPPIE_HUB_JAR` / `hub.jvmargs` parsing:

  ```bash
  #!/bin/bash            # macOS /bin/bash 3.2-safe
  set -euo pipefail
  ENV_FILE="${CYPPIE_HUB_ENV_FILE:-/etc/cyppiehub/hub.env}"
  [ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }   # source the 0600 secrets (never echoed)
  exec /opt/cyppiehub/bin/CyppieHub                          # the jpackage launcher owns the JVM args
  ```
- Graceful stop: launchd sends `SIGTERM` then `SIGKILL` after the exit timeout → the JVM flushes stores + reaps pty4j
  children (the `KillMode=mixed`/`TimeoutStopSec` analog; `ExitTimeOut` tunable if needed). The hub binds **loopback**
  by config (`PlatformConfig.host` default `127.0.0.1`) — no host override in the plist.

## 3. ★ Provisioning + secret custody (macOS analog of CYP-628/635) — custody ALREADY ratified

`CyppieHubProvision` / `ProvisionMain` is **cross-platform and reused verbatim** (its `ownerOnly()` already applies POSIX
0600 on macOS). The `postinstall` mirrors the Linux `postinst` (CYP-635) 1:1:

1. **Create the dedicated non-root service user — the one real mechanism difference.** macOS has no `useradd`; use
   **`dscl`** (Directory Service CLI) or `sysadminctl`. Create a hidden daemon user `_cyppie` (underscore-prefixed per
   the macOS `_service` convention) with an unused system UID (< 500, hidden from the login window), `UserShell
   /usr/bin/false`, its own `_cyppie` group. Idempotent (check `dscl . -read /Users/_cyppie` first).
2. **Data + secrets dirs:** `install -d`-equivalent → `/var/lib/cyppiehub` (data, `PLATFORM_GIT_ROOT`, owned `_cyppie`
   0750) + `/etc/cyppiehub` (0755 root); `/var/log/cyppiehub` (owned `_cyppie`).
3. **Provision once, preserve-safe** (only if `/etc/cyppiehub/hub.env` absent — a reinstall reattaches, never re-mints):
   run `/opt/cyppiehub/bin/CyppieHubProvision --data-dir /var/lib/cyppiehub --host 127.0.0.1 --port 8787 --tunnel-port
   8786 --secrets-out <mktemp 0600>`; assemble `hub.env` = the 3 minted secrets (`CYPPIE_MASTER_KEY`, `OPERATOR_TOKEN`,
   `HUB_TOKEN_PO`) + the non-secret `PLATFORM_CONFIG`/`PLATFORM_GIT_ROOT` paths; `chown _cyppie`, `chmod 0600`;
   **securely delete** the transient file (`rm -P` — the macOS `shred` analog). Values are **never** printed (ProvisionMain
   prints only a non-secret summary).
4. **Install + load the LaunchDaemon:** `cp` the payload plist → `/Library/LaunchDaemons/com.cyppie.hub.plist` (root:wheel
   0644, CYP-636 single-source), then `launchctl bootstrap system /Library/LaunchDaemons/com.cyppie.hub.plist` +
   `launchctl enable system/com.cyppie.hub` (the modern `bootstrap`/`enable` verbs; `load -w` on older macOS).

**Custody = the ratified option (a), already ported in CYP-670** — a dedicated non-root user + the 0600
`/etc/cyppiehub/hub.env` sourced by the wrapper. **No new custody decision** (unlike the Linux pass, where option-a was
the ratification): macOS inherits the same call. `ProtectSystem`/`NoNewPrivileges` have no launchd analog → the macOS
posture is **SIP + TCC + the non-root user** (as CYP-670 already documents), not a faked systemd sandbox.

## 4. First-run operator setup (CYP-629) — reused as-is, no macOS delta

CYP-629 is cross-platform (the operator GUI + the existing operator-gated `GET/PUT /api/config/apikey`). On macOS the
operator does the same first-run flow (`{set:false}` → PUT → `{set:true,masked}`). **No macOS-specific work.** (The live
app-wiring of the first-run gate is the separate opt-in-off seam, being routed to Team-1 frontend.)

## 5. Uninstall + data-preservation (macOS analog of CYP-630/635)

macOS `.pkg` has **no built-in uninstaller** (unlike `apt remove`/`purge`). Ship an `uninstall.sh` (+ wire jpackage's
pkg **uninstaller-script** hook where available) with the **same preserve-default / explicit-wipe** semantics:

- **`uninstall.sh` (default = preserve)** — `launchctl bootout system /Library/LaunchDaemons/com.cyppie.hub.plist` (stop
  + unload; `launchctl unload -w` on older macOS), `rm` the plist, `rm -rf /opt/cyppiehub` (the app payload),
  `pkgutil --forget com.cyppie.hub` (drop the receipt) — but **PRESERVE** `/var/lib/cyppiehub` **and**
  `/etc/cyppiehub/hub.env` (incl. `CYPPIE_MASTER_KEY`) so a reinstall reattaches.
- **`uninstall.sh --wipe-data` (explicit, destructive)** — also `rm -rf /var/lib/cyppiehub`, `rm /etc/cyppiehub/hub.env`,
  and delete the `_cyppie` user/group (`dscl . -delete /Users/_cyppie`) — the macOS analog of `apt purge` / `-WipeData`.
- **★ Same master-key ↔ SecretStore coupling as CYP-630/635:** preserve keeps **both** the data dir and the master
  key/tokens (a preserved `hub-secrets.db` would be orphaned without its key); wipe removes **both together** — never a
  half-deleted state (orphaned key XOR orphaned store).

## 6. API-key posture (Option B) — identical

Unchanged from CYP-629/635: the `ANTHROPIC_API_KEY` is **plaintext in an owner-only (0600) file** under
`/var/lib/cyppiehub`, protected by filesystem permissions — **not** encrypted-at-rest (deferred to CYP-220). The master
key protects only the SecretStore. `deploy/macos` carries the same honest posture as `deploy/linux`/`deploy/windows` — no
false "encrypted-at-rest" claim.

## 7. Intel + ARM64, and what is / isn't provable off a Mac

- **Two arch-specific `.pkg`s.** jpackage builds a native installer for the **host arch only**, and the jlink JRE is
  arch-specific → an Intel `.pkg` is built on an Intel Mac, an ARM64 `.pkg` on Apple Silicon. This matches the epic's two
  deliverables (Intel-Mac → ARM64-Mac). A **universal2** single package (`lipo`-merged runtime) is a *later* option, not
  MVP.
- **★ The runner gap (flag — analog of the Windows CYP-625 dependency):** `jpackage --type pkg`, `launchctl`, `dscl`,
  `pkgutil` and the live install/boot/uninstall lifecycle need a **real Mac host** (×2 arches). This build host is Linux
  → the **live proof is Mac-runner-gated**, exactly as the `.msi` is Windows-runner-gated.
- **What IS buildable/reviewable here (no Mac):** the gradle `--type pkg` branch, the `pkg-resources`
  (`preinstall`/`postinstall`/uninstaller) scripts, the customer `com.cyppie.hub.plist` + `hub-run.sh`, the
  `uninstall.sh`, `sh -n` syntax checks, and the honest README — i.e. **design + structural packaging complete**, exactly
  how the Windows chain was landed ("design+config complete, execution blocked"). Only the final on-Mac lifecycle proof
  hands off.
- **Open decision (§1):** whether `jpackage --type pkg` honors `--install-dir /opt` for a headless (non-`.app`) layout,
  or forces `/Applications/CyppieHub.app`. Mirror-Linux `/opt/cyppiehub` is proposed for parity + a clean daemon path; if
  pkg pins `/Applications`, the plist/scripts reference the `.app` internal path instead. **Confirmed on the Mac spike.**

## 8. Signing / notarization — the macOS analog of the Windows cert (deferred, but flag the lead-time)

Modern macOS **Gatekeeper** blocks an unsigned/un-notarized `.pkg` by default (GUI double-click → "unidentified
developer"). Two dispositions, mirroring the ratified Windows code-signing decision:

- **MVP = unsigned**, documented install path that bypasses the GUI Gatekeeper prompt for a self-hoster:
  `sudo installer -pkg CyppieHub.pkg -target /` (the CLI installer does not enforce the GUI quarantine prompt), or
  right-click → Open. Honest README note.
- **Post-MVP hardening = signed + notarized:** jpackage `--mac-sign --mac-signing-key-user-name "<Developer ID
  Installer>"` (+ `--mac-signing-keychain`), then `xcrun notarytool submit` + `xcrun stapler staple`. This needs an
  **Apple Developer ID** — **⚠ Auftraggeber-procured, with Apple Developer Program enrollment lead-time; flag EARLY**
  (the direct twin of the Windows cert lead-time already deferred in the epic). Off my plate to procure; the gradle hook
  (`--mac-sign …`) is a small additive follow-up once the cert exists.

## 9. ★ §4b check (PL standing condition) — NONE

Per the PL's condition, I checked for a real §4b element (a **new trust/security boundary** or scope **beyond pure
infra-delivery**). **There is none.** The macOS `.pkg` reproduces the **exact trust posture of the already-ratified
`.deb`**, only on macOS primitives:

- **Same secret model:** on-host provisioning (no secret ships in the package), custody = the already-ratified option-a
  0600 `hub.env` owned by a dedicated **non-root** user (CYP-670 already ported this to macOS). No new credential
  surface — `ProvisionMain` is the existing cross-platform entrypoint, unchanged.
- **Same privileged boundary as the `.deb`/cold-boot:** the `postinstall` runs as **root** (as every macOS installer
  script and every dpkg maintainer script does), does the privileged setup (create `_cyppie`, provision, install the
  LaunchDaemon), then the daemon **drops to non-root `_cyppie`** — privileged→drop, no standing root service. No secret
  value ever touches a script argument or a log (only ProvisionMain's non-secret summary + a 0600 transient that is
  securely deleted).
- **No topology/trust change:** Topology A (loopback, no relay), same as the ratified epic. Signing introduces the Apple
  Developer ID trust anchor, but that is **deferred procurement** (like the Windows cert), not a boundary crossed in this
  build.

⟹ Pure packaging/infra-delivery. If ratification surfaces any concern here, I hold — but I assess no Auftraggeber
escalation is warranted, consistent with the PL's §4a framing.

## 10. PL review-criteria mapping (build accordingly)

| PL criterion | How this design meets it |
|---|---|
| **Install-mechanism security** (privileged steps clean, cold-boot reflex) | `postinstall`/`launchctl bootstrap` run as root only for setup; explicit privileged→drop to `_cyppie`; the LaunchDaemon plist is the reviewable single-source (`--app-content` → `cp`), no inline privilege. |
| **Secret-safety** (no values in scripts/logs) | Secrets minted on-host by `ProvisionMain` → a 0600 `mktemp` → assembled into 0600 `hub.env` owned `_cyppie` → transient securely deleted (`rm -P`). Never in the world-readable plist (that's why the sourcing wrapper exists), never in a script arg, never logged (non-secret summary only). |
| **Uninstall preserve/purge parity** (like the `.deb`) | `uninstall.sh` default preserves data + master key (reattach); `--wipe-data` removes data + secrets + user together; the master-key↔SecretStore coupling is explicit — never half-deleted. |

## 11. Proposed stories (under Epic CYP-623 — pending ratification; PO creates the CYP keys)

0. **macOS packaging spike** (analog of CYP-625, Mac-host) — confirm on a real Mac: `--type pkg` + `--resource-dir`
   preinstall/postinstall/uninstaller wiring, `--install-dir` honoring (§7), the native-dep self-extract (sqlite-jdbc +
   pty4j) under the jlink JRE inside a `.pkg`, per arch. The one genuinely Mac-gated unknown-retirement.
1. **`.pkg` packaging** — the `hubInstaller` macOS branch (`--type pkg` + `--resource-dir` + `--app-content`); arch-aware
   jlink; `deploy/macos/` skeleton.
2. **launchd + provisioning scripts** — customer `com.cyppie.hub.plist` + `hub-run.sh` (source-env wrapper) +
   `pkg-resources/{CyppieHub-preinstall,CyppieHub-postinstall}` (dscl `_cyppie` + reuse `CyppieHubProvision` + custody-a +
   `launchctl bootstrap/enable`).
3. **Uninstall preserve/purge** — `uninstall.sh` (+ pkg uninstaller hook); the master-key↔SecretStore coupling (§5).
4. **`deploy/macos/README.md`** — honest Option-B posture, the Gatekeeper/unsigned MVP note + deferred notarization, the
   launchd/pkg lifecycle + Mac-runner verification steps.
5. **★ e2e proof on a Mac host (Intel + ARM64)** — build → `installer -pkg` → the LaunchDaemon boots → `/api/health=ok`
   → `uninstall.sh` preserves → reinstall reattaches → `--wipe-data` wipes. The Mac-runner leg (the story the Linux
   `.deb` could do here but macOS cannot).

## 12. Summary

The macOS `.pkg` is the `.deb`'s twin: **one jpackage call** (`--type pkg` + `--resource-dir`, Context7-confirmed to
support background-service pre/postinstall + uninstaller scripts), **launchd** for the service (reusing the CYP-670
plist/wrapper form + D2 Core=0), **`ProvisionMain` reused verbatim** with the **already-ratified option-a custody**, and
**preserve/purge uninstall** with the master-key coupling. The only genuinely new mechanism is `dscl` (vs `useradd`) for
the `_cyppie` user. **No §4b element** — same trust posture as the ratified `.deb`. Design + structural packaging are
buildable/reviewable here; the **live build + install proof is Mac-runner-gated** (Intel + ARM64), the direct analog of
the Windows-runner dependency. Signing/notarization deferred like the Windows cert (⚠ flag the Apple Developer ID
lead-time). Awaiting PL ratification of this artifact → then build in §11 order.
