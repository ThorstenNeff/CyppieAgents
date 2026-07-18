#!/usr/bin/env bash
# CYP-142 — launch ONE Bridge instance from the bundled app-image, with per-instance isolation.
#
# All N Bridges run as the SAME OS user, so isolation is NOT by user — it is by a per-instance HOME and
# working directory. HOME and BRIDGE_CWD propagate through the Bridge to the spawned `claude` process
# (the connector env whitelist passes HOME/PATH/USER/LOGNAME/locale), so each claude gets its OWN
# ~/.claude — separate config, session state, and credential store. That is what keeps 6 concurrent
# instances from writing over each other.
#
#   Required env : HUB_URL  HUB_AGENT_ID  HUB_TOKEN
#   Optional env : BRIDGE_ROOT (per-instance dir; default ./instances/<HUB_AGENT_ID>)
#                  CLAUDE_CMD  (default: claude)
#
# ⚠ AUTH TRADE-OFF (operator decision — see README-BRIDGE.md §Parallel instances):
#   An isolated per-instance HOME means each ~/.claude needs its OWN claude authentication seeded once
#   (copy an already-authenticated ~/.claude into each instance HOME). If instead you want the 6 to
#   SHARE one login, point every instance at the same BRIDGE_ROOT/home — but then they share one state
#   store, which is the collision this isolation avoids. Pick deliberately; do not assume shared holds.

set -euo pipefail
: "${HUB_URL:?set HUB_URL, e.g. wss://api.cyppie-agents.com}"
: "${HUB_AGENT_ID:?set HUB_AGENT_ID, the server-assigned agent id}"
: "${HUB_TOKEN:?set HUB_TOKEN, the per-agent bearer token}"

HERE="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_ROOT="${BRIDGE_ROOT:-$HERE/instances/$HUB_AGENT_ID}"

export HOME="$BRIDGE_ROOT/home"   # per-instance ~/.claude lives here → no cross-instance state collision
export BRIDGE_CWD="$BRIDGE_ROOT/work"
mkdir -p "$HOME" "$BRIDGE_CWD"

echo "[run-bridge] agent=$HUB_AGENT_ID  hub=$HUB_URL  HOME=$HOME  cwd=$BRIDGE_CWD" >&2
exec "$HERE/CyppieBridge/bin/CyppieBridge"
