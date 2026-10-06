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

## Design for extension

- **Config layer** (`src/config.rs`): tailnet-only MVP defaults
  (`127.0.0.1:7392`), an optional JSON config file
  (`CAPP_BRIDGE_CONFIG` or `~/.config/cappuccino-bridge/config.json`), then
  `CAPP_BRIDGE_HOST`/`CAPP_BRIDGE_PORT`/`CAPP_BRIDGE_DATA_DIR` env overrides.
  The `auth` section is **reserved**: unset (or `enabled: false`) is the
  no-auth MVP; `enabled: true` is rejected until an authenticator exists.
- **Middleware hook**: `auth_middleware` in `main.rs` is the single slot where
  an authenticator layers in — endpoint handlers never change.
- **Composable modules** (`src/modules/`): agents/transcript/stream each
  contribute a router; approvals (#9) and push (#11) bolt on as new modules
  plus new manifest actions.

**No auth, by owner decision:** the tailnet/loopback boundary is the security
model. The binary refuses any non-loopback bind; `tailscale serve --bg
http://127.0.0.1:7392` is the only supported exposure path. Public-internet
exposure and transport authentication are explicit non-goals — do not add a
token, do not widen the bind.

## Run (quick)

```text
cargo build --release
./target/release/cappuccino-bridge  # binds 127.0.0.1:7392, no auth
sh scripts/bridge.sh status
cargo test
```

## Server setup (stories S1-S4)

Once per herdr machine: herdr 0.9+, Tailscale running, a Rust toolchain.

**S1 Install.** Link the plugin from this checkout (development), or install a
release bundle later:

```text
herdr plugin link /path/to/cappuccino/services/bridge
herdr plugin list        # azusachino.cappuccino-bridge appears
```

herdr runs the manifest `[[build]]` (`cargo build --release`) and keeps the
plugin registered across restarts.

**S2 Auto-run.** The manifest `[[startup]]` hook is idempotent: on every herdr
start it launches the built binary if it is not already answering on the
configured port (default 7392) and records the pid under the plugin state dir.
Nothing else to run. Check it:

```text
herdr plugin action azusachino.cappuccino-bridge status    # running + port
herdr plugin action azusachino.cappuccino-bridge logs      # recent log tail
herdr plugin log                                           # herdr's own plugin log
```

Configuration (optional): `CAPP_BRIDGE_CONFIG` or
`~/.config/cappuccino-bridge/config.json` — `{"port": 7392, "bind":
"127.0.0.1", "data_dir": "..."}` — then `CAPP_BRIDGE_HOST`/`CAPP_BRIDGE_PORT`/
`CAPP_BRIDGE_DATA_DIR` env overrides. The `auth` section is reserved (unset =
no-auth MVP).

**S3 Expose to tailnet.** The bridge refuses non-loopback binds; Tailscale is
the supported exposure. Once per machine (the port matches your config):

```text
tailscale serve --bg --https=443 http://127.0.0.1:7392
```

Verify from another tailnet device:

```text
curl -fsS https://<host>.<tailnet>.ts.net/api/session
# {"event":"paired","machine_id":"...","protocol":1,"plugin":"azusachino.cappuccino-bridge"}
```

**S4 Second machine.** Repeat S1-S3 verbatim; nothing is shared between
machines. The phone adds each machine's URL separately (see the top-level
README "Getting started").
