#!/bin/bash
# CYP-667 S7 — launchd wrapper for the isolated Gateway (A2) process on the macOS host.
#
# Matches the MEASURED hub pattern (com.cyppie.hub.plist → deploy/launchd/hub-run.sh): source the out-of-repo env,
# then exec the JVM. Written for macOS's stock /bin/bash (3.2) — no mapfile/readarray, no bashisms beyond 3.2.
#
# ★ SINGLE SOURCE for the JVM launch-hardening flags: this wrapper READS deploy/gateway/gateway.jvmargs and passes
#   whatever it carries to the JVM. It hand-copies NO -XX flag (the CYP-623 drift lesson: two arg lists diverge). So
#   the real launcher can never drift from the tested source — delete a flag from the argfile and the launched process
#   loses it too. GatewayLaunchdPlistTest pins that this wrapper reads the argfile and inlines no flag; the argfile's
#   own contents are pinned by GatewayLaunchHardeningTest. The OS core-dump complement (Core=0) lives in the plist.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# hub-pattern: source the out-of-repo .env for CYPPIE_* config (host/port, hub url, kratos url, SPA dir). Location is
# deploy-configurable; default is the repo/deploy root two levels up from this script.
ENV_FILE="${CYPPIE_GATEWAY_ENV_FILE:-$SCRIPT_DIR/../../.env}"
if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi

# Read the single-source JVM args (skip comments + blank lines; trim leading/trailing whitespace, pure-bash-3.2).
ARGFILE="$SCRIPT_DIR/../gateway/gateway.jvmargs"
JVM_ARGS=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"                               # strip inline/whole-line comment
  line="${line#"${line%%[![:space:]]*}"}"          # ltrim
  line="${line%"${line##*[![:space:]]}"}"          # rtrim
  [ -n "$line" ] && JVM_ARGS+=("$line")
done < "$ARGFILE"

# The built server classpath/jar (the same <server-all> jar the relay LaunchAgent runs). Deploy-provided.
: "${CYPPIE_GATEWAY_JAR:?set CYPPIE_GATEWAY_JAR to the built server jar (or classpath) that contains GatewayServerKt}"

exec java "${JVM_ARGS[@]}" -cp "$CYPPIE_GATEWAY_JAR" com.tneff.cyppieagents.gateway.GatewayServerKt
