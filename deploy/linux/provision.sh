#!/bin/sh
# CYP-635 — standalone provisioning for the Cyppie hub on Linux (the reviewable twin of the .deb postinst; also usable
# for a manual/non-deb install or to RE-provision from scratch). Secret-free: every secret is minted here on the host
# via the shipped CyppieHubProvision (CYP-628) — the .deb ships none. Custody = ratified option (a): a dedicated system
# user + /etc/cyppiehub/hub.env at 0600. Idempotent: an existing hub.env is preserved (never re-minted over).
#
# Usage (root):  provision.sh [--install-dir /opt/cyppiehub] [--data-dir /var/lib/cyppiehub] [--host 127.0.0.1] [--port 8787]
set -eu

INSTALL_DIR="/opt/cyppiehub"
DATA_DIR="/var/lib/cyppiehub"
HOST="127.0.0.1"
PORT="8787"
TUNNEL_PORT="8786"
while [ $# -gt 0 ]; do
  case "$1" in
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --data-dir)    DATA_DIR="$2";    shift 2 ;;
    --host)        HOST="$2";        shift 2 ;;
    --port)        PORT="$2";        shift 2 ;;
    --tunnel-port) TUNNEL_PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# 1. dedicated low-privilege system user (idempotent).
if ! getent passwd cyppie >/dev/null 2>&1; then
  useradd -r -s /usr/sbin/nologin -d "$DATA_DIR" -c "Cyppie hub service" cyppie
fi

# 2. data dir + secrets dir.
install -d -o cyppie -g cyppie -m 0750 "$DATA_DIR" "$DATA_DIR/logs"
install -d -o root  -g root  -m 0755 /etc/cyppiehub

# 3. mint master key + tokens + default config (only if not already provisioned — preserve-safe).
if [ ! -f /etc/cyppiehub/hub.env ]; then
  SECRETS="$(mktemp)"; chmod 0600 "$SECRETS"
  "$INSTALL_DIR/bin/CyppieHubProvision" \
    --data-dir "$DATA_DIR" --host "$HOST" --port "$PORT" --tunnel-port "$TUNNEL_PORT" \
    --secrets-out "$SECRETS" >/dev/null
  {
    cat "$SECRETS"
    echo "PLATFORM_CONFIG=$DATA_DIR/platform.config.json"
    echo "PLATFORM_GIT_ROOT=$DATA_DIR"
  } > /etc/cyppiehub/hub.env
  shred -u "$SECRETS" 2>/dev/null || rm -f "$SECRETS"
  chown cyppie:cyppie /etc/cyppiehub/hub.env; chmod 0600 /etc/cyppiehub/hub.env
  chown -R cyppie:cyppie "$DATA_DIR"
  echo "provisioned: master key + tokens minted, /etc/cyppiehub/hub.env written (0600, owned by cyppie); secret values not logged."
else
  echo "already provisioned (/etc/cyppiehub/hub.env exists) — preserved, not re-minted."
fi

# 4. enable + start the service (the unit is installed by the .deb postinst; enable it here for a manual install).
if [ -f /lib/systemd/system/cyppiehub.service ]; then
  systemctl daemon-reload 2>/dev/null || true
  systemctl enable --now cyppiehub.service 2>/dev/null || true
  echo "service enabled + started. Next: open the operator GUI to enter the ANTHROPIC_API_KEY (owner-only 0600, NOT encrypted-at-rest — CYP-220) + set the repo/roster."
fi
