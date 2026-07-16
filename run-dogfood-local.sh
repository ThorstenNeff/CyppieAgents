#!/usr/bin/env bash
#
# CyppieAgents — LOCAL dogfood launcher (macOS).
#
# Runs the FULL local stack for a dogfood team, entirely on this machine — no remote,
# no relay, no staging:
#   1. the HUB      (:server) on http://127.0.0.1:8787
#   2. the DESKTOP  client (:app:desktopApp:run) in LOCAL mode → connects to the local hub
#
# LOCAL mode = CYP_REMOTE_HUB is *unset* (the app targets HubEndpoint.local, localhost:8787);
# the mux/relay/staging path is never touched.
#
# Usage (from the repo checkout, e.g. /Users/customer/cyppie-agent/KMPCyppieAgents):
#   ./run-dogfood-local.sh
#   REPO_DIR=/Users/customer/cyppie-agent/KMPCyppieAgents ./run-dogfood-local.sh
#   SKIP_CHECKOUT=1 ./run-dogfood-local.sh     # run whatever is checked out, no git touch
#   FIX_REF=<sha|branch> ./run-dogfood-local.sh # override the build ref (default: origin/develop)
#
# Requirements: macOS with a graphical display, JDK 17+ (JetBrains Runtime ideal), git, curl.
#   ANTHROPIC_API_KEY must be exported — the LOCAL hub spawns real Claude agents with it.

set -euo pipefail

FIX_REF="${FIX_REF:-origin/develop}"
REPO_DIR="${REPO_DIR:-$(pwd)}"

# --- 0. Locate the repo -------------------------------------------------------
if [[ ! -f "$REPO_DIR/settings.gradle.kts" || ! -d "$REPO_DIR/app/desktopApp" ]]; then
  echo "ERROR: '$REPO_DIR' does not look like the KMPCyppieAgents repo" >&2
  echo "       (no settings.gradle.kts / app/desktopApp). Run from the repo, or set REPO_DIR." >&2
  exit 1
fi
cd "$REPO_DIR"

# --- 0.5 Check out the dogfood build (detached HEAD of the ref) --------------
if [[ "${SKIP_CHECKOUT:-0}" != "1" ]]; then
  echo "==> Fetching origin ($FIX_REF)..."
  git fetch origin --quiet || { echo "ERROR: git fetch failed (offline?)." >&2; exit 1; }
  TARGET="$(git rev-parse --verify --quiet "${FIX_REF}^{commit}" || true)"
  [[ -z "$TARGET" ]] && { echo "ERROR: ref '$FIX_REF' not found even after fetch." >&2; exit 1; }
  if ! git diff-index --quiet HEAD -- 2>/dev/null; then
    echo "ERROR: working tree has uncommitted changes — commit/stash them, then re-run" >&2
    echo "       (or: SKIP_CHECKOUT=1 ./run-dogfood-local.sh to run the current checkout as-is)." >&2
    exit 1
  fi
  if [[ "$(git rev-parse HEAD)" != "$TARGET" ]]; then
    echo "==> Checking out $FIX_REF (detached HEAD)... (return later: git checkout develop)"
    git checkout --detach "$TARGET" --quiet
  else
    echo "==> Already at $FIX_REF."
  fi
fi

# --- 1. JDK 17+ ---------------------------------------------------------------
command -v java >/dev/null 2>&1 || { echo "ERROR: no 'java' on PATH — install JDK 17+ (JetBrains Runtime recommended)." >&2; exit 1; }
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F. '{print ($1=="1")?$2:$1}')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "ERROR: JDK 17+ required, found major version '${JAVA_MAJOR:-unknown}'." >&2
  exit 1
fi

# --- 2. ANTHROPIC_API_KEY required (local hub spawns real agents) ------------
if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "ERROR: export ANTHROPIC_API_KEY before running — the LOCAL hub spawns real Claude agents with it." >&2
  echo "       e.g.:  export ANTHROPIC_API_KEY=sk-ant-...   then re-run." >&2
  exit 1
fi

# --- 3. First-run setup: .env (dev tokens) + platform.config.json ------------
# dev-setup.sh generates random HUB_TOKEN_* / OPERATOR_TOKEN into .env and a
# platform.config.json seed. Both are gitignored (never committed).
if [[ ! -f .env || ! -f platform.config.json ]]; then
  if [[ -x ./scripts/dev-setup.sh ]]; then
    echo "==> First-run setup (generating .env + platform.config.json)..."
    ./scripts/dev-setup.sh
  else
    [[ ! -f .env && -f .env.example ]] && cp .env.example .env && echo "==> copied .env.example -> .env"
    [[ ! -f platform.config.json && -f platform.config.example.json ]] && cp platform.config.example.json platform.config.json && echo "==> copied platform.config.example.json -> platform.config.json"
  fi
fi
[[ -f .env ]] || { echo "ERROR: no .env and no scripts/dev-setup.sh / .env.example to generate it." >&2; exit 1; }
[[ -f platform.config.json ]] || { echo "ERROR: no platform.config.json (needed to boot the hub)." >&2; exit 1; }

# --- 3b. Load the dev tokens (.env) into the env for the hub -----------------
set -a; source ./.env; set +a

# --- 4. LOCAL mode: ensure NO remote/relay/staging flags leak in -------------
unset CYP_REMOTE_HUB CYP_MUX_TRANSPORT CYPPIE_AUTH_LIVE CYPPIE_AUTH_ORIGIN \
      CYPPIE_AUTH_PROXY CYPPIE_REMOTE_RELAY_URL CYPPIE_CP_BASE_URL 2>/dev/null || true

LOG="${DOGFOOD_LOG:-$HOME/dogfood-local.log}"
: > "$LOG"
echo "==> Repo:  $REPO_DIR"
echo "==> Java:  major $JAVA_MAJOR"
echo "==> Mode:  LOCAL (hub 127.0.0.1:8787, no remote/relay)"
echo "==> Log:   $LOG   (hub + client output)"
echo

# --- 5. Start the local hub (:server) + wait for health ----------------------
echo "==> Starting local hub (:server -> http://127.0.0.1:8787)..."
./gradlew --no-daemon :server:run >>"$LOG" 2>&1 &
SERVER_PID=$!
cleanup() { echo "==> Stopping local hub (pid $SERVER_PID)..."; kill "$SERVER_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

printf "==> Waiting for hub health"
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8787/api/health >/dev/null 2>&1; then echo " — up ✓"; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo; echo "ERROR: hub process exited during boot — see $LOG" >&2; exit 1
  fi
  printf "."; sleep 2
  if [[ "$i" == "90" ]]; then
    echo; echo "ERROR: hub not healthy within 180s — see $LOG" >&2; exit 1
  fi
done

# --- 6. Launch the desktop client (LOCAL mode) -------------------------------
echo "==> Launching desktop client (local mode, connects to 127.0.0.1:8787)..."
echo
./gradlew --no-daemon :app:desktopApp:run 2>&1 | tee -a "$LOG"
