# CYP-623 — macOS `.pkg` installer design (non-Windows survey, self-hosting first-class)

> Status: **DESIGN PASS (ungated survey)**. Live-proof is **Mac-host-gated** (no macOS build host in CI) — this
> doc closes the design carry; the build/notarize/install proof runs on a Mac on a PL-GO, like CYP-670's
> LaunchDaemon delivery. Author: Backend. Mirrors the PROVEN Linux `.deb` (CYP-626/634/635/636/687) 1:1.

## 1. Goal & the gap

The hub ships an OS-native installer per platform (one `installDist → jlink → jpackage` chain, `--type` per OS,
`server/build.gradle.kts` CYP-626 block). Today: **Windows `.msi`** (design-done, CYP-625 spike), **Linux `.deb`**
(PROVEN, ships `d132fdab…`), **macOS = bare `dmg`** (`installerType = … os.isMacOsX -> "dmg"`, line 275).

A `dmg` is a **drag-to-`/Applications`** archive with **no install-time hook** → it cannot create the service user,
provision secrets, or register a boot daemon. So the hub-**as-a-service** story has no macOS installer today, even
though the macOS **service itself already exists**: the CYP-670 boot-persistent LaunchDaemon
(`deploy/launchd/com.cyppie.hub.plist` + `hub-run.sh`, hardened per CYP-685/D2). The gap is purely the **installer
that wires that daemon up** — the macOS analog of the `.deb`'s `postinst`.

**CYP-623 macOS deliverable: switch macOS from `dmg` → jpackage `--type pkg`, plus a `postinstall` script that
mirrors the `.deb` `postinst`** (create `_cyppie`, provision secret-free, relocate the JDK per CYP-685, install +
`launchctl load` the existing plist). Secret-free installer; secrets minted on the host at install.

## 2. What already exists (reuse, don't re-invent)

| Piece | Linux `.deb` | macOS today | macOS `.pkg` plan |
|---|---|---|---|
| Service unit | `deploy/linux/cyppiehub.service` (systemd) | **`deploy/launchd/com.cyppie.hub.plist`** (CYP-670) ✅ | ship it in the payload + `postinstall` copies → `/Library/LaunchDaemons/` |
| Run wrapper | launcher `.cfg` + jvmargs | **`deploy/launchd/hub-run.sh`** (CYP-685 absolute-JAVA, 0600 custody) ✅ | ship it; the plist already `exec`s it |
| Provisioning | `postinst` → `CyppieHubProvision` → `/etc/cyppiehub/hub.env` (0600) | — | **`postinstall`** runs the SAME `CyppieHubProvision` add-launcher |
| Service user | `useradd -r cyppie` | plist `UserName=_cyppie` (CYP-670) | `postinstall` creates `_cyppie` via `dscl`/`sysadminctl` |
| Secret custody | 0600 EnvironmentFile, non-root user (option a) | 0600 `/etc/cyppiehub/hub.env` sourced by the wrapper (option a) ✅ | identical — one env-file, one custody model |
| Core-dump-off (D2) | systemd `LimitCORE=0` | plist `Soft/HardResourceLimits Core=0` ✅ | inherited from the shipped plist |

**The `.pkg` adds one new artifact — the `postinstall` script — and one build change — `--type pkg` + macOS
resource wiring.** Everything else is already reviewed + (for the daemon) live on the box.

## 3. The `postinstall` (mirror of `deb-resources/postinst`)

Same four idempotent, preserve-safe steps, in macOS idiom:

1. **Dedicated non-root daemon user `_cyppie`** (idempotent). macOS reserves `_`-prefixed daemon users; create via
   `sysadminctl -addUser _cyppie -role … -home /var/empty -shell /usr/bin/false` **or** the classic `dscl . -create`
   sequence with a free UID in the system range (`<500`). (Weiche §5.b — which mechanism; both are non-interactive.)
2. **Data + secrets dirs**, owner-only where secrets live: `/var/lib/cyppie-hub` (+`/logs`, +`/var/log/cyppie-hub`)
   owned `_cyppie:_cyppie 0750`; `/etc/cyppiehub` `root:wheel 0755`. (Paths match the plist's `/opt/cyppie-hub` prefix
   + the wrapper's `/etc/cyppiehub/hub.env`.)
3. **PROVISION once, only if `/etc/cyppiehub/hub.env` absent** (reinstall reattaches, never re-mints): run the shipped
   `CyppieHubProvision` add-launcher → mint `CYPPIE_MASTER_KEY`/`OPERATOR_TOKEN`/`HUB_TOKEN_PO` into a `mktemp` 0600
   file, append the non-secret `PLATFORM_*` paths → `/etc/cyppiehub/hub.env`, `chown _cyppie`, `chmod 0600`,
   secure-delete the temp. **★ CYP-685 cold-boot: relocate the corretto-21 JDK → `/opt/cyppie-hub/jdk`, `chown -R
   _cyppie`, and set `JAVA_HOME` in `hub.env`** — a non-root LaunchDaemon's `PATH` has no JDK and cannot read a console
   user's `~`; the wrapper fails closed on a bare `java`.
4. **Install + load the daemon**: `install -m 0644` the shipped plist → `/Library/LaunchDaemons/com.cyppie.hub.plist`
   (root:wheel, 0644 — launchd refuses group/other-writable), then `launchctl bootstrap system …` (or
   `load -w`). RunAtLoad+KeepAlive bring it up headless on reboot.

`preinstall`/`postinstall` are jpackage macOS resource-dir hooks (§5.a — verify the exact resource filenames against
the target JDK's jpackage on the Mac host). Uninstall (macOS `.pkg` has no native uninstaller) → ship a
`cyppie-hub-uninstall.sh` that `launchctl bootout` + removes the plist, mirroring `prerm`/`postrm` (remove-preserves
`/var/lib`; a `--purge` flag wipes) — surfaced as Weiche §5.d.

## 4. Build change (`server/build.gradle.kts`)

Minimal, mirrors the Linux branch:
- `installerType`: `os.isMacOsX -> "pkg"` (was `"dmg"`). Keep a separate `hubDmg` task only if a bare dev-drag artifact
  is still wanted (§5.c).
- macOS `jpackage` args (added under `if (isMacOsX)`, analogous to `if (isLinux)`):
  `--type pkg`, `--mac-package-identifier com.cyppie.hub`, `--mac-package-name CyppieHub`,
  `--install-dir /opt` (→ `/opt/cyppie-hub`, matching the plist prefix — Weiche §5.b),
  `--resource-dir deploy/macos` (the pre/post-install scripts + uninstall), `--app-content` the plist + `hub-run.sh`
  + `hub.jvmargs` (SINGLE-SOURCE, exactly like `.deb` `--app-content` ships the unit).
  Reuse the SAME `--add-launcher CyppieHubProvision` (provisioning) + `CyppieHubAcceptance` (BYOA acceptance) as the
  `.deb`. `--java-options` from `hubJvmArgs` (single-sourced, CYP-678). Signing flags gated on §5.e.

## 5. ★ Weichen (decisions for the PO / PL — I did NOT invent the envelope)

- **(a) jpackage macOS post-install hook** — the exact resource-dir script name (`…-post-install.sh` vs a
  `--mac-*` flag) and whether `--type pkg` runs it as root. **Verify on the Mac host against the shipped JDK's
  jpackage** (docs drift by JDK version). Low-risk (the hook exists; only the filename/root-context is to pin).
- **(b) Install root + user-create mechanism** — `/opt/cyppie-hub` (matches the CYP-670 plist, my recommendation)
  vs `/usr/local/cyppie-hub`; and `sysadminctl` vs `dscl` for `_cyppie`. Recommend `/opt` + `sysadminctl` (modern,
  non-interactive).
- **(c) dmg vs pkg** — the `.pkg` is the SERVICE installer; the `dmg` cannot register the daemon. Recommend **replace
  `dmg` with `pkg`** for the service path; keep a `dmg` task only if a bare drag-install dev artifact is explicitly
  wanted. Decide.
- **(d) Uninstall story** — ship `cyppie-hub-uninstall.sh` (bootout + remove, preserve-safe; `--purge` wipes)?
  Recommend yes (parity with `.deb` `prerm`/`postrm`).
- **(e) Signing / notarization** — **self-hosting (Option-A, user builds+installs on their own Mac) = UNSIGNED local
  pkg works** (no Gatekeeper block for a locally-built pkg run with the installer). **Distribution (shipping the pkg
  to users) REQUIRES Developer-ID-Installer signing + Apple notarization** (needs an Apple Developer account + a
  notarization pass in CI). MVP recommendation: unsigned/self-built now; notarization is a distinct distribution-track
  ticket. Decide whether distribution is in the CYP-623 envelope or a follow-on.

## 6. Proven vs Mac-host-gated

- **Proven / reused**: the LaunchDaemon plist + wrapper (CYP-670/685, live on the box), the provisioning add-launcher
  + custody model (`.deb`, PROVEN), the jlink+jpackage chain + add-launchers (`.deb`, PROVEN), secret-free posture.
- **Mac-host-gated (live-proof on a PL-GO, no hot-migration — same discipline as CYP-670)**: the actual `jpackage
  --type pkg` build on macOS, the postinstall running as root, `sysadminctl` user-create, `launchctl bootstrap`, and
  (if in envelope) signing/notarization. The Windows analog CYP-625 is likewise runner-blocked — this is the same
  class of "design-done, host-gated build".

**Bottom line:** the macOS `.pkg` is a **thin, low-risk delta over the proven `.deb`** — one `postinstall` script +
a `--type pkg` build branch, reusing the already-live daemon. The open items are the five Weichen above (mostly
pin-on-Mac-host details), not architectural unknowns.
