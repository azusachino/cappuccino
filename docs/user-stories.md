# MVP user stories and setup (two herdr machines)

Server prerequisite (once per machine): herdr 0.9+, Tailscale running, Rust
toolchain for building from source.

## Server stories

- **S1 Install:** "On each Herdr machine, I link the bridge's plugin
  manifest." → `herdr plugin link <path-to-services/bridge>` (dev) or
  `herdr plugin install <release>` (later). `herdr plugin list` shows it.
  Herdr invokes the manifest commands; the Rust bridge server is a separate
  process, not loaded into Herdr.
- **S2 Startup and control:** "The bridge starts when Herdr starts and I can
  inspect or stop it." → linking builds the binary; the plugin `[[startup]]`
  invokes its idempotent `start` command. The plugin `status`, `stop` and
  `logs` actions call the binary directly. The server is not supervised by
  Herdr and no automatic shutdown hook is configured.
- **S3 Expose to tailnet:** "I reach the bridge from my phone over Tailscale."
  → automatic with `serve.auto_apply` at its default: the status/startup
  lifecycle applies `tailscale serve --bg --https=443
  http://127.0.0.1:7392` and prints the tailnet URL
  (`https://<host>.<tailnet>.ts.net/api/session` answers JSON). One-time
  prerequisite: `tailscale set --operator=$USER` (never sudo). Set
  `serve.auto_apply=false` (config or `CAPP_BRIDGE_SERVE_AUTO_APPLY=0`) for
  verification/no-mutation mode — then run the printed manual command.
- **S4 Two machines, same steps:** identical install on both; no cross-machine
  config; the phone simply adds both URLs.

## Phone stories (iOS MVP)

- **S5 Add machine:** "I paste a bridge URL once; the machine appears in
  Machines." → add/edit/delete per-machine base URL (stored locally).
- **S6 See agents:** "I open Machines and see each machine's agents, states
  and branches; unreachable machines show a visible error without affecting
  others." → pull-to-refresh; per-machine failure states.
- **S7 Install the app:** "I build and install the app from the repo with
  Xcode (free provisioning, weekly re-sign)." → documented; no store, no
  TestFlight in MVP.
- **S8 (in scope, issue #7 — shipped in this slice):** tap an agent → live
  active-branch transcript with expandable tool details. **(#9, post-MVP):**
  answer prompts from the phone.

## Rules

- Access control = tailnet membership; no credentials anywhere in MVP. Public
  exposure is a documented non-goal.
- Server-side config: port/bind/data-dir via config file + env; auth section
  reserved (unset).
- Every story must be executable from docs alone; instructions live in the
  bridge README (server) and README (phone).

## Instructions placement

- `services/bridge/README.md`: server setup S1–S4 (link/install, status/log,
  tailscale serve, verification curl).
- Top-level README "Getting started": S7 phone install + S5/S6 machine add,
  linking to bridge README.
