# MVP user stories and setup (two herdr machines)

Server prerequisite (once per machine): herdr 0.9+, Tailscale running, Rust
toolchain for building from source.

## Server stories

- **S1 Install:** "On each herdr machine, I install the bridge so it lives
  inside herdr." → `herdr plugin link <path-to-services/bridge>` (dev) or
  `herdr plugin install <release>` (later). `herdr plugin list` shows it.
- **S2 Auto-run:** "The bridge starts and stops with herdr — nothing else to
  run." → plugin `[[startup]]` builds/launches the binary; the plugin
  `status` action reports running + port; `herdr plugin log` shows output.
- **S3 Expose to tailnet:** "I reach the bridge from my phone over Tailscale."
  → run the printed `tailscale serve --bg --https=443 http://127.0.0.1:7392`
  once per machine; `https://<host>.<tailnet>.ts.net/api/session` answers
  JSON. The bridge status output names this exact command for the configured
  port.
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
- **S8 (post-MVP, issue #7):** tap an agent → live transcript. **(#9)** answer
  prompts from the phone.

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
