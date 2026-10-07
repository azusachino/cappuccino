# Cappuccino architecture and implementation boundary

Native phone clients reach selected machine-local Herdr APIs through a separate Rust HTTP/WebSocket transport facade. Herdr and Pi, not Cappuccino, own agent and session lifetime.

## Runtime pieces

- **Herdr (0.9.3 in the verified environment):** owns panes, agent processes, sessions and its local socket API. Its plugin manifest runs commands; the bridge is not loaded into Herdr's process or into an agent.
- **Cappuccino bridge (`services/bridge`):** a standalone Rust binary linked as a Herdr plugin. Herdr's one-shot startup hook and actions invoke the binary directly. It starts and manages a separate loopback HTTP/WebSocket server, which calls the local Herdr socket and exposes the routes below to the native clients. It is a phone-reachable facade, not an agent runtime or a replacement for Herdr's APIs.
- **Phone:** native SwiftUI/Compose clients call the bridge over the private tailnet. The bridge binds to loopback, has no auth by the owner's decision, and may be exposed using Tailscale Serve. Public ingress is out of scope.
- **Reference daemon:** `services/daemon` remains frozen historical/reference work; it is not the current product runtime.

## Bridge routes currently implemented

- `GET /api/session`: bridge reachability and machine identity.
- `GET /api/agents`: maps Herdr `agent.list` metadata, including unnamed panes.
- `GET /api/transcript`: resolves the Herdr-reported Pi session path and fails closed unless it is inside the canonical Pi session store.
- `WS /api/stream`: exposes the read-only pane-line reconciliation stream.

The bridge currently has **no prompt-delivery route**. These read paths do not control agent lifetime, invoke Pi queue APIs, or prove that a phone transcript matches a live canonical session.

## Herdr input API and delivery boundary

The installed Herdr 0.9.3 API includes `agent.prompt`, `agent.read`, `agent.get`, `agent.wait` and `agent.send-keys`:

- `agent.prompt` writes text and Enter to the recognized agent's PTY. Herdr reports successful submission after the PTY write; its optional `wait` observes lifecycle state, not a correlated Pi queue receipt. When the target is already working, completion of that active turn can satisfy the wait.
- `agent.read` returns terminal output; `agent.get` returns agent metadata/lifecycle state; `agent.wait` waits for lifecycle states. `agent.send-keys` validates and writes logical terminal keys. None takes an explicit Nudge/Follow-up delivery kind or supplies a Pi-queue acceptance receipt.
- A task-owned disposable Pi runtime received one idle synthetic prompt and one synthetic prompt while its shell command was running. The observed responses were `CAP-HERDR-IDLE-ACK`, `CAP-HERDR-WORKING-MARKER`, then `CAP-HERDR-SECOND-MARKER`; this proves those harmless submissions produced visible responses in that session, not durable queue acceptance, a delivery kind, or a receipt contract.

Pi's installed RPC documentation separately describes queue-specific `steer` and `follow_up` methods. Herdr's `agent.prompt` schema does not select between them. Owner acceptance defines `confirmed` as Pi acceptance of the requested queue operation and an ambiguous outcome as final `unresolved` with no replay. Those semantics are accepted contract, not behavior Herdr currently exposes: do not label PTY submission as Nudge, Follow-up, `sent`, or `confirmed`. The distinction and Pi acceptance remain unproven through Herdr. See [behavior spec](behavior-spec.md#delivery-nudge--follow-up) and the task report for source/runtime limits.

## Configuration and lifecycle

The manifest invokes `target/release/cappuccino-bridge` directly for `start`, `stop`, `status` and `logs`. Lifecycle state lives under `HERDR_PLUGIN_STATE_DIR` (or `~/.local/state/cappuccino-bridge`) in a current-user-owned mode-0700 directory; state/log files and the private Unix-domain control socket are verified owner-only, no-follow paths. A random per-instance token authenticates control requests. Stop asks the running bridge to shut itself down gracefully; it never signals a PID read from state. Malformed, legacy, stale or unsafe paths fail closed without replacement or cleanup. The `serve.auto_apply` setting defaults to true for the existing product workflow; `false` or `CAPP_BRIDGE_SERVE_AUTO_APPLY=0` returns before any Tailscale command and only prints the manual exposure command. Invalid values fail closed.

Config precedence is defaults, optional JSON (`CAPP_BRIDGE_CONFIG` or `~/.config/cappuccino-bridge/config.json`), then supported environment overrides. Bind addresses must remain loopback. See [bridge setup and commands](../services/bridge/README.md).

## Delivery acceptance remains open

The behavior spec records the owner-accepted Nudge/Follow-up and receipt contract, not shipped behavior. Herdr's terminal input acknowledgement alone does not establish which Pi queue accepted text, whether it survives restart, or whether a target session occupant remained unchanged. These capabilities remain unimplemented pending a supported integration and verified acceptance evidence; do not revive the superseded daemon/pairing design or add a Pi extension without approval.

## Slice map

- #7: transcript UI and read-only stream presentation; canonical live runtime proof remains outstanding.
- #8: explicit Nudge/Follow-up delivery and receipts; not implemented.
- #9: typed approvals and grants; not implemented.
- #12: Android consumes the selected bridge transport.
