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

exec java "${JVM_ARGS[@]}" -cp "$CYPPIE_RELAY_JAR" com.tneff.cyppieagents.relay.RelayServerKt
