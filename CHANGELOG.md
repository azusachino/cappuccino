# Changelog

All notable changes to Cappuccino are documented in this file.

## 0.1.0 - 2026-10-30

Initial release of Cappuccino: a companion client for remote coding agents, pairing native Android and Apple apps with the `cappuccino-bridge` daemon over Tailscale and iroh peer-to-peer transport.

### Added

- **Modern Mobile UI**:
  - Dynamic collapsing top bar with scroll under-bleed and backdrop blur.
  - Redesigned Settings screen featuring an Apple-style segmented control for theme modes (System, Light, Dark).
  - Clean settings sections for Appearance, iroh Transport, Saved Conversations, and Diagnostics.
  - Centered full-width floating bottom dock for smooth navigation across Machines, History, and Settings.
  - Telegram-inspired chat interface with contextual bubble radii, fluid padding, timestamps, and refined markdown rendering.
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
