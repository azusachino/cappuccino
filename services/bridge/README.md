# Cappuccino bridge

The bridge is Cappuccino's phone-reachable transport facade over selected machine-local Herdr APIs. It is a Rust/Tokio/axum executable linked through a Herdr plugin manifest; Herdr starts and controls it, but does not load it into the Herdr server or an agent. Herdr and Pi continue to own agent/session lifetime. The old `services/daemon` remains frozen reference work.

This standalone process serves a loopback HTTP/WebSocket API to native clients over the private tailnet. It has no auth by owner decision; public ingress is out of scope. The bridge currently has no prompt-delivery endpoint and is not an agent runtime.

## Layout

```text
services/bridge/
├── herdr-plugin.toml    # direct Rust executable commands
└── src/
    ├── main.rs          # foreground server and CLI dispatch
    ├── lifecycle.rs     # start/stop/status/logs and private Unix control
    ├── state.rs         # owner-only, no-follow lifecycle files/directories
    ├── config.rs        # defaults, JSON file, environment overrides
    ├── herdr.rs         # short-lived NDJSON calls over the local Herdr socket
    ├── agents.rs        # agent.list mapping, including unnamed panes
    ├── transcript.rs    # fail-closed Pi session-store validation
    ├── reconcile.rs     # read-only pane-line reconciliation
    ├── modules/         # agents, transcript and stream routes
    └── routes.rs        # HTTP and WebSocket handlers
```

## Read-only API

| Route | Behavior |
| --- | --- |
| `GET /api/session` | reachability and machine identity |
| `GET /api/agents` | Herdr `agent.list` mapping; names/pane IDs are locators, not proven native Pi session IDs |
| `GET /api/transcript?session=…` | resolves Herdr-reported `agent_session`; fails closed when missing or outside Pi's canonical session store |
| `WS /api/stream?session=…` | read-only pane-line append/reset events with visible gaps |

The current bridge has no send route. Herdr 0.9.3 has `agent.prompt`, `agent.read`, `agent.get`, `agent.wait` and `agent.send-keys` APIs, but its `agent.prompt` submits PTY text plus Enter and an optional lifecycle wait. That surface does not select Pi's `steer` versus `follow_up` queue or return a correlated Pi receipt. See [architecture](../../docs/architecture.md#herdr-input-api-and-delivery-boundary) and the [behavior spec](../../docs/behavior-spec.md#delivery-nudge--follow-up). Do not describe a terminal write acknowledgement as Nudge, Follow-up, `sent`, or `confirmed`.

## Configuration and security

Defaults are loopback `127.0.0.1:7392`. Configuration precedence is defaults, optional JSON (`CAPP_BRIDGE_CONFIG` or `~/.config/cappuccino-bridge/config.json`), then environment (`CAPP_BRIDGE_HOST`, `CAPP_BRIDGE_PORT`, `CAPP_BRIDGE_DATA_DIR`, `CAPP_BRIDGE_SERVE_AUTO_APPLY`). Non-loopback binds and enabled reserved auth are rejected.

`serve.auto_apply` defaults to `true`. On `start` and `status`, the binary checks `tailscale serve status` and may apply a Serve entry if one is missing. This can mutate the machine's Tailscale Serve configuration. Set the config value to `false` or `CAPP_BRIDGE_SERVE_AUTO_APPLY=0` to prevent all Tailscale commands; accepted false spellings are `0`, `false`, `no`, and `off` (case-insensitive), and the only true spelling is `true`. Invalid values fail closed. Disabled mode prints the manual command without running it.

## Install and lifecycle

On each Herdr machine, link this checkout (or install a later release):

```text
herdr plugin link /path/to/cappuccino/services/bridge
herdr plugin list
```

Herdr's `[[build]]` action compiles `target/release/cappuccino-bridge`. The one-shot `[[startup]]` command invokes that binary's `start` subcommand. It creates a separate server process with redirected logs and an owner-only state directory under `HERDR_PLUGIN_STATE_DIR` (or `~/.local/state/cappuccino-bridge`). The private `bridge.control` record contains a random per-instance token; `bridge.sock` is a mode-0600 Unix-domain control socket. The socket validates that token for status and graceful shutdown. No PID from a state file is signaled. The process is not supervised after startup, and no automatic Herdr-shutdown hook is configured. Use the plugin actions to inspect, stop and view logs:

```text
herdr plugin action azusachino.cappuccino-bridge status
herdr plugin action azusachino.cappuccino-bridge stop
herdr plugin action azusachino.cappuccino-bridge logs
herdr plugin log
```

`stop` sends a token-authenticated shutdown request over the private Unix socket and waits for both the control endpoint and HTTP listener to close. A stale/malformed record, unsafe path, socket mismatch or stop timeout fails closed and preserves uncertain state for manual inspection; it never guesses at a PID or deletes an unrelated file. A legacy `bridge.pid` from the former lifecycle also blocks start/status/stop until an operator verifies any old process and removes that file manually. Earlier versions may have created a permissive state directory: verify the path, owner, contents and any old bridge process before manually securing or cleaning it. This binary refuses unsafe directories and never chmods an existing custom directory. State directories must be current-user-owned mode 0700; lock, control record and log files must be regular owner-owned mode 0600 files. Symlinked, nonregular, multiply-linked, foreign-owned or permissive paths are rejected. `logs` prints the last 80 lines.

## Safe local verification

Use a task-owned loopback port and private state directory, with auto-apply disabled. This starts a fresh isolated bridge process; it does not restart a retained bridge or touch Tailscale:

```sh
cd services/bridge
cargo build --release
TMP_STATE="$(mktemp -d)"
export HERDR_PLUGIN_STATE_DIR="$(cd "$TMP_STATE" && pwd -P)"
export CAPP_BRIDGE_SERVE_AUTO_APPLY=0
export CAPP_BRIDGE_PORT=17392
target/release/cappuccino-bridge start
target/release/cappuccino-bridge status
curl -fsS http://127.0.0.1:17392/api/session
target/release/cappuccino-bridge logs
target/release/cappuccino-bridge stop
# Remove $TMP_STATE only after stop succeeded and the port is closed.
```

The `start` and `status` output must say auto-apply is disabled. Do not run this recipe against an owner's retained state directory or without the explicit no-Tailscale setting.

For foreground debugging, run `target/release/cappuccino-bridge` without a subcommand; it binds the configured loopback address until stopped. Do not use foreground mode as a Herdr startup command.

## Checks and dependencies

From this directory:

```text
cargo fmt --check
cargo test
```

The manifest uses argv arrays and invokes the Rust binary directly; there are no bridge shell launchers. Rust dependencies include Tokio, axum, serde/serde_json, and libc for Unix file-descriptor operations and lifecycle locking; stop never signals a PID. Herdr calls use its local socket protocol (fixture pinned to installed Herdr 0.9.3 / protocol 22); optional Serve integration calls the `tailscale` CLI without a shell. Lifecycle checks were run on macOS only; Linux runtime behavior is not certified by this report. The release binary is not claimed to be statically linked.
