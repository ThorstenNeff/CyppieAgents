#!/bin/bash
# CYP-670 — launchd wrapper for the Hub on the macOS host (boot-persistent LaunchDaemon). macOS /bin/bash 3.2-safe.
#
# ★ CUSTODY (ratified option (a), same as the .deb's cyppiehub.service): source secrets from the 0600 EnvironmentFile
#   OUTSIDE any user home — /etc/cyppiehub/hub.env (CYPPIE_MASTER_KEY, OPERATOR_TOKEN, HUB_TOKEN_PO, PLATFORM_CONFIG,
#   PLATFORM_GIT_ROOT, ANTHROPIC_API_KEY). A LaunchDaemon runs outside the console session → it MUST NOT depend on a
#   `customer`-home .env. launchd has no EnvironmentFile key, so the wrapper sources it. deploy provisions it 0600,
#   owned by the dedicated service user.
# ★ SINGLE SOURCE the JVM args from deploy/hub/hub.jvmargs — NO hand-copied -XX flag here (CYP-623 drift lesson).
# The hub binds LOOPBACK by config (PlatformConfig.host default 127.0.0.1, "should stay there") — no host override here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

ENV_FILE="${CYPPIE_HUB_ENV_FILE:-/etc/cyppiehub/hub.env}"
if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi

ARGFILE="$SCRIPT_DIR/../hub/hub.jvmargs"
JVM_ARGS=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -n "$line" ] && JVM_ARGS+=("$line")
done < "$ARGFILE"

: "${CYPPIE_HUB_JAR:?set CYPPIE_HUB_JAR to the built server jar (or classpath) that contains ApplicationKt}"

exec java "${JVM_ARGS[@]}" -cp "$CYPPIE_HUB_JAR" com.tneff.cyppieagents.ApplicationKt
