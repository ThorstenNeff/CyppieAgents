# CYP-623 — Linux `.deb` installer — design pass

> Status: **DESIGN — awaiting PO review → ratification (esp. §3 secret custody) → then build.**
> Reprioritized (Auftraggeber, 2026-07-16): Linux `.deb` is the next platform, **before CYP-633** (paused, latent).
> Convention: design-first. This is a **lean** pass — the Windows chain (CYP-625→630) is the precedent; the
> architecture + Topology A are identical, only the Linux **mechanisms** differ. This doc maps the 6 stories → Linux
> and names the Linux-specific calls.

## 0. The big advantage over `.msi`

`jpackage` builds a native installer only for the **host** OS — so the Windows `.msi` + conpty leg is stuck behind a
Windows-runner (the open CYP-623 dependency). **The `.deb` has no such gap: it builds AND installs/boots on a Linux
host — including this build host** (`dpkg-deb`, `fakeroot`, `dpkg`, `systemctl` all present). So the Linux chain is
**fully e2e-provable here** (build the `.deb` → `dpkg -i` → the systemd service boots → `/api/health = ok` → uninstall
preserves data), not a hand-off. This is the reason to do Linux next.

## 1. Packaging (Linux analog of CYP-626)

`hubInstaller` already selects `--type` per OS (`msi`/`dmg`/`app-image`). Linux gets **`--type deb`** — the same
`installDist → jlink → jpackage` chain, only the final wrapper changes.

- `jpackage --type deb` needs **`dpkg`/`fakeroot`** on the build host (present here). Same jlink runtime
  (`java.se,jdk.unsupported,jdk.crypto.ec,jdk.jfr` — CYP-625/626), same two mandatory JVM args baked via
  `--java-options`. The `.deb` installs to `/opt/cyppiehub` (jpackage default `--install-dir`) with the launcher +
  bundled JRE 21.
- **★ Fold in the JDK-21 toolchain pin (README §6 follow-up):** `hubJlink`/`hubInstaller` currently resolve
  `jlink`/`jpackage` from the Gradle JVM's `java.home` — pin it via a Gradle **toolchain** so the bundled JRE is
  deterministically 21 regardless of the Gradle JVM. Small; worth doing with the deb branch (the deb build is the
  first time we exercise packaging on a second OS, so determinism matters now).
- jpackage `--type deb` supports `--linux-package-name cyppiehub`, `--linux-app-category`, `--linux-shortcut`, and —
  key for the service — **`--linux-package-deps`** and the maintainer-script hooks (below). jpackage can embed
  `--resource-dir` scripts (`postinst`/`prerm`/`postrm`) so the service install + data-preservation ride in the deb.

## 2. Service (Linux analog of CYP-627 WinSW → systemd)

A **systemd unit** `cyppiehub.service` replaces the WinSW wrapper (systemd is the native Linux service manager — no
third-party wrapper needed, unlike Windows):

```ini
[Unit]
Description=Cyppie self-hosted agent hub
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=cyppie                                  # dedicated system user (§3)
ExecStart=/opt/cyppiehub/bin/CyppieHub       # the jpackage launcher (its .cfg owns the 2 JVM args — NOT here)
WorkingDirectory=/var/lib/cyppiehub          # the data dir (PLATFORM_GIT_ROOT)
EnvironmentFile=/etc/cyppiehub/hub.env       # §3 — the secret custody call
Restart=on-failure
RestartSec=10
StartLimitIntervalSec=3600                   # escalation analog: cap restarts/hour (StartLimitBurst)
StartLimitBurst=5
TimeoutStopSec=30                            # graceful stop — JVM flushes stores + pty4j children (analog CYP-627 30s)
KillMode=mixed                               # SIGTERM the main, then the group (pty4j children) — no orphans
# journald captures stdout/stderr (no logpath needed — journalctl -u cyppiehub)

[Install]
WantedBy=multi-user.target
```

Mapping to CYP-627: headless/auto-start = `WantedBy=multi-user.target`; restart-on-failure + escalation =
`Restart=on-failure` + `StartLimit*`; stdout capture = **journald** (`journalctl -u cyppiehub` — cleaner than WinSW's
file logs); graceful stop + pty4j-children = `TimeoutStopSec` + `KillMode=mixed`; the 2 JVM args ride the launcher
`.cfg` (not the unit), exactly as the WinSW design.

## 3. ★ Provisioning + secret custody (Linux analog of CYP-628 — THE design call to ratify)

`provision.sh` (invoked by the deb `postinst`) mirrors `provision.ps1`, and **reuses the already-built, cross-platform
`CyppieHubProvision`/`ProvisionMain`** (CYP-628) verbatim — it mints `CYPPIE_MASTER_KEY` (single-line Tink keyset) +
tokens + the default `platform.config.json`. Config → `/var/lib/cyppiehub`; secrets → the transient `--secrets-out`
file the script consumes then `shred`s. **The only genuinely new decision is the Linux secret-custody model:**

| Option | How | Pros | Cons |
|---|---|---|---|
| **(a) dedicated system user + `EnvironmentFile` 0600** (RECOMMENDED MVP) | `useradd -r cyppie`; write the 3 secrets to `/etc/cyppiehub/hub.env` (0600, `chown cyppie`); the unit's `EnvironmentFile=` loads them; `Secrets.fromEnv` reads them. | Simplest; standard; the **direct analog of the Windows service-account-env**; `EnvKeysetMasterKeyCustody` reads the master key from env out-of-the-box (no app change). | Secrets in a 0600 file (same posture as Windows / the API key). |
| **(b) systemd `LoadCredential=` / `systemd-creds`** (hardening) | `systemd-creds encrypt` the secrets at rest (host/TPM key); the unit loads them into `$CREDENTIALS_DIRECTORY` (tmpfs) at start. | Secrets **encrypted at rest**, not in a plaintext file; not in the process env (less leak surface). | The app reads env, not `$CREDENTIALS_DIRECTORY/<name>` files → needs a tiny `ExecStartPre`/wrapper that exports them to env before the launcher, **or** an app change to read the credentials dir. More moving parts. |
| **(c) `DynamicUser=yes`** | systemd mints an ephemeral uid per start. | No standing user; auto-sandboxed. | A **stateful** hub needs a stable owner for `/var/lib/cyppiehub` (the uid churns); `StateDirectory` helps but complicates the data-dir + the ACL model. Poor fit for persistent state. |

**Backend recommendation: (a) for the MVP** (simplest, standard, matches the Windows service-account-env posture and
the ratified Option-B API-key posture), **with (b) `systemd-creds` as a follow-up hardening** (encrypted-at-rest
secrets via a small env-shim). This is the Linux twin of the Windows master-key-custody decision — **PO ratifies (an
Auftraggeber call if it changes the security posture)**. (c) is not recommended for a stateful hub.

## 4. First-run operator setup (CYP-629) — reused as-is, no Linux delta

CYP-629 is cross-platform: the operator GUI + the existing operator-gated `GET/PUT /api/config/apikey` endpoints. On
Linux the operator does the same first-run flow (`{set:false}` → PUT → `{set:true,masked}`). **No Linux-specific work.**

## 5. Uninstall + data-preservation (Linux analog of CYP-630) — the deb semantics map for free

Debian's `remove` vs `purge` maps **naturally** onto CYP-630's preserve-vs-wipe:
- **`apt remove cyppiehub` (default)** — the `prerm` stops + disables the service; the `postrm remove` removes the
  package files (`/opt/cyppiehub`) but **PRESERVES the data dir** (`/var/lib/cyppiehub`) + the secrets — a reinstall
  reattaches. This is dpkg's own default (a package's `remove` does not delete conffiles/state), so data-preservation
  is the **native** behavior, matching CYP-630.
- **`apt purge cyppiehub` (explicit)** — the `postrm purge` also deletes `/var/lib/cyppiehub` **and** the
  account-scoped secrets (`/etc/cyppiehub/hub.env`) + the system user — the destructive wipe, gated behind the explicit
  `purge` verb (the Linux analog of `-WipeData`).
- **★ Same master-key ↔ SecretStore coupling as CYP-630:** `remove` keeps both the data dir AND the master key/tokens
  (else the preserved `hub-secrets.db` is orphaned); `purge` removes both together — never half-deleted.

The maintainer scripts (`prerm`/`postrm`) ship inside the `.deb` via jpackage's `--resource-dir`.

## 6. API-key posture (Option B) — identical

Unchanged from CYP-629: the ANTHROPIC_API_KEY is **plaintext in an owner-only (0600) file** under `/var/lib/cyppiehub`,
protected by filesystem permissions — **not** encrypted-at-rest; encryption-at-rest deferred to CYP-220. No false
claim; the master key protects only the SecretStore. The `deploy/linux` docs carry the same honest posture as
`deploy/windows`.

## 7. MVP scope + what's e2e-provable HERE (no hand-off)

**MVP:** `hubInstaller --type deb` (+ the JDK-21 toolchain pin) → a `cyppiehub.deb` bundling `:server` + JRE 21 + the
systemd unit + `provision.sh` + the maintainer scripts. `provision.sh` (option-a custody) + first-run (reused) +
`remove`/`purge` data-preservation.

**Proven HERE (this build/design pass can e2e-prove, unlike the `.msi`):** build the `.deb` → `dpkg -i` → the systemd
service auto-starts → `journalctl -u cyppiehub` shows `Application started` + `Responding at 127.0.0.1:8787` →
`curl /api/health = ok` (on the `provision.sh`-minted master key + tokens + config, master-key-gated SecretStore up) →
`systemctl stop` graceful → `apt remove` preserves `/var/lib/cyppiehub` → reinstall reattaches → `apt purge` wipes.
That is the full CYP-630-style lifecycle, on a real service, on this host.

**Later:** Intel-Mac + ARM64-Mac `.dmg` (jpackage `--type dmg` + launchd plist — needs a Mac host, the next
platform-runner gap); `systemd-creds` custody hardening (§3b); code-signing (`.deb` can be signed with `dpkg-sig`/a
repo GPG key — post-MVP, Auftraggeber-procured like the Windows cert).

## 8. Proposed stories (under Epic CYP-623 — pending ratification)

1. **`.deb` packaging** — `hubInstaller --type deb` branch + the JDK-21 toolchain pin; `deploy/linux/` skeleton.
2. **systemd unit** — `cyppiehub.service` (§2) + the maintainer-script wiring (`--resource-dir`).
3. **`provision.sh` + secret custody** — reuse `CyppieHubProvision`; option-a custody (dedicated user + EnvironmentFile 0600).
4. **`remove`/`purge` data-preservation** — `prerm`/`postrm` (§5); the master-key↔SecretStore coupling.
5. **`deploy/linux/README.md`** — the honest Option-B posture + the systemd/dpkg lifecycle + verification.
6. **★ e2e proof on this host** — build → `dpkg -i` → service boots → `/api/health=ok` → `remove` preserves → `purge` wipes (the story the `.msi` could not do).
