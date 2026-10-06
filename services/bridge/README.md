# Cappuccino bridge

The bridge is Cappuccino's phone-reachable transport facade over selected machine-local Herdr APIs. It is a Rust/Tokio/axum executable linked through a Herdr plugin manifest; Herdr starts and controls it, but does not load it into the Herdr server or an agent. Herdr and Pi continue to own agent/session lifetime. The old `services/daemon` remains frozen reference work.

This standalone process serves a loopback HTTP/WebSocket API to native clients over the private tailnet. It has no auth by owner decision; public ingress is out of scope. The bridge currently has no prompt-delivery endpoint and is not an agent runtime.

## Layout

```text
services/bridge/
├── herdr-plugin.toml    # direct Rust executable commands
└── src/
    ├── main.rs          # foreground server and CLI dispatch
    ├── lifecycle.rs     # start/stop/status/logs and process identity
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

Herdr's `[[build]]` action compiles `target/release/cappuccino-bridge`. The one-shot `[[startup]]` command invokes that binary's `start` subcommand. It creates a separate server process with redirected logs, records its PID/executable/start marker under `HERDR_PLUGIN_STATE_DIR` (or `~/.local/state/cappuccino-bridge`), and avoids starting a second copy when that exact process is healthy. It is not supervised after startup, and no automatic Herdr-shutdown hook is configured. Use the plugin actions to inspect, stop and view logs:

```text
herdr plugin action azusachino.cappuccino-bridge status
herdr plugin action azusachino.cappuccino-bridge stop
herdr plugin action azusachino.cappuccino-bridge logs
herdr plugin log
```

`stop` verifies the recorded executable and per-start marker before sending SIGTERM to that PID; it does not use process-name matching. State and log files belong to the plugin state directory. `logs` prints the last 80 lines.

## Safe local verification

Use a task-owned loopback port and state directory, with auto-apply disabled. This starts a fresh isolated bridge process; it does not restart a retained bridge or touch Tailscale:

```text
cd services/bridge
cargo build --release
export CAPP_BRIDGE_SERVE_AUTO_APPLY=0
export CAPP_BRIDGE_PORT=17392
export HERDR_PLUGIN_STATE_DIR="$(mktemp -d)"
target/release/cappuccino-bridge start
target/release/cappuccino-bridge status
curl -fsS http://127.0.0.1:17392/api/session
target/release/cappuccino-bridge logs
target/release/cappuccino-bridge stop
```

The `start` and `status` output must say auto-apply is disabled. Do not run this recipe against an owner's retained state directory or without the explicit no-Tailscale setting.

For foreground debugging, run `target/release/cappuccino-bridge` without a subcommand; it binds the configured loopback address until stopped. Do not use foreground mode as a Herdr startup command.

## Checks and dependencies

From this directory:

```text
cargo fmt --check
cargo test
```

The manifest uses argv arrays and invokes the Rust binary directly; there are no bridge shell launchers. Rust dependencies include Tokio, axum, serde/serde_json, and libc for targeted Unix process signaling. Herdr calls use its local socket protocol (fixture pinned to installed Herdr 0.9.3 / protocol 22); optional Serve integration calls the `tailscale` CLI without a shell. `ps` is used to verify the exact recorded process command before signaling. The release binary is not claimed to be statically linked.
