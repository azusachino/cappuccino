# Cappuccino MVP design statement (2026-10-06 — implementation contract for this issue)

One sentence: native phone clients read live agent state from a tiny Rust bridge that runs inside each machine's existing herdr server as a plugin; Tailscale is the only network and the only auth.

## Deployed view

- Two herdr machines (macOS + Ubuntu), identical setup: `herdr plugin link` installs the bridge; plugin [[startup]] builds/launches the binary per herdr start; status action applies `tailscale serve --bg --https=443 http://127.0.0.1:<port>` automatically (idempotent, manual fallback printed only if Tailscale CLI unavailable or `serve.auto_apply=false`; one-time `tailscale set --operator=$USER` prerequisite documented).
- Phone: native SwiftUI app; Machines = per-machine bridge URLs (defaults-stored, editable); device Tailscale membership is the only auth; app contains zero auth code; VPN-off renders existing visible unreachable states.

### Bridge (single Rust binary; tokio + axum + serde; herdr RPC over HERDR_SOCKET UnixStream)

- Config layer: file + env overrides — port, bind (default 127.0.0.1), data dir, reserved `auth` section (unset by default = no-auth MVP).
- Middleware chain position reserved for a future authenticator; MVP chain is empty.
- Router with composable modules: `GET /api/session`, `GET /api/agents` (herdr agent.list RPC; exact parity incl. unnamed panes via pane_id fallback), `GET /api/transcript` (issue #7: pi `agent_session` → canonical store containment validation), `WS /api/stream` (pane-line appends; content-diff reconciliation; idempotent entry ids; no duplicates on reconnect).
- Plugin manifest: declarative; [[build]] cargo build --release; [[startup]] runs binary with HERDR_SOCKET; actions: start/stop/status; status prints tailnet URL and applies serve when needed.
- Non-goals (MVP): auth/token, public/beyond-tailnet exposure, approvals, delivery, transcripts UI (bridge endpoint exists for #7), push.

### Slice mapping

## 7 transcript UI consumes /api/transcript + stream; #8 delivery adds pane-input module; #9 approvals adds typed-card relay; #12 Android consumes same API

### Frozen history

services/daemon = reference only; SSH-exec pivot superseded; herdr-web-ui = design prior art (refs clone 4964293; study, never import).
