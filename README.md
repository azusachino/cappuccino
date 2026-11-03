# Cappuccino

<p align="center">
  <img src="docs/assets/icon.png" width="128" alt="Cappuccino icon" />
</p>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0 license" /></a>
  <img src="https://img.shields.io/badge/platform-iPhone%20%7C%20Android-black" alt="iPhone and Android" />
  <img src="https://img.shields.io/badge/Herdr-0.9.0%2B-4C4F69" alt="Herdr 0.9.0 or later" />
</p>

Cappuccino is a native iPhone and Android app for the coding agents already
running in [Herdr](https://github.com/herdrdev/herdr) panes on your machines. It
shows each agent's conversation and lets you send a prompt, stop a run, start a
new session, or answer an approval. It does not start, stop or replace agents.

A small Rust bridge, installed as a Herdr plugin, serves the apps over
**iroh** (peer-to-peer, paired with a QR code) and, as a fallback, your Tailnet.

## What you can do

- **Machines:** pair each Herdr machine by scanning its QR code, or add its
  Tailnet URL. An unreachable machine does not block the others.
- **Agents:** see each agent's name, working directory, branch and state
  (idle, working or blocked).
- **Conversations:** read the transcript for Pi, Claude Code and Agy. When no
  session file is available, the app shows the pane's scrollback, read-only.
- **Prompts, stop and new session:** type into the agent's pane. Stop sends
  Escape. `/new` starts a fresh session only while the agent is idle.
- **Approvals and questions:** answer tool approvals and Claude Code's
  question forms. An answer is refused if the prompt on screen has changed.
- **Slash commands:** commands such as `/model` or `/new` need an explicit
  confirmation before they are sent.
- **Session stats:** model, thinking level, context tokens and prompt-cache use.
- **Saved conversations (Android and iPhone):** recent conversations are kept
  on the device and open read-only.
- **Notifications (Android):** a foreground service alerts you when an agent
  finishes or waits for input.
- **Telegram alerts (optional, bridge):** the bridge can post a plain-text
  message to a private group on completion or when an agent needs an answer.
  Off by default. See [Telegram alerts setup](docs/telegram-alerts-setup.md).

## How it works

```text
phone (iPhone or Android)
  ├── iroh (QUIC, TLS 1.3, paired by QR code)          primary
  └── Tailnet HTTPS -> tailscale serve -> 127.0.0.1    fallback
        │
        ▼
cappuccino-bridge (loopback 127.0.0.1:7392)
  │  Herdr socket
  ▼
Herdr -> agent panes (Pi, Claude Code, Agy)
```

Clients start with `POST /api/v2/handshake`, then send
`X-Cappuccino-Protocol: 2` on each call. Capabilities are negotiated per
handshake, and the app acts only on those both sides agree to. The contract is
in [docs/bridge-v2-contract.md](docs/bridge-v2-contract.md).

Prompts and stop reach the pane as typed input. Herdr does not report delivery,
so the app shows no receipt.

## Setup

1. **Bridge on each Herdr machine.** Install, link and check the plugin as
   described in [services/bridge/README.md](services/bridge/README.md). Herdr
   0.9.0 or later is required.
2. **Pair.** Start the bridge and run `cappuccino-bridge pair`. The QR code and
   iroh options are in [docs/iroh-setup.md](docs/iroh-setup.md).
3. **iPhone app.** Run `make setup`, then `make dev` with a USB-connected phone.
   iOS 17.5 or later. Install steps, the free-account re-signing limit and
   SideStore are in [docs/install-iphone.md](docs/install-iphone.md).
4. **Android app.** Open `apps/android/` in Android Studio, or run
   `make build-android` for a debug APK. Android 13 (API 33) or later. Only
   debug builds are provided.
5. **Pair the phone.** Tap `+` in the app and scan the QR code. Or enter the
   Tailnet URL from step 2.

Bridge limits and timeouts are configurable under `[tuning]`; defaults are in
[services/bridge/README.md](services/bridge/README.md#configuration-and-security).

## Limitations

- **iroh after a long network loss.** A phone that sleeps or changes network can
  take up to a minute to re-establish a direct path. The app falls back to the
  Tailnet URL when one is configured.
- **Tailnet fallback has no app-level authentication.** The bridge binds to
  loopback only, and `tailscale serve` is the boundary.
- **Approvals are read from the screen.** There are no typed approval grants and
  no reconciliation of pending requests after a reconnect.
- **History depends on the session file.** An agent without a session path that
  Herdr reports shows the read-only scrollback fallback.
- **The bridge is not supervised after startup.** Use the plugin's stop action.
- **The iPhone app has no notifications.** Telegram alerts come from the bridge.
- **Android is a debug build only.** Release signing is not set up.
- **Live Telegram alerts are not yet verified end to end.** A test message reached
  the group; the setup guide lists the remaining checks.
- **The Mac app is retired.** Herdr covers the desktop.

## Documentation

- [docs/intent.md](docs/intent.md): product intent and owner decisions
- [docs/architecture.md](docs/architecture.md): components and the Herdr input boundary
- [docs/bridge-v2-contract.md](docs/bridge-v2-contract.md): HTTP and WebSocket contract
- [services/bridge/README.md](services/bridge/README.md): bridge install, API, configuration
- [docs/iroh-setup.md](docs/iroh-setup.md): pairing and relays
- [docs/install-iphone.md](docs/install-iphone.md): iPhone install paths
- [docs/telegram-alerts-setup.md](docs/telegram-alerts-setup.md): Telegram alerts setup
- [docs/verification.md](docs/verification.md): what has been tested, and the limits
- [docs/development.md](docs/development.md): toolchains, gates and CI
- [CHANGELOG.md](CHANGELOG.md): release notes

## Development

Run `make check` for the focused platform gates and `make validate` before a
pull request. Style and contribution rules are in
[CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

Cappuccino reads and relays agents you already run. Keep changes within
[docs/intent.md](docs/intent.md): the apps must not start, stop or replace agents.

## License

Cappuccino is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE).
