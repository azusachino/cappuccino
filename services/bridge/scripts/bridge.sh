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
  require_valid_serve_auto_apply
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
# serve.auto_apply is not disabled. Accepted disable values (case-insensitive):
# 0, false, no, off. ANY other non-empty value is invalid: the caller must
# refuse to start or apply rather than silently auto-applying.
# Never requires sudo (documented prerequisite: tailscale set --operator=$USER).

# Prints "disabled" / "enabled" / "invalid:<value>" for the resolved knob.
resolve_serve_auto_apply() {
  local value="${CAPP_BRIDGE_SERVE_AUTO_APPLY:-}"
  if [ -z "$value" ]; then
    local config_file="${CAPP_BRIDGE_CONFIG:-$HOME/.config/cappuccino-bridge/config.json}"
    if [ -f "$config_file" ] && command -v python3 >/dev/null 2>&1; then
      value="$(python3 -c "
import json,sys
config=json.load(open('$config_file'))
value=config.get('serve',{}).get('auto_apply',True)
print('true' if value is True else 'false' if value is False else 'invalid:'+str(value))
" 2>/dev/null)"
      [ -z "$value" ] && value="true"
    else
      value="true"
    fi
  fi
  local lowered
  lowered="$(echo "$value" | tr '[:upper:]' '[:lower:]')"
  case "$lowered" in
    "true") echo "enabled" ;;
    "0"|"false"|"no"|"off") echo "disabled" ;;
    *) echo "invalid:$value" ;;
  esac
}

# Exits non-zero with a clear error when the knob value is invalid.
require_valid_serve_auto_apply() {
  local resolved
  resolved="$(resolve_serve_auto_apply)"
  case "$resolved" in
    enabled|disabled) return 0 ;;
    invalid:*)
      echo "cappuccino-bridge: invalid serve.auto_apply value '${resolved#invalid:}' — accepted: true, 0, false, no, off (case-insensitive)" >&2
      exit 2
      ;;
  esac
}

serve_auto_apply() {
  [ "$(resolve_serve_auto_apply)" = "disabled" ]
}

ensure_serve() {
  require_valid_serve_auto_apply
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
  require_valid_serve_auto_apply
  if is_running; then
    echo "cappuccino-bridge: running (pid $(cat "$PID_FILE"), port $PORT)"
    ensure_serve
  else
    echo "cappuccino-bridge: not running"
    return 1
  fi
}

# When sourced (tests), only definitions load; dispatch needs an explicit arg.
case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  "") : ;;  # sourced (tests): definitions only
  *) echo "usage: bridge.sh {start|stop|status}" >&2; exit 2 ;;
esac
