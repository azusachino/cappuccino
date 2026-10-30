# Cappuccino architecture and implementation boundary

Native phone clients reach selected machine-local Herdr APIs through a separate Rust HTTP/WebSocket transport facade. Herdr and Pi, not Cappuccino, own agent and session lifetime.

## Runtime pieces

- **Herdr (0.9.3 in the verified environment):** owns panes, agent processes, sessions and its local socket API. Its plugin manifest runs commands; the bridge is not loaded into Herdr's process or into an agent.
- **Cappuccino bridge (`services/bridge`):** a standalone Rust binary linked as a Herdr plugin. Herdr's one-shot startup hook and actions invoke the binary directly. It starts and manages a separate loopback HTTP/WebSocket server, which calls the local Herdr socket and exposes the routes below to the native clients. It is a phone-reachable facade, not an agent runtime or a replacement for Herdr's APIs.
- **Phone:** native SwiftUI and Compose clients call the bridge over the private tailnet. The bridge binds to loopback, has no auth by the owner's decision, and is exposed using Tailscale Serve. Public ingress is out of scope. An optional iroh transport (off by default) tunnels the same HTTP/WebSocket protocol from paired phones off the tailnet; see the [iroh design](design/public-exposure-iroh.md).

## Bridge routes

- `GET /api/session`: reachability, machine identity, version and a capability advertisement.
- `GET /api/agents`: maps Herdr `agent.list` metadata, including unnamed panes.
- `GET /api/agents/{sessionId}/conversation`: the canonical transcript for Pi, Claude Code or Agy when Herdr reports a usable session. Otherwise the pane's recent scrollback, labeled `scrollback`. Returns active model metadata, context tokens, and upstream provider rate limit quotas in `stats`.
- `POST /api/agents/{sessionId}/prompt`: sends prompt text into the pane, or answers a visible approval or question card. Text that starts with an agent command is refused with `409 confirm_command` unless the owner confirmed it. Interactive terminal commands (like `/status`) are blocked in mobile clients to prevent unescapable curses locks.
- `POST /api/agents/{sessionId}/control`: `interrupt` sends Escape; `new_session` types the agent's `/new` command and is refused while the agent is working or blocked.
- `GET /api/transcript`: resolves the Herdr-reported session path and fails closed unless it is inside the canonical session store.
- `WS /api/stream`: the read-only pane-line stream.
- `GET /pair`: only when `iroh.enabled` is true (otherwise 404). Issues a fresh single-use pairing token on each load and renders it as an inline-SVG QR code plus the copy-paste payload. Loopback only, like every route; it is reachable from the tailnet only through Tailscale Serve.

The prompt and control routes type into an existing pane. They do not start or stop a process, use Pi's queue API, or prove that a phone transcript matches a live canonical session.

## Herdr input API and delivery boundary

The installed Herdr 0.9.3 API includes `agent.prompt`, `agent.read`, `agent.get`, `agent.wait` and `agent.send-keys`:

- `agent.prompt` writes text and Enter to the recognized agent's PTY. Herdr reports successful submission after the PTY write; its optional `wait` observes lifecycle state, not a correlated Pi queue receipt. When the target is already working, completion of that active turn can satisfy the wait.
- `agent.read` returns terminal output; `agent.get` returns agent metadata and lifecycle state; `agent.wait` waits for lifecycle states. `agent.send-keys` validates and writes logical terminal keys. None takes an explicit Nudge or Follow-up delivery kind, or supplies a Pi-queue acceptance receipt.
- A task-owned disposable Pi runtime received one idle synthetic prompt and one synthetic prompt while its shell command was running. The observed responses were visible in that session. That proves the submissions produced responses, not durable queue acceptance, a delivery kind, or a receipt contract.

Pi's installed RPC documentation describes queue-specific `steer` and `follow_up` methods. Herdr's `agent.prompt` schema does not select between them. Owner acceptance defines `confirmed` as Pi acceptance of the requested queue operation, and an ambiguous outcome as final `unresolved` with no replay. Those semantics are accepted contract, not behavior Herdr currently exposes. Do not label PTY submission as Nudge, Follow-up, `sent` or `confirmed`. See the [behavior spec](behavior-spec.md#delivery-nudge--follow-up).

## Configuration and lifecycle

The manifest invokes the installed `cappuccino-bridge` command for `start`, `stop`, `status` and `logs`. Lifecycle state lives under `HERDR_PLUGIN_STATE_DIR` (without it, Herdr's `${XDG_STATE_HOME:-~/.local/state}/herdr/plugins/cappuccino-bridge`) in a current-user-owned mode-0700 directory. State and log files and the private Unix-domain control socket are verified owner-only, no-follow paths. A random per-instance token authenticates control requests. Stop asks the running bridge to shut down gracefully; it never signals a PID read from state. Malformed, legacy, stale or unsafe paths fail closed without replacement or cleanup.

The `serve.auto_apply` setting defaults to true. With `false`, or `CAPP_BRIDGE_SERVE_AUTO_APPLY=0`, the bridge runs no Tailscale command and only prints the manual exposure command. Invalid values fail closed.

Config precedence is defaults, then optional TOML/JSON config (`CAPP_BRIDGE_CONFIG`, else `$XDG_CONFIG_HOME/cappuccino-bridge/config.toml` if present, else `$XDG_CONFIG_HOME/cappuccino-bridge/config.json`, else `~/.config/cappuccino-bridge/config.toml` if present, else `~/.config/cappuccino-bridge/config.json`), then supported environment overrides. Bind addresses must stay on loopback. See the [bridge setup and commands](../services/bridge/README.md).

## Delivery acceptance remains open

The behavior spec records the owner-accepted Nudge, Follow-up and receipt contract. That contract is not shipped behavior. Herdr's terminal input acknowledgement alone does not establish which Pi queue accepted text, whether it survives a restart, or whether the target session occupant stayed the same. These capabilities stay unimplemented until a supported integration and verified acceptance evidence exist. Do not revive the superseded companion-daemon or pairing design, and do not add a Pi extension without approval.

## Slice map

- #7: transcript UI and read-only stream. Implemented; canonical live runtime proof is outstanding.
- #8: explicit Nudge and Follow-up delivery with receipts. Not implemented. The prompt route sends text without a receipt.
- #9: typed approvals and grants. Not implemented. Approvals are answered from the screen through the prompt route.
- #12: Android consumes the selected bridge transport.
