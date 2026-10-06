# Cappuccino bridge (herdr plugin)

A Rust herdr plugin serving the token-authed loopback API the Cappuccino phone
client consumes: agent list, pi transcripts, pane-line stream. This is the
product transport per the 2026-10-06 plugin pivot (issue #16); the TypeScript
daemon direction and the SSH-exec sketch were both superseded, and
`services/daemon` stays merged as a frozen reference. herdr-web-ui
(`refs/coding-agents/herdr-web-ui` @ 4964293) was studied as a design
reference for the plugin surface, token model and pi-store validation; the
implementation here is our own.

## Layout

```text
services/bridge/
├── herdr-plugin.toml     # plugin manifest: cargo build + idempotent startup hook
├── scripts/bridge.sh     # start/stop/status lifecycle (pid file under plugin state dir)
└── src/
    ├── main.rs           # axum server, loopback-only bind guard
    ├── auth.rs           # constant-time token compare; env/0600-file token source
    ├── herdr.rs          # herdr socket RPC (one NDJSON request per connection)
    ├── agents.rs         # agent.list parity (unnamed panes included) + honest branch
    ├── transcript.rs     # pi agent_session → canonical sessions-store validation
    ├── reconcile.rs      # LCS/two-poll-stability reconciliation (DaemonCore contract)
    └── routes.rs         # HTTP routes + WS pane-line stream
```

## API (wire-compatible with the reference daemon's v0 shapes)

| Route | Behavior |
| --- | --- |
| `GET /api/session` | pair/validation: `{"event":"paired","machine_id":…}`; wrong token → visible `unauthorized`, never a silent retry |
| `GET /api/agents` | herdr socket RPC `agent.list`; exact parity including unnamed panes (pane id as session id, kind as label); `active_branch` only when the git toplevel equals the agent cwd (`branch_source: own\|none`) |
| `GET /api/transcript?session=…` | resolves `agent_session` via `agent.get`, validates the path is inside the canonical pi sessions store; fail-closed `{"available":false}` when absent |
| `WS /api/stream?session=…` | pane-line appends (`stream_open`, `entries`, `stream_reset` events) with the reconciliation contract: churn never fabricates entries, appends are exactly-once, gaps are visible placeholders |

Auth: `Authorization: Bearer <token>` (or `?token=` for the WS handshake).
Token source: `CAPP_BRIDGE_TOKEN` env or `CAPP_BRIDGE_TOKEN_FILE` (0600),
compared constant-time. Bind: loopback only by design — tailnet exposure is
`tailscale serve`'s job (run `tailscale serve --bg http://127.0.0.1:7392` if
the owner wants phone reachability; this binary refuses any other bind).

## Run

```text
cargo build --release
CAPP_BRIDGE_TOKEN=… ./target/release/cappuccino-bridge   # binds 127.0.0.1:7392
sh scripts/bridge.sh status
cargo test
```
