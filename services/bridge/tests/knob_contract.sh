#!/bin/sh
# Knob contract test: accepted disable values disable; other values resolve
# invalid. Run directly: sh tests/knob_contract.sh
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
fails=0

resolve() {
  CAPP_BRIDGE_SERVE_AUTO_APPLY="$1" sh -c "
    . '$SCRIPT_DIR/scripts/bridge.sh' >/dev/null 2>&1
    resolve_serve_auto_apply
" 2>/dev/null
}

expect() {
  local value="$1" expected="$2"
  local out
  out="$(resolve "$value")"
  if [ "$out" != "$expected" ]; then
    echo "FAIL: value '$value' resolved to '$out', expected '$expected'"
    fails=$((fails + 1))
  fi
}

expect "0" "disabled"
expect "false" "disabled"
expect "no" "disabled"
expect "off" "disabled"
expect "FALSE" "disabled"
expect "No" "disabled"
expect "OFF" "disabled"
expect "true" "enabled"
expect "" "enabled"

out="$(resolve "maybe")"
case "$out" in
  invalid:*) : ;;
  *) echo "FAIL: invalid value must resolve invalid:*, got '$out'"; fails=$((fails + 1)) ;;
esac

# Invalid value refuses to start: script exits non-zero with a clear error.
out="$(CAPP_BRIDGE_SERVE_AUTO_APPLY="maybe" sh "$SCRIPT_DIR/scripts/bridge.sh" start 2>&1)"
case "$out" in
  *"invalid serve.auto_apply"*) : ;;
  *) echo "FAIL: start with invalid knob must refuse loudly, got: $out"; fails=$((fails + 1)) ;;
esac

if [ "$fails" -eq 0 ]; then
  echo "knob-contract: all cases passed"
  exit 0
fi
exit 1
