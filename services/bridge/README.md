# Cappuccino bridge (herdr plugin)

A Rust herdr plugin serving the loopback API the Cappuccino phone client
consumes: agent list, pi transcripts, pane-line stream. This is the product
transport per the 2026-10-06 plugin pivot (issue #16); the TypeScript daemon
direction and the SSH-exec sketch were both superseded, and `services/daemon`
stays merged as a frozen reference. herdr-web-ui
(`refs/coding-agents/herdr-web-ui` @ 4964293) was studied as a design
reference for the plugin surface and pi-store validation; the implementation
here is our own.

## Layout (actual)

```text
services/bridge/
├── herdr-plugin.toml     # plugin manifest: cargo build + idempotent startup hook
├── scripts/bridge.sh     # start/stop/status lifecycle (pid file under plugin state dir)
└── src/
    ├── main.rs           # bootstrap: config load, loopback guard, module merge, middleware slot
    ├── config.rs         # defaults ← JSON config file ← env overrides; reserved auth section
    ├── auth_middleware   # pass-through slot in main.rs where an authenticator layers in
    ├── herdr.rs          # herdr socket RPC (one NDJSON request per Unix connection)
    ├── agents.rs         # agent.list parity (unnamed panes included) + honest branch reporting
    ├── transcript.rs     # pi agent_session → canonical sessions-store validation (fail-closed)
    ├── reconcile.rs      # LCS/two-poll-stability reconciliation (DaemonCore contract)
    └── routes.rs         # HTTP handlers + WebSocket pane-line stream
```

There is no auth module and no token: the owner decision is that the
tailnet/loopback boundary is the security model. The `auth` section in the
config is reserved and errors if enabled until an authenticator exists.

## API (wire-compatible with the reference daemon's v0 shapes)

| Route | Behavior |
| --- | --- |
| `GET /api/session` | reachability + identity: `{"event":"paired","machine_id":…}` |
| `GET /api/agents` | herdr socket RPC `agent.list`; exact parity including unnamed panes (pane id as session id, kind as label); `active_branch` only when the git toplevel equals the agent cwd (`branch_source: own\|none`) |
| `GET /api/transcript?session=…` | resolves `agent_session` via `agent.get`, validates the path is inside the canonical pi sessions store; fail-closed `{"available":false}` when absent |
| `WS /api/stream?session=…` | pane-line appends (`stream_open`, `entries`, `stream_reset` events) with the reconciliation contract: churn never fabricates entries, appends are exactly-once, gaps are visible placeholders |

**No auth, by owner decision:** the tailnet/loopback boundary is the security
model. The binary refuses any non-loopback bind; `tailscale serve` is the
supported exposure path. Public-internet exposure and transport
authentication are explicit non-goals — do not add a token, do not widen the
bind.

## Configuration

Defaults for the tailnet-only MVP (`127.0.0.1:7392`), an optional JSON config
file (`CAPP_BRIDGE_CONFIG` or `~/.config/cappuccino-bridge/config.json`), then
env overrides (`CAPP_BRIDGE_HOST` / `CAPP_BRIDGE_PORT` /
`CAPP_BRIDGE_DATA_DIR`). The `auth` section is reserved (unset = no-auth MVP;
`enabled: true` is rejected at startup).

**Tailscale exposure** (`serve.auto_apply`, default `true`): the
`status`/`start` lifecycle checks `tailscale serve status` and, when our
serve entry is missing and the CLI is available, applies
`tailscale serve --bg --https=443 http://127.0.0.1:<port>` itself (idempotent)
and prints the resulting tailnet URL. One-time prerequisite, never sudo:
`tailscale set --operator=$USER`. When the CLI is absent or errors, the
manual command is printed as fallback. Set `"serve": {"auto_apply": false}`
(or `CAPP_BRIDGE_SERVE_AUTO_APPLY=0`) and the lifecycle never touches
Tailscale — it only prints the manual command.

## Server setup (stories S1–S4)

Once per herdr machine: herdr 0.9+, Tailscale running, a Rust toolchain.

**S1 Install.** Link the plugin from this checkout (development), or install a
release bundle later:

```sh
herdr plugin link /path/to/cappuccino/services/bridge
herdr plugin list        # azusachino.cappuccino-bridge appears
```

herdr runs the manifest `[[build]]` (`cargo build --release`) and keeps the
plugin registered across restarts.

**S2 Auto-run.** The manifest `[[startup]]` hook is idempotent: on every herdr
start it launches the built binary if it is not already answering on the
configured port and records the pid under the plugin state dir. Nothing else
to run. Check it:

```sh
herdr plugin action azusachino.cappuccino-bridge status    # running + port + tailnet URL
herdr plugin action azusachino.cappuccino-bridge logs      # recent log tail
herdr plugin log                                           # herdr's own plugin log
```

**S3 Expose to tailnet.** With `serve.auto_apply` at its default `true`, the
status/start lifecycle applies the serve entry and prints the tailnet URL,
e.g. `tailnet URL:
https://<host>.<tailnet>.ts.net/api/session` — no manual step. One-time
prerequisite (never sudo): `tailscale set --operator=$USER`. With
`serve.auto_apply=false`, run the printed manual command yourself:

```sh
tailscale serve --bg --https=443 http://127.0.0.1:7392
```

**S4 Second machine.** Repeat S1–S3 verbatim; nothing is shared between
machines. The phone adds each machine's URL separately (see the top-level
README "Getting started").

## Verification recipe (no Tailscale mutation)

For checks, CI or a verifier that must not touch the machine's Tailscale
exposure:

```sh
cd services/bridge
cargo build --release
CAPP_BRIDGE_SERVE_AUTO_APPLY=0 CAPP_BRIDGE_PORT=7991 sh scripts/bridge.sh start
curl -fsS http://127.0.0.1:7991/api/session
# {"event":"paired","machine_id":"…","protocol":1,"plugin":"azusachino.cappuccino-bridge"}
curl -fsS http://127.0.0.1:7991/api/agents | head -c 400
sh scripts/bridge.sh stop
```

With `CAPP_BRIDGE_SERVE_AUTO_APPLY=0` the lifecycle never invokes `tailscale
serve`; it prints the manual command only.

## Run (quick)

```text
cargo build --release
./target/release/cappuccino-bridge  # binds 127.0.0.1:7392, no auth
sh scripts/bridge.sh status
cargo test
```
