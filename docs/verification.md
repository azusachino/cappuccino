# Verification

This page records what has been exercised and what has not. Individual
checkpoint logs were removed once their results were carried into the README,
the bridge README or the limits below. Git history keeps the full records.

## Current limits

These limits were true of every accepted checkpoint and still hold.

- **No live canonical parity proof.** Native UI tests use scripted or synthetic
  transcripts and fixtures. No run has proven that a phone transcript matches
  the active branch of a live owner session.
- **No physical-device acceptance.** Simulator and emulator journeys cover the
  shells. Physical iPhone signing and seven-day re-signing, and a physical
  Android phone reaching the bridge over tailnet HTTPS, are unverified.
- **Android API coverage.** UI journeys ran on API 35 only.
- **Tailnet transport.** Tailscale Serve behaviour in front of the bridge is not
  tested. WebSocket runs over the HTTP/1.1 Upgrade handshake only.
- **No delivery receipts.** Herdr's prompt write is not a Pi queue acceptance.
  Nudge and Follow-up selection and `confirmed` or `unresolved` receipts are not
  implemented.
- **No typed approval gate.** Approvals are answered from the screen. Grants,
  reconnect semantics for pending requests, and process-death recovery are not
  implemented or verified.
- **No push or Telegram alerts.** Android runs a foreground service that watches
  configured machines for completions and input requests. iOS has no alerts.
  Telegram notifications are not configured.
- **Performance and leaks.** Benchmark results are baseline observations, not
  budgets. The bridge soak (`make resource-bridge-client`) is opt-in before
  releases; a short sample does not prove zero leaks.

## S1/S2 installed Herdr plugin journey

Verified on macOS and Linux/arm64 with Herdr 0.9.3 (protocol 22) in isolated
HOME and XDG directories, using a task-owned Cargo root and auto-apply disabled.

- The installed `cappuccino-bridge` binary is found through Herdr's inherited
  `PATH`. A run where it is missing fails at startup, as expected.
- The plugin manifest registers as `azusachino.cappuccino-bridge`. Its
  `[[startup]]` hook and `start`, `status`, `logs` and `stop` actions all run.
- Repeated `start` reports the already-running bridge instead of starting a
  second one.
- After `stop`, the HTTP listener refuses connections and the control socket
  and control record are removed. State directories are mode `0700`; lock and
  log files are `0600`.
- `GET /api/session` returns `event: paired` JSON while the bridge runs.
- No Tailscale command ran. No existing Herdr server, agent session or Serve
  configuration was touched.

The journey covers the lifecycle before any device is involved. It does not
cover live-agent delivery or physical-device connectivity.
