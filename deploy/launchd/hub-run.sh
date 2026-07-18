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

# CYP-685 — resolve an ABSOLUTE java from a _cyppie-readable JDK. A system LaunchDaemon's PATH is
# /usr/bin:/bin:/usr/sbin:/sbin (no JDK), and a corretto JDK under a console user's ~/.gradle is unreadable by the
# dedicated daemon user — so a bare `java` invocation hit the macOS /usr/bin/java stub ("Unable to locate a Java
# Runtime") and every daemon failed to boot (CYP-670 regression: the old User-LaunchAgent used a full absolute path).
# JAVA_HOME is provisioned in the 0600 env file (deploy relocates corretto-21 → /opt/cyppie-hub/jdk, chown _cyppie, and
# sets JAVA_HOME to its java-home). Fail-closed: never fall through to a PATH lookup.
: "${JAVA_HOME:?CYP-685: set JAVA_HOME to a _cyppie-readable JDK 21 in the env file — the LaunchDaemon PATH has no JDK}"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || { echo "CYP-685: no executable java at $JAVA_BIN (JAVA_HOME=$JAVA_HOME) — daemon cannot start" >&2; exit 1; }

exec "$JAVA_BIN" "${JVM_ARGS[@]}" -cp "$CYPPIE_HUB_JAR" com.tneff.cyppieagents.ApplicationKt
