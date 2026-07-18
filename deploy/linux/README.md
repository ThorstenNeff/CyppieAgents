# CYP-623 — Linux `.deb` hub install (deploy/linux)

> Epic CYP-623, Topology A (standalone/local). The Linux twin of `deploy/windows`. The `.deb` builds AND
> installs/boots on a Linux host (no external-runner hand-off, unlike the `.msi`).
>
> **⚠ build-host ≠ target (CYP-687 / M1.1).** CYP-636's lifecycle was proven **on the build host** — that is
> necessary but NOT sufficient for the real BYOA target. The Team-2 target is **Ubuntu 26.04 amd64**; the `.deb`
> is built on an Ubuntu-26.04 host (glibc 2.43) so the auto-`Depends` match, and the **install + service +
> BYOA-agent lifecycle is validated on po2's real Ubuntu-26 box**, NOT the build host. **DoD = a REMOTE agent
> registers + connects + exchanges a message BOTH ways over `/ws/hub`** (the BYOA connection), NOT `health=ok`
> (a live service that can do nothing is the expensive false-green).

## Target & prerequisites (Ubuntu 26.04)

- **Runtime = self-contained.** The `.deb` bundles a jlink JRE 21 (jpackage `--runtime-image`); every entry point
  (systemd `ExecStart=/opt/cyppiehub/bin/CyppieHub`, the `CyppieHubProvision` launcher) uses the **bundled**
  runtime — **no system JDK is needed** (the box's stock JDK is irrelevant; there is no bare-`java` PATH lookup —
  the anti-CYP-685). The hub is compiled to Java-21 bytecode, so a system JDK 17 could NOT run it anyway.
- **`git` = declared `Depends`** (CYP-687): the hub clones/pulls the repo and agents work in worktrees, so
  `apt install ./cyppiehub_<ver>_amd64.deb` pulls `git` on a fresh box.
- **`claude` CLI = a separate, non-apt prereq** for the *local-spawn* model (the service user must reach it). Held
  pending the attach-vs-spawn decision (CYP-200): a BYOA/remote agent connects over `/ws/hub` and the hub spawns
  nothing locally, in which case the hub `.deb` needs no `claude` at all.
- **The auto-`Depends` include ~10 X11/audio libs** (`libx11-6`, `libxtst6`, `libasound2t64`, …). These are
  **legitimate** — `java.desktop` (ImageIO + Thumbnailator, the CYP-215 avatar processor) links them; they are
  apt-resolvable on Ubuntu 26 even headless. The hub runs headless (server); avatar processing needs no display.

## Files

| File | Role |
|---|---|
| `cyppiehub.service` | the systemd unit (reviewable master; CYP-634). The `deb-resources/postinst` embeds a mirror it installs to `/lib/systemd/system/`. |
| `deb-resources/{postinst,prerm,postrm}` | the `.deb` maintainer scripts, wired via jpackage `--resource-dir` (CYP-635). |
| `provision.sh` | standalone provisioning (the reviewable twin of the postinst; also for a manual install / a from-scratch re-provision). |

## Install (`apt install ./cyppiehub_<ver>_amd64.deb` / `dpkg -i`)

The `postinst configure` does, idempotently:
1. `useradd -r cyppie` — a dedicated low-privilege system user.
2. `/var/lib/cyppiehub` (data dir, `PLATFORM_GIT_ROOT`, 0750 `cyppie`) + `/etc/cyppiehub` (0755).
3. **Provision** (only if `/etc/cyppiehub/hub.env` is absent — preserve-safe): run the shipped
   `/opt/cyppiehub/bin/CyppieHubProvision` to mint `CYPPIE_MASTER_KEY` (single-line Tink keyset) + `OPERATOR_TOKEN` +
   `HUB_TOKEN_PO` + the default `platform.config.json`; write the 3 secrets + the `PLATFORM_*` paths to
   `/etc/cyppiehub/hub.env`; `shred` the transient file. **Secret values are never logged.**
4. Install + `systemctl enable --now cyppiehub.service`.

**★ Secret custody = ratified option (a):** the secrets live in `/etc/cyppiehub/hub.env` at **0600, owned by `cyppie`**
— the systemd unit's `EnvironmentFile=` loads them; `Secrets.fromEnv` / `EnvKeysetMasterKeyCustody` read them. Never in
world-readable system env, never in the `.deb` (minted on the host at install). (Follow-up hardening: `systemd-creds`
encrypted-at-rest — its own ticket; MVP = parity with Windows.)

## First-run (CYP-629, cross-platform — no Linux delta)

After the service is up, the operator opens the GUI (authenticate with the provisioned `OPERATOR_TOKEN`) →
`GET /api/config/apikey` `{set:false}` → prompt → `PUT` the key → `{set:true,masked}`. Then set the repo/roster.

### ★ Honest API-key posture (Option B) — no false claim
The **ANTHROPIC_API_KEY is stored plaintext in an owner-only (0600) file** (`/var/lib/cyppiehub/.cyppie/…`,
`SqliteProjectConfigStore`), protected by filesystem permissions — **NOT encrypted-at-rest under the master key**.
`CYPPIE_MASTER_KEY` encrypts **only** the SecretStore (hub identity / device / remote tokens), never the API key.
Encryption-at-rest for the API key is **deferred to CYP-220**. We do not ship the self-hoster a protection they do not
have.

## Uninstall — data-preserving by default (CYP-630 mapping is free on Debian)

- **`apt remove cyppiehub`** — `prerm` stops + disables the service; `postrm remove` removes only the unit +
  `/opt/cyppiehub`. **`/var/lib/cyppiehub` + `/etc/cyppiehub/hub.env` (incl. the master key) are PRESERVED** — a
  reinstall reattaches. (Keeping the master key WITH the data is the SecretStore coupling — a preserved store without
  its key would be orphaned.)
- **`apt purge cyppiehub`** — the destructive path: `postrm purge` wipes `/var/lib/cyppiehub` **and**
  `/etc/cyppiehub/hub.env` **and** the `cyppie` user, **together** — never a half-deleted state (orphaned key XOR
  orphaned store). The Linux analog of CYP-630's `-WipeData`.

## Verification (CYP-636, on this host)

```sh
./gradlew :server:hubInstaller                       # → server/build/hub-installer/cyppiehub_<ver>_amd64.deb
sudo apt install ./…/cyppiehub_<ver>_amd64.deb       # postinst: user + provision + enable --now
systemctl status cyppiehub && journalctl -u cyppiehub  # Application started, Responding at 127.0.0.1:8787
curl http://127.0.0.1:8787/api/health                # → ok  (master-key-gated SecretStore up)
sudo apt remove cyppiehub                            # /var/lib/cyppiehub PRESERVED → reinstall reattaches
sudo apt purge  cyppiehub                            # data + secrets + user WIPED together
```

## Automated lifecycle acceptance (CYP-637) — TEST-SCOPED, safe-by-construction

To exercise the full lifecycle on a real host **without risking the live hub**, build the isolated `-test` variant.
Every name/path/port is `-test`-scoped — package `cyppiehub-test`, user `cyppie-test`, `/opt/cyppiehub-test`,
`/var/lib/cyppiehub-test`, `/etc/cyppiehub-test`, unit `cyppiehub-test.service`, ports **18787/18786**. Its `postrm purge`
can only ever wipe the `-test` set; it shares nothing with the live install (different package/user/paths, live tunnel
port 8786). Every destructive op in the maintainer scripts, the unit, and the harness references **only** `-test` names.

```sh
./gradlew :server:hubInstallerTest                   # → server/build/hub-installer-test/cyppiehub-test_<ver>_amd64.deb
sudo ./deploy/linux/test-lifecycle.sh                # root harness: install → health@18787 → remove(preserve) →
                                                     #   reinstall(reattach, same master key) → purge(wipe) → host clean
```

The harness pre-flights (root, free `-test` ports, no pre-existing `-test` artifacts), asserts each step (PASS/FAIL),
proves the master key is UNCHANGED across remove→reinstall (reattach, not re-mint), verifies the host is left clean after
purge, confirms the **live** hub (tunnel port 8786) stayed listening throughout, and purges on any exit via a cleanup
trap. It is a one-off acceptance artifact — not a customer deliverable.
