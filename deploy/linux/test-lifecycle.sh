#!/usr/bin/env bash
# CYP-637 — .deb lifecycle acceptance harness (TEST-SCOPED, safe-by-construction).
#
# Proves the full cyppiehub-test .deb lifecycle on the real host WITHOUT ever touching the live hub:
#   install → enable → health@18787 → remove(preserve) → reinstall(reattach, same master key) → purge(wipe) → host clean.
#
# ★ SAFETY: this script references EXCLUSIVELY the `-test` package/user/paths/ports. Every destructive op
#   (dpkg -r/--purge, systemctl stop) targets `cyppiehub-test` / `cyppie-test` / `/var/lib/cyppiehub-test` /
#   `/etc/cyppiehub-test` only. The live hub (different package/user/paths, tunnel port 8786) is structurally
#   out of reach. As a positive check it also verifies the live tunnel port stays LISTENing throughout (read-only).
#
# Run as root on the target host:   sudo ./test-lifecycle.sh [path/to/cyppiehub-test_<ver>_amd64.deb]
# If the path is omitted it auto-discovers server/build/hub-installer-test/*.deb.
set -uo pipefail

# CYP-639 — tee the FULL run (stdout+stderr) to a readable file so the run can be read directly (no copy-paste).
# Defaults next to this script (i.e. the staged install dir); override with $CYP637_LOGFILE. Truncated per run.
LOGFILE="${CYP637_LOGFILE:-$(cd "$(dirname "$0")" && pwd)/cyp637-run.log}"
: > "$LOGFILE" 2>/dev/null || LOGFILE="/tmp/cyp637-run.log"  # fall back if the script dir is not writable
exec > >(tee "$LOGFILE") 2>&1
echo "CYP-637 lifecycle — full output also written to: $LOGFILE"

# ---- test-scoped constants (NEVER the live names) -----------------------------------------------------------
PKG="cyppiehub-test"
SVC="cyppiehub-test.service"
USR="cyppie-test"
DATA="/var/lib/cyppiehub-test"
ETC="/etc/cyppiehub-test"
ENVF="$ETC/hub.env"
UNIT="/lib/systemd/system/$SVC"
PORT=18787            # test hub public port
TPORT=18786           # test hub tunnel port
LIVE_TUNNEL_PORT=8786 # live hub tunnel port — READ-ONLY liveness probe, never written/stopped
# CYP-637 G5 — live-side paths, referenced READ-ONLY for a POSITIVE isolation proof (never written/removed).
LIVE_DATA="/var/lib/cyppiehub"
LIVE_ETC="/etc/cyppiehub"
LIVE_UNIT="/lib/systemd/system/cyppiehub.service"

PASS=0; FAIL=0; SKIPPED=0
ok()   { echo "  ✅ PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ FAIL: $1"; FAIL=$((FAIL+1)); }
# CYP-637 G1 — a safety proof that could NOT be evaluated is INCONCLUSIVE, never a silent pass. It gets its own
# counter, is named in the summary, and changes the exit code — so a skipped isolation proof can never look
# identical to a demonstrated one.
skip() { echo "  ⚠️  SKIPPED (INCONCLUSIVE): $1"; SKIPPED=$((SKIPPED+1)); }
step() { echo; echo "=== $1 ==="; }
listening() { (exec 3<>/dev/tcp/127.0.0.1/"$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

# ---- cleanup trap: on ANY exit, purge the -test package so the host is left clean (idempotent, -test only) ----
cleanup() {
  echo; echo "=== cleanup trap: ensuring host is left clean (-test only) ==="
  dpkg --purge "$PKG" >/dev/null 2>&1 || true
  systemctl stop "$SVC" >/dev/null 2>&1 || true
  rm -f "$UNIT" >/dev/null 2>&1 || true; systemctl daemon-reload >/dev/null 2>&1 || true
  rm -rf "$DATA" >/dev/null 2>&1 || true; rm -f "$ENVF" >/dev/null 2>&1 || true; rmdir "$ETC" >/dev/null 2>&1 || true
  getent passwd "$USR" >/dev/null 2>&1 && userdel "$USR" >/dev/null 2>&1 || true
}

# CYP-637 G2 — sample live-hub liveness AFTER each destructive step, not just at start/end. A disturbance that
# knocks the live hub down and lets it recover would otherwise be invisible to a start/end comparison; this also
# LOCATES which step caused it. Read-only: probes a port (and /api/health when reachable), never writes.
live_probe() { # $1 = step label
  [ "$LIVE_UP_START" = yes ] || return 0   # nothing to compare against; G1 already reports the INCONCLUSIVE
  if listening "$LIVE_TUNNEL_PORT"; then ok "live hub still listening after $1"
  else bad "live hub went DOWN during $1 — the test disturbed the live hub"; fi
}

health() { # retry the /api/health endpoint for up to ~30s
  for _ in $(seq 1 30); do
    [ "$(curl -fsS "http://127.0.0.1:$PORT/api/health" 2>/dev/null)" = "ok" ] && return 0
    sleep 1
  done
  return 1
}

# ============================================================================================================
step "0. pre-flight"
[ "$(id -u)" -eq 0 ] || { echo "must run as root (sudo)"; exit 2; }

DEB="${1:-}"
if [ -z "$DEB" ]; then
  DEB="$(ls -1 "$(dirname "$0")/../../server/build/hub-installer-test/"*.deb 2>/dev/null | head -1)"
fi
[ -n "$DEB" ] && [ -f "$DEB" ] || { echo "no .deb found (build with :server:hubInstallerTest, or pass the path)"; exit 2; }
echo "deb: $DEB"

# refuse to run if a -test instance already exists (keep this a clean, deterministic run)
for art in "$DATA" "$ETC" "$UNIT"; do
  [ -e "$art" ] && { echo "refusing: -test artifact already present: $art (run purge first)"; exit 2; }
done
getent passwd "$USR" >/dev/null 2>&1 && { echo "refusing: $USR user already exists (run purge first)"; exit 2; }
listening "$PORT"  && { echo "refusing: test port $PORT already in use"; exit 2; }
listening "$TPORT" && { echo "refusing: test tunnel port $TPORT already in use"; exit 2; }
ok "clean starting state; deb present; test ports free"

# record live-hub liveness so we can prove we never disturbed it
LIVE_UP_START=no; listening "$LIVE_TUNNEL_PORT" && LIVE_UP_START=yes
echo "  (live tunnel port $LIVE_TUNNEL_PORT listening at start: $LIVE_UP_START)"
# CYP-637 G5 — record which live-side PATHS exist now, so the end-state check is a real comparison. Same
# discipline as G1: a path that was never there cannot prove "still there", so absence at start => INCONCLUSIVE,
# not a free pass.
LIVE_PATHS_AT_START=""
for lp in "$LIVE_DATA" "$LIVE_ETC" "$LIVE_UNIT"; do [ -e "$lp" ] && LIVE_PATHS_AT_START="$LIVE_PATHS_AT_START $lp"; done
echo "  (live paths present at start:${LIVE_PATHS_AT_START:- none})"

trap cleanup EXIT

# ============================================================================================================
step "1. install (dpkg -i) → provision + enable + boot"
if dpkg -i "$DEB" >/tmp/cyp637-install.log 2>&1; then ok "dpkg -i succeeded"; else bad "dpkg -i failed"; cat /tmp/cyp637-install.log; fi
[ -f "$ENVF" ]        && ok "provisioned $ENVF"                         || bad "missing $ENVF"
[ -d "$DATA" ]        && ok "created data dir $DATA"                    || bad "missing $DATA"
getent passwd "$USR" >/dev/null 2>&1 && ok "created $USR user"          || bad "missing $USR user"
systemctl is-enabled "$SVC" >/dev/null 2>&1 && ok "$SVC enabled"        || bad "$SVC not enabled"
if health; then ok "health@$PORT = ok"; else bad "health@$PORT never came up"; journalctl -u "$SVC" --no-pager | tail -30; fi
# capture the master key line to later prove reinstall REATTACHED (did not re-mint)
MK_BEFORE="$(grep '^CYPPIE_MASTER_KEY=' "$ENVF" 2>/dev/null | sha256sum | cut -d' ' -f1)"

# CYP-637 G4 — state CO-INSTALL as ONE assertion. It follows from the two measurements above, but the report
# should not require the reader to infer the very claim the run exists to make: -test healthy WHILE live is up.
if [ "$LIVE_UP_START" = yes ]; then
  if health && listening "$LIVE_TUNNEL_PORT"; then ok "CO-INSTALL: -test healthy on $PORT WHILE live hub listens on $LIVE_TUNNEL_PORT"
  else bad "co-install assertion failed (test-health and live-port not simultaneously up)"; fi
else
  skip "CO-INSTALL unprovable: live hub was not up at start, so 'runs alongside live' was never demonstrated"
fi
live_probe "step 1 (install)"

# ============================================================================================================
# CYP-637 G3 — the OPERATIONAL path: restart/stop/start on the RUNNING service. Package install/remove is not
# the same path (config change, key rotation -> restart) and a broken unit file surfaces HERE first. Parity with
# the CYP-670 reboot-persistence expectation: the service must come back WITHOUT a reinstall.
step "1b. restart / stop / start (running service, no reinstall)"
if systemctl restart "$SVC" >/dev/null 2>&1; then ok "systemctl restart accepted"; else bad "systemctl restart failed"; fi
if health; then ok "health@$PORT = ok after restart"; else bad "health@$PORT down after restart"; journalctl -u "$SVC" --no-pager | tail -20; fi
systemctl stop "$SVC" >/dev/null 2>&1
systemctl is-active "$SVC" >/dev/null 2>&1 && bad "$SVC still active after stop" || ok "$SVC stopped on request"
listening "$PORT" && bad "port $PORT still bound after stop — the service did not release it" || ok "port $PORT released after stop"
if systemctl start "$SVC" >/dev/null 2>&1; then ok "systemctl start accepted"; else bad "systemctl start failed"; fi
if health; then ok "health@$PORT = ok after start (recovered WITHOUT reinstall)"; else bad "health@$PORT never returned after start"; fi
live_probe "step 1b (restart/stop/start)"

# ============================================================================================================
step "2. remove (dpkg -r) → PRESERVES data + secrets, stops service"
if dpkg -r "$PKG" >/tmp/cyp637-remove.log 2>&1; then ok "dpkg -r succeeded"; else bad "dpkg -r failed"; cat /tmp/cyp637-remove.log; fi
systemctl is-active "$SVC" >/dev/null 2>&1 && bad "$SVC still active after remove" || ok "$SVC stopped"
[ -f "$ENVF" ] && ok "PRESERVED $ENVF (incl. master key)"               || bad "remove WIPED $ENVF — data-loss bug"
[ -d "$DATA" ] && ok "PRESERVED data dir $DATA"                         || bad "remove WIPED $DATA — data-loss bug"
live_probe "step 2 (remove)"

# ============================================================================================================
step "3. reinstall (dpkg -i) → REATTACHES (same master key), boots again"
if dpkg -i "$DEB" >/tmp/cyp637-reinstall.log 2>&1; then ok "dpkg -i (reinstall) succeeded"; else bad "reinstall failed"; cat /tmp/cyp637-reinstall.log; fi
MK_AFTER="$(grep '^CYPPIE_MASTER_KEY=' "$ENVF" 2>/dev/null | sha256sum | cut -d' ' -f1)"
[ -n "$MK_BEFORE" ] && [ "$MK_BEFORE" = "$MK_AFTER" ] && ok "master key UNCHANGED (reattached, not re-minted)" || bad "master key changed on reinstall — would orphan the SecretStore"
if health; then ok "health@$PORT = ok (after reattach)"; else bad "health@$PORT down after reinstall"; fi
live_probe "step 3 (reinstall)"

# ============================================================================================================
step "4. purge (dpkg --purge) → WIPES the -test set entirely"
if dpkg --purge "$PKG" >/tmp/cyp637-purge.log 2>&1; then ok "dpkg --purge succeeded"; else bad "purge failed"; cat /tmp/cyp637-purge.log; fi
[ ! -e "$DATA" ]      && ok "wiped data dir $DATA"                       || bad "purge left $DATA"
[ ! -e "$ENVF" ]      && ok "wiped secrets $ENVF"                        || bad "purge left $ENVF"
[ ! -e "$ETC" ]       && ok "wiped $ETC"                                 || bad "purge left $ETC"
[ ! -e "$UNIT" ]      && ok "removed unit $UNIT"                         || bad "purge left $UNIT"
getent passwd "$USR" >/dev/null 2>&1 && bad "purge left $USR user"       || ok "removed $USR user"
live_probe "step 4 (purge)"

# ============================================================================================================
step "5. host-clean + live-untouched assertions"
REM=0; for art in "$DATA" "$ETC" "$UNIT"; do [ -e "$art" ] && { bad "residual -test artifact: $art"; REM=1; }; done
getent passwd "$USR" >/dev/null 2>&1 && { bad "residual $USR user"; REM=1; }
[ "$REM" -eq 0 ] && ok "no residual -test artifacts on host"
if [ "$LIVE_UP_START" = yes ]; then
  listening "$LIVE_TUNNEL_PORT" && ok "live hub (tunnel port $LIVE_TUNNEL_PORT) STILL listening — never disturbed" || bad "live tunnel port $LIVE_TUNNEL_PORT went down — the test disturbed the live hub!"
else
  # CYP-637 G1 — was: a bare echo, so the run still ended PASS/exit-0 and looked identical to one that PROVED
  # isolation. The single claim that justifies running this beside production must never be silently skipped.
  skip "live-untouched assertion NOT evaluated: live hub was not listening at start — isolation is UNPROVEN by this run"
fi

# CYP-637 G5 — POSITIVE isolation proof on the filesystem, complementing the port check: a mis-targeted purge
# could take live FILES while the running process keeps its port open, and that would only surface at the next
# live restart — long after the run that caused it. Only paths that existed at START are asserted (a path that
# was never there cannot prove "still there" — same discipline as G1).
if [ -n "$LIVE_PATHS_AT_START" ]; then
  LIVE_GONE=0
  for lp in $LIVE_PATHS_AT_START; do
    [ -e "$lp" ] || { bad "live path REMOVED by this run: $lp"; LIVE_GONE=1; }
  done
  [ "$LIVE_GONE" -eq 0 ] && ok "all live paths present at start are STILL present (filesystem untouched)"
else
  skip "live-path isolation NOT evaluated: no live paths existed at start (nothing to compare against)"
fi

# ============================================================================================================
echo; echo "============================================================"
echo "  CYP-637 lifecycle: PASS=$PASS  FAIL=$FAIL  SKIPPED=$SKIPPED"
if [ "$SKIPPED" -gt 0 ]; then
  echo "  ⚠️  $SKIPPED safety assertion(s) could NOT be evaluated — this run is INCONCLUSIVE for those claims."
  echo "      A skipped proof is not a passed proof: re-run with the live hub UP before accepting isolation."
fi
echo "  full run log: $LOGFILE"
echo "============================================================"
sync 2>/dev/null || true   # flush the tee'd log before exit
# CYP-637 G1 — three distinct outcomes, so "proven" and "not evaluated" can never share an exit code:
#   0 = all assertions evaluated and passed · 1 = a real failure · 2 = passed-but-INCONCLUSIVE (something skipped)
[ "$FAIL" -gt 0 ] && exit 1
[ "$SKIPPED" -gt 0 ] && exit 2
exit 0
