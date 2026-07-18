#!/usr/bin/env bash
# CYP-687 (M1.1) — BYOA acceptance harness (gap-4). Run by po2, as ROOT, on the real Ubuntu-26 box, against the PROD
# .deb. Proves the M1.1 DoD from docs/CYP-687-byoa-m1-abnahme-kriterien.md (dcd8c249): a REMOTE agent REGISTERS +
# CONNECTS + exchanges a message BOTH ways over /ws/hub — A–E + 4 fail-closed + a restart-repetition — NOT health=ok.
#
# It drives the claude-free wire-client (/opt/cyppiehub/bin/CyppieHubAcceptance, one probe per invocation) + curl/REST,
# and seeds a CONFIG-DECLARED remote agent (durable ⇒ survives restart) via CyppieHubProvision --add-remote-agent.
#
# Usage:  sudo ./byoa-m1-acceptance.sh /path/to/cyppiehub_<ver>_amd64.deb
# Prereq: the box is CLEAN (no prior cyppie hub) — `getent passwd cyppie` empty, /opt|/var/lib|/etc/cyppiehub absent,
#         ports 8787/8786 free, `git` present (a .deb Depend), python3 present (Ubuntu-26 default).
set -uo pipefail

DEB="${1:?usage: sudo $0 /path/to/cyppiehub_<ver>_amd64.deb}"
AGENT="sidekick"; SPOKE="po-${AGENT}"
BASE="http://127.0.0.1:8787"; WS="ws://127.0.0.1:8787/ws/hub"
ACCEPT="/opt/cyppiehub/bin/CyppieHubAcceptance"; PROVISION="/opt/cyppiehub/bin/CyppieHubProvision"
ENVFILE="/etc/cyppiehub/hub.env"; DATADIR="/var/lib/cyppiehub"
FAILURES=0

say()  { printf '\n=== %s ===\n' "$*"; }
ok()   { printf '  PASS: %s\n' "$*"; }
bad()  { printf '  FAIL: %s\n' "$*"; FAILURES=$((FAILURES+1)); }
die()  { printf '\nABORT: %s\n' "$*" >&2; exit 2; }
[ "$(id -u)" = 0 ] || die "run as root"
command -v python3 >/dev/null || die "python3 required for the JSON assertions"

nonce()  { printf 'CYP687-%s' "$(cat /proc/sys/kernel/random/uuid)"; }
health() { local _; for _ in $(seq 1 30); do [ "$(curl -fsS "$BASE/api/health" 2>/dev/null)" = ok ] && return 0; sleep 1; done; return 1; }
api_get()  { curl -fsS -H "Authorization: Bearer $1" "$BASE$2" 2>/dev/null; }             # api_get  <token> <path>
api_post() { curl -fsS -X POST -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3" "$BASE$2" 2>/dev/null; } # <token> <path> <json>
# probe <label> -- <CyppieHubAcceptance args...> : run a wire probe IN THIS SHELL (so bad() persists FAILURES), print result.
probe() { local label="$1"; shift 2; local out; if out="$("$ACCEPT" "$@" 2>&1)"; then ok "$label ($(printf '%s' "$out" | head -1))"; else bad "$label -> $(printf '%s' "$out" | head -1)"; fi; }
# json_true <json> <python-expr over `d`> : returns 0 iff the expression is truthy (empty/bad JSON -> non-zero -> caller bad()).
json_true() { printf '%s' "$1" | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: sys.exit(1)
sys.exit(0 if ('"$2"') else 1)'; }
items() { echo "d if isinstance(d,list) else d.get('$1',[])"; } # events/agents/messages responses may be a list OR {key:[...]}

# ── Setup: install the prod .deb, seed a config-declared remote agent, restart ────────────────────────────────────
say "Setup — install prod .deb + seed a config-declared remote agent"
getent passwd cyppie >/dev/null && die "a 'cyppie' user already exists — box not clean (precondition)"
{ apt-get install -y "$DEB" || dpkg -i "$DEB"; } || die "install failed"
health || die "hub did not come up after install"
# ③ CYP-687: add a config-DECLARED remote agent (durable). Minted token appended to a transient, merged into hub.env.
SECRETS="$(mktemp)"; chmod 0600 "$SECRETS"
sudo -u cyppie "$PROVISION" --add-remote-agent "$AGENT" --data-dir "$DATADIR" --secrets-out "$SECRETS" >/dev/null || die "--add-remote-agent failed"
cat "$SECRETS" >> "$ENVFILE"; shred -u "$SECRETS" 2>/dev/null || rm -f "$SECRETS"
chown cyppie:cyppie "$ENVFILE"; chmod 0600 "$ENVFILE"
systemctl restart cyppiehub; health || die "hub did not come up after seeding $AGENT"
OPERATOR_TOKEN="$(sed -n 's/^OPERATOR_TOKEN=//p' "$ENVFILE")"
PO_TOKEN="$(sed -n 's/^HUB_TOKEN_PO=//p' "$ENVFILE")"
AGENT_TOKEN="$(sed -n "s/^HUB_TOKEN_${AGENT^^}=//p" "$ENVFILE")"
[ -n "$OPERATOR_TOKEN" ] && [ -n "$PO_TOKEN" ] && [ -n "$AGENT_TOKEN" ] || die "missing a required token in $ENVFILE"

# ── A: baseline (the non-vacuosity anchor) — roster has the agent, but NO wire session + NO source=remote yet ──────
say "A — baseline (anchor)"
AGENTS="$(api_get "$OPERATOR_TOKEN" /api/agents)"
json_true "$AGENTS" "any(x.get('id')=='$AGENT' for x in ($(items agents)))" \
  && ok "roster contains '$AGENT' (registered STOPPED, awaiting /ws/hub — config, NOT connection)" || bad "roster missing '$AGENT'"
EV0="$(api_get "$OPERATOR_TOKEN" /api/events)"
json_true "$EV0" "not any(('$AGENT' in json.dumps(x)) and (x.get('source')=='remote') for x in ($(items events)))" \
  && ok "no source=remote event for '$AGENT' before connect (anchor holds)" || bad "a source=remote event exists pre-connect (anchor broken)"

# ── B: auth-bound connect → WireAck("hello") ──────────────────────────────────────────────────────────────────────
say "B — auth-bound connect (WireHello -> WireAck hello)"
probe "B connect+hello" -- --cmd hello --url "$WS" --token "$AGENT_TOKEN"

# ── C: agent -> hub, forgery-resistant (hub-assigned id + server-stamped from/projectId) ──────────────────────────
say "C — agent->hub (WireSend -> hub-assigned id, server-stamped from)"
NC="$(nonce)"
if C_OUT="$("$ACCEPT" --cmd send --url "$WS" --token "$AGENT_TOKEN" --channel "$SPOKE" --nonce "$NC" 2>&1)"; then
  ok "C send ($(printf '%s' "$C_OUT" | head -1))"
  MID="$(printf '%s' "$C_OUT" | sed -n 's/^MESSAGE_ID=//p' | head -1)"
  MSGS="$(api_get "$OPERATOR_TOKEN" "/api/channels/$SPOKE/messages")"
  json_true "$MSGS" "any(m.get('id')=='$MID' and m.get('from')=='$AGENT' and ('$NC' in m.get('body','')) and m.get('projectId') for m in ($(items messages)))" \
    && ok "REST: id=$MID present, server-stamped from=$AGENT + projectId + nonce" || bad "REST: id/from/projectId mismatch for the sent message"
else bad "C send -> $(printf '%s' "$C_OUT" | head -1)"; fi

# ── D: hub -> agent (PO posts the nonce; the connected agent receives WireDeliver) ────────────────────────────────
say "D — hub->agent (WireDeliver carries the nonce)"
ND="$(nonce)"
"$ACCEPT" --cmd await-deliver --url "$WS" --token "$AGENT_TOKEN" --channel "$SPOKE" --nonce "$ND" >/tmp/cyp687-d.out 2>&1 &
DPID=$!
sleep 2 # let it connect + subscribe before the post
api_post "$PO_TOKEN" "/api/channels/$SPOKE/messages" "{\"body\":\"$ND\"}" >/dev/null || bad "D: PO post failed"
if wait "$DPID"; then ok "D await-deliver ($(head -1 /tmp/cyp687-d.out))"; else bad "D -> $(head -1 /tmp/cyp687-d.out)"; fi

# ── E: source=remote (THE discriminator — without it a LOCAL agent satisfies A–D) ─────────────────────────────────
say "E — source=remote (discriminator)"
EV1="$(api_get "$OPERATOR_TOKEN" /api/events)"
json_true "$EV1" "any(('$AGENT' in json.dumps(x)) and (x.get('source')=='remote') for x in ($(items events)))" \
  && ok "/api/events shows source=remote for '$AGENT' (over the wire, not a local process)" || bad "no source=remote event for '$AGENT' (E fails)"

# ── 4 fail-closed controls (so a green can actually FAIL) ─────────────────────────────────────────────────────────
say "Fail-closed controls"
probe "FC1 operator token on /ws/hub -> close 1008" -- --cmd expect-unauthorized --url "$WS" --token "$OPERATOR_TOKEN"
probe "FC2 send-before-hello -> WireError(PROTOCOL)" -- --cmd send-before-hello --url "$WS" --token "$AGENT_TOKEN" --channel "$SPOKE"
probe "FC3 elevated-caps accepted; server clamps REMOTE" -- --cmd elevated-caps --url "$WS" --token "$AGENT_TOKEN"
AGD="$(api_get "$OPERATOR_TOKEN" /api/agents)"
# best-effort: the clamped agent must not surface an ENABLED capability MODE (po2: adjust the field if the shape differs).
json_true "$AGD" "'enabled' not in json.dumps([x for x in ($(items agents)) if x.get('id')=='$AGENT']).lower()" \
  && ok "FC3 REST: '$AGENT' caps DEGRADED/clamped, never ENABLED" || bad "FC3: '$AGENT' shows ENABLED caps (clamp broken)"
probe "FC4 send-without-canWrite -> uniform WireError(FORBIDDEN)" -- --cmd send-no-write --url "$WS" --token "$AGENT_TOKEN" --channel "forbidden-$(cat /proc/sys/kernel/random/uuid)"

# ── Restart-repetition (the CYP-172 guard — a config-declared agent survives restart) ─────────────────────────────
say "Restart-repetition (C+D again after systemctl restart — CYP-172 guard)"
systemctl restart cyppiehub; health || die "hub did not come up after restart"
NC2="$(nonce)"; probe "C(after restart) send" -- --cmd send --url "$WS" --token "$AGENT_TOKEN" --channel "$SPOKE" --nonce "$NC2"
ND2="$(nonce)"
"$ACCEPT" --cmd await-deliver --url "$WS" --token "$AGENT_TOKEN" --channel "$SPOKE" --nonce "$ND2" >/tmp/cyp687-d2.out 2>&1 &
DPID2=$!; sleep 2
api_post "$PO_TOKEN" "/api/channels/$SPOKE/messages" "{\"body\":\"$ND2\"}" >/dev/null || bad "D(after restart): PO post failed"
if wait "$DPID2"; then ok "D(after restart) await-deliver ($(head -1 /tmp/cyp687-d2.out))"; else bad "D(after restart) -> $(head -1 /tmp/cyp687-d2.out)"; fi

# ── Verdict ───────────────────────────────────────────────────────────────────────────────────────────────────────
say "Verdict"
if [ "$FAILURES" = 0 ]; then echo "M1.1 ACCEPTANCE: PASS (A–E + 4 fail-closed + restart-repetition, on the .deb-installed hub)"; exit 0
else echo "M1.1 ACCEPTANCE: FAIL ($FAILURES step(s))"; exit 1; fi
