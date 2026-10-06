#!/bin/sh
# Cappuccino bridge lifecycle for herdr plugin hooks.
#
# herdr startup hooks are one-shot commands, not supervisors, so this script
# owns the process: `start` is idempotent (a bridge already answering on the
# port is left alone), `stop` takes it down, `status` reports. State lives
# under HERDR_PLUGIN_STATE_DIR when herdr provides it, ~/.local/state/ otherwise.
#
# No auth by owner decision: tailnet/loopback is the boundary. Socket:
# HERDR_SOCKET_PATH (what herdr injects), then HERDR_SOCKET, then the default
# user path.

set -u

STATE_DIR="${HERDR_PLUGIN_STATE_DIR:-$HOME/.local/state/cappuccino-bridge}"
PID_FILE="$STATE_DIR/bridge.pid"
LOG_FILE="$STATE_DIR/bridge.log"
PORT="${CAPP_BRIDGE_PORT:-7392}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$SCRIPT_DIR/../target/release/cappuccino-bridge"

mkdir -p "$STATE_DIR"

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

answers() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/api/session" 2>/dev/null
}

start() {
  if is_running; then
    echo "cappuccino-bridge: already running (pid $(cat "$PID_FILE"))"
    return 0
  fi
  if [ ! -x "$BIN" ]; then
    echo "cappuccino-bridge: binary missing at $BIN — run the plugin build first" >&2
    return 1
  fi
  : > "$LOG_FILE"
  nohup "$BIN" >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  sleep 1
  if is_running; then
    echo "cappuccino-bridge: started (pid $(cat "$PID_FILE"), port $PORT)"
    status
    return 0
  fi
  echo "cappuccino-bridge: failed to start — see $LOG_FILE" >&2
  return 1
}

stop() {
  if is_running; then
    kill "$(cat "$PID_FILE")"
    rm -f "$PID_FILE"
    echo "cappuccino-bridge: stopped"
  else
    rm -f "$PID_FILE"
    echo "cappuccino-bridge: not running"
  fi
}

# S3: tailnet exposure is automatic when the tailscale CLI is available and
# serve.auto_apply is not disabled (config file or CAPP_BRIDGE_SERVE_AUTO_APPLY=0).
# Idempotent — re-applying the same serve entry is fine. Never requires sudo
# (one-time prerequisite, documented in the README: tailscale set --operator=$USER).
serve_auto_apply() {
  # Env wins over the config file.
  if [ "${CAPP_BRIDGE_SERVE_AUTO_APPLY:-}" = "0" ] \
    || [ "${CAPP_BRIDGE_SERVE_AUTO_APPLY:-}" = "false" ]; then
    return 1
  fi
  local config_file="${CAPP_BRIDGE_CONFIG:-$HOME/.config/cappuccino-bridge/config.json}"
  if [ -f "$config_file" ] && command -v python3 >/dev/null 2>&1; then
    if python3 -c "
import json,sys
config=json.load(open('$config_file'))
sys.exit(0 if config.get('serve',{}).get('auto_apply',True) else 1)
" 2>/dev/null; then
      return 0
    fi
    return 1
  fi
  return 0
}

ensure_serve() {
  if ! serve_auto_apply; then
    echo "tailscale serve auto-apply is disabled; expose manually if needed:"
    echo "  tailscale serve --bg --https=443 http://127.0.0.1:$PORT"
    return 0
  fi
  if ! command -v tailscale >/dev/null 2>&1; then
    echo "tailscale CLI not found; expose manually:"
    echo "  tailscale serve --bg --https=443 http://127.0.0.1:$PORT"
    return 0
  fi
  if tailscale serve status 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
    echo "tailscale serve already exposes port $PORT"
  else
    tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" \
      || echo "tailscale serve failed; expose manually:"
    echo "  tailscale serve --bg --https=443 http://127.0.0.1:$PORT"
    return 0
  fi
  local serve_url
  serve_url="$(tailscale serve status 2>/dev/null | grep -o 'https://[^ ]*' | head -1)"
  if [ -n "$serve_url" ]; then
    echo "tailnet URL: ${serve_url%/}/api/session"
  fi
}

status() {
  if is_running; then
    echo "cappuccino-bridge: running (pid $(cat "$PID_FILE"), port $PORT)"
    ensure_serve
  else
    echo "cappuccino-bridge: not running"
    return 1
  fi
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: bridge.sh {start|stop|status}" >&2; exit 2 ;;
esac
