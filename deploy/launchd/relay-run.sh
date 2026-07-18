#!/bin/bash
# CYP-670 — launchd wrapper for the Relay on the macOS host (boot-persistent LaunchDaemon). macOS /bin/bash 3.2-safe.
#
# The relay is a dumb Noise-frame pipe — it links NO hub store/secret, so there is little secret to protect; still it
# runs as a dedicated non-root user (custody consistency) and sources an optional 0600 env if deploy provides one.
# ★ SINGLE SOURCE the JVM args from deploy/relay/relay.jvmargs — NO hand-copied -XX flag here (CYP-623 drift lesson).
#
# ★ LOOPBACK BIND (role-based, measured CASE 2): on THIS host Caddy fronts the relay (`/relay → 127.0.0.1:8788`), so
#   the relay binds loopback. We EXPORT it here (after sourcing env) so it is authoritative for the daemon and cannot
#   be left at the code default 0.0.0.0 by a missing env. NOTE: a case-1 deployment (public Noise rendezvous, remote
#   clients dial it directly — legitimate because Noise-encrypted) would use a different value; deploy owns this file.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

ENV_FILE="${CYPPIE_RELAY_ENV_FILE:-/etc/cyppiehub/relay.env}"
if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi

# loopback bind, authoritative for this Caddy-fronted host (see header). deploy changes this only for a case-1 host.
export CYPPIE_RELAY_HOST="${CYPPIE_RELAY_HOST:-127.0.0.1}"

ARGFILE="$SCRIPT_DIR/../relay/relay.jvmargs"
JVM_ARGS=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%#*}"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  [ -n "$line" ] && JVM_ARGS+=("$line")
done < "$ARGFILE"

: "${CYPPIE_RELAY_JAR:?set CYPPIE_RELAY_JAR to the built server jar (or classpath) that contains RelayServerKt}"

# CYP-685 — resolve an ABSOLUTE java from a _cyppie-readable JDK. A system LaunchDaemon's PATH is
# /usr/bin:/bin:/usr/sbin:/sbin (no JDK), and a corretto JDK under a console user's ~/.gradle is unreadable by the
# dedicated daemon user — so a bare `java` invocation hit the macOS /usr/bin/java stub ("Unable to locate a Java
# Runtime") and every daemon failed to boot (CYP-670 regression: the old User-LaunchAgent used a full absolute path).
# JAVA_HOME is provisioned in the 0600 env file (deploy relocates corretto-21 → /opt/cyppie-hub/jdk, chown _cyppie, and
# sets JAVA_HOME to its java-home). Fail-closed: never fall through to a PATH lookup.
: "${JAVA_HOME:?CYP-685: set JAVA_HOME to a _cyppie-readable JDK 21 in the env file — the LaunchDaemon PATH has no JDK}"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || { echo "CYP-685: no executable java at $JAVA_BIN (JAVA_HOME=$JAVA_HOME) — daemon cannot start" >&2; exit 1; }

exec "$JAVA_BIN" "${JVM_ARGS[@]}" -cp "$CYPPIE_RELAY_JAR" com.tneff.cyppieagents.relay.RelayServerKt
