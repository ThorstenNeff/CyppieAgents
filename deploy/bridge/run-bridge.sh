#!/usr/bin/env bash
# CYP-142 — launch ONE Bridge instance from the bundled app-image.
#
# All N Bridges run as the SAME OS user and SHARE the real HOME (~/.claude is preserved). This is
# DELIBERATE:
#   • The agent's MEMORY lives under ~/.claude/projects/<cwd-slug>/ (keyed by the working-directory
#     path). Overriding HOME to a fresh dir would point ~ at an empty tree → every agent boots
#     WITHOUT its memory, silently, no error. So HOME is NOT touched here.
#   • The single OAuth login lives in ~/.claude. Splitting HOME splits one login into N — the
#     Auftraggeber's credentials/cost, not ours to fan out.
# Per-agent separation comes from a per-instance WORKING DIRECTORY: claude keys per-project memory by
# the cwd slug, so each agent's own cwd → its own memory + role, with the shared login intact.
#
#   Required env : HUB_URL  HUB_AGENT_ID  HUB_TOKEN
#   Optional env : BRIDGE_CWD (per-instance working dir; DEFAULT /home/thorsten/cyppie-agents/<agent> —
#                  the path whose existing cwd-slug already holds that agent's memory), CLAUDE_CMD.
#
# ⚠ Do NOT start all instances at the same instant — claude does a read-modify-write on
#   ~/.claude.json; stagger the launches (see README-BRIDGE.md §Parallel instances).

set -euo pipefail
: "${HUB_URL:?set HUB_URL, e.g. wss://api.cyppie-agents.com}"
: "${HUB_AGENT_ID:?set HUB_AGENT_ID, the server-assigned agent id}"
: "${HUB_TOKEN:?set HUB_TOKEN, the per-agent bearer token}"

HERE="$(cd "$(dirname "$0")" && pwd)"
# HOME is intentionally NOT overridden — the shared ~/.claude carries the login + existing memory.
# Per-instance cwd → own cwd-slug → own memory/role. Default = the agent's existing repo dir on the box.
export BRIDGE_CWD="${BRIDGE_CWD:-/home/thorsten/cyppie-agents/$HUB_AGENT_ID}"

# Fail LOUD if the cwd is missing: a non-existent cwd would make claude mint a FRESH (memory-less)
# slug — the exact silent-blank-boot we are guarding against. Do not mkdir a blank one.
[ -d "$BRIDGE_CWD" ] || {
  echo "[run-bridge] ERROR: BRIDGE_CWD '$BRIDGE_CWD' does not exist — refusing to boot agent '$HUB_AGENT_ID'" >&2
  echo "[run-bridge]        a fresh cwd = a fresh, memory-less ~/.claude/projects slug. Point BRIDGE_CWD at" >&2
  echo "[run-bridge]        the agent's existing working directory." >&2
  exit 1
}

echo "[run-bridge] agent=$HUB_AGENT_ID  hub=$HUB_URL  HOME=$HOME (shared)  cwd=$BRIDGE_CWD" >&2
exec "$HERE/CyppieBridge/bin/CyppieBridge"
