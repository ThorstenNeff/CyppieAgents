#!/usr/bin/env bash
#
# CyppieAgents — B1 Live-Dogfood desktop client launcher (Step 1).
# Points the desktop app at LIVE staging (api.cyppie-agents.com) and runs it.
#
# Usage:
#   ./run-dogfood-client.sh              # run from inside the repo checkout
#   REPO_DIR=/path/to/KMPCyppieAgents ./run-dogfood-client.sh   # or point it at the repo
#   FIX_REF=<sha|branch> ./run-dogfood-client.sh                # override the commit to run (default: CYP-575 fix)
#   SKIP_CHECKOUT=1 ./run-dogfood-client.sh                     # run whatever is already checked out, no git touch
#
# Requirements: a graphical display + a browser (NOT a headless server), JDK 17+ (JetBrains Runtime ideal).

set -euo pipefail

# Build ref: origin/develop carries the full GitHub-login fix (CYP-575 browser-launch + CYP-576
# native OIDC token-exchange + the auth-robustness follow-on). Detached-HEAD checkout of the ref.
FIX_REF="${FIX_REF:-origin/develop}"

# --- 0. Locate the repo -------------------------------------------------------
REPO_DIR="${REPO_DIR:-$(pwd)}"
if [[ ! -f "$REPO_DIR/settings.gradle.kts" || ! -d "$REPO_DIR/app/desktopApp" ]]; then
  echo "ERROR: '$REPO_DIR' does not look like the KMPCyppieAgents repo" >&2
  echo "       (no settings.gradle.kts / app/desktopApp). Run from the repo, or set REPO_DIR." >&2
  exit 1
fi
cd "$REPO_DIR"

# --- 0.5 Ensure the CYP-575-fixed client is checked out ----------------------
# The fix lives on a branch that's checked out in another git worktree, so a
# `git checkout <branch>` is refused. We check out the COMMIT instead (detached
# HEAD), which is always allowed. Skipped if already there or SKIP_CHECKOUT=1.
if [[ "${SKIP_CHECKOUT:-0}" != "1" ]]; then
  echo "==> Fetching origin (for the CYP-575 fix $FIX_REF)..."
  git fetch origin --quiet || { echo "ERROR: git fetch failed (offline?)." >&2; exit 1; }

  TARGET="$(git rev-parse --verify --quiet "${FIX_REF}^{commit}" || true)"
  if [[ -z "$TARGET" ]]; then
    echo "ERROR: commit/ref '$FIX_REF' not found even after fetch." >&2
    exit 1
  fi
  CURRENT="$(git rev-parse HEAD)"

  # Refuse to build/checkout over uncommitted tracked changes — checked on BOTH
  # paths (already-at-target AND needs-checkout) so a dirty tree can never
  # silently ship a build that differs from $FIX_REF. (Assist completeness-critic
  # Minor 5: the guard used to live only in the needs-checkout branch, so being
  # already on the target commit with a dirty tree built silently.)
  if ! git diff-index --quiet HEAD -- 2>/dev/null; then
    echo "ERROR: working tree has uncommitted changes — commit/stash them, then re-run" >&2
    echo "       (or: SKIP_CHECKOUT=1 ./run-dogfood-client.sh to run the current checkout as-is)." >&2
    exit 1
  fi

  if [[ "$CURRENT" == "$TARGET" ]]; then
    echo "==> Already at the fix ($FIX_REF) — no checkout needed."
  else
    echo "==> Checking out the fix commit $FIX_REF (detached HEAD)..."
    git checkout --detach "$TARGET" --quiet
    echo "    (to return afterwards: git checkout develop)"
  fi
fi

# --- 1. Sanity-check the JDK (17+) -------------------------------------------
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: no 'java' on PATH — install JDK 17+ (JetBrains Runtime recommended)." >&2
  exit 1
fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F. '{print ($1=="1")?$2:$1}')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "ERROR: JDK 17+ required, found major version '${JAVA_MAJOR:-unknown}'." >&2
  exit 1
fi

# --- 2. Live-staging environment (the 7 remote-path vars) --------------------
export CYPPIE_AUTH_LIVE=true
export CYPPIE_AUTH_ORIGIN=https://api.cyppie-agents.com
export CYPPIE_AUTH_PROXY=https://api.cyppie-agents.com/.ory/kratos/public/
export CYP_REMOTE_HUB=true
export CYPPIE_CP_BASE_URL=https://api.cyppie-agents.com
export CYPPIE_REMOTE_RELAY_URL=wss://api.cyppie-agents.com/relay
# CYP-620: activate the multiplexed transport on the CLIENT (the fix for the pool-exhaustion /
# "Server unreachable" remote bug). This is the CLIENT-side flag (RemoteHubMode.jvm.kt:45,
# reads CYP_MUX_TRANSPORT=true). The HUB has its OWN separate flag (CYPPIE_REMOTE_TRANSPORT=mux,
# set on the staging hub at deploy). BOTH sides must be mux — the G7 hello refuses a mismatch.
export CYP_MUX_TRANSPORT=true

# --- 2b. OOM-safety: route ALL build/JVM scratch to DISK, never RAM ----------
# /tmp on this host is tmpfs (RAM-backed) with 0 swap, so anything written there
# consumes physical RAM. An ~11 GB build-output dump in /tmp OOM-killed processes
# on 2026-07-15. Route TMPDIR (the forked app JVM's java.io.tmpdir) + the Gradle
# JVM's java.io.tmpdir to a disk path (vda1 has ~499 GB free) so the desktop build
# never eats RAM. Override with BUILD_SCRATCH=/some/disk/path if you prefer.
BUILD_SCRATCH="${BUILD_SCRATCH:-$HOME/.cyppie-build-scratch}"
mkdir -p "$BUILD_SCRATCH"
export TMPDIR="$BUILD_SCRATCH"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.io.tmpdir=$BUILD_SCRATCH"
echo "==> Build scratch (DISK, not RAM-tmpfs /tmp): $BUILD_SCRATCH"

echo "==> Repo:  $REPO_DIR"
echo "==> Java:  major $JAVA_MAJOR"
echo "==> Hub:   https://api.cyppie-agents.com  (relay: wss://api.cyppie-agents.com/relay)"
echo "==> Launching desktop client (--no-daemon so the env reaches the app JVM)..."
echo
echo "    NEXT: Login (GitHub) -> pick hub 'hub_c1d6f5ffd892a03d' -> Remote -> Verbinden"
echo "    Then STOP at the fingerprint word-list and paste the words to the PO before pinning."
echo

# --- 3. Force a FRESH app recompile (kill stale/mixed builds) -----------------
# The OOM crashes corrupted Gradle's incremental-compile state, so a run could
# ship a MIXED build (app/shared fresh but app/desktopApp stale → old-looking UI,
# and an untrustworthy re-test). Delete the app build outputs so BOTH recompile
# from the checked-out commit. core/protocol/server stay cached (not deleted).
echo "==> Forcing fresh app recompile (clearing app/shared + app/desktopApp build)..."
rm -rf app/shared/build app/desktopApp/build

# --- 4. Launch + ALWAYS capture the client log -------------------------------
# Auto-tee stdout (incl. the [ws-teardown:…] diagnostics) to a fixed path so the
# re-test verdict harness always has the log. NOT `exec`, so the pipe runs.
RETEST_LOG="${RETEST_LOG:-$HOME/retest-client.log}"
echo "==> Client log → $RETEST_LOG   (send THIS file to the PO after the run)"
echo
./gradlew --no-daemon :app:desktopApp:run 2>&1 | tee "$RETEST_LOG"
