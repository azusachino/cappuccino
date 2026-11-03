# Changelog

All notable changes to Cappuccino are documented in this file.

## 0.1.1 - 2026-11-03

Bridge, Android and iOS ship together at 0.1.1. Android `versionCode` 2; iOS build 2.

### Added

- **Telegram attention alerts** (bridge): an optional, send-only route that posts one plain-text message to a private group when an agent finishes a work cycle or waits for an answer. Off by default; setup in `docs/telegram-alerts-setup.md`.
- Alerts carry a per-kind session number (`claude #2`), and the herdr pane name when the owner sets `show_pane_names = true`.

### Changed

- Android and iOS reconnect now redials the machine's transport, shows the retry reason on the machine row, and restarts an attempt when a machine is opened.
- iOS: transcript bubbles with timestamps, a per-agent history sheet, a composer menu, a reconnecting banner, and the transport each machine last used.

### Fixed

- Bridge: Telegram baselines survive brief herdr gaps; invisible and bidi characters are stripped from pane names.
- Android: a second add or pair while one is running is ignored; reconnect joins in-flight refreshes instead of starting another.
- iOS: history matches by machine and agent kind; prompts and agent control carry the session binding; the app icon is in the xtool IPA.

## 0.1.0 - 2026-10-30

Initial release of Cappuccino: a companion client for remote coding agents, pairing native Android and Apple apps with the `cappuccino-bridge` daemon over Tailscale and iroh peer-to-peer transport.

### Added

- **Modern Mobile UI**:
  - Dynamic collapsing top bar with scroll under-bleed and backdrop blur.
  - Redesigned Settings screen featuring an Apple-style segmented control for theme modes (System, Light, Dark).
  - Clean settings sections for Appearance, iroh Transport, Saved Conversations, and Diagnostics.
  - Centered full-width floating bottom dock for smooth navigation across Machines, History, and Settings.
  - Telegram-inspired chat interface with right-aligned outgoing user bubbles hugging content, tail corners, and bottom-right timestamps (omitting redundant "› You" headers).
  - Left-aligned assistant responses with bot name headers (`Claude`, `Pi`, `Agy`), grouped rounded corners, and bottom-right timestamps integrated into prose blocks.
  - Tool invocations and executions consolidated into centered Telegram service pill badges (`Ran X commands`) with inline expand/collapse.
  - Status notices (`ResponseStatusRow`) rendered as centered Telegram service pill badges.
  - Empty text and blank thinking filter preventing fractured activity groups and hollow prose cards from streaming preambles.
- **Connection Lifecycle & Reconnect Reliability**:
  - Automatic reconnection triggered on network return via `ConnectivityManager.NetworkCallback`.
  - In-memory `ConnectionLogger` ring buffer tracking connection state transitions, dial attempts, and error details.
  - In-app Connection Logs bottom sheet in Settings with one-tap clipboard copy for mobile diagnostics.
- **iroh Peer-to-Peer Pairing**:
  - Direct camera QR scanning via Google Code Scanner (`GmsBarcodeScanning`) with zero extra permissions required.
  - Instant pairing handshake directly from terminal output (`cappuccino-bridge pair`).
  - Dual-transport support: attempts secure iroh peer-to-peer first, with automatic fallback to Tailnet bridge URL.
  - QR code pairing payloads include the auto-detected Tailnet URL (`tailnet_url`) alongside iroh credentials, configuring both peer-to-peer and fallback transport in a single scan.
  - Default connection to N0's Tokyo relay (`aps1`) with full UDP NAT traversal and QUIC hole punching.
- **cappuccino-bridge Daemon**:
  - Thin-client WebSocket and HTTP server fronting Herdr agent panes and processes.
  - Terminal QR pairing command (`cappuccino-bridge pair`) with rotating tokens.
  - Strict TOML configuration file support (`config.toml`).

### Changed

- Replaced legacy JSON bridge configuration with `~/.config/cappuccino-bridge/config.toml`.
- Simplified the machine pairing popup: removed redundant JSON text fields, clipboard paste buttons, and tab switches in favor of a direct, single-tap QR scanner flow.
- Retired self-hosted HTTP reverse-proxy relay experiments in favor of N0's public relay network for reliable UDP traversal.
